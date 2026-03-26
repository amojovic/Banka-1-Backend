package com.banka1.transfer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Glavna konfiguraciona i izvršna klasa Transfer mikroservisa.
 * Pokreće Spring Boot aplikaciju i inicijalizuje kontekst.
 */
@SpringBootApplication
public class TransferServiceApplication {
    /**
     * Ulazna tačka aplikacije.
     * @param args Argumenti komandne linije.
     */
    public static void main(String[] args) {
        SpringApplication.run(TransferServiceApplication.class, args);
    }
}
