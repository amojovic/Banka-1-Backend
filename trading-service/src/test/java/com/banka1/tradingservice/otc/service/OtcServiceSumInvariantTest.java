package com.banka1.tradingservice.otc.service;

import com.banka1.order.client.ClientClient;
import com.banka1.order.client.StockClient;
import com.banka1.order.repository.PortfolioRepository;
import com.banka1.tradingservice.otc.client.UserServiceClient;
import com.banka1.tradingservice.otc.domain.OptionContract;
import com.banka1.tradingservice.otc.domain.OptionContractStatus;
import com.banka1.tradingservice.otc.domain.OtcOffer;
import com.banka1.tradingservice.otc.domain.OtcOfferStatus;
import com.banka1.tradingservice.otc.exception.InsufficientPublicStockException;
import com.banka1.tradingservice.otc.repository.OptionContractRepository;
import com.banka1.tradingservice.otc.repository.OtcOfferRepository;
import com.banka1.tradingservice.otc.repository.OtcOfferRevisionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR_32 Phase 12 KRIT #2 + #3: verifikuje reserved-stock invariant i da se
 * OptionContract kreira sa statusom PENDING_PREMIUM (ne ACTIVE).
 *
 * <p>{@code OtcService.accept} radi pesimisticki lock ({@code findByIdForUpdate})
 * i invariant proveru preko {@code OtcPortfolioService.getOtcCapacity} +
 * {@code OtcOfferRepository.sumPendingBySellerAndTickerExcluding}:
 * {@code pendingNegotiations + requested <= otcCapacity}.
 */
@ExtendWith(MockitoExtension.class)
class OtcServiceSumInvariantTest {

    @Mock private OtcOfferRepository otcOfferRepository;
    @Mock private OptionContractRepository optionContractRepository;
    @Mock private OtcOfferRevisionRepository revisionRepository;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private StockClient stockClient;
    @Mock private ClientClient clientClient;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private OtcPortfolioService portfolioService;
    @Mock private UserServiceClient userServiceClient;
    @Mock private com.banka1.tradingservice.otc.notification.OtcNotificationProducer notificationProducer;

    @InjectMocks private OtcService service;

    @Test
    void accept_kreiraOptionContractSaStatusomPendingPremium() {
        OtcOffer offer = newOffer(20);
        when(otcOfferRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(offer));

        // OTC kapacitet 50, nista u pregovorima -> 0 + 20 <= 50 -> dozvoljeno.
        when(portfolioService.getOtcCapacity(200L, "AAPL")).thenReturn(50L);
        when(otcOfferRepository.sumPendingBySellerAndTickerExcluding(200L, "AAPL", 1L)).thenReturn(0L);
        when(optionContractRepository.save(any(OptionContract.class)))
                .thenAnswer(inv -> {
                    OptionContract c = inv.getArgument(0);
                    c.setId(99L);
                    return c;
                });

        service.accept(1L, 200L);

        ArgumentCaptor<OptionContract> captor = ArgumentCaptor.forClass(OptionContract.class);
        verify(optionContractRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OptionContractStatus.PENDING_PREMIUM);
        assertThat(offer.getStatus()).isEqualTo(OtcOfferStatus.ACCEPTED);
        // Prihvatanjem se akcije prodavca rezervisu.
        verify(portfolioService).reserveForContract(200L, "AAPL", 20);
        // WP-15: prodavac (200L) prihvata -> obavestava se kupac (100L).
        verify(notificationProducer).notifyAccepted(1L, 100L);
    }

    @Test
    void accept_throws_kadSumPlusOfferPrelaziPortfolio() {
        OtcOffer offer = newOffer(30);
        when(otcOfferRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(offer));

        // 25 vec u pregovorima + 30 nova = 55 > 50 kapacitet -> invariant pada.
        when(portfolioService.getOtcCapacity(200L, "AAPL")).thenReturn(50L);
        when(otcOfferRepository.sumPendingBySellerAndTickerExcluding(200L, "AAPL", 1L)).thenReturn(25L);

        assertThatThrownBy(() -> service.accept(1L, 200L))
                .isInstanceOf(InsufficientPublicStockException.class)
                .hasMessageContaining("ne moze se rezervisati jos");

        verify(optionContractRepository, never()).save(any(OptionContract.class));
        verify(portfolioService, never()).reserveForContract(anyLong(), anyString(), anyInt());
    }

    @Test
    void accept_throws_kadProdavacNemaPoziciju() {
        OtcOffer offer = newOffer(5);
        when(otcOfferRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(offer));

        // Prodavac nema poziciju za ticker -> kapacitet 0 -> 0 + 5 > 0 -> invariant pada.
        when(portfolioService.getOtcCapacity(200L, "AAPL")).thenReturn(0L);
        when(otcOfferRepository.sumPendingBySellerAndTickerExcluding(200L, "AAPL", 1L)).thenReturn(0L);

        assertThatThrownBy(() -> service.accept(1L, 200L))
                .isInstanceOf(InsufficientPublicStockException.class)
                .hasMessageContaining("ima preostalih 0");

        verify(optionContractRepository, never()).save(any(OptionContract.class));
    }

    @Test
    void accept_dozvoliKadJeSumPlusOfferTacnoUOkviru() {
        OtcOffer offer = newOffer(25);
        when(otcOfferRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(offer));

        // 25 u pregovorima + 25 nova = 50 (tacno na granici kapaciteta 50, dozvoljeno).
        when(portfolioService.getOtcCapacity(200L, "AAPL")).thenReturn(50L);
        when(otcOfferRepository.sumPendingBySellerAndTickerExcluding(200L, "AAPL", 1L)).thenReturn(25L);
        when(optionContractRepository.save(any(OptionContract.class)))
                .thenAnswer(inv -> {
                    OptionContract c = inv.getArgument(0);
                    c.setId(99L);
                    return c;
                });

        service.accept(1L, 200L);

        verify(optionContractRepository).save(any(OptionContract.class));
        verify(portfolioService).reserveForContract(200L, "AAPL", 25);
    }

    private static OtcOffer newOffer(int amount) {
        OtcOffer offer = new OtcOffer();
        offer.setId(1L);
        offer.setBuyerId(100L);
        offer.setSellerId(200L);
        offer.setStockTicker("AAPL");
        offer.setAmount(amount);
        offer.setPricePerStock(new BigDecimal("150"));
        offer.setPremium(new BigDecimal("400"));
        offer.setSettlementDate(LocalDate.now().plusMonths(2));
        offer.setStatus(OtcOfferStatus.PENDING_SELLER);
        return offer;
    }
}
