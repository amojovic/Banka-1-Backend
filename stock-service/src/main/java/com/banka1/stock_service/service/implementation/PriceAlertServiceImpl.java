package com.banka1.stock_service.service.implementation;

import com.banka1.stock_service.domain.PriceAlert;
import com.banka1.stock_service.dto.CreatePriceAlertRequest;
import com.banka1.stock_service.dto.PriceAlertDto;
import com.banka1.stock_service.repository.ListingRepository;
import com.banka1.stock_service.repository.PriceAlertRepository;
import com.banka1.stock_service.service.PriceAlertService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Default CRUD implementation for price alerts (Celina 3.2).
 *
 * <p>Ownership is enforced on every mutating operation. A mismatch is reported
 * as {@code 404 NOT_FOUND} to avoid leaking other users' alerts.
 */
@Service
public class PriceAlertServiceImpl implements PriceAlertService {

    private static final Set<String> SUPPORTED_NOTIFICATION_TYPES = Set.of("EMAIL", "PUSH", "IN_APP", "ALL");

    private final PriceAlertRepository priceAlertRepository;
    private final ListingRepository listingRepository;
    private final Clock clock;

    @Autowired
    public PriceAlertServiceImpl(PriceAlertRepository priceAlertRepository, ListingRepository listingRepository) {
        this(priceAlertRepository, listingRepository, Clock.systemUTC());
    }

    PriceAlertServiceImpl(PriceAlertRepository priceAlertRepository, ListingRepository listingRepository, Clock clock) {
        this.priceAlertRepository = priceAlertRepository;
        this.listingRepository = listingRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PriceAlertDto> getAlertsForUser(Long userId) {
        return priceAlertRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(PriceAlertDto::from)
                .toList();
    }

    @Override
    @Transactional
    public PriceAlertDto createAlert(Long userId, String recipientType, CreatePriceAlertRequest request) {
        String normalizedNotificationType = normalizeNotificationType(request.notificationType());

        if (!listingRepository.existsById(request.listingId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Listing with id %s was not found.".formatted(request.listingId())
            );
        }

        PriceAlert alert = new PriceAlert();
        alert.setUserId(userId);
        alert.setRecipientType(recipientType);
        alert.setListingId(request.listingId());
        alert.setCondition(request.condition());
        alert.setThreshold(request.threshold());
        alert.setNotificationType(normalizedNotificationType);
        alert.setActive(true);
        alert.setCreatedAt(LocalDateTime.now(clock));

        return PriceAlertDto.from(priceAlertRepository.save(alert));
    }

    @Override
    @Transactional
    public PriceAlertDto toggleAlert(Long userId, Long alertId) {
        PriceAlert alert = requireOwnedAlert(userId, alertId);
        alert.setActive(!alert.isActive());
        return PriceAlertDto.from(priceAlertRepository.save(alert));
    }

    @Override
    @Transactional
    public void deleteAlert(Long userId, Long alertId) {
        PriceAlert alert = requireOwnedAlert(userId, alertId);
        priceAlertRepository.delete(alert);
    }

    private PriceAlert requireOwnedAlert(Long userId, Long alertId) {
        PriceAlert alert = priceAlertRepository.findById(alertId)
                .orElseThrow(() -> alertNotFound(alertId));
        if (!alert.getUserId().equals(userId)) {
            throw alertNotFound(alertId);
        }
        return alert;
    }

    private String normalizeNotificationType(String notificationType) {
        String normalized = notificationType.trim().toUpperCase(java.util.Locale.ROOT);
        if (!SUPPORTED_NOTIFICATION_TYPES.contains(normalized)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported notificationType '%s'. Supported values are EMAIL, PUSH, IN_APP and ALL."
                            .formatted(notificationType)
            );
        }
        return normalized;
    }

    private ResponseStatusException alertNotFound(Long alertId) {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Price alert with id %s was not found.".formatted(alertId)
        );
    }
}
