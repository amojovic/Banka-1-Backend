package com.banka1.ordertestsupport;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Minimal {@code @SpringBootConfiguration} for {@code @DataJpaTest} slice tests.
 *
 * <p>Placed outside {@code com.banka1.order} to avoid being picked up by the
 * {@code @SpringBootTest} in {@code SpringdocCompatibilityTest} (which would trigger
 * {@code @EnableAutoConfiguration} twice and cause {@code BeanDefinitionOverrideException}).
 * Slice tests reference it explicitly via {@code @ContextConfiguration}.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan("com.banka1.order.entity")
@EnableJpaRepositories("com.banka1.order.repository")
public class OrderSliceTestConfiguration {
}
