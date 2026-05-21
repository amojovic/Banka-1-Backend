package com.banka1.tradingservice.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TradingNotificationProducer {

    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    public void send(String routingKey, String username, String userEmail, Map<String, String> variables) {
        rabbitTemplate.convertAndSend(exchange, routingKey,
                new TradingNotificationRequest(username, userEmail, variables == null ? Map.of() : new HashMap<>(variables)));
    }

    public record TradingNotificationRequest(
            String username,
            String userEmail,
            Map<String, String> templateVariables
    ) {}
}
