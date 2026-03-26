package com.banka1.account_service.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * DTO za zahtev azuriranja dnevnog i mesecnog limita trosenja.
 * <p>
 * Omogucava vlasnicima racuna da promene svoje dnevne i mesečne limite
 * trosenja (spending limit). Zahteva verifikaciju preko mobilne aplikacije.
 * <p>
 * Validacija:
 * <ul>
 *   <li>Oba limitni moraju biti > 0</li>
 *   <li>Dnevni limit mora biti <= mesečni limit</li>
 *   <li>Verifikacijski kod mora biti popunjen</li>
 *   <li>Verifikacijska sesija mora biti validna</li>
 * </ul>
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class EditAccountLimitDto {
    /**
     * Novi dnevni limit trosenja.
     * <p>
     * Mora biti manji ili jednak od mesečnog limitna i veći od 0.
     */
    @NotNull(message = "Unesi dnevni limit racuna")
    @DecimalMin(value = "0.0", inclusive = false)
    private BigDecimal dailyLimit;

    /**
     * Novi mesečni limit trosenja.
     * <p>
     * Mora biti veći ili jednak od dnevnog limitna.
     */
    @NotNull(message = "Unesi mesecni limit racuna")
    @DecimalMin(value = "0.0", inclusive = false)
    private BigDecimal monthlyLimit;

    /**
     * Kod za verifikaciju koji je korisnik prosledio iz mobilne aplikacije.
     */
    @NotBlank(message = "Unesi kod za verifikaciju")
    private String verificationCode;

    /**
     * ID sesije verifikacije koja je iniciјalna na mobilnoj aplikaciji.
     */
    @NotNull(message = "Unesi verification session ID")
    private Long verificationSessionId;
}
