package com.banka1.account_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point za Account Service mikroservis u Banka1 sistemu.
 * <p>
 * Servis upravljackim akauntima (tekucim i deviznim), karticama,
 * transakcijama i limitima. Integrisuje se sa ostalim servisima preko REST-a
 * i RabbitMQ-a.
 * <p>
 * Omogucene su:
 * <ul>
 *   <li>Automatska konfiguracija Spring Boot-a</li>
 *   <li>Zakazana poslova (daily/monthly resets, maintenance fees)</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
public class AccountServiceApplication {

	/**
	 * Glavna metoda koja pokrece Spring Boot aplikaciju.
	 *
	 * @param args argumenti komandne linije (napomena: se ne koriste)
	 */
	public static void main(String[] args) {
		SpringApplication.run(AccountServiceApplication.class, args);
	}

}
