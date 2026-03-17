package com.banka1.clientService.dto.rabbitmq;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO koji se salje RabbitMQ email servisu.
 * Sadrzi podatke potrebne za generisanje odgovarajuceg email-a.
 * Polja sa {@code null} vrednoscu se iskljucuju iz JSON serijalizacije.
 */
@NoArgsConstructor
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EmailDto {

    /**
     * Email adresa primaoca.
     */
    private String userEmail;

    /**
     * Ime ili korisnicko ime primaoca (koristi se u tekstu mejla).
     */
    private String username;

    /**
     * Tip email notifikacije koji odredjuje sadrzaj i sablonu mejla.
     */
    private EmailType emailType;

    /**
     * Opcioni objekat sa linkom za reset lozinke ili aktivaciju naloga.
     */
    private ResetLinkDto templateVariables;

    /**
     * Kreira payload za mejl koji sadrzi i link za aktivaciju ili reset lozinke.
     *
     * @param username  korisnicko ime ili ime za prikaz
     * @param userEmail email adresa primaoca
     * @param emailType tip email notifikacije
     * @param link      link koji se salje korisniku
     */
    public EmailDto(String username, String userEmail, EmailType emailType, String link) {
        this.userEmail = userEmail;
        this.username = username;
        this.emailType = emailType;
        templateVariables = new ResetLinkDto(link, emailType);
    }

    /**
     * Kreira payload za mejl koji ne zahteva dodatni link.
     *
     * @param username  korisnicko ime ili ime za prikaz
     * @param userEmail email adresa primaoca
     * @param emailType tip email notifikacije
     */
    public EmailDto(String username, String userEmail, EmailType emailType) {
        this.userEmail = userEmail;
        this.username = username;
        this.emailType = emailType;
    }
}
