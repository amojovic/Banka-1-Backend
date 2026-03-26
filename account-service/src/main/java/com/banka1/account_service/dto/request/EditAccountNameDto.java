package com.banka1.account_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO za zahtev promenе названa bankarskog racuna.
 * <p>
 * Omogucava vlasnicima racuna da promene naziv svog racuna
 * na nešto što je lakše za pamcenje.
 * <p>
 * Validacija:
 * <ul>
 *   <li>Naziv mora biti popunjen</li>
 *   <li>Naziv mora imati između 3 i 50 karaktera</li>
 *   <li>Naziv mora biti jedinstven za tog vlasnika</li>
 * </ul>
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class EditAccountNameDto {
    /**
     * Novi naziv za racun.
     * <p>
     * Mora biti drugačiji od trenutnog naziva i ne sme se
     * podudarati sa nazivom drugog racuna istog vlasnika.
     */
    @NotBlank(message = "Unesi accountName")
    @Size(min = 3, max = 50)
    private String accountName;
}
