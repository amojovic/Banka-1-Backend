package com.banka1.tradingservice.otc.service;

import com.banka1.order.client.ClientClient;
import com.banka1.order.client.StockClient;
import com.banka1.order.repository.PortfolioRepository;
import com.banka1.tradingservice.otc.client.UserServiceClient;
import com.banka1.order.dto.CustomerDto;
import com.banka1.tradingservice.otc.domain.OptionContract;
import com.banka1.tradingservice.otc.domain.OtcOffer;
import com.banka1.tradingservice.otc.domain.OtcOfferRevision;
import com.banka1.tradingservice.otc.domain.OtcOfferStatus;
import com.banka1.tradingservice.otc.domain.OtcRevisionAction;
import com.banka1.tradingservice.otc.dto.CounterOfferRequest;
import com.banka1.tradingservice.otc.dto.CreateOtcOfferRequest;
import com.banka1.tradingservice.otc.dto.OtcOfferDto;
import com.banka1.tradingservice.otc.dto.OtcOfferRevisionDto;
import com.banka1.tradingservice.otc.notification.OtcNotificationProducer;
import com.banka1.tradingservice.otc.repository.OptionContractRepository;
import com.banka1.tradingservice.otc.repository.OtcOfferRepository;
import com.banka1.tradingservice.otc.repository.OtcOfferRevisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-16 (Celina 4.2): verifikuje da {@code OtcService} pri svakoj akciji u
 * pregovoru ({@code create}/{@code counter}/{@code accept}/{@code reject}/
 * {@code withdraw}) upisuje {@link OtcOfferRevision} red sa ispravnim
 * starim/novim vrednostima, aktorom i ulogom (BUYER/SELLER).
 */
@ExtendWith(MockitoExtension.class)
class OtcServiceRevisionTest {

    @Mock private OtcOfferRepository otcOfferRepository;
    @Mock private OptionContractRepository optionContractRepository;
    @Mock private OtcOfferRevisionRepository revisionRepository;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private StockClient stockClient;
    @Mock private ClientClient clientClient;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private OtcPortfolioService portfolioService;
    @Mock private UserServiceClient userServiceClient;
    @Mock private OtcNotificationProducer notificationProducer;

    @InjectMocks private OtcService service;

    @BeforeEach
    void setUp() {
        lenient().when(otcOfferRepository.save(any())).thenAnswer(inv -> {
            OtcOffer o = inv.getArgument(0);
            o.setId(1L);
            return o;
        });
    }

    private OtcOfferRevision captureRevision() {
        ArgumentCaptor<OtcOfferRevision> captor = ArgumentCaptor.forClass(OtcOfferRevision.class);
        verify(revisionRepository).save(captor.capture());
        return captor.getValue();
    }

    private static OtcOffer pendingOffer(OtcOfferStatus status) {
        OtcOffer offer = new OtcOffer();
        offer.setId(1L);
        offer.setBuyerId(100L);
        offer.setSellerId(200L);
        offer.setStockTicker("AAPL");
        offer.setAmount(10);
        offer.setPricePerStock(new BigDecimal("150.00"));
        offer.setPremium(new BigDecimal("400.00"));
        offer.setSettlementDate(LocalDate.of(2026, 8, 1));
        offer.setStatus(status);
        return offer;
    }

    @Test
    void createOffer_writesCreateRevisionWithNullOldAndPopulatedNew() {
        CreateOtcOfferRequest req = new CreateOtcOfferRequest(
                "AAPL", 200L, 10, new BigDecimal("150.00"), new BigDecimal("400.00"),
                LocalDate.of(2026, 8, 1));

        service.createOffer(100L, req, "Marko Kupac");

        OtcOfferRevision rev = captureRevision();
        assertThat(rev.getOfferId()).isEqualTo(1L);
        assertThat(rev.getAction()).isEqualTo(OtcRevisionAction.CREATE);
        assertThat(rev.getActorUserId()).isEqualTo(100L);
        assertThat(rev.getActorName()).isEqualTo("Marko Kupac");
        assertThat(rev.getActorRole()).isEqualTo("BUYER");
        // CREATE: stara polja null, nova = pocetne vrednosti.
        assertThat(rev.getOldAmount()).isNull();
        assertThat(rev.getOldPricePerStock()).isNull();
        assertThat(rev.getOldPremium()).isNull();
        assertThat(rev.getOldSettlementDate()).isNull();
        assertThat(rev.getNewAmount()).isEqualTo(10);
        assertThat(rev.getNewPricePerStock()).isEqualByComparingTo("150.00");
        assertThat(rev.getNewPremium()).isEqualByComparingTo("400.00");
        assertThat(rev.getNewSettlementDate()).isEqualTo(LocalDate.of(2026, 8, 1));
    }

