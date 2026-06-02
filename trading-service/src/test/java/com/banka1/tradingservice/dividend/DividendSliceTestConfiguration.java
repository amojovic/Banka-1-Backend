package com.banka1.tradingservice.dividend;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * WP-14: minimalni {@code @SpringBootConfiguration} za dividend slice testove.
 *
 * <p>{@code @DataJpaTest}/{@code @WebMvcTest} trazi najblizu
 * {@code @SpringBootConfiguration} sirenjem nagore kroz pakete. Bez ove klase
 * slice bi pronasao {@code TradingServiceApplication}, cija eksplicitna
 * {@code @ComponentScan(basePackages="com.banka1")} povlaci {@code security-lib}
 * {@code SecurityConfig} ({@code apiChain}) i ruzi slice kontekst
 * ({@code UnreachableFilterChainException}).
 *
 * <p>Namerno BEZ {@code @ComponentScan} (isti razlog kao
 * {@code AuditSliceTestConfiguration}): slice anotacije same registruju
 * relevantne beanove.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class DividendSliceTestConfiguration {
}
