package com.banka1.account_service.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
//todo zasto je sve 12 12 12 ko je smisljao ovu glupost, nadam se da moze da ide inkrementalno i tu
public enum AccountConcrete {
    STANDARDNI(AccountOwnershipType.PERSONAL,11),
    STEDNI(AccountOwnershipType.PERSONAL,13),
    PENZIONERSKI(AccountOwnershipType.PERSONAL,14),
    ZA_MLADE(AccountOwnershipType.PERSONAL,15),
    ZA_STUDENTE(AccountOwnershipType.PERSONAL,16),
    ZA_NEZAPOSLENE(AccountOwnershipType.PERSONAL,17),
    DOO(AccountOwnershipType.BUSINESS,12),
    AD(AccountOwnershipType.BUSINESS,12),
    FONDACIJA(AccountOwnershipType.BUSINESS,12);

    private final AccountOwnershipType accountOwnershipType;
    private final int val;

}
