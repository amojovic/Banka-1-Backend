package com.banka1.userService.domain.service;

import com.banka1.userService.domain.Zaposlen;

/**
 * Servis za domensku logiku vezanu za zaposlene.
 */
public interface ZaposlenService {

    /**
     * Postavlja skup permisija zaposlenog na osnovu njegove uloge i konfiguracije iz {@code AppProperties}.
     * Zaposleni dobija sve permisije ciji je nivo snage manji ili jednak nivou njegove uloge.
     *
     * @param zaposlen zaposleni kome se postavljaju permisije
     */
    void setovanjePermisija(Zaposlen zaposlen);
}
