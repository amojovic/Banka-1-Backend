package com.banka1.stock_service.service;

import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.PriceAlert;
import com.banka1.stock_service.domain.PriceAlertCondition;
import com.banka1.stock_service.dto.PriceAlertNotificationPayload;
import com.banka1.stock_service.repository.ListingRepository;
import com.banka1.stock_service.repository.PriceAlertRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Evaluates active {@link PriceAlert price alerts} against the latest listing
 * prices and publishes a {@code price.alert_triggered} notification when a
 * condition is met (Celina 3.2).
 *
 * <p>The {@code stock-service} runs consolidated inside {@code market-service};
 * this service publishes to the shared {@code employee.events} topic exchange via
 * the {@link RabbitTemplate} declared by the market-service AMQP configuration.
 *
 * <h2>Debounce rule</h2>
 * <p>An alert is <strong>not</strong> re-fired while its condition stays
 * continuously satisfied across consecutive evaluation passes. The rule is
 * derived purely from the persisted {@code lastTriggeredAt} field so it survives
 * application restarts:
 *
 * <ul>
 *     <li>condition satisfied and {@code lastTriggeredAt} is {@code null}
 *         → fire, then set {@code lastTriggeredAt = now}</li>
 *     <li>condition satisfied and {@code lastTriggeredAt} is already set
 *         → suppressed (the alert is still "armed-and-fired")</li>
 *     <li>condition not satisfied
 *         → re-arm by clearing {@code lastTriggeredAt}, so the next time the
 *         price re-enters the trigger zone the alert fires again</li>
 * </ul>
 */
@Slf4j
@Service
public class PriceAlertEvaluationService {

    /**
     * Routing key used for price-alert notifications on the shared topic exchange.
     */
    static final String ROUTING_KEY = "price.alert_triggered";

    private final PriceAlertRepository priceAlertRepository;
    private final ListingRepository listingRepository;
    private final RabbitTemplate rabbitTemplate;
    private final Clock clock;

    /**
     * Topic exchange the notification is published to (shared {@code employee.events}).
     */
    @Value("${rabbitmq.exchange}")
    private String exchange;

    /**
     * Creates the production service using the system UTC clock.
     *
     * @param priceAlertRepository repository for price alerts
     * @param listingRepository repository for current listing snapshots
     * @param rabbitTemplate AMQP template for publishing notifications
     */
    @Autowired
    public PriceAlertEvaluationService(
            PriceAlertRepository priceAlertRepository,
            ListingRepository listingRepository,
            RabbitTemplate rabbitTemplate
    ) {
        this(priceAlertRepository, listingRepository, rabbitTemplate, Clock.systemUTC());
    }

    /**
     * Creates the service with an explicit clock for deterministic tests.
     *
     * @param priceAlertRepository repository for price alerts
     * @param listingRepository repository for current listing snapshots
     * @param rabbitTemplate AMQP template for publishing notifications
     * @param clock time source used for the {@code lastTriggeredAt} timestamp
     */
    PriceAlertEvaluationService(
            PriceAlertRepository priceAlertRepository,
            ListingRepository listingRepository,
            RabbitTemplate rabbitTemplate,
            Clock clock
    ) {
        this.priceAlertRepository = priceAlertRepository;
        this.listingRepository = listingRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.clock = clock;
    }

    /**
     * Evaluates every active price alert against its listing's current price.
     *
     * <p>Intended to be invoked at the end of a market-data refresh cycle so that
     * alerts are checked against freshly refreshed prices. A failure to publish a
     * single notification is logged and does not abort the remaining evaluations.
     *
     * @return the number of alerts that fired during this pass
     */
    @Transactional
    public int evaluateActiveAlerts() {
        int firedCount = 0;
        for (PriceAlert alert : priceAlertRepository.findByActiveTrue()) {
            try {
                if (evaluateSingleAlert(alert)) {
                    firedCount++;
                }
            } catch (RuntimeException exception) {
                log.warn(
                        "Price alert evaluation failed for alertId={} listingId={} because {}",
                        alert.getId(), alert.getListingId(), exception.getMessage()
                );
            }
        }
        if (firedCount > 0) {
            log.info("Price alert evaluation pass completed. firedCount={}", firedCount);
        }
        return firedCount;
    }

