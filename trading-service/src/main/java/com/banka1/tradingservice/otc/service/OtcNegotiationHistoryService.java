package com.banka1.tradingservice.otc.service;

import com.banka1.tradingservice.otc.domain.OtcNegotiationEventType;
import com.banka1.tradingservice.otc.domain.OtcNegotiationHistory;
import com.banka1.tradingservice.otc.domain.OtcOffer;
import com.banka1.tradingservice.otc.dto.OtcNegotiationHistoryFilterRequest;
import com.banka1.tradingservice.otc.dto.OtcNegotiationHistoryResponse;
import com.banka1.tradingservice.otc.repository.OtcNegotiationHistoryRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OtcNegotiationHistoryService {

    private final OtcNegotiationHistoryRepository repository;

    @Transactional
    public void record(OtcOffer before, OtcOffer after, OtcNegotiationEventType eventType, Long actorId, String actorName) {
        OtcOffer source = after != null ? after : before;
        if (source == null) {
            return;
        }
        OtcNegotiationHistory row = new OtcNegotiationHistory();
        row.setOfferId(source.getId());
        row.setBuyerId(source.getBuyerId());
        row.setSellerId(source.getSellerId());
        row.setActorId(actorId);
        row.setActorName(actorName);
        row.setEventType(eventType);
        row.setStockTicker(source.getStockTicker());
        if (before != null) {
            row.setOldAmount(before.getAmount());
            row.setOldPricePerStock(before.getPricePerStock());
            row.setOldPremium(before.getPremium());
            row.setOldSettlementDate(before.getSettlementDate());
            row.setOldStatus(before.getStatus());
        }
        if (after != null) {
            row.setNewAmount(after.getAmount());
            row.setNewPricePerStock(after.getPricePerStock());
            row.setNewPremium(after.getPremium());
            row.setNewSettlementDate(after.getSettlementDate());
            row.setNewStatus(after.getStatus());
        }
        repository.save(row);
    }

    @Transactional(readOnly = true)
    public List<OtcNegotiationHistoryResponse> historyForUser(Long userId, OtcNegotiationHistoryFilterRequest filter) {
        return repository.findAll((root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.or(
                    cb.equal(root.get("buyerId"), userId),
                    cb.equal(root.get("sellerId"), userId)
            ));
            if (filter != null) {
                if (filter.getStatus() != null) {
                    predicates.add(cb.equal(root.get("newStatus"), filter.getStatus()));
                }
                if (filter.getOtherPartyId() != null) {
                    predicates.add(cb.or(
                            cb.and(
                                    cb.equal(root.get("buyerId"), userId),
                                    cb.equal(root.get("sellerId"), filter.getOtherPartyId())
                            ),
                            cb.and(
                                    cb.equal(root.get("sellerId"), userId),
                                    cb.equal(root.get("buyerId"), filter.getOtherPartyId())
                            )
                    ));
                }
                if (filter.getDateFrom() != null) {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("changedAt"), filter.getDateFrom().atStartOfDay()));
                }
                if (filter.getDateTo() != null) {
                    predicates.add(cb.lessThan(root.get("changedAt"), filter.getDateTo().plusDays(1).atStartOfDay()));
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        }, Sort.by(Sort.Direction.DESC, "changedAt")).stream().map(row -> OtcNegotiationHistoryResponse.builder()
                .id(row.getId())
                .offerId(row.getOfferId())
                .buyerId(row.getBuyerId())
                .sellerId(row.getSellerId())
                .actorId(row.getActorId())
                .actorName(row.getActorName())
                .eventType(row.getEventType())
                .stockTicker(row.getStockTicker())
                .oldAmount(row.getOldAmount())
                .newAmount(row.getNewAmount())
                .oldPricePerStock(row.getOldPricePerStock())
                .newPricePerStock(row.getNewPricePerStock())
                .oldPremium(row.getOldPremium())
                .newPremium(row.getNewPremium())
                .oldSettlementDate(row.getOldSettlementDate())
                .newSettlementDate(row.getNewSettlementDate())
                .oldStatus(row.getOldStatus())
                .newStatus(row.getNewStatus())
                .changedAt(row.getChangedAt())
                .build())
                .toList();
    }

    public OtcOffer snapshot(OtcOffer offer) {
        if (offer == null) {
            return null;
        }
        OtcOffer copy = new OtcOffer();
        copy.setId(offer.getId());
        copy.setStockTicker(offer.getStockTicker());
        copy.setBuyerId(offer.getBuyerId());
        copy.setSellerId(offer.getSellerId());
        copy.setAmount(offer.getAmount());
        copy.setPricePerStock(offer.getPricePerStock());
        copy.setPremium(offer.getPremium());
        copy.setSettlementDate(offer.getSettlementDate());
        copy.setStatus(offer.getStatus());
        copy.setModifiedBy(offer.getModifiedBy());
        copy.setLastModified(offer.getLastModified());
        copy.setCreatedAt(offer.getCreatedAt());
        copy.setVersion(offer.getVersion());
        return copy;
    }
}
