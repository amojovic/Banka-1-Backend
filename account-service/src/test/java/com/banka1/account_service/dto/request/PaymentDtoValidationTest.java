package com.banka1.account_service.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentDtoValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        validatorFactory.close();
    }

    @Test
    void accountNumbersAccept18And19DigitsAndRejectWrongLength() {
        // Produkcijski PaymentDto regex je ^\d{18,19}$ — prihvata i 18 i 19 cifara
        // (18 je aktuelni format po Celini 2, 19 ostavljen radi tranzicione kompatibilnosti).
        PaymentDto valid19 = validDto();
        assertThat(validator.validate(valid19)).isEmpty();

        PaymentDto valid18 = validDto();
        valid18.setFromAccountNumber("111000100000000011");  // 18 digits — valid
        valid18.setToAccountNumber("111000100000000219");    // 18 digits — valid
        assertThat(validator.validate(valid18)).isEmpty();

        PaymentDto invalid = validDto();
        invalid.setFromAccountNumber("11100010000000011");  // 17 digits — should fail
        Set<ConstraintViolation<PaymentDto>> violations = validator.validate(invalid);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("fromAccountNumber");
    }

    @Test
    void fromAmountRejectsZeroAndNegative() {
        PaymentDto zero = validDto();
        zero.setFromAmount(BigDecimal.ZERO);

        Set<ConstraintViolation<PaymentDto>> zeroViolations = validator.validate(zero);
        assertThat(zeroViolations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("fromAmount");

        PaymentDto negative = validDto();
        negative.setFromAmount(new BigDecimal("-1"));

        Set<ConstraintViolation<PaymentDto>> negativeViolations = validator.validate(negative);
        assertThat(negativeViolations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("fromAmount");
    }

    @Test
    void toAmountRejectsZeroAndNegative() {
        PaymentDto zero = validDto();
        zero.setToAmount(BigDecimal.ZERO);

        Set<ConstraintViolation<PaymentDto>> zeroViolations = validator.validate(zero);
        assertThat(zeroViolations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("toAmount");

        PaymentDto negative = validDto();
        negative.setToAmount(new BigDecimal("-1"));

        Set<ConstraintViolation<PaymentDto>> negativeViolations = validator.validate(negative);
        assertThat(negativeViolations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("toAmount");
    }

    @Test
    void commissionAllowsZeroAndRejectsNegative() {
        PaymentDto zeroCommission = validDto();
        zeroCommission.setCommission(BigDecimal.ZERO);

        Set<ConstraintViolation<PaymentDto>> zeroViolations = validator.validate(zeroCommission);
        assertThat(zeroViolations).isEmpty();

        PaymentDto negativeCommission = validDto();
        negativeCommission.setCommission(new BigDecimal("-0.01"));

        Set<ConstraintViolation<PaymentDto>> negativeViolations = validator.validate(negativeCommission);
        assertThat(negativeViolations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("commission");
    }

    private PaymentDto validDto() {
        return new PaymentDto(
                "1110001000000000115",
                "1110001000000000116",
                new BigDecimal("100.00"),
                new BigDecimal("99.00"),
                BigDecimal.ZERO,
                42L
        );
    }
}

