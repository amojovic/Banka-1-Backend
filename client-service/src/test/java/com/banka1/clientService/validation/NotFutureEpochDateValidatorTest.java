package com.banka1.clientService.validation;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jedinicni testovi za {@link NotFutureEpochDateValidator}.
 * Validator se instancira direktno (bez Spring konteksta) jer ne zavisi od injektovanih beanova.
 */
class NotFutureEpochDateValidatorTest {

    /** Validator pod testom; {@code ConstraintValidatorContext} nije potreban (nije referenciran u logici). */
    private final NotFutureEpochDateValidator validator = new NotFutureEpochDateValidator();

    @Test
    void nullValueIsValid() {
        assertTrue(validator.isValid(null, null), "null vrednost prepusta se @NotNull anotaciji");
    }

    @Test
    void pastEpochMillisIsValid() {
        long past = System.currentTimeMillis() - Duration.ofDays(365 * 30L).toMillis();
        assertTrue(validator.isValid(past, null), "datum u proslosti je validan");
    }

    @Test
    void currentEpochMillisIsValid() {
        assertTrue(validator.isValid(System.currentTimeMillis(), null), "trenutni trenutak je validan");
    }

    @Test
    void futureEpochMillisIsInvalid() {
        long future = System.currentTimeMillis() + Duration.ofDays(1).toMillis();
        assertFalse(validator.isValid(future, null), "datum u buducnosti nije validan");
    }

    @Test
    void epochZeroIsValid() {
        assertTrue(validator.isValid(0L, null), "epoch nula (1970) je validan");
    }
}
