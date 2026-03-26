package com.banka1.account_service.rabbitMQ;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class CardEventDto {
    private Long clientId;
    private String accountNumber;
    private CardEventType eventType;
}
