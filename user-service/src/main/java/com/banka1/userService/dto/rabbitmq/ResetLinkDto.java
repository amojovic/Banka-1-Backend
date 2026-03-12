package com.banka1.userService.dto.rabbitmq;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO koji nosi link za reset lozinke ili aktivaciju naloga unutar {@link EmailDto}.
 * Tacno jedno od polja ({@code resetLink} ili {@code activationLink}) bice popunjeno
 * u zavisnosti od tipa mejla. Polja sa {@code null} vrednoscu se preskaciju pri serijalizaciji.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@NoArgsConstructor
@Getter
@Setter
public class ResetLinkDto {

    /**
     * Link za reset lozinke (popunjava se samo za {@link EmailType#EMPLOYEE_PASSWORD_RESET}).
     */
    private String resetLink;

    /**
     * Link za aktivaciju naloga (popunjava se samo za {@link EmailType#EMPLOYEE_CREATED}).
     */
    private String activationLink;

    /**
     * Popunjava odgovarajuce polje za link na osnovu tipa email poruke.
     *
     * @param email     link za reset lozinke ili aktivaciju naloga
     * @param emailType tip mejla koji odredjuje koje polje se popunjava
     */
    public ResetLinkDto(String email, EmailType emailType) {
        switch (emailType) {
            case EmailType.EMPLOYEE_PASSWORD_RESET -> resetLink = email;
            case EmailType.EMPLOYEE_CREATED -> activationLink = email;
            default -> throw new IllegalStateException("Kako si ovo uspeo majke ti");
        }
    }
}
