package com.banka1.account_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class CardResponseDto {
    private Long id;
    private String cardNumber;
    private String cardType;
    private String status;
    private String expiryDate;
    private String accountNumber;
}
