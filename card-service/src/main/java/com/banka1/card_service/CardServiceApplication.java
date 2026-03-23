package com.banka1.card_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Glavna klasa Card Service mikroservisa.
 * Ovaj servis upravlja debitnim karticama povezanim sa bankovnim racunima
 * (kreiranje, blokiranje, deblokiranje, deaktivacija, provera limita).
 */
@SpringBootApplication
public class CardServiceApplication {

	/**
	 * Pokretanje Spring Boot aplikacije.
	 *
	 * @param args argumenti komandne linije
	 */
	public static void main(String[] args) {
		SpringApplication.run(CardServiceApplication.class, args);
	}
}
