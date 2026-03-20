package com.banka1.account_service.dto.response;

import com.banka1.account_service.domain.Account;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class AccountResponseDto {
    private String nazivRacuna;
    private String brojRacuna;
    private BigDecimal raspolozivoStanje;

    public AccountResponseDto(Account account)
    {
       this.nazivRacuna=account.getNazivRacuna();
       this.brojRacuna=account.getBrojRacuna();
       this.raspolozivoStanje=account.getRaspolozivoStanje();
    }
}
