package com.banka1.card_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Osnovna integracija test klasa za Card Service.
 * Proverava da li se Spring kontekst uspesno ucitava.
 */
@SpringBootTest
@ActiveProfiles("test")
class CardServiceApplicationTests {

	/**
	 * Proverava da li se aplikacijski kontekst uspesno pokrece.
	 */
	@Test
	void contextLoads() {
	}
}
