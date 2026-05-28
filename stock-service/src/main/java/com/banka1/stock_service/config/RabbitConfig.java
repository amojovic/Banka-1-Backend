package com.banka1.stock_service.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * AMQP configuration for price-alert publishing in stock-service (Celina 3.2).
 *
 * <p>Declares the shared {@code employee.events} topic exchange and a
 * {@link RabbitTemplate} with JSON conversion so that
 * {@link com.banka1.stock_service.service.PriceAlertEvaluationService}
 * can publish {@code price.alert_triggered} notifications.
 */
@Configuration
public class RabbitConfig {

    @Bean
    public JacksonJsonMessageConverter stockJacksonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    @Primary
    public RabbitTemplate stockRabbitTemplate(
            ConnectionFactory connectionFactory,
            JacksonJsonMessageConverter stockJacksonMessageConverter
    ) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(stockJacksonMessageConverter);
        return template;
    }

    @Bean
    public TopicExchange stockEmployeeEventsExchange(
            @Value("${rabbitmq.exchange}") String exchangeName
    ) {
        return new TopicExchange(exchangeName, true, false);
    }
}
