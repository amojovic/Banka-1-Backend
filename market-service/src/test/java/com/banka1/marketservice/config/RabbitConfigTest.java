package com.banka1.marketservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-3 (W3): verifikuje da {@link RabbitConfig} ispravno wire-uje AMQP infrastrukturu.
 *
 * Test ne zahteva running RabbitMQ broker — starter ne pravi konekciju dok se
 * template ne upotrebi; auto-config samo kreira lazy connection factory.
 */
@SpringBootTest(classes = RabbitConfig.class)
@ImportAutoConfiguration(RabbitAutoConfiguration.class)
@TestPropertySource(properties = {
        "spring.rabbitmq.host=localhost",
        "spring.rabbitmq.port=5672",
        "rabbitmq.exchange=employee.events"
})
class RabbitConfigTest {

    @Autowired
    private ApplicationContext ctx;

    @Test
    void rabbitTemplateBeanLoadsWithJsonConverter() {
        RabbitTemplate template = ctx.getBean("marketRabbitTemplate", RabbitTemplate.class);
        assertThat(template).isNotNull();
        assertThat(template.getMessageConverter()).isInstanceOf(JacksonJsonMessageConverter.class);
    }

    @Test
    void messageConverterBeanIsJacksonJson() {
        MessageConverter converter = ctx.getBean("marketJacksonMessageConverter", MessageConverter.class);
        assertThat(converter).isInstanceOf(JacksonJsonMessageConverter.class);
    }

    @Test
    void topicExchangeIsDurableAndNamedFromProperty() {
        TopicExchange exchange = ctx.getBean("marketEventsExchange", TopicExchange.class);
        assertThat(exchange.getName()).isEqualTo("employee.events");
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
    }
}
