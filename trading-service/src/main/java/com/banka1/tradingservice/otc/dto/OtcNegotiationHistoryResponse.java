package com.banka1.tradingservice.otc.dto;

import com.banka1.tradingservice.otc.domain.OtcNegotiationEventType;
import com.banka1.tradingservice.otc.domain.OtcOfferStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class OtcNegotiationHistoryResponse {

    private Long id;
    private Long offerId;
    private Long buyerId;
    private Long sellerId;
    private Long actorId;
    private String actorName;
    private OtcNegotiationEventType eventType;
    private String stockTicker;
    private Integer oldAmount;
    private Integer newAmount;
    private BigDecimal oldPricePerStock;
    private BigDecimal newPricePerStock;
    private BigDecimal oldPremium;
    private BigDecimal newPremium;
    private LocalDate oldSettlementDate;
    private LocalDate newSettlementDate;
    private OtcOfferStatus oldStatus;
    private OtcOfferStatus newStatus;
    private LocalDateTime changedAt;
}
