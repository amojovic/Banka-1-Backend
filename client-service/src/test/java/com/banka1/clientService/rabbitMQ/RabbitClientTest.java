package com.banka1.clientService.rabbitMQ;

import com.banka1.clientService.dto.rabbitmq.EmailDto;
import com.banka1.clientService.dto.rabbitmq.EmailType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RabbitClientTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private RabbitClient rabbitClient;

    @Test
    void sendEmailNotificationForClientCreatedUsesCorrectRoutingKey() {
        ReflectionTestUtils.setField(rabbitClient, "exchange", "test-exchange");
        EmailDto dto = new EmailDto("Marko", "marko@banka.com", EmailType.CLIENT_CREATED);

        rabbitClient.sendEmailNotification(dto);

        verify(rabbitTemplate).convertAndSend("test-exchange", "client.created", dto);
    }

    @Test
    void sendEmailNotificationForPasswordResetUsesCorrectRoutingKey() {
        ReflectionTestUtils.setField(rabbitClient, "exchange", "test-exchange");
        EmailDto dto = new EmailDto("Marko", "marko@banka.com", EmailType.CLIENT_PASSWORD_RESET, "http://reset.link");

        rabbitClient.sendEmailNotification(dto);

        verify(rabbitTemplate).convertAndSend("test-exchange", "client.password_reset", dto);
    }
}
