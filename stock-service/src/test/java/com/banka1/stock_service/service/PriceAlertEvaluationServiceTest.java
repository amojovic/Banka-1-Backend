package com.banka1.stock_service.service;

import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.domain.PriceAlert;
import com.banka1.stock_service.domain.PriceAlertCondition;
import com.banka1.stock_service.dto.PriceAlertNotificationPayload;
import com.banka1.stock_service.repository.ListingRepository;
import com.banka1.stock_service.repository.PriceAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PriceAlertEvaluationService}.
 */
@ExtendWith(MockitoExtension.class)
class PriceAlertEvaluationServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-05-18T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 5, 18, 12, 0);
    private static final String EXCHANGE = "employee.events";

    @Mock
    private PriceAlertRepository priceAlertRepository;

    @Mock
    private ListingRepository listingRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private PriceAlertEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new PriceAlertEvaluationService(
                priceAlertRepository, listingRepository, rabbitTemplate, FIXED_CLOCK);
        ReflectionTestUtils.setField(service, "exchange", EXCHANGE);
    }

    @Test
    void aboveAlertFiresWhenPriceReachesThreshold() {
        PriceAlert alert = alert(1L, PriceAlertCondition.ABOVE, new BigDecimal("200.0000"));
        when(priceAlertRepository.findByActiveTrue()).thenReturn(List.of(alert));
        when(listingRepository.findById(15L))
                .thenReturn(Optional.of(listing(new BigDecimal("205.00000000"), BigDecimal.ZERO)));

        int fired = service.evaluateActiveAlerts();

        assertThat(fired).isEqualTo(1);
        assertThat(alert.getLastTriggeredAt()).isEqualTo(FIXED_NOW);
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq("price.alert_triggered"), any(Object.class));
        verify(priceAlertRepository).save(alert);
    }

    @Test
    void aboveAlertDoesNotFireWhenPriceBelowThreshold() {
        PriceAlert alert = alert(1L, PriceAlertCondition.ABOVE, new BigDecimal("200.0000"));
        when(priceAlertRepository.findByActiveTrue()).thenReturn(List.of(alert));
        when(listingRepository.findById(15L))
                .thenReturn(Optional.of(listing(new BigDecimal("190.00000000"), BigDecimal.ZERO)));

        int fired = service.evaluateActiveAlerts();

        assertThat(fired).isZero();
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    void belowAlertFiresWhenPriceAtOrBelowThreshold() {
        PriceAlert alert = alert(1L, PriceAlertCondition.BELOW, new BigDecimal("100.0000"));
        when(priceAlertRepository.findByActiveTrue()).thenReturn(List.of(alert));
        when(listingRepository.findById(15L))
                .thenReturn(Optional.of(listing(new BigDecimal("100.00000000"), BigDecimal.ZERO)));

        int fired = service.evaluateActiveAlerts();

        assertThat(fired).isEqualTo(1);
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq("price.alert_triggered"), any(Object.class));
    }

    @Test
    void intradayDropAlertFiresWhenChangePercentDropsByThreshold() {
        PriceAlert alert = alert(1L, PriceAlertCondition.PCT_DROP_INTRADAY, new BigDecimal("5.0000"));
        when(priceAlertRepository.findByActiveTrue()).thenReturn(List.of(alert));
        when(listingRepository.findById(15L)).thenReturn(Optional.of(
                listing(new BigDecimal("95.00000000"), new BigDecimal("-5.00000000"))));

        int fired = service.evaluateActiveAlerts();

        assertThat(fired).isEqualTo(1);
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq("price.alert_triggered"), any(Object.class));
    }

    @Test
    void intradayDropAlertDoesNotFireForSmallDrop() {
        PriceAlert alert = alert(1L, PriceAlertCondition.PCT_DROP_INTRADAY, new BigDecimal("5.0000"));
        when(priceAlertRepository.findByActiveTrue()).thenReturn(List.of(alert));
        when(listingRepository.findById(15L)).thenReturn(Optional.of(
                listing(new BigDecimal("98.00000000"), new BigDecimal("-2.00000000"))));

        int fired = service.evaluateActiveAlerts();

        assertThat(fired).isZero();
    }

    @Test
    void intradayDropAlertDoesNotFireOnUpwardMove() {
        PriceAlert alert = alert(1L, PriceAlertCondition.PCT_DROP_INTRADAY, new BigDecimal("5.0000"));
        when(priceAlertRepository.findByActiveTrue()).thenReturn(List.of(alert));
        when(listingRepository.findById(15L)).thenReturn(Optional.of(
                listing(new BigDecimal("110.00000000"), new BigDecimal("10.00000000"))));

        int fired = service.evaluateActiveAlerts();

        assertThat(fired).isZero();
    }

    @Test
    void intradayDropAlertTreatsZeroPreviousPriceAsNotSatisfied() {
        PriceAlert alert = alert(1L, PriceAlertCondition.PCT_DROP_INTRADAY, new BigDecimal("5.0000"));
        when(priceAlertRepository.findByActiveTrue()).thenReturn(List.of(alert));
        when(listingRepository.findById(15L)).thenReturn(Optional.of(
                listing(new BigDecimal("10.00000000"), new BigDecimal("10.00000000"))));

        int fired = service.evaluateActiveAlerts();

        assertThat(fired).isZero();
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    void alertIsSuppressedWhileConditionStaysContinuouslySatisfied() {
        PriceAlert alert = alert(1L, PriceAlertCondition.ABOVE, new BigDecimal("200.0000"));
        alert.setLastTriggeredAt(LocalDateTime.of(2026, 5, 18, 11, 0));
        when(priceAlertRepository.findByActiveTrue()).thenReturn(List.of(alert));
        when(listingRepository.findById(15L))
                .thenReturn(Optional.of(listing(new BigDecimal("205.00000000"), BigDecimal.ZERO)));

        int fired = service.evaluateActiveAlerts();

        assertThat(fired).isZero();
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
        verify(priceAlertRepository, never()).save(any());
    }

    @Test
    void alertReArmsWhenPriceLeavesTriggerZone() {
        PriceAlert alert = alert(1L, PriceAlertCondition.ABOVE, new BigDecimal("200.0000"));
        alert.setLastTriggeredAt(LocalDateTime.of(2026, 5, 18, 11, 0));
        when(priceAlertRepository.findByActiveTrue()).thenReturn(List.of(alert));
        when(listingRepository.findById(15L))
                .thenReturn(Optional.of(listing(new BigDecimal("180.00000000"), BigDecimal.ZERO)));

        int fired = service.evaluateActiveAlerts();

        assertThat(fired).isZero();
        assertThat(alert.getLastTriggeredAt()).isNull();
        verify(priceAlertRepository).save(alert);
    }

    @Test
    void alertFiresAgainAfterReArmWhenConditionIsReMet() {
        PriceAlert alert = alert(1L, PriceAlertCondition.ABOVE, new BigDecimal("200.0000"));
        alert.setLastTriggeredAt(null);
        when(priceAlertRepository.findByActiveTrue()).thenReturn(List.of(alert));
        when(listingRepository.findById(15L))
                .thenReturn(Optional.of(listing(new BigDecimal("210.00000000"), BigDecimal.ZERO)));

        int fired = service.evaluateActiveAlerts();

        assertThat(fired).isEqualTo(1);
        assertThat(alert.getLastTriggeredAt()).isEqualTo(FIXED_NOW);
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq("price.alert_triggered"), any(Object.class));
    }

    @Test
    void publishedPayloadCarriesClientIdAndTemplateVariablesForClientOwner() {
        PriceAlert alert = alert(1L, PriceAlertCondition.ABOVE, new BigDecimal("200.0000"));
        alert.setUserId(42L);
        alert.setRecipientType("CLIENT");
        when(priceAlertRepository.findByActiveTrue()).thenReturn(List.of(alert));
        when(listingRepository.findById(15L))
                .thenReturn(Optional.of(listing(new BigDecimal("205.00000000"), BigDecimal.ZERO)));

        service.evaluateActiveAlerts();

        ArgumentCaptor<PriceAlertNotificationPayload> captor =
                ArgumentCaptor.forClass(PriceAlertNotificationPayload.class);
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq("price.alert_triggered"), captor.capture());
        PriceAlertNotificationPayload payload = captor.getValue();
        assertThat(payload.clientId()).isEqualTo(42L);
        assertThat(payload.templateVariables()).containsEntry("ticker", "AAPL");
        assertThat(payload.templateVariables()).containsEntry("price", "205.00000000");
        assertThat(payload.templateVariables()).containsEntry("threshold", "200.0000");
        assertThat(payload.templateVariables()).containsEntry("condition", "ABOVE");
    }

    @Test
    void publishedPayloadOmitsClientIdForEmployeeOwner() {
        PriceAlert alert = alert(1L, PriceAlertCondition.ABOVE, new BigDecimal("200.0000"));
        alert.setUserId(77L);
        alert.setRecipientType("EMPLOYEE");
        when(priceAlertRepository.findByActiveTrue()).thenReturn(List.of(alert));
        when(listingRepository.findById(15L))
                .thenReturn(Optional.of(listing(new BigDecimal("205.00000000"), BigDecimal.ZERO)));

        service.evaluateActiveAlerts();

        ArgumentCaptor<PriceAlertNotificationPayload> captor =
                ArgumentCaptor.forClass(PriceAlertNotificationPayload.class);
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq("price.alert_triggered"), captor.capture());
        assertThat(captor.getValue().clientId()).isNull();
    }

    @Test
    void evaluationSkipsAlertWhenListingNoLongerExists() {
        PriceAlert alert = alert(1L, PriceAlertCondition.ABOVE, new BigDecimal("200.0000"));
        when(priceAlertRepository.findByActiveTrue()).thenReturn(List.of(alert));
        when(listingRepository.findById(15L)).thenReturn(Optional.empty());

        int fired = service.evaluateActiveAlerts();

        assertThat(fired).isZero();
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    void evaluationContinuesAfterASingleAlertFails() {
        PriceAlert failing = alert(1L, PriceAlertCondition.ABOVE, new BigDecimal("200.0000"));
        PriceAlert healthy = alert(2L, PriceAlertCondition.ABOVE, new BigDecimal("200.0000"));
        healthy.setListingId(16L);
        when(priceAlertRepository.findByActiveTrue()).thenReturn(List.of(failing, healthy));
        when(listingRepository.findById(15L)).thenThrow(new IllegalStateException("db boom"));
        when(listingRepository.findById(16L))
                .thenReturn(Optional.of(listing(new BigDecimal("250.00000000"), BigDecimal.ZERO)));

        int fired = service.evaluateActiveAlerts();

        assertThat(fired).isEqualTo(1);
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq("price.alert_triggered"), any(Object.class));
    }

    private PriceAlert alert(Long id, PriceAlertCondition condition, BigDecimal threshold) {
        PriceAlert alert = new PriceAlert();
        alert.setId(id);
        alert.setUserId(5L);
        alert.setRecipientType("CLIENT");
        alert.setListingId(15L);
        alert.setCondition(condition);
        alert.setThreshold(threshold);
        alert.setNotificationType("ALL");
        alert.setActive(true);
        alert.setCreatedAt(LocalDateTime.of(2026, 5, 18, 10, 0));
        return alert;
    }

    private Listing listing(BigDecimal price, BigDecimal change) {
        Listing listing = new Listing();
        listing.setId(15L);
        listing.setSecurityId(101L);
        listing.setListingType(ListingType.STOCK);
        listing.setTicker("AAPL");
        listing.setName("Apple Inc.");
        listing.setLastRefresh(LocalDateTime.of(2026, 5, 18, 11, 59));
        listing.setPrice(price);
        listing.setAsk(price);
        listing.setBid(price);
        listing.setChange(change);
        listing.setVolume(1_000L);
        return listing;
    }
}