    @Test
    void counterOffer_writesCounterRevisionWithBothOldAndNew() {
        OtcOffer existing = pendingOffer(OtcOfferStatus.PENDING_BUYER);
        when(otcOfferRepository.findById(1L)).thenReturn(Optional.of(existing));

        CounterOfferRequest req = new CounterOfferRequest(
                12, new BigDecimal("160.00"), new BigDecimal("450.00"), LocalDate.of(2026, 9, 1));
        // Kupac (100L) salje protivponudu.
        service.counterOffer(1L, 100L, req, "Marko Kupac");

        OtcOfferRevision rev = captureRevision();
        assertThat(rev.getAction()).isEqualTo(OtcRevisionAction.COUNTER);
        assertThat(rev.getActorUserId()).isEqualTo(100L);
        assertThat(rev.getActorRole()).isEqualTo("BUYER");
        // COUNTER: stara polja = snapshot PRE izmene.
        assertThat(rev.getOldAmount()).isEqualTo(10);
        assertThat(rev.getOldPricePerStock()).isEqualByComparingTo("150.00");
        assertThat(rev.getOldPremium()).isEqualByComparingTo("400.00");
        assertThat(rev.getOldSettlementDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        // COUNTER: nova polja = vrednosti iz request-a.
        assertThat(rev.getNewAmount()).isEqualTo(12);
        assertThat(rev.getNewPricePerStock()).isEqualByComparingTo("160.00");
        assertThat(rev.getNewPremium()).isEqualByComparingTo("450.00");
        assertThat(rev.getNewSettlementDate()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void counterOffer_bySeller_recordsSellerRole() {
        OtcOffer existing = pendingOffer(OtcOfferStatus.PENDING_SELLER);
        when(otcOfferRepository.findById(1L)).thenReturn(Optional.of(existing));

        CounterOfferRequest req = new CounterOfferRequest(
                8, new BigDecimal("140.00"), new BigDecimal("380.00"), LocalDate.of(2026, 9, 1));
        service.counterOffer(1L, 200L, req, "Petar Prodavac");

        OtcOfferRevision rev = captureRevision();
        assertThat(rev.getActorRole()).isEqualTo("SELLER");
        assertThat(rev.getActorUserId()).isEqualTo(200L);
    }

    @Test
    void accept_writesAcceptRevisionWithSellerActor() {
        OtcOffer offer = pendingOffer(OtcOfferStatus.PENDING_SELLER);
        offer.setAmount(20);
        when(otcOfferRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(offer));
        when(portfolioService.getOtcCapacity(200L, "AAPL")).thenReturn(50L);
        when(otcOfferRepository.sumPendingBySellerAndTickerExcluding(200L, "AAPL", 1L)).thenReturn(0L);
        when(optionContractRepository.save(any(OptionContract.class))).thenAnswer(inv -> {
            OptionContract c = inv.getArgument(0);
            c.setId(99L);
            return c;
        });

        service.accept(1L, 200L);

        OtcOfferRevision rev = captureRevision();
        assertThat(rev.getAction()).isEqualTo(OtcRevisionAction.ACCEPT);
        assertThat(rev.getActorUserId()).isEqualTo(200L);
        assertThat(rev.getActorRole()).isEqualTo("SELLER");
        // ACCEPT: bez izmene vrednosti — stara i nova ostaju neoznacene/jednake.
        assertThat(rev.getNewAmount()).isEqualTo(20);
    }

    @Test
    void reject_writesRejectRevision() {
        OtcOffer offer = pendingOffer(OtcOfferStatus.PENDING_SELLER);
        when(otcOfferRepository.findById(1L)).thenReturn(Optional.of(offer));

        service.reject(1L, 200L);

        OtcOfferRevision rev = captureRevision();
        assertThat(rev.getAction()).isEqualTo(OtcRevisionAction.REJECT);
        assertThat(rev.getActorUserId()).isEqualTo(200L);
        assertThat(rev.getActorRole()).isEqualTo("SELLER");
    }

    @Test
    void withdraw_writesWithdrawRevisionWithBuyerActor() {
        OtcOffer offer = pendingOffer(OtcOfferStatus.PENDING_SELLER);
        when(otcOfferRepository.findById(1L)).thenReturn(Optional.of(offer));

        service.withdraw(1L, 100L);

        OtcOfferRevision rev = captureRevision();
        assertThat(rev.getAction()).isEqualTo(OtcRevisionAction.WITHDRAW);
        assertThat(rev.getActorUserId()).isEqualTo(100L);
        assertThat(rev.getActorRole()).isEqualTo("BUYER");
    }

    // ---- historyForUser ----

    @Test
    @SuppressWarnings("unchecked")
    void historyForUser_returnsOffersNewestFirst() {
        OtcOffer older = pendingOffer(OtcOfferStatus.REJECTED);
        older.setLastModified(LocalDateTime.of(2026, 5, 10, 9, 0));
        OtcOffer newer = pendingOffer(OtcOfferStatus.ACCEPTED);
        newer.setLastModified(LocalDateTime.of(2026, 5, 18, 9, 0));
        when(otcOfferRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(older, newer));

        List<OtcOfferDto> history = service.historyForUser(100L, null, null, null, null);

        assertThat(history).hasSize(2);
        // Najskorije izmenjena prvo.
        assertThat(history.get(0).getStatus()).isEqualTo(OtcOfferStatus.ACCEPTED);
        assertThat(history.get(1).getStatus()).isEqualTo(OtcOfferStatus.REJECTED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void historyForUser_filtersByCounterpartyName() {
        // Druga strana ponude (kupac 100) je prodavac 200.
        OtcOffer offer = pendingOffer(OtcOfferStatus.ACCEPTED);
        offer.setLastModified(LocalDateTime.of(2026, 5, 18, 9, 0));
        when(otcOfferRepository.findAll(any(Specification.class))).thenReturn(List.of(offer));
        CustomerDto seller = new CustomerDto();
        seller.setFirstName("Petar");
        seller.setLastName("Prodavac");
        when(clientClient.getCustomer(200L)).thenReturn(seller);

        // Ime se poklapa -> ponuda prolazi.
        assertThat(service.historyForUser(100L, null, null, null, "Petar Prodavac")).hasSize(1);
        // Ime se ne poklapa -> ponuda otpada.
        assertThat(service.historyForUser(100L, null, null, null, "Neko Drugi")).isEmpty();
    }

    // ---- revisionTrail ----

    @Test
    void revisionTrail_returnsOrderedDtosForParticipant() {
        OtcOffer offer = pendingOffer(OtcOfferStatus.ACCEPTED);
        when(otcOfferRepository.findById(1L)).thenReturn(Optional.of(offer));
        OtcOfferRevision create = OtcOfferRevision.builder()
                .id(10L).offerId(1L).action(OtcRevisionAction.CREATE).actorUserId(100L)
                .actorRole("BUYER").newAmount(10).createdAt(LocalDateTime.of(2026, 5, 10, 9, 0))
                .build();
        OtcOfferRevision counter = OtcOfferRevision.builder()
                .id(11L).offerId(1L).action(OtcRevisionAction.COUNTER).actorUserId(200L)
                .actorRole("SELLER").oldAmount(10).newAmount(12)
                .createdAt(LocalDateTime.of(2026, 5, 12, 9, 0))
                .build();
        when(revisionRepository.findByOfferIdOrderByCreatedAtAscIdAsc(1L))
                .thenReturn(List.of(create, counter));

        // Kupac (100L) je ucesnik -> sme da vidi trag.
        List<OtcOfferRevisionDto> trail = service.revisionTrail(1L, 100L);

        assertThat(trail).hasSize(2);
        assertThat(trail.get(0).getAction()).isEqualTo(OtcRevisionAction.CREATE);
        assertThat(trail.get(1).getAction()).isEqualTo(OtcRevisionAction.COUNTER);
        assertThat(trail.get(1).getOldAmount()).isEqualTo(10);
        assertThat(trail.get(1).getNewAmount()).isEqualTo(12);
    }

    @Test
    void revisionTrail_throwsForNonParticipant() {
        OtcOffer offer = pendingOffer(OtcOfferStatus.ACCEPTED);
        when(otcOfferRepository.findById(1L)).thenReturn(Optional.of(offer));

        // Korisnik 999 nije ni kupac (100) ni prodavac (200) — kao "ne postoji".
        assertThatThrownBy(() -> service.revisionTrail(1L, 999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ne postoji");
    }

    @Test
    void revisionTrail_throwsWhenOfferMissing() {
        when(otcOfferRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revisionTrail(404L, 100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ne postoji");
    }
}
