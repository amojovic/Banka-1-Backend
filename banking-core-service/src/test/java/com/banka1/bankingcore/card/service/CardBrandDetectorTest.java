package com.banka1.bankingcore.card.service;

import com.banka1.card_service.domain.enums.CardBrand;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifikuje brand detection po PAN prefixu (Celina 2.txt spec):
 *   Visa: pocinje sa 4
 *   MasterCard: 51-55 ili 2221-2720
 *   AmEx: 34 ili 37
 *   DinaCard: 9891
 *
 * <p>Banking-core-service konsoliduje card-service kao library dep (PR_02). Brand
 * detekcija u produkciji zivi u enum-u {@link com.banka1.card_service.domain.enums.CardBrand}
 * preko metode {@code matches(String)} — prefix + duzina po izdavacu.
 */
class CardBrandDetectorTest {

    /** Vraca brend ciji issuer-format odgovara PAN-u, ili "UNKNOWN" ako nijedan. */
    private String detect(String pan) {
        Optional<CardBrand> match = Arrays.stream(CardBrand.values())
                .filter(brand -> brand.matches(pan))
                .findFirst();
        return match.map(Enum::name).orElse("UNKNOWN");
    }

    @Test
    void detect_visa_kad_pocinje_sa_4() {
        assertThat(detect("4532015112830366")).isEqualTo("VISA");
    }

    @Test
    void detect_mastercard_kad_pocinje_sa_51_do_55() {
        assertThat(detect("5425233430109903")).isEqualTo("MASTERCARD");
        assertThat(detect("5105105105105100")).isEqualTo("MASTERCARD");
    }

    @Test
    void detect_amex_kad_pocinje_sa_34_ili_37() {
        assertThat(detect("374245455400126")).isEqualTo("AMEX");
        assertThat(detect("340000000000009")).isEqualTo("AMEX");
    }

    @Test
    void detect_unknown_za_nepoznat_prefix() {
        assertThat(detect("9999000000000000")).isEqualTo("UNKNOWN");
    }
}
