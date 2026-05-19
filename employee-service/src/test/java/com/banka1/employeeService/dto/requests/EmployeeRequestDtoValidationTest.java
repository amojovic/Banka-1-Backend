package com.banka1.employeeService.dto.requests;

import com.banka1.employeeService.domain.enums.Pol;
import com.banka1.employeeService.domain.enums.Role;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validacione provere za DTO objekte zaposlenog:
 * {@code @PastOrPresent} na {@link EmployeeCreateRequestDto#getDatumRodjenja()} i
 * {@code @Pattern} na {@link EmployeeEditRequestDto#getBrojTelefona()}.
 */
class EmployeeRequestDtoValidationTest {

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

    private EmployeeCreateRequestDto validCreateDto() {
        EmployeeCreateRequestDto dto = new EmployeeCreateRequestDto();
        dto.setIme("Mika");
        dto.setPrezime("Mikic");
        dto.setDatumRodjenja(LocalDate.now().minusYears(30));
        dto.setPol(Pol.M);
        dto.setEmail("mika@banka.com");
        dto.setPozicija("Sef");
        dto.setDepartman("IT");
        dto.setRole(Role.BASIC);
        return dto;
    }

    @Test
    void createDto_pastBirthDateIsValid() {
        Set<ConstraintViolation<EmployeeCreateRequestDto>> violations = validator.validate(validCreateDto());
        assertTrue(violations.isEmpty(), "validan DTO ne sme imati prekrsaje: " + violations);
    }

    @Test
    void createDto_futureBirthDateIsRejected() {
        EmployeeCreateRequestDto dto = validCreateDto();
        dto.setDatumRodjenja(LocalDate.now().plusDays(1));

        Set<ConstraintViolation<EmployeeCreateRequestDto>> violations = validator.validate(dto);

        assertFalse(violations.isEmpty(), "datum rodjenja u buducnosti mora biti odbijen");
        assertTrue(violations.stream()
                        .anyMatch(v -> v.getPropertyPath().toString().equals("datumRodjenja")),
                "prekrsaj mora biti na polju datumRodjenja");
    }

    @Test
    void editDto_validPhoneNumberPasses() {
        EmployeeEditRequestDto dto = new EmployeeEditRequestDto();
        dto.setBrojTelefona("+381641234567");

        Set<ConstraintViolation<EmployeeEditRequestDto>> violations = validator.validate(dto);

        assertTrue(violations.isEmpty(), "validan broj telefona ne sme imati prekrsaje: " + violations);
    }

    @Test
    void editDto_nullPhoneNumberPasses() {
        EmployeeEditRequestDto dto = new EmployeeEditRequestDto();

        Set<ConstraintViolation<EmployeeEditRequestDto>> violations = validator.validate(dto);

        assertTrue(violations.isEmpty(), "null broj telefona je dozvoljen (opciono polje)");
    }

    @Test
    void editDto_phoneNumberWithLettersIsRejected() {
        EmployeeEditRequestDto dto = new EmployeeEditRequestDto();
        dto.setBrojTelefona("06abc1234");

        Set<ConstraintViolation<EmployeeEditRequestDto>> violations = validator.validate(dto);

        assertFalse(violations.isEmpty(), "broj telefona sa slovima mora biti odbijen");
        assertTrue(violations.stream()
                        .anyMatch(v -> v.getPropertyPath().toString().equals("brojTelefona")),
                "prekrsaj mora biti na polju brojTelefona");
    }

    @Test
    void editDto_phoneNumberWithPlusNotAtStartIsRejected() {
        EmployeeEditRequestDto dto = new EmployeeEditRequestDto();
        dto.setBrojTelefona("381+641234567");

        Set<ConstraintViolation<EmployeeEditRequestDto>> violations = validator.validate(dto);

        assertFalse(violations.isEmpty(), "znak '+' van pocetka mora biti odbijen");
    }
}
