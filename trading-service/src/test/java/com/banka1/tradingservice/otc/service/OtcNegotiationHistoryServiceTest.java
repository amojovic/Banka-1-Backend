package com.banka1.tradingservice.otc.service;

import com.banka1.tradingservice.otc.domain.OtcNegotiationEventType;
import com.banka1.tradingservice.otc.domain.OtcNegotiationHistory;
import com.banka1.tradingservice.otc.domain.OtcOffer;
import com.banka1.tradingservice.otc.domain.OtcOfferStatus;
import com.banka1.tradingservice.otc.dto.OtcNegotiationHistoryFilterRequest;
import com.banka1.tradingservice.otc.repository.OtcNegotiationHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OtcNegotiationHistoryServiceTest {

    @Mock
    private OtcNegotiationHistoryRepository repository;

    @InjectMocks
    private OtcNegotiationHistoryService service;

    @Test
    void record_persistiraStareINoveVrednosti() {
        OtcOffer before = offer(OtcOfferStatus.PENDING_BUYER, 10, new BigDecimal("100"));
        OtcOffer after = offer(OtcOfferStatus.PENDING_SELLER, 12, new BigDecimal("110"));

        service.record(before, after, OtcNegotiationEventType.COUNTER_OFFERED, 100L, "Buyer");

        verify(repository).save(any(OtcNegotiationHistory.class));
    }

    @Test
    void historyForUser_primenjujeFiltereISortiraDesc() {
        OtcNegotiationHistory row = new OtcNegotiationHistory();
        row.setId(1L);
        row.setOfferId(5L);
        row.setBuyerId(100L);
        row.setSellerId(200L);
        row.setActorId(100L);
        row.setActorName("Buyer");
        row.setEventType(OtcNegotiationEventType.COUNTER_OFFERED);
        row.setStockTicker("AAPL");
        row.setOldStatus(OtcOfferStatus.PENDING_BUYER);
        row.setNewStatus(OtcOfferStatus.PENDING_SELLER);
        row.setChangedAt(LocalDateTime.now());

        when(repository.findAll(any(Specification.class), eq(Sort.by(Sort.Direction.DESC, "changedAt"))))
                .thenReturn(List.of(row));

        OtcNegotiationHistoryFilterRequest filter = new OtcNegotiationHistoryFilterRequest();
        filter.setStatus(OtcOfferStatus.PENDING_SELLER);
        filter.setOtherPartyId(200L);
        filter.setDateFrom(LocalDate.now().minusDays(1));
        filter.setDateTo(LocalDate.now());

        var results = service.historyForUser(100L, filter);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getOfferId()).isEqualTo(5L);
        assertThat(results.get(0).getNewStatus()).isEqualTo(OtcOfferStatus.PENDING_SELLER);
    }

    private static OtcOffer offer(OtcOfferStatus status, int amount, BigDecimal price) {
        OtcOffer offer = new OtcOffer();
        offer.setId(5L);
        offer.setBuyerId(100L);
        offer.setSellerId(200L);
        offer.setStockTicker("AAPL");
        offer.setAmount(amount);
        offer.setPricePerStock(price);
        offer.setPremium(new BigDecimal("25"));
        offer.setSettlementDate(LocalDate.now().plusDays(10));
        offer.setStatus(status);
        return offer;
    }
}
