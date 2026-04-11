package com.banka1.verificationService.service;

import com.banka1.verificationService.dto.request.GenerateRequest;
import com.banka1.verificationService.dto.request.ValidateRequest;
import com.banka1.verificationService.dto.response.GenerateResponse;
import com.banka1.verificationService.dto.response.StatusResponse;
import com.banka1.verificationService.dto.response.ValidateResponse;
import com.banka1.verificationService.exception.BusinessException;
import com.banka1.verificationService.exception.ErrorCode;
import com.banka1.verificationService.model.entity.VerificationSession;
import com.banka1.verificationService.model.enums.VerificationStatus;
import com.banka1.verificationService.repository.VerificationSessionRepository;
import com.company.observability.starter.domain.UserIdExtractor;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Service for managing verification sessions and OTP validation.
 *
 * Handles the lifecycle of two-factor authentication (2FA) sessions including:
 * <ul>
 *   <li>Generating and hashing one-time passwords (OTP)</li>
 *   <li>Validating user-provided codes against stored hashes</li>
 *   <li>Tracking session state (PENDING, VERIFIED, EXPIRED, CANCELLED)</li>
 *   <li>Publishing verification events to RabbitMQ for email delivery</li>
 *   <li>Enforcing security constraints (attempt limits, expiration times)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class VerificationService {

    private final VerificationSessionRepository repository;
    private final OtpHashingService otpHashingService;
    private final RabbitTemplate rabbitTemplate;
    private final UserIdExtractor userIdExtractor;

    /** RabbitMQ exchange name for publishing messages. */
    @Value("${rabbitmq.exchange}")
    private String exchange;

    /** RabbitMQ routing key (unused but available for extensibility). */
    @Value("${rabbitmq.routing-key}")
    private String routingKey;

    /** RabbitMQ routing key for verification events and OTP delivery. */
    @Value("${rabbitmq.routing-key.verification}")
    private String verificationRoutingKey;

    /**
     * Generates a new verification session with a randomly generated 6-digit OTP code.
     *
     * This method:
     * <ol>
     *   <li>Validates that the requesting client matches the authenticated user (from JWT)</li>
     *   <li>Cancels any existing PENDING sessions for the same clientId, operationType, and relatedEntityId</li>
     *   <li>Generates a random 6-digit code and hashes it using HMAC-SHA256</li>
     *   <li>Creates a new VerificationSession with 5-minute expiration</li>
     *   <li>Publishes a verification event to RabbitMQ for email notification</li>
     * </ol>
     *
     * @param request contains clientId, operationType, relatedEntityId, and clientEmail
     * @return response containing the newly created session ID
     * @throws BusinessException with ErrorCode.FORBIDDEN if clientId does not match authenticated user
     * @throws BusinessException with ErrorCode.VERIFICATION_SESSION_ALREADY_PENDING if a PENDING
     *         session exists for the same combination (duplicate unique constraint)
     * @see GenerateRequest
     * @see GenerateResponse
     */
    @Transactional
    public GenerateResponse generate(GenerateRequest request) {
        JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        Object idClaim = auth.getToken().getClaims().get("id");
        if (idClaim == null || !String.valueOf(idClaim).equals(String.valueOf(request.getClientId()))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Cannot generate verification for other client");
        }

        List<VerificationSession> existingSessions = repository.findByClientIdAndOperationTypeAndRelatedEntityIdAndStatus(
                request.getClientId(), request.getOperationType(), request.getRelatedEntityId(), VerificationStatus.PENDING);
        for (VerificationSession existing : existingSessions) {
            existing.setStatus(VerificationStatus.CANCELLED);
            repository.save(existing);
        }

        String rawCode = generateCode();
        String hashedCode = otpHashingService.hash(rawCode);
        LocalDateTime now = LocalDateTime.now();

        VerificationSession session = VerificationSession.builder()
                .clientId(request.getClientId())
                .code(hashedCode)
                .operationType(request.getOperationType())
                .relatedEntityId(request.getRelatedEntityId())
                .createdAt(now)
                .expiresAt(now.plusMinutes(5))
                .attemptCount(0)
                .status(VerificationStatus.PENDING)
                .build();

        try {
            session = repository.saveAndFlush(session);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(
                    ErrorCode.VERIFICATION_SESSION_ALREADY_PENDING,
                    "Client ID: %s, operationType: %s, relatedEntityId: %s"
                            .formatted(request.getClientId(), request.getOperationType(), request.getRelatedEntityId())
            );
        }

        final Long sessionId = session.getId();

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishGeneratedEvent(request, rawCode, sessionId);
                }
            });
        } else {
            publishGeneratedEvent(request, rawCode, sessionId);
        }

        return new GenerateResponse(session.getId());
    }

    /**
     * Validates a user-provided verification code against a stored session.
     *
     * This method:
     * <ol>
     *   <li>Retrieves the session by ID; throws exception if not found</li>
     *   <li>Checks session state and rejects if already VERIFIED, CANCELLED, or EXPIRED</li>
     *   <li>Compares the provided code against the stored hash using constant-time matching</li>
     *   <li>On successful match: sets status to VERIFIED and returns success response</li>
     *   <li>On failure: increments attemptCount and sets status to CANCELLED if attempts >= 3</li>
     * </ol>
     *
     * @param request contains sessionId and the user-provided 6-digit code
     * @return response indicating success/failure, current session status, and remaining attempts
     * @throws BusinessException with ErrorCode.VERIFICATION_SESSION_NOT_FOUND if session does not exist
     * @throws BusinessException with ErrorCode.VERIFICATION_SESSION_CANCELLED if session is already cancelled
     * @throws BusinessException with ErrorCode.VERIFICATION_SESSION_ALREADY_VERIFIED if already verified
     * @throws BusinessException with ErrorCode.VERIFICATION_CODE_EXPIRED if session is expired or past expiresAt time
     * @see ValidateRequest
     * @see ValidateResponse
     */
    @Transactional
    public ValidateResponse validate(ValidateRequest request) {
        VerificationSession session = repository.findById(request.getSessionId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.VERIFICATION_SESSION_NOT_FOUND,
                        "Session ID: " + request.getSessionId()
                ));

        if (session.getStatus() == VerificationStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.VERIFICATION_SESSION_CANCELLED, "Session ID: " + request.getSessionId());
        }
        if (session.getStatus() == VerificationStatus.VERIFIED) {
            throw new BusinessException(ErrorCode.VERIFICATION_SESSION_ALREADY_VERIFIED, "Session ID: " + request.getSessionId());
        }
        if (session.getStatus() == VerificationStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_EXPIRED, "Session ID: " + request.getSessionId());
        }

        if (LocalDateTime.now().isAfter(session.getExpiresAt())) {
            session.setStatus(VerificationStatus.EXPIRED);
            repository.save(session);
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_EXPIRED, "Session ID: " + request.getSessionId());
        }

        boolean matches = otpHashingService.matches(request.getCode(), session.getCode());
        if (matches) {
            session.setStatus(VerificationStatus.VERIFIED);
            repository.save(session);
            return new ValidateResponse(true, session.getStatus(), 0);
        }

        session.setAttemptCount(session.getAttemptCount() + 1);
        if (session.getAttemptCount() >= 3) {
            session.setStatus(VerificationStatus.CANCELLED);
        }
        repository.save(session);

        return new ValidateResponse(false, session.getStatus(), 3 - session.getAttemptCount());
    }

    /**
     * Retrieves the current status of a verification session.
     *
     * If the session is PENDING and has passed its expiration time, this method
     * automatically transitions the session to EXPIRED status before returning.
     *
     * @param sessionId the ID of the session to check
     * @return response containing the session ID and its current status
     * @throws BusinessException with ErrorCode.VERIFICATION_SESSION_NOT_FOUND if session does not exist
     * @see StatusResponse
     */
    @Transactional
    public StatusResponse getStatus(Long sessionId) {
        VerificationSession session = repository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_SESSION_NOT_FOUND, "Session ID: " + sessionId));

        if (session.getStatus() == VerificationStatus.PENDING
                && LocalDateTime.now().isAfter(session.getExpiresAt())) {
            session.setStatus(VerificationStatus.EXPIRED);
            repository.save(session);
        }

        return new StatusResponse(sessionId, session.getStatus());
    }

    /**
     * Publishes a verification event to RabbitMQ for email delivery.
     *
     * Creates a payload containing the client's email address and the raw (unhashed)
     * OTP code as a template variable, then sends it to the notification service
     * via RabbitMQ for asynchronous email delivery.
     *
     * @param request contains the clientEmail for the recipient
     * @param rawCode the 6-digit OTP code to send (before hashing)
     */
    private void publishGeneratedEvent(GenerateRequest request, String rawCode, Long sessionId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("userEmail", request.getClientEmail());
        payload.put("clientId", request.getClientId());
        payload.put("operationType", request.getOperationType().name());
        payload.put("sessionId", String.valueOf(sessionId));
        Map<String, String> templateVariables = new HashMap<>();
        templateVariables.put("code", rawCode);
        payload.put("templateVariables", templateVariables);
        rabbitTemplate.convertAndSend(exchange, verificationRoutingKey, payload);
    }

    /**
     * Generates a random 6-digit OTP code.
     *
     * Uses {@link java.util.concurrent.ThreadLocalRandom} for thread-safe randomization.
     * The range [100000, 1000000) ensures exactly 6 digits.
     *
     * @return a 6-digit string representation of the generated code
     */
    private String generateCode() {
        int code = ThreadLocalRandom.current().nextInt(100000, 1000000);
        return String.valueOf(code);
    }
}
