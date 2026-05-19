package com.banka1.clientService.dto.requests;

import com.banka1.clientService.domain.enums.Pol;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validaciona provera za {@link ClientCreateRequestDto} – fokus na {@code @NotFutureEpochDate}
 * anotaciju na polju {@code datumRodjenja}.
 */
class ClientCreateRequestDtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private ClientCreateRequestDto validDto() {
        ClientCreateRequestDto dto = new ClientCreateRequestDto();
        dto.setIme("Pera");
        dto.setPrezime("Peric");
        dto.setPol(Pol.M);
        dto.setEmail("pera@banka.com");
        dto.setJmbg("1234567890123");
        dto.setDatumRodjenja(System.currentTimeMillis() - Duration.ofDays(365 * 25L).toMillis());
        return dto;
    }

    @Test
    void pastBirthDateIsValid() {
        Set<ConstraintViolation<ClientCreateRequestDto>> violations = validator.validate(validDto());
        assertTrue(violations.isEmpty(), "validan DTO ne sme imati prekrsaje: " + violations);
    }

    @Test
    void futureBirthDateIsRejected() {
        ClientCreateRequestDto dto = validDto();
        dto.setDatumRodjenja(System.currentTimeMillis() + Duration.ofDays(1).toMillis());

        Set<ConstraintViolation<ClientCreateRequestDto>> violations = validator.validate(dto);

        assertFalse(violations.isEmpty(), "datum rodjenja u buducnosti mora biti odbijen");
        assertTrue(violations.stream()
                        .anyMatch(v -> v.getPropertyPath().toString().equals("datumRodjenja")),
                "prekrsaj mora biti na polju datumRodjenja");
    }

    @Test
    void nullBirthDateIsRejectedByNotNull() {
        ClientCreateRequestDto dto = validDto();
        dto.setDatumRodjenja(null);

        Set<ConstraintViolation<ClientCreateRequestDto>> violations = validator.validate(dto);

        assertTrue(violations.stream()
                        .anyMatch(v -> v.getPropertyPath().toString().equals("datumRodjenja")),
                "null datum rodjenja mora pasti na @NotNull");
    }
}
