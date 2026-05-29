package com.banka1.card_service.integration;

import com.banka1.card_service.rabbitMQ.CardCreationEventListener;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the automatic card creation RabbitMQ binding.
 *
 * <p>The explicit {@code RabbitConfig} {@code @Bean Binding} was removed during the
 * Spring Boot 4 migration; the binding is now declared inline via the
 * {@link QueueBinding} annotation on the {@link RabbitListener} method. This test
 * still asserts the same behavioural contract: the automatic card creation flow is
 * bound with the {@code card.create} routing key.
 */
class RabbitBindingsConfigIntegrationTest {

    @Test
    void automaticCardCreationBindingUsesCardCreateRoutingKey() throws NoSuchMethodException {
        Method listenerMethod = CardCreationEventListener.class.getMethod(
                "handleCardCreateEvent",
                com.banka1.card_service.rabbitMQ.CardCreationEventDto.class,
                String.class);

        RabbitListener rabbitListener = listenerMethod.getAnnotation(RabbitListener.class);
        assertNotNull(rabbitListener, "handleCardCreateEvent must be a @RabbitListener");

        QueueBinding[] bindings = rabbitListener.bindings();
        assertEquals(1, bindings.length, "Expected exactly one @QueueBinding");
        assertEquals("card.create.#", bindings[0].key()[0]);
    }
}
