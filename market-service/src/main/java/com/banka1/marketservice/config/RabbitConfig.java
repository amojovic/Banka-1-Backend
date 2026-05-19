package com.banka1.marketservice.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * WP-3 (W3): AMQP enablement za market-service.
 *
 * Omogucava da market-service publish-uje JSON poruke na zajednicki topic
 * exchange (`employee.events`). Connection factory dolazi iz Spring Boot
 * auto-konfiguracije ({@code spring.rabbitmq.*}); ovde se samo deklarisu
 * {@link RabbitTemplate} sa JSON converter-om i exchange bean.
 *
 * Ovaj WP samo postavlja infrastrukturu — bez business logike i listener-a
 * (WP-9 Price Alert ga koristi za `price.alert_triggered` notifikacije).
 *
 * Bean imena su FQ-prefiksovana (market*) da ne sudaraju sa legacy
 * stock-service / exchange-service modulima u konsolidovanom JVM-u.
 */
@Configuration
public class RabbitConfig {

    @Bean
    public MessageConverter marketJacksonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    @Primary
    public RabbitTemplate marketRabbitTemplate(ConnectionFactory connectionFactory,
                                               MessageConverter marketJacksonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(marketJacksonMessageConverter);
        return template;
    }

    @Bean
    public TopicExchange marketEventsExchange(@Value("${rabbitmq.exchange}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }
}
