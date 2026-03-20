package com.banka1.account_service.dto.response;

import com.banka1.account_service.domain.Account;
import com.banka1.account_service.domain.CheckingAccount;
import com.banka1.account_service.domain.FxAccount;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class AccountDetailsResponseDto {
    private String nazivRacuna;
    private String brojRacuna;
    private Long vlasnik;
    private String tip;
    private BigDecimal raspolozivoStanje;
    private BigDecimal rezervisanaSredstva;
    private BigDecimal stanjeRacuna;
    private String nazivFirme;

    public AccountDetailsResponseDto(Account account) {
        this.nazivRacuna = account.getNazivRacuna();
        this.brojRacuna = account.getBrojRacuna();
        this.vlasnik = account.getVlasnik();
        if (account instanceof CheckingAccount ca) {
            this.tip = "tekuci";
        } else
            if (account instanceof FxAccount fa) {
                this.tip = "devizni";
        }
        this.raspolozivoStanje = account.getRaspolozivoStanje();
         if(account.getStanje()!=null && account.getRaspolozivoStanje()!=null)
            this.rezervisanaSredstva = account.getStanje().subtract(account.getRaspolozivoStanje());
        this.stanjeRacuna = account.getStanje();
        if(account.getCompany()!=null)
            this.nazivFirme=account.getCompany().getNaziv();
    }
}
