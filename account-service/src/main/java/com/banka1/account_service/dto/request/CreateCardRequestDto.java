package com.banka1.account_service.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class CreateCardRequestDto {
    private Long clientId;
    private String accountNumber;
    private String accountName;
    private String accountCurrency;
    private String accountCategory;
    private String accountType;
    private String accountSubtype;
    private String ownerFirstName;
    private String ownerLastName;
    private String ownerEmail;
    private String ownerUsername;
    private LocalDate accountExpirationDate;
}

