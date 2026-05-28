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
 * <h2>Debounce rule</h2>
 * <p>An alert is not re-fired while its condition stays continuously satisfied.
 * It fires again only after the price leaves and re-enters the trigger zone.
 */
@Slf4j
@Service
public class PriceAlertEvaluationService {

    /** Routing key used for price-alert notifications on the shared topic exchange. */
    static final String ROUTING_KEY = "price.alert_triggered";

    private final PriceAlertRepository priceAlertRepository;
    private final ListingRepository listingRepository;
    private final RabbitTemplate rabbitTemplate;
    private final Clock clock;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    @Autowired
    public PriceAlertEvaluationService(
            PriceAlertRepository priceAlertRepository,
            ListingRepository listingRepository,
            RabbitTemplate rabbitTemplate
    ) {
        this(priceAlertRepository, listingRepository, rabbitTemplate, Clock.systemUTC());
    }

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
            if (alert.getLastTriggeredAt() != null) {
                alert.setLastTriggeredAt(null);
                priceAlertRepository.save(alert);
            }
            return false;
        }

        if (alert.getLastTriggeredAt() != null) {
            return false;
        }

        alert.setLastTriggeredAt(LocalDateTime.now(clock));
        priceAlertRepository.save(alert);
        publishNotification(alert, listing.get());
        return true;
    }

    private boolean isConditionSatisfied(PriceAlert alert, Listing listing) {
        BigDecimal price = listing.getPrice();
        BigDecimal threshold = alert.getThreshold();
        return switch (alert.getCondition()) {
            case ABOVE -> price.compareTo(threshold) >= 0;
            case BELOW -> price.compareTo(threshold) <= 0;
            case PCT_DROP_INTRADAY -> isIntradayDropSatisfied(listing, threshold);
        };
    }

    private boolean isIntradayDropSatisfied(Listing listing, BigDecimal threshold) {
        BigDecimal changePercent;
        try {
            changePercent = listing.calculateChangePercent();
        } catch (ArithmeticException arithmeticException) {
            return false;
        }
        return changePercent.compareTo(threshold.negate()) <= 0;
    }

    private void publishNotification(PriceAlert alert, Listing listing) {
        Map<String, String> templateVariables = new HashMap<>();
        templateVariables.put("name", "korisnice");
        templateVariables.put("ticker", listing.getTicker());
        templateVariables.put("price", String.valueOf(listing.getPrice()));
        templateVariables.put("threshold", String.valueOf(alert.getThreshold()));
        templateVariables.put("condition", alert.getCondition().name());

        boolean isClient = "CLIENT".equalsIgnoreCase(alert.getRecipientType());
        PriceAlertNotificationPayload payload = new PriceAlertNotificationPayload(
                "korisnice",
                null,
                templateVariables,
                isClient ? alert.getUserId() : null
        );

        rabbitTemplate.convertAndSend(exchange, ROUTING_KEY, payload);
        log.info(
                "Published price.alert_triggered for alertId={} ticker={} price={}",
                alert.getId(), listing.getTicker(), listing.getPrice()
        );
    }
}
