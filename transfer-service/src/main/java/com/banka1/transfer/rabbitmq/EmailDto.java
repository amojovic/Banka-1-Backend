package com.banka1.transfer.rabbitmq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO koji sadrži podatke potrebne za asinhrono slanje email notifikacije putem RabbitMQ-a.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailDto {
    private String ime; // Ime primaoca za personalizaciju poruke
    private String email; // Email adresa na koju se šalje poruka
    private EmailType emailType;  // Tip poruke (Completed, Failed...)
    private String message;  // Sadržaj poruke ili dodatne informacije
}

