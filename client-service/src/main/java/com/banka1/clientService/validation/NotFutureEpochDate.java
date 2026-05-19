package com.banka1.clientService.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Bean Validation ogranicenje koje proverava da epoch-milisekundni datum nije u buducnosti.
 * Namenjeno poljima tipa {@link Long} koja predstavljaju Unix timestamp (npr. datum rodjenja),
 * gde standardne {@code @Past}/{@code @PastOrPresent} anotacije nisu primenljive.
 * {@code null} vrednost se smatra validnom – prisustvo se proverava odvojeno preko {@code @NotNull}.
 */
@Documented
@Constraint(validatedBy = NotFutureEpochDateValidator.class)
@Target({FIELD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface NotFutureEpochDate {

    /** Poruka greske koja se vraca kada je datum u buducnosti. */
    String message() default "Datum ne sme biti u buducnosti";

    /** Grupe validacije (standardni Bean Validation clan). */
    Class<?>[] groups() default {};

    /** Payload metapodaci (standardni Bean Validation clan). */
    Class<? extends Payload>[] payload() default {};
}
