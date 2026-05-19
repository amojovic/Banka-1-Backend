package com.banka1.transfer.rabbitmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

class RabbitClientTest {

    private RabbitClient rabbitClient;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        rabbitClient = new RabbitClient(rabbitTemplate);
        // Ručno postavljamo @Value polje
        ReflectionTestUtils.setField(rabbitClient, "exchange", "test-exchange");
    }

    @Test
    void sendEmailNotification_ShouldCallRabbitTemplate() {
        EmailDto dto = new EmailDto("Ime", "test@test.com", EmailType.TRANSFER_COMPLETED, "Poruka");

        rabbitClient.sendEmailNotification(dto);

        // Proveravamo da li je rabbitTemplate pozvan sa ispravnim exchange-om i routing key-om
        verify(rabbitTemplate).convertAndSend(
                eq("test-exchange"),
                eq(EmailType.TRANSFER_COMPLETED.getRoutingKey()),
                eq(dto)
        );
    }

    /**
     * WP-7b: notification-service potrosac cita {@code username} (alias za
     * {@code {{name}}}) i {@code templateVariables}. EmailDto.ime se zato mora
     * serijalizovati pod JSON kljucem {@code username}, a ne {@code ime}.
     */
    @Test
    void emailDtoSerializesImeAsUsernameAndCarriesTemplateVariables() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EmailDto dto = new EmailDto("Pera", "pera@test.com", EmailType.TRANSFER_COMPLETED, "Poruka");
        dto.getTemplateVariables().put("amount", "1500.00");

        String json = objectMapper.writeValueAsString(dto);

        assertThat(json).contains("\"username\":\"Pera\"");
        assertThat(json).doesNotContain("\"ime\"");

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        assertThat(parsed).containsEntry("username", "Pera");
        assertThat(parsed).containsKey("templateVariables");
        @SuppressWarnings("unchecked")
        Map<String, Object> vars = (Map<String, Object>) parsed.get("templateVariables");
        assertThat(vars).containsEntry("amount", "1500.00");
    }
}