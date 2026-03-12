package com.banka1.userService.domain.enums;

import lombok.Getter;

/**
 * Enum koji definise RBAC uloge zaposlenih.
 * Svaka uloga ima numericki nivo snage ({@code power}) koji se koristi za poredjenje
 * pri odlucivanju da li korisnik ima dovoljnu privilegiju za operaciju nad drugim korisnikom.
 */
@Getter
public enum Role {

    /** Osnovna uloga – standardne bankarske operacije. */
    BASIC(1),

    /** Uloga agenta – dozvoljava trgovinu hartijama od vrednosti. */
    AGENT(2),

    /** Uloga supervizora – ukljucuje OTC operacije. */
    SUPERVISOR(3),

    /** Administratorska uloga – puno upravljanje svim zaposlenima. */
    ADMIN(4);

    /** Numericki nivo privilegije; veca vrednost znaci vise prava. */
    private final int power;

    /**
     * Kreira ulogu sa zadatim nivoom privilegije.
     *
     * @param power numericki nivo privilegije
     */
    Role(int power) {
        this.power = power;
    }
}
