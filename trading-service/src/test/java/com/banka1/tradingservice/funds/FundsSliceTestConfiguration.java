package com.banka1.tradingservice.funds;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * WP-17: minimalni {@code @SpringBootConfiguration} za funds slice testove.
 *
 * <p>{@code @DataJpaTest}/{@code @WebMvcTest} trazi najblizu
 * {@code @SpringBootConfiguration} klasu sirenjem nagore kroz pakete. Bez ove
 * klase slice bi pronasao {@code TradingServiceApplication}, cija eksplicitna
 * {@code @ComponentScan(basePackages="com.banka1")} povlaci {@code security-lib}
 * {@code SecurityConfig} ({@code apiChain}) i ruzi slice kontekst
 * ({@code UnreachableFilterChainException}).
 *
 * <p>Namerno BEZ {@code @ComponentScan} (isti razlog kao
 * {@code AuditSliceTestConfiguration} / {@code DividendSliceTestConfiguration}):
 * slice anotacije same registruju relevantne beanove —
 * {@code @WebMvcTest(controllers=...)} svoj kontroler, {@code @DataJpaTest}
 * entitete + repozitorijume preko auto-konfiguracije. Sirok scan bi povukao
 * fund listenere/klijente koji traze {@code RabbitTemplate} u WebMvc slice gde
 * broker nije dostupan.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class FundsSliceTestConfiguration {
}
