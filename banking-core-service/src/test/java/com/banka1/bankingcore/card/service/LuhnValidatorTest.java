package com.banka1.bankingcore.card.service;

import com.banka1.card_service.service.LuhnService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifikuje Luhn checksum algoritam za PAN brojeve (Celina 2.txt spec).
 *
 * <p>Banking-core-service konsoliduje card-service kao library dep (PR_02), pa se
 * Luhn validacija testira nad stvarnom produkcionom klasom
 * {@link com.banka1.card_service.service.LuhnService}.
 */
class LuhnValidatorTest {

    private final LuhnService validator = new LuhnService();

    @ParameterizedTest
    @ValueSource(strings = {
            "4532015112830366",  // Visa
            "5425233430109903",  // MasterCard
            "374245455400126",   // AmEx (15 cifara)
            "6011000990139424"   // Discover
    })
    void validatePan_prepoznaje_validan_Luhn_PAN(String pan) {
        assertThat(validator.isValid(pan)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "4532015112830367",  // Visa sa pogresnim check digit-om
            "1234567890123456"   // Generic invalid
    })
    void validatePan_odbija_pogresne_Luhn_PAN(String pan) {
        assertThat(validator.isValid(pan)).isFalse();
    }

    @Test
    void validatePan_odbija_prazno_ili_null() {
        assertThat(validator.isValid(null)).isFalse();
        assertThat(validator.isValid("")).isFalse();
    }

    @Test
    void validatePan_odbija_ne_cifre() {
        assertThat(validator.isValid("4532-0151-1283-0366")).isFalse();
    }
}