    /**
     * Evaluates one alert, applying the debounce rule and publishing on a fire.
     *
     * @param alert active alert under evaluation
     * @return {@code true} when the alert fired during this evaluation
     */
    private boolean evaluateSingleAlert(PriceAlert alert) {
        Optional<Listing> listing = listingRepository.findById(alert.getListingId());
        if (listing.isEmpty()) {
            log.warn(
                    "Skipping price alert alertId={} — listing id={} no longer exists.",
                    alert.getId(), alert.getListingId()
            );
            return false;
        }

        boolean satisfied = isConditionSatisfied(alert, listing.get());

        if (!satisfied) {
            // Re-arm: once the price leaves the trigger zone the alert may fire again.
            if (alert.getLastTriggeredAt() != null) {
                alert.setLastTriggeredAt(null);
                priceAlertRepository.save(alert);
            }
            return false;
        }

        // Condition satisfied. Debounce: suppress while it stays continuously satisfied.
        if (alert.getLastTriggeredAt() != null) {
            return false;
        }

        alert.setLastTriggeredAt(LocalDateTime.now(clock));
        priceAlertRepository.save(alert);
        publishNotification(alert, listing.get());
        return true;
    }

    /**
     * Returns whether one alert's trigger condition holds against a listing price.
     *
     * @param alert alert holding the condition and threshold
     * @param listing listing holding the current price and intraday change
     * @return {@code true} when the condition is met
     */
    private boolean isConditionSatisfied(PriceAlert alert, Listing listing) {
        BigDecimal price = listing.getPrice();
        BigDecimal threshold = alert.getThreshold();
        return switch (alert.getCondition()) {
            case ABOVE -> price.compareTo(threshold) >= 0;
            case BELOW -> price.compareTo(threshold) <= 0;
            case PCT_DROP_INTRADAY -> isIntradayDropSatisfied(listing, threshold);
        };
    }

    /**
     * Returns whether the listing's intraday percentage change is a drop of at
     * least {@code threshold} percent.
     *
     * <p>{@link Listing#calculateChangePercent()} returns a signed percentage; an
     * intraday drop is a negative value. The alert fires when that value is at or
     * below {@code -threshold}. The previous-price-zero edge case (where
     * {@code calculateChangePercent} throws) is treated as "not satisfied".
     *
     * @param listing listing holding the current price and absolute change
     * @param threshold positive percentage magnitude of the drop to detect
     * @return {@code true} when an intraday drop of at least {@code threshold} percent occurred
     */
    private boolean isIntradayDropSatisfied(Listing listing, BigDecimal threshold) {
        BigDecimal changePercent;
        try {
            changePercent = listing.calculateChangePercent();
        } catch (ArithmeticException arithmeticException) {
            // price - change == 0: no meaningful previous price, so no drop can be derived.
            return false;
        }
        return changePercent.compareTo(threshold.negate()) <= 0;
    }

    /**
     * Builds and publishes the {@code price.alert_triggered} notification payload.
     *
     * @param alert the alert that fired
     * @param listing the listing whose price triggered the alert
     */
    private void publishNotification(PriceAlert alert, Listing listing) {
        Map<String, String> templateVariables = new HashMap<>();
        // The PRICE_ALERT_TRIGGERED template uses {{name}}, {{ticker}} and {{price}};
        // condition + threshold are included for richer in-app rendering.
        templateVariables.put("name", "korisnice");
        templateVariables.put("ticker", listing.getTicker());
        templateVariables.put("price", String.valueOf(listing.getPrice()));
        templateVariables.put("threshold", String.valueOf(alert.getThreshold()));
        templateVariables.put("condition", alert.getCondition().name());

        boolean isClient = "CLIENT".equalsIgnoreCase(alert.getRecipientType());
        PriceAlertNotificationPayload payload = new PriceAlertNotificationPayload(
                "korisnice",
                // The alert owner's email is not resolvable from stock-service; the
                // in-app and push channels deliver independently of email.
                null,
                templateVariables,
                alert.getUserId(),
                alert.getRecipientType(),
                isClient ? alert.getUserId() : null
        );

        rabbitTemplate.convertAndSend(exchange, ROUTING_KEY, payload);
        log.info(
                "Published price.alert_triggered for alertId={} ticker={} price={}",
                alert.getId(), listing.getTicker(), listing.getPrice()
        );
    }
}
