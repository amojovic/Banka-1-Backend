package com.banka1.stock_service.service.implementation;

import com.banka1.stock_service.domain.PriceAlert;
import com.banka1.stock_service.domain.PriceAlertCondition;
import com.banka1.stock_service.dto.CreatePriceAlertRequest;
import com.banka1.stock_service.dto.PriceAlertDto;
import com.banka1.stock_service.repository.ListingRepository;
import com.banka1.stock_service.repository.PriceAlertRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PriceAlertServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class PriceAlertServiceImplTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-05-18T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private PriceAlertRepository priceAlertRepository;

    @Mock
    private ListingRepository listingRepository;

    @Test
    void getAlertsForUserReturnsMappedDtos() {
        when(priceAlertRepository.findByUserIdOrderByCreatedAtDesc(7L))
                .thenReturn(List.of(alert(1L, 7L, "CLIENT", PriceAlertCondition.ABOVE)));

        List<PriceAlertDto> result = service().getAlertsForUser(7L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo(7L);
        assertThat(result.get(0).condition()).isEqualTo(PriceAlertCondition.ABOVE);
    }

    @Test
    void createAlertPersistsAlertWithOwnerRecipientTypeAndActiveTrue() {
        when(listingRepository.existsById(15L)).thenReturn(true);
        when(priceAlertRepository.save(any(PriceAlert.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PriceAlertDto created = service().createAlert(7L, "CLIENT", new CreatePriceAlertRequest(
                15L, PriceAlertCondition.ABOVE, new BigDecimal("200.0000"), "ALL"));

        ArgumentCaptor<PriceAlert> captor = ArgumentCaptor.forClass(PriceAlert.class);
        verify(priceAlertRepository).save(captor.capture());
        PriceAlert saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getRecipientType()).isEqualTo("CLIENT");
        assertThat(saved.getListingId()).isEqualTo(15L);
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 5, 18, 12, 0));
        assertThat(saved.getLastTriggeredAt()).isNull();
        assertThat(created.notificationType()).isEqualTo("ALL");
    }

    @Test
    void createAlertNormalizesLowercaseNotificationType() {
        when(listingRepository.existsById(15L)).thenReturn(true);
        when(priceAlertRepository.save(any(PriceAlert.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PriceAlertDto created = service().createAlert(7L, "EMPLOYEE", new CreatePriceAlertRequest(
                15L, PriceAlertCondition.BELOW, new BigDecimal("100.0000"), "email"));

        assertThat(created.notificationType()).isEqualTo("EMAIL");
    }

    @Test
    void createAlertRejectsUnsupportedNotificationType() {
        assertThatThrownBy(() -> service().createAlert(7L, "CLIENT", new CreatePriceAlertRequest(
                15L, PriceAlertCondition.ABOVE, new BigDecimal("200.0000"), "SMS")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unsupported notificationType");
        verify(priceAlertRepository, never()).save(any());
    }

    @Test
    void createAlertRejectsUnknownListing() {
        when(listingRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> service().createAlert(7L, "CLIENT", new CreatePriceAlertRequest(
                999L, PriceAlertCondition.ABOVE, new BigDecimal("200.0000"), "ALL")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Listing with id 999 was not found");
        verify(priceAlertRepository, never()).save(any());
    }

    @Test
    void toggleAlertFlipsActiveFlagForOwner() {
        PriceAlert alert = alert(1L, 7L, "CLIENT", PriceAlertCondition.ABOVE);
        alert.setActive(true);
        when(priceAlertRepository.findById(1L)).thenReturn(Optional.of(alert));
        when(priceAlertRepository.save(any(PriceAlert.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PriceAlertDto result = service().toggleAlert(7L, 1L);

        assertThat(result.active()).isFalse();
    }

    @Test
    void toggleAlertRejectsAlertOwnedByAnotherUser() {
        PriceAlert alert = alert(1L, 99L, "CLIENT", PriceAlertCondition.ABOVE);
        when(priceAlertRepository.findById(1L)).thenReturn(Optional.of(alert));

        assertThatThrownBy(() -> service().toggleAlert(7L, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Price alert with id 1 was not found");
        verify(priceAlertRepository, never()).save(any());
    }

    @Test
    void toggleAlertRejectsMissingAlert() {
        when(priceAlertRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().toggleAlert(7L, 404L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Price alert with id 404 was not found");
    }

    @Test
    void deleteAlertRemovesOwnedAlert() {
        PriceAlert alert = alert(1L, 7L, "CLIENT", PriceAlertCondition.ABOVE);
        when(priceAlertRepository.findById(1L)).thenReturn(Optional.of(alert));

        service().deleteAlert(7L, 1L);

        verify(priceAlertRepository).delete(alert);
    }

    @Test
    void deleteAlertRejectsAlertOwnedByAnotherUser() {
        PriceAlert alert = alert(1L, 99L, "CLIENT", PriceAlertCondition.ABOVE);
        when(priceAlertRepository.findById(1L)).thenReturn(Optional.of(alert));

        assertThatThrownBy(() -> service().deleteAlert(7L, 1L))
                .isInstanceOf(ResponseStatusException.class);
        verify(priceAlertRepository, never()).delete(any());
    }

    private PriceAlertServiceImpl service() {
        return new PriceAlertServiceImpl(priceAlertRepository, listingRepository, FIXED_CLOCK);
    }

    private PriceAlert alert(Long id, Long userId, String recipientType, PriceAlertCondition condition) {
        PriceAlert alert = new PriceAlert();
        alert.setId(id);
        alert.setUserId(userId);
        alert.setRecipientType(recipientType);
        alert.setListingId(15L);
        alert.setCondition(condition);
        alert.setThreshold(new BigDecimal("200.0000"));
        alert.setNotificationType("ALL");
        alert.setActive(true);
        alert.setCreatedAt(LocalDateTime.of(2026, 5, 18, 12, 0));
        return alert;
    }
}
