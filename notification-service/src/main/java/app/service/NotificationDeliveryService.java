package app.service;

import app.dto.NotificationRequest;
import app.dto.ResolvedEmail;
import app.dto.RetryTask;
import app.entities.NotificationDelivery;
import app.entities.NotificationDeliveryStatus;
import app.exception.BusinessException;
import app.exception.ErrorCode;
import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Glavni servis koji vodi ceo tok obrade jedne notifikacije.
 *
 * <p>Ukratko, tok izgleda ovako:
 * <ol>
 *     <li>Stigne poruka sa RabbitMQ-a.</li>
 *     <li>Na osnovu routing key-a odredi se tip notifikacije.</li>
 *     <li>Proveri se da li je payload ispravan i renderuje se subject/body.</li>
 *     <li>Upise se in-app notifikacija (ako poruka ima primaoca).</li>
 *     <li>Ako poruka ima email adresu, u bazi se pravi zapis sa statusom {@code PENDING}.</li>
 *     <li>Tek nakon commit-a transakcije pokusava se stvarno slanje email-a.</li>
 *     <li>Ako slanje uspe, zapis prelazi u {@code SUCCEEDED}.</li>
 *     <li>Ako slanje ne uspe, zapis prelazi u {@code RETRY_SCHEDULED} ili {@code FAILED}.</li>
 * </ol>
 *
 * <p>In-app feed i email su nezavisni kanali — poruka sa primaocem ali bez (ili sa
 * praznom) email adresom i dalje dobija in-app notifikaciju; samo se email kanal
 * preskace. Slicno, poruka sa email adresom ali bez {@code recipientUserId}-a salje
 * samo email. Nepoznat tip notifikacije i dalje prekida celu obradu.
 *
 * <p>Bitna razlika kod gresaka:
 * <ul>
 *     <li>Ako greska nastane pre samog slanja email-a
 *     (na primer los payload ili ne postoji template),
 *     email jos nije usao u delivery retry tok i u tim slucajevima se ovde ne kreira
 *     {@code PENDING} zapis.</li>
 *     <li>Ako greska nastane tek kada servis proba da posalje email,
 *     zapis vec postoji u bazi i tada se update-uje status i po potrebi zakazuje retry.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDeliveryService {
    /** Maximum stored error message length. */
    private static final int MAX_ERROR_LENGTH = 1000;
    /** Placeholder recipient stored when the incoming payload does not contain one. */
    private static final String UNKNOWN_RECIPIENT = "unknown";
    /** Placeholder text stored when email content could not be rendered. */
    private static final String EMPTY_CONTENT = "";
    /**
     * Conventional template-variable keys producers use to carry the id of the
     * originating domain object; used as the in-app notification deep link.
     */
    private static final List<String> REFERENCE_ID_KEYS = List.of(
            "referenceId", "transactionId", "transferId", "accountNumber",
            "creditId", "orderId", "negotiationId", "contractId"
    );

    /**
     * Persistence layer for delivery state transitions.
     */
    private final NotificationDeliveryTxService notificationDeliveryTxService;
    /**
     * Email renderer/sender abstraction.
     */
    private final NotificationService notificationService;
    /**
     * In-memory scheduler optimization for due retries.
     */
    private final RetryTaskQueue retryTaskQueue;
    /**
     * FCM push service for verification code delivery.
     */
    private final FcmPushService fcmPushService;
    /**
     * FCM token lookup service.
     */
    private final FcmTokenService fcmTokenService;

    /**
     * In-app notification feed persistence.
     */
    private final InAppNotificationService inAppNotificationService;

    /**
     * Configured routing keys map.
     */
    private final Map<String, String> routingKeysMap;

    /**
     * Configured retry budget per new delivery record.
     */
    @Value("${notification.retry.max-retries:4}")
    private int defaultMaxRetries;

    /**
     * Delay in seconds before a retryable failed delivery is attempted again.
     */
    @Value("${notification.retry.delay-seconds:5}")
    private int retryDelaySeconds;

    /**
     * Batch size used while reloading retryable deliveries on startup.
     */
    @Value("${notification.retry.startup-page-size:500}")
    private int startupPageSize;

    /**
     * Validates that retry configuration values are sensible at startup.
     */
    @PostConstruct
    void validateRetryConfig() {
        if (defaultMaxRetries < 1) {
            throw new IllegalStateException(
                    "notification.retry.max-retries must be >= 1, got: " + defaultMaxRetries
            );
        }
        if (retryDelaySeconds < 1) {
            throw new IllegalStateException(
                    "notification.retry.delay-seconds must be >= 1, got: " + retryDelaySeconds
            );
        }
    }

    /**
     * Handles a newly consumed RabbitMQ message using the raw routing key.
     *
     * <p>Ovo je ulazna tacka za poruke koje dolaze sa broker-a.
     * Prvo se proverava da li routing key moze da se mapira na poznat {@link NotificationType}.
     * Ako ne moze, poruka se tretira kao nepodrzana i odmah se cuva failed audit zapis,
     * bez pokusaja slanja.
     *
     * <p>Ako je routing key poznat, obrada se nastavlja dalje kroz validaciju payload-a,
     * renderovanje email-a i eventualno slanje.
     *
     * @param req incoming notification payload
     * @param routingKey routing key from RabbitMQ
     */
    @Transactional
    public void handleIncomingMessage(NotificationRequest req, String routingKey) {
        Optional<String> notificationType = resolveNotificationType(routingKey);
        if (notificationType.isEmpty()) {
            notificationDeliveryTxService.persistFailedAudit(
                    buildFailedAudit(
                            req,
                            "UNKNOWN",
                            "Unsupported routing key: " + routingKey
                    )
            );
            return;
        }
        processIncomingMessage(req, notificationType.get());
    }

    /**
     * Validates, renders, and dispatches a consumed notification across its
     * independent channels (in-app feed, email, FCM push).
     *
     * <p>IMPORTANT: this method never sends email directly. It only registers the
     * send attempt to run after the surrounding transaction commits successfully.
     *
     * <p>Kanali su nezavisni:
     * <ul>
     *     <li>tip notifikacije se uvek validira — nepoznat tip prekida obradu;</li>
     *     <li>subject/body se renderuju iz {@code templateVariables}
     *     (ne treba im email adresa);</li>
     *     <li>in-app red se uvek upisuje kada postoji {@code recipientUserId},
     *     bez obzira na to da li je email prisutan;</li>
     *     <li>email delivery zapis ({@code PENDING}) se pravi samo kada poruka
     *     nosi ispravnu email adresu — ako je nema, email kanal se preskace
     *     (ne baca se greska, ostali kanali rade normalno);</li>
     *     <li>FCM push je fire-and-forget kao i pre.</li>
     * </ul>
     *
     * @param req incoming notification payload
     * @param notificationType resolved notification type
     */
    private void processIncomingMessage(
            NotificationRequest req,
            String notificationType
    ) {
        validateIncoming(req);
        validateNotificationType(notificationType);

        // Render subject/body from templateVariables — this needs the notification
        // type but NOT the recipient email, so it is shared by every channel.
        ResolvedEmail resolvedContent = notificationService.renderContent(req, notificationType);

        // In-app notification row — an independent channel. Created whenever the
        // payload carries a recipient, regardless of whether an email address is
        // present or valid. Skipped (no-op) only when recipientUserId is null.
        inAppNotificationService.createForRecipient(
                req.getRecipientUserId(),
                req.getRecipientType(),
                notificationType,
                resolvedContent.subject(),
                resolvedContent.body(),
                resolveReferenceId(req)
        );

        // Email delivery — an independent channel. A missing/blank email address
        // skips only this leg; it must never abort the message or suppress the
        // in-app row created above.
        if (hasDeliverableEmail(req)) {
            String deliveryId = UUID.randomUUID().toString();
            NotificationDelivery delivery = buildPendingDelivery(
                    deliveryId, resolvedContent, notificationType
            );
            notificationDeliveryTxService.createPendingDelivery(delivery);
            runAfterCommit(() -> attemptDelivery(deliveryId));
        } else {
            log.info("No deliverable email on message type={}, skipping email channel "
                    + "(in-app notification still recorded)", notificationType);
        }

        // FCM push for verification OTPs (fire-and-forget, email is authoritative)
        if ("VERIFICATION_OTP".equals(notificationType) && req.getClientId() != null) {
            String rawCode = req.getTemplateVariables().get("code");
            Long clientId = req.getClientId();
            String opType = req.getOperationType();
            String sessId = req.getSessionId();
            runAfterCommit(() -> attemptFcmPush(clientId, rawCode, opType, sessId));
        } else if (req.getClientId() != null) {
            // Generic FCM push for every other consumed event (fire-and-forget).
            Long clientId = req.getClientId();
            String subject = resolvedContent.subject();
            String body = resolvedContent.body();
            runAfterCommit(() -> attemptGenericFcmPush(clientId, notificationType, subject, body));
        }
    }

    /**
     * Checks whether the incoming payload carries a deliverable email address.
     *
     * <p>This gates only the email-delivery channel. When it returns {@code false}
     * the email leg is skipped, but the in-app channel and FCM push are unaffected.
     *
     * @param req incoming notification payload
     * @return {@code true} when a non-blank recipient email is present
     */
    private boolean hasDeliverableEmail(NotificationRequest req) {
        String email = req.getUserEmail();
        return email != null && !email.isBlank();
    }

    /**
     * Extracts an optional deep-link reference id from the incoming payload.
     *
     * <p>Producers carry the originating domain object id under one of a few
     * conventional template-variable keys; the first present non-blank value
     * wins. A missing reference is fine — the in-app row simply has none.
     *
     * @param req incoming notification payload
     * @return reference id when present, otherwise {@code null}
     */
    private String resolveReferenceId(NotificationRequest req) {
        Map<String, String> vars = req.getTemplateVariables();
        if (vars == null) {
            return null;
        }
        for (String key : REFERENCE_ID_KEYS) {
            String value = vars.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * Scheduled retry worker that only inspects the queue head and processes due
     * tasks.
     */
    @Scheduled(fixedDelayString = "${notification.retry.scheduler-delay-millis:1000}")
    public void processDueRetries() {
        while (true) {
            RetryTask head = retryTaskQueue.peek();
            Instant now = Instant.now();
            if (head == null || head.nextAttemptAt().isAfter(now)) {
                return;
            }

            RetryTask dueTask = retryTaskQueue.pollDue(now);
            if (dueTask == null) {
                return;
            }
            processRetryTask(dueTask.deliveryId());
        }
    }

    /**
     * Reloads retryable deliveries from PostgreSQL into the in-memory queue on
     * startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadRetryTasksOnStartup() {
        try {
            Instant now = Instant.now();
            reloadStartupTasks(NotificationDeliveryStatus.PENDING, now);
            reloadStartupTasks(NotificationDeliveryStatus.RETRY_SCHEDULED, now);
        } catch (Exception ex) {
            log.error("Failed to load retry tasks on startup — pending deliveries may not be rescheduled", ex);
        }
    }

    /**
     * Executes one retry task after validating the latest database state.
     *
     * @param deliveryId internal delivery identifier
     */
    private void processRetryTask(String deliveryId) {
        Optional<@NonNull NotificationDelivery> optionalDelivery =
                notificationDeliveryTxService.findByDeliveryId(deliveryId);
        if (optionalDelivery.isEmpty()) {
            return;
        }

        NotificationDelivery delivery = optionalDelivery.get();
        if (shouldSkipAttempt(delivery, Instant.now())) {
            return;
        }
        attemptDelivery(deliveryId);
    }

    /**
     * Creates a new persisted delivery record.
     *
     * @param deliveryId generated internal UUID
     * @param resolvedEmail rendered email content
     * @param notificationType resolved notification type
     * @return persisted delivery record
     */
    private NotificationDelivery buildPendingDelivery(
            String deliveryId,
            ResolvedEmail resolvedEmail,
            String notificationType
    ) {
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setDeliveryId(deliveryId);
        delivery.setAttemptCount(0);
        delivery.setMaxRetries(defaultMaxRetries);
        delivery.setStatus(NotificationDeliveryStatus.PENDING);
        delivery.setNotificationType(notificationType);
        updateDeliveryPayload(delivery, resolvedEmail);
        return delivery;
    }

    /**
     * Persists a terminally failed record for invalid or unsupported incoming
     * messages.
     *
     * <p>Ovaj zapis se koristi kada poruka ne moze ni da udje u normalan delivery tok.
     * Na primer, kada stigne nepodrzan routing key. Tada nema smisla praviti
     * {@code PENDING} delivery i cekati slanje, ali ipak zelimo trag u bazi da je
     * takva poruka primljena i odbijena.
     *
     * @param request incoming payload, which may be null or malformed
     * @param notificationType resolved or fallback notification type
     * @param error error reason stored with the delivery
     * @return failed audit record ready for persistence
     */
    private NotificationDelivery buildFailedAudit(
            NotificationRequest request,
            String notificationType,
            String error
    ) {
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setDeliveryId(UUID.randomUUID().toString());
        delivery.setAttemptCount(0);
        delivery.setMaxRetries(defaultMaxRetries);
        delivery.setStatus(NotificationDeliveryStatus.FAILED);
        delivery.setNotificationType(notificationType);
        delivery.setRecipientEmail(extractUserEmail(request));
        delivery.setSubject(EMPTY_CONTENT);
        delivery.setBody(EMPTY_CONTENT);
        delivery.setLastError(error);
        delivery.setNextAttemptAt(null);
        delivery.setLastAttemptAt(null);
        delivery.setSentAt(null);
        return delivery;
    }

    /**
     * Copies rendered email fields into a mutable delivery entity.
     *
     * @param delivery target entity
     * @param resolvedEmail source email content
     */
    private void updateDeliveryPayload(
            NotificationDelivery delivery,
            ResolvedEmail resolvedEmail
    ) {
        delivery.setRecipientEmail(resolvedEmail.recipientEmail());
        delivery.setSubject(resolvedEmail.subject());
        delivery.setBody(resolvedEmail.body());
    }

    /**
     * Performs 1 immediate send attempt and updates persistence state accordingly.
     *
     * <p>Do ove tacke dolazimo tek kada delivery vec postoji u bazi.
     * To je vazno, jer sve greske koje nastanu ovde pripadaju "send" fazi,
     * a ne "validation/render" fazi.
     *
     * <p>Ponasanje je sledece:
     * <ul>
     *     <li>ako slanje uspe, delivery ide u {@code SUCCEEDED},</li>
     *     <li>ako padne autentikacija prema mail serveru, delivery se obelezava kao neuspesan
     *     bez retry-a,</li>
     *     <li>za ostale exception-e proverava se da li su retryable i po potrebi se
     *     zakazuje sledeci pokusaj.</li>
     * </ul>
     *
     * @param deliveryId internal delivery identifier
     */
    private void attemptDelivery(String deliveryId) {
        Optional<@NonNull NotificationDelivery> optionalDelivery =
                notificationDeliveryTxService.findByDeliveryId(deliveryId);
        if (optionalDelivery.isEmpty()) {
            return;
        }

        NotificationDelivery delivery = optionalDelivery.get();
        Instant now = Instant.now();
        if (shouldSkipAttempt(delivery, now)) {
            return;
        }
        delivery.setLastAttemptAt(now);
        try {
            notificationService.sendEmail(
                    delivery.getRecipientEmail(),
                    delivery.getSubject(),
                    delivery.getBody()
            );
            notificationDeliveryTxService.markSucceeded(deliveryId, now);
        } catch (MailAuthenticationException e) {
            // Non-retryable greske
            notificationDeliveryTxService.markFailedOrRetry(
                    deliveryId, now, trimError(e), false, retryDelaySeconds
            );
        } catch (Exception e) {
            boolean retryable = isRetryable(e);
            Instant nextAttempt = notificationDeliveryTxService.markFailedOrRetry(
                    deliveryId, now, trimError(e), retryable, retryDelaySeconds
            );

            if (retryable && nextAttempt != null) {
                retryTaskQueue.schedule(deliveryId, nextAttempt);
            }
        }
    }

    /**
     * Determines whether the delivery should be skipped for now.
     *
     * @param delivery persisted delivery state
     * @param now current wall-clock timestamp
     * @return {@code true} when sending must not proceed yet
     */
    private boolean shouldSkipAttempt(NotificationDelivery delivery, Instant now) {
        if (isRecoverablePending(delivery)) {
            return false;
        }
        if (!isRecoverableScheduledRetry(delivery)) {
            return true;
        }
        if (delivery.getNextAttemptAt().isAfter(now)) {
            retryTaskQueue.schedule(delivery.getDeliveryId(), delivery.getNextAttemptAt());
            return true;
        }
        return false;
    }

    /**
     * Checks whether a pending delivery can still enter an attempt.
     *
     * @param delivery persisted delivery state
     * @return {@code true} when the delivery is pending and still retryable
     */
    private boolean isRecoverablePending(NotificationDelivery delivery) {
        return delivery.getStatus() == NotificationDeliveryStatus.PENDING
                && delivery.getAttemptCount() < delivery.getMaxRetries();
    }

    /**
     * Checks whether a scheduled retry is still eligible for processing.
     *
     * @param delivery persisted delivery state
     * @return {@code true} when retry metadata is complete and retry budget
     *         remains
     */
    private boolean isRecoverableScheduledRetry(NotificationDelivery delivery) {
        return delivery.getStatus() == NotificationDeliveryStatus.RETRY_SCHEDULED
                && delivery.getAttemptCount() < delivery.getMaxRetries()
                && delivery.getNextAttemptAt() != null;
    }

    /**
     * Loads 1 status bucket page-by-page and enqueues recoverable records.
     *
     * @param status lifecycle status being reloaded
     * @param now current wall-clock timestamp used for pending records
     */
    private void reloadStartupTasks(NotificationDeliveryStatus status, Instant now) {
        int pageNumber = 0;
        while (true) {
            Page<@NonNull NotificationDelivery> page =
                    notificationDeliveryTxService.findPageByStatus(
                            status,
                            PageRequest.of(pageNumber, startupPageSize)
                    );
            for (NotificationDelivery delivery : page.getContent()) {
                if (isRecoverablePending(delivery)) {
                    retryTaskQueue.schedule(delivery.getDeliveryId(), now);
                    continue;
                }
                if (isRecoverableScheduledRetry(delivery)) {
                    retryTaskQueue.schedule(delivery.getDeliveryId(), delivery.getNextAttemptAt());
                }
            }
            if (!page.hasNext()) {
                return;
            }
            pageNumber++;
        }
    }

    /**
     * Attempts to send an FCM push notification for a verification OTP.
     * Fire-and-forget: if no token is registered or sending fails,
     * email delivery remains the authoritative channel.
     */
    private void attemptFcmPush(Long clientId, String code,
                                 String operationType, String sessionId) {
        try {
            Optional<String> token = fcmTokenService.findToken(clientId);
            if (token.isEmpty()) {
                log.debug("No FCM token for clientId={}, skipping push", clientId);
                return;
            }
            fcmPushService.sendVerificationPush(token.get(), code, operationType, sessionId);
        } catch (Exception e) {
            log.warn("FCM push failed for clientId={}: {}", clientId, e.getMessage());
        }
    }

    /**
     * Attempts a generic FCM push for a non-verification notification.
     * Fire-and-forget: when no token is registered or sending fails, email
     * delivery remains the authoritative channel.
     *
     * @param clientId client whose device should receive the push
     * @param type notification type resolved from the routing key
     * @param title rendered email subject reused as the push headline
     * @param body rendered email body reused as the push message
     */
    private void attemptGenericFcmPush(Long clientId, String type,
                                       String title, String body) {
        try {
            Optional<String> token = fcmTokenService.findToken(clientId);
            if (token.isEmpty()) {
                log.debug("No FCM token for clientId={}, skipping push type={}", clientId, type);
                return;
            }
            fcmPushService.sendPush(token.get(), type, title, body);
        } catch (Exception e) {
            log.warn("FCM push failed for clientId={}: {}", clientId, e.getMessage());
        }
    }

    /**
     * Registers work that must run strictly after transaction commit.
     *
     * <p>IMPORTANT: callers must already be inside an active Spring-managed
     * transaction. Running the action earlier would break delivery guarantees.
     *
     * @param action callback that must run after commit
     * @throws IllegalStateException when no active transaction synchronization
     *         exists
     */
    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("runAfterCommit called without active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private Optional<String> resolveNotificationType(String routingKey) {
        return Optional.ofNullable(routingKeysMap.get(routingKey));
    }

    /**
     * Validates basic incoming payload shape.
     *
     * <p>Ova validacija se desava pre nego sto delivery bude upisan kao {@code PENDING}.
     * Zato greska iz ove metode znaci da email nije ni usao u fazu slanja.
     *
     * @param request payload object from listener
     * @throws BusinessException if request is null
     */
    private void validateIncoming(NotificationRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.NOTIFICATION_PAYLOAD_REQUIRED, "Notification payload is required");
        }
    }

    /**
     * Validates required notification type resolved from routing key.
     *
     * <p>I ova provera se desava pre kreiranja delivery zapisa za slanje.
     * Ako nema validnog tipa notifikacije, nema ni smisla da delivery ulazi u retry tok.
     *
     * @param notificationType resolved notification type
     * @throws BusinessException if notificationType is null
     */
    private void validateNotificationType(String notificationType) {
        if (notificationType == null || notificationType.isBlank()) {
            throw new BusinessException(ErrorCode.NOTIFICATION_TYPE_REQUIRED, "notificationType is required");
        }
    }

    /**
     * Produces a bounded string representation for persistence.
     *
     * @param ex source exception
     * @return trimmed error text up to 1000 characters
     */
    private String trimError(Exception ex) {
        String message = ex.getMessage();
        String error = ex.getClass().getSimpleName() + (message != null ? ": " + message : "");
        if (error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }

    /**
     * Decides whether a failed send attempt should be retried.
     *
     * <p>Trenutna logika je namerno jednostavna:
     * sve osim {@link MailAuthenticationException} tretira se kao retryable greska.
     * To znaci da se klasifikacija jos ne deli detaljnije na
     * "permanent recipient problem" i "privremeni provider problem".
     *
     * @param ex delivery failure thrown by the mail layer
     * @return {@code true} when the failure is considered transient
     */
    private boolean isRetryable(Exception ex) {
        if (ex instanceof MailAuthenticationException) {
            return false;
        }
        if (ex instanceof MailSendException mailSendEx) {
            for (Exception failure : mailSendEx.getFailedMessages().values()) {
                if (isPermanentSmtpFailure(failure)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isPermanentSmtpFailure(Exception ex) {
        String className = ex.getClass().getName();
        if (className.contains("SMTPAddressFailedException") || className.contains("SMTPSendFailedException")) {
            String message = ex.getMessage();
            if (message != null && message.length() >= 3) {
                char first = message.charAt(0);
                if (first == '5') return true;
            }
        }
        if (ex.getCause() != null) {
            return isPermanentSmtpFailure(ex.getCause() instanceof Exception cause ? cause : new RuntimeException(ex.getCause()));
        }
        return false;
    }

    /**
     * Extracts the recipient email for audit-only failures.
     *
     * @param request incoming payload that may be null or incomplete
     * @return request email or a placeholder when the payload is incomplete
     */
    private String extractUserEmail(NotificationRequest request) {
        if (request == null || request.getUserEmail() == null || request.getUserEmail().isBlank()) {
            return UNKNOWN_RECIPIENT;
        }
        return request.getUserEmail();
    }
}
