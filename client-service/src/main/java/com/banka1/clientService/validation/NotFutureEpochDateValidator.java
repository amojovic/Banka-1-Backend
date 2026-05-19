package com.banka1.clientService.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validator za {@link NotFutureEpochDate} ogranicenje.
 * Odbija epoch-milisekundne vrednosti koje su u buducnosti u odnosu na trenutno vreme servera.
 * {@code null} se tretira kao validno – prisustvo polja je odgovornost {@code @NotNull} anotacije.
 */
public class NotFutureEpochDateValidator implements ConstraintValidator<NotFutureEpochDate, Long> {

    /**
     * Proverava da li je zadati epoch-milisekundni datum validan (nije u buducnosti).
     *
     * @param value   epoch timestamp u milisekundama; {@code null} je dozvoljeno
     * @param context kontekst validacije (nije koriscen)
     * @return {@code true} ako je {@code value} {@code null} ili nije posle trenutnog vremena
     */
    @Override
    public boolean isValid(Long value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return value <= System.currentTimeMillis();
    }
}
