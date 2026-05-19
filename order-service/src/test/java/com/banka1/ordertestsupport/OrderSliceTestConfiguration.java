package com.banka1.ordertestsupport;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Minimal {@code @SpringBootConfiguration} for {@code @DataJpaTest} slice tests
 * of the order-service repositories.
 *
 * <p>Deliberately placed <em>outside</em> the {@code com.banka1.order} package
 * tree. {@link com.banka1.order.OrderServiceApplication} component-scans
 * {@code com.banka1.order}; were this {@code @SpringBootConfiguration} inside
 * that tree, the {@code @SpringBootTest} in {@code SpringdocCompatibilityTest}
 * would pick it up and trigger {@code @EnableAutoConfiguration} a second time,
 * registering every JPA repository twice ({@code BeanDefinitionOverrideException}).
 *
 * <p>Slice tests reference this class explicitly via {@code @ContextConfiguration}
 * rather than relying on the package walk, so its out-of-tree location is safe.
 * The entity and repository packages are declared explicitly because this class
 * shares no package with them.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan("com.banka1.order.entity")
@EnableJpaRepositories("com.banka1.order.repository")
public class OrderSliceTestConfiguration {
}
