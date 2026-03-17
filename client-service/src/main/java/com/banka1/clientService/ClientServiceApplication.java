package com.banka1.clientService;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Ulazna tacka client-service aplikacije.
 * Upravlja klijentima banke (CRUD operacije), dostupna samo zaposlenima.
 */
@EnableScheduling
@SpringBootApplication
public class ClientServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClientServiceApplication.class, args);
    }
}
