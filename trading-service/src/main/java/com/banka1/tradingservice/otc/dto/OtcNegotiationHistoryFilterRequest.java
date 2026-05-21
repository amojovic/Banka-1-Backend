package com.banka1.tradingservice.otc.dto;

import com.banka1.tradingservice.otc.domain.OtcOfferStatus;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Data
public class OtcNegotiationHistoryFilterRequest {

    private OtcOfferStatus status;
    private Long otherPartyId;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateTo;
}
