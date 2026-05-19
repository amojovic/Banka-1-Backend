package com.banka1.tradingservice.otc.service;

import com.banka1.tradingservice.otc.domain.OtcOffer;
import com.banka1.tradingservice.otc.domain.OtcOfferStatus;
import com.banka1.tradingservice.otc.dto.CounterOfferRequest;
import com.banka1.tradingservice.otc.dto.CreateOtcOfferRequest;
import com.banka1.tradingservice.otc.dto.OtcOfferDto;
import com.banka1.tradingservice.otc.repository.OptionContractRepository;
import com.banka1.tradingservice.otc.repository.OtcOfferRepository;
import com.banka1.tradingservice.otc.repository.OtcOfferRevisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OtcServiceTest {

    @Mock private OtcOfferRepository otcOfferRepository;
    @Mock private OptionContractRepository optionContractRepository;
    @Mock private OtcOfferRevisionRepository revisionRepository;
    @Mock private com.banka1.order.repository.PortfolioRepository portfolioRepository;
    @Mock private com.banka1.order.client.StockClient stockClient;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private com.banka1.tradingservice.otc.notification.OtcNotificationProducer notificationProducer;

    @InjectMocks private OtcService service;

    @BeforeEach
    void setUp() {
        // Lenient: ne sve test metode pozivaju save (one koje testiraju throws ne pozivaju).
        lenient().when(otcOfferRepository.save(any())).thenAnswer(inv -> {
            OtcOffer o = inv.getArgument(0);
            o.setId(1L);
            return o;
        });
    }

    @Test
    void createOffer_postaviStatusPendingSeller() {
        CreateOtcOfferRequest req = new CreateOtcOfferRequest(
                "AAPL", 200L, 10, new BigDecimal("150"), new BigDecimal("400"),
                LocalDate.now().plusMonths(2));
        OtcOfferDto resp = service.createOffer(100L, req, "Buyer");

        assertThat(resp.getStatus()).isEqualTo(OtcOfferStatus.PENDING_SELLER);
        assertThat(resp.getStockTicker()).isEqualTo("AAPL");
        assertThat(resp.getModifiedBy()).isEqualTo("Buyer");
    }

    @Test
    void counterOffer_kadPosaljeKupac_statusFlipsNaPendingSeller() {
        OtcOffer existing = new OtcOffer();
        existing.setId(1L);
        existing.setBuyerId(100L);
        existing.setSellerId(200L);
        existing.setStatus(OtcOfferStatus.PENDING_BUYER);
        when(otcOfferRepository.findById(1L)).thenReturn(Optional.of(existing));

        CounterOfferRequest req = new CounterOfferRequest(
                12, new BigDecimal("160"), new BigDecimal("450"),
                LocalDate.now().plusMonths(3));
        OtcOfferDto resp = service.counterOffer(1L, 100L, req, "Buyer");

        assertThat(resp.getStatus()).isEqualTo(OtcOfferStatus.PENDING_SELLER);
        // WP-15: kupac salje protivponudu -> obavestava se prodavac (200L).
        verify(notificationProducer).notifyCounterOffer(1L, 200L);
    }

    @Test
    void counterOffer_kadPosaljeProdavac_obavestavaKupca() {
        OtcOffer existing = new OtcOffer();
        existing.setId(1L);
        existing.setBuyerId(100L);
        existing.setSellerId(200L);
        existing.setStatus(OtcOfferStatus.PENDING_SELLER);
        when(otcOfferRepository.findById(1L)).thenReturn(Optional.of(existing));

        CounterOfferRequest req = new CounterOfferRequest(
                8, new BigDecimal("140"), new BigDecimal("380"),
                LocalDate.now().plusMonths(2));
        OtcOfferDto resp = service.counterOffer(1L, 200L, req, "Seller");

        assertThat(resp.getStatus()).isEqualTo(OtcOfferStatus.PENDING_BUYER);
        // WP-15: prodavac salje protivponudu -> obavestava se kupac (100L).
        verify(notificationProducer).notifyCounterOffer(1L, 100L);
    }

    @Test
    void counterOffer_throws_kadVecAccepted() {
        OtcOffer existing = new OtcOffer();
        existing.setId(1L);
        existing.setBuyerId(100L);
        existing.setSellerId(200L);
        existing.setStatus(OtcOfferStatus.ACCEPTED);
        when(otcOfferRepository.findById(1L)).thenReturn(Optional.of(existing));

        CounterOfferRequest req = new CounterOfferRequest(
                10, BigDecimal.ONE, BigDecimal.ONE, LocalDate.now().plusMonths(1));

        assertThatThrownBy(() -> service.counterOffer(1L, 100L, req, "Buyer"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vec u finalnom statusu");
    }

    @Test
    void reject_obavestavaSuprotnuStranu() {
        OtcOffer existing = new OtcOffer();
        existing.setId(1L);
        existing.setBuyerId(100L);
        existing.setSellerId(200L);
        existing.setStatus(OtcOfferStatus.PENDING_SELLER);
        when(otcOfferRepository.findById(1L)).thenReturn(Optional.of(existing));

        // Prodavac (200L) odbija -> obavestava se kupac (100L).
        service.reject(1L, 200L);

        verify(notificationProducer).notifyRejected(1L, 100L);
    }

    @Test
    void withdraw_obavestavaSuprotnuStranu() {
        OtcOffer existing = new OtcOffer();
        existing.setId(1L);
        existing.setBuyerId(100L);
        existing.setSellerId(200L);
        existing.setStatus(OtcOfferStatus.PENDING_SELLER);
        when(otcOfferRepository.findById(1L)).thenReturn(Optional.of(existing));

        // Kupac (100L) povlaci dok je PENDING_SELLER -> obavestava se prodavac (200L).
        service.withdraw(1L, 100L);

        verify(notificationProducer).notifyWithdrawn(1L, 200L);
    }
}
