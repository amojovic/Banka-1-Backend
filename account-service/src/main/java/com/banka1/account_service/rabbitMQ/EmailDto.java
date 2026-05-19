package com.banka1.account_service.rabbitMQ;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

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
     * Promenljive sablona (npr. broj racuna, stara/nova vrednost limita).
     * Mapiraju se u {@code templateVariables} na potrosacu (notification-service).
     */
    private Map<String, String> templateVariables;

    /**
     * Identifikator korisnika za koga se kreira in-app notifikacija.
     * Polje se na potrosacu (notification-service) mapira u {@code recipientUserId};
     * kada je {@code null}, potrosac preskace in-app red i salje samo email.
     */
    private Long recipientUserId;

    /**
     * Diskriminator id prostora primaoca — {@code CLIENT} ili {@code EMPLOYEE}.
     * Mapira se u {@code recipientType} na potrosacu; obavezno zajedno sa
     * {@code recipientUserId} da bi se kreirao in-app red.
     */
    private String recipientType;

    /**
     * Kreira payload za mejl koji ne zahteva dodatni link.
     *
     * @param userEmail email adresa primaoca
     * @param username  korisnicko ime ili ime za prikaz
     * @param emailType tip email notifikacije
     */
    public EmailDto(String username, String userEmail, EmailType emailType) {
        this.userEmail = userEmail;
        this.username = username;
        this.emailType = emailType;
    }

    /**
     * Kreira payload za {@link EmailType#ACCOUNT_LIMIT_CHANGED} notifikaciju.
     * Stara i nova vrednost dnevnog/mesecnog limita se prosledjuju kroz
     * {@code templateVariables} kako bi ih sablona potrosaca renderovala.
     *
     * @param username        korisnicko ime ili ime za prikaz
     * @param userEmail       email adresa primaoca
     * @param accountNumber   broj racuna na kome je limit izmenjen
     * @param oldDailyLimit   prethodni dnevni limit
     * @param newDailyLimit   novi dnevni limit
     * @param oldMonthlyLimit prethodni mesecni limit
     * @param newMonthlyLimit novi mesecni limit
     */
    public EmailDto(String username,
                    String userEmail,
                    String accountNumber,
                    BigDecimal oldDailyLimit,
                    BigDecimal newDailyLimit,
                    BigDecimal oldMonthlyLimit,
                    BigDecimal newMonthlyLimit) {
        this.userEmail = userEmail;
        this.username = username;
        this.emailType = EmailType.ACCOUNT_LIMIT_CHANGED;
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("accountNumber", accountNumber);
        variables.put("oldDailyLimit", String.valueOf(oldDailyLimit));
        variables.put("newDailyLimit", String.valueOf(newDailyLimit));
        variables.put("oldMonthlyLimit", String.valueOf(oldMonthlyLimit));
        variables.put("newMonthlyLimit", String.valueOf(newMonthlyLimit));
        // Sablona ACCOUNT_LIMIT_CHANGED koristi {{newLimit}} — punimo ga novim dnevnim limitom.
        variables.put("newLimit", String.valueOf(newDailyLimit));
        this.templateVariables = variables;
    }
}
