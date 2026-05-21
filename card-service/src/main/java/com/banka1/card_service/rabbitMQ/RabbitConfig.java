package com.banka1.card_service.rabbitMQ;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for automatic card creation events.
 */
@Configuration
public class RabbitConfig {

    @Bean
    public Queue automaticCardCreationQueue(
            @Value("${card.rabbit.auto.queue:card.creation.auto.queue}") String queueName
    ) {
        return new Queue(queueName, true);
    }

    @Bean
    public TopicExchange automaticCardCreationExchange(
            @Value("${card.rabbit.auto.exchange:card.events}") String exchangeName
    ) {
        return new TopicExchange(exchangeName, true, false);
    }

    @Bean
    public Binding automaticCardCreationBinding(
            Queue automaticCardCreationQueue,
            TopicExchange automaticCardCreationExchange
    ) {
        return BindingBuilder.bind(automaticCardCreationQueue)
                .to(automaticCardCreationExchange)
                .with("card.create");
    }
}
