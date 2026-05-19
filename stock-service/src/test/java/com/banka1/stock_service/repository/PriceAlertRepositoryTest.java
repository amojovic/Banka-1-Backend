package com.banka1.stock_service.repository;

import com.banka1.stock_service.domain.PriceAlert;
import com.banka1.stock_service.domain.PriceAlertCondition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence tests for {@link PriceAlertRepository}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PriceAlertRepositoryTest {

    @Autowired
    private PriceAlertRepository priceAlertRepository;

    @Test
    void shouldPersistAndLoadPriceAlertWithAllFields() {
        PriceAlert alert = createAlert(5L, "CLIENT", 15L, PriceAlertCondition.ABOVE,
                new BigDecimal("200.0000"), "ALL", true);

        PriceAlert saved = priceAlertRepository.saveAndFlush(alert);

        PriceAlert persisted = priceAlertRepository.findById(saved.getId()).orElseThrow();
        assertNotNull(persisted.getId());
        assertEquals(5L, persisted.getUserId());
        assertEquals("CLIENT", persisted.getRecipientType());
        assertEquals(15L, persisted.getListingId());
        assertEquals(PriceAlertCondition.ABOVE, persisted.getCondition());
        assertEquals(new BigDecimal("200.0000"), persisted.getThreshold());
        assertEquals("ALL", persisted.getNotificationType());
        assertTrue(persisted.isActive());
        assertNotNull(persisted.getCreatedAt());
    }

    @Test
    void findByUserIdOrderByCreatedAtDescReturnsOnlyOwnerAlertsNewestFirst() {
        PriceAlert older = createAlert(7L, "CLIENT", 15L, PriceAlertCondition.BELOW,
                new BigDecimal("100.0000"), "EMAIL", true);
        older.setCreatedAt(LocalDateTime.of(2026, 5, 1, 10, 0));
        PriceAlert newer = createAlert(7L, "CLIENT", 16L, PriceAlertCondition.ABOVE,
                new BigDecimal("300.0000"), "PUSH", true);
        newer.setCreatedAt(LocalDateTime.of(2026, 5, 10, 10, 0));
        PriceAlert otherUser = createAlert(99L, "EMPLOYEE", 17L, PriceAlertCondition.ABOVE,
                new BigDecimal("50.0000"), "IN_APP", true);

        priceAlertRepository.saveAndFlush(older);
        priceAlertRepository.saveAndFlush(newer);
        priceAlertRepository.saveAndFlush(otherUser);

        List<PriceAlert> result = priceAlertRepository.findByUserIdOrderByCreatedAtDesc(7L);

        assertEquals(2, result.size());
        assertEquals(16L, result.get(0).getListingId());
        assertEquals(15L, result.get(1).getListingId());
    }

    @Test
    void findByActiveTrueReturnsOnlyActiveAlerts() {
        PriceAlert active = createAlert(7L, "CLIENT", 15L, PriceAlertCondition.ABOVE,
                new BigDecimal("200.0000"), "ALL", true);
        PriceAlert inactive = createAlert(7L, "CLIENT", 16L, PriceAlertCondition.ABOVE,
                new BigDecimal("200.0000"), "ALL", false);

        priceAlertRepository.saveAndFlush(active);
        priceAlertRepository.saveAndFlush(inactive);

        List<PriceAlert> result = priceAlertRepository.findByActiveTrue();

        assertEquals(1, result.size());
        assertEquals(15L, result.get(0).getListingId());
    }

    private PriceAlert createAlert(Long userId, String recipientType, Long listingId,
                                   PriceAlertCondition condition, BigDecimal threshold,
                                   String notificationType, boolean active) {
        PriceAlert alert = new PriceAlert();
        alert.setUserId(userId);
        alert.setRecipientType(recipientType);
        alert.setListingId(listingId);
        alert.setCondition(condition);
        alert.setThreshold(threshold);
        alert.setNotificationType(notificationType);
        alert.setActive(active);
        alert.setCreatedAt(LocalDateTime.of(2026, 5, 18, 12, 0));
        return alert;
    }
}
