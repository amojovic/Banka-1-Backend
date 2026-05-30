package com.banka1.tradingservice.audit;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * WP-2: minimalni {@code @SpringBootConfiguration} za audit slice testove.
 *
 * <p>{@code @DataJpaTest}/{@code @WebMvcTest} trazi najblizu
 * {@code @SpringBootConfiguration} klasu sirenjem nagore kroz pakete. Bez ove
 * klase slice bi pronasao {@code TradingServiceApplication}, cija eksplicitna
 * {@code @ComponentScan(basePackages="com.banka1")} povlaci {@code security-lib}
 * {@code SecurityConfig} ({@code apiChain}) i ruzi slice kontekst
 * ({@code UnreachableFilterChainException}).
 *
 * <p>Namerno BEZ {@code @ComponentScan}: slice anotacije same registruju
 * relevantne beanove ({@code @WebMvcTest(controllers=...)} svoj kontroler,
 * {@code @DataJpaTest} entitete + repozitorijume preko {@code @EntityScan}/
 * {@code @EnableJpaRepositories} iz {@code @EnableAutoConfiguration}). Sirok
 * scan bi povukao {@code AuditPublisher}/{@code AuditEventListener} (traze
 * {@code RabbitTemplate}) u WebMvc slice gde broker nije dostupan.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class AuditSliceTestConfiguration {
}
