package com.banka1.transfer.rabbitmq;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * DTO koji sadrži podatke potrebne za asinhrono slanje email notifikacije putem RabbitMQ-a.
 *
 * <p>notification-service potrosac renderuje telo mejla tako sto zameni
 * {@code {{key}}} placeholdere iz {@code templateVariables} (i alias
 * {@code username} -> {@code {{name}}}). Zato se ime primaoca serijalizuje
 * pod JSON kljucem {@code username} (potrosac ga cita direktno), a vrednosti
 * koje sablon TRANSFER_COMPLETED / TRANSFER_FAILED ocekuje ({@code {{amount}}},
 * i {@code {{reason}}} za neuspeh) nose se kroz {@code templateVariables}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailDto {
    /**
     * Ime primaoca za personalizaciju poruke. Serijalizuje se pod JSON kljucem
     * {@code username} kako bi ga notification-service potrosac procitao i
     * iskoristio za {@code {{name}}} placeholder.
     */
    @JsonProperty("username")
    private String ime;

    /** Email adresa na koju se šalje poruka (potrosac je alias-uje u {@code userEmail}). */
    private String email;

    /** Tip poruke (Completed, Failed...). */
    private EmailType emailType;

    /** Sadržaj poruke ili dodatne informacije (slobodan tekst, backward-compatible). */
    private String message;

    /**
     * Promenljive sablona prosledjene notification-service template engine-u.
     * Mapira se na {@code templateVariables} potrosaca. Za TRANSFER_COMPLETED
     * nosi {@code amount}; za TRANSFER_FAILED dodatno {@code reason}.
     */
    private Map<String, String> templateVariables = new HashMap<>();

    /**
     * Identifikator korisnika za koga se kreira in-app notifikacija.
     * Mapira se u {@code recipientUserId} na potrosacu (notification-service);
     * kada je {@code null}, potrosac preskace in-app red i salje samo email.
     */
    private Long recipientUserId;

    /**
     * Diskriminator id prostora primaoca. Za transfer notifikacije uvek
     * {@code CLIENT}. Mapira se u {@code recipientType} na potrosacu.
     */
    private String recipientType;

    /**
     * Kreira payload bez in-app recipient identiteta (backward-compatible).
     *
     * @param ime       ime primaoca
     * @param email     email adresa primaoca
     * @param emailType tip poruke
     * @param message   sadrzaj poruke
     */
    public EmailDto(String ime, String email, EmailType emailType, String message) {
        this.ime = ime;
        this.email = email;
        this.emailType = emailType;
        this.message = message;
        this.templateVariables = new HashMap<>();
    }
}
