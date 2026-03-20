package com.banka1.employeeService;

import com.banka1.employeeService.configuration.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Glavna klasa aplikacije za upravljanje zaposlenima (employee-service).
 * Pokrece Spring Boot kontekst, registruje konfiguraciju i omogucava zakazane taskove.
 */
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
@SpringBootApplication
public class EmployeeServiceApplication {

	/**
	 * Pokrece Spring Boot aplikaciju.
	 *
	 * @param args argumenti komandne linije
	 */
	public static void main(String[] args) {
		SpringApplication.run(EmployeeServiceApplication.class, args);
	}

}
