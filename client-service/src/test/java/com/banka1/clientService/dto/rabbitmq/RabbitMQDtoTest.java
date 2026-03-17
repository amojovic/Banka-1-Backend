package com.banka1.clientService.dto.rabbitmq;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testovi za EmailType, EmailDto i ResetLinkDto DTO objekte koji se koriste
 * za RabbitMQ email notifikacije.
 */
class RabbitMQDtoTest {

    // ── EmailType ─────────────────────────────────────────────────────────────

    @Test
    void emailTypeClientCreatedHasCorrectRoutingKey() {
        assertThat(EmailType.CLIENT_CREATED.getRoutingKey()).isEqualTo("client.created");
    }

    @Test
    void emailTypeClientPasswordResetHasCorrectRoutingKey() {
        assertThat(EmailType.CLIENT_PASSWORD_RESET.getRoutingKey()).isEqualTo("client.password_reset");
    }

    // ── EmailDto ─────────────────────────────────────────────────────────────

    @Test
    void emailDtoWithoutLinkSetsAllFieldsAndLeavesTemplateVariablesNull() {
        EmailDto dto = new EmailDto("Marko", "marko@banka.com", EmailType.CLIENT_CREATED);

        assertThat(dto.getUsername()).isEqualTo("Marko");
        assertThat(dto.getUserEmail()).isEqualTo("marko@banka.com");
        assertThat(dto.getEmailType()).isEqualTo(EmailType.CLIENT_CREATED);
        assertThat(dto.getTemplateVariables()).isNull();
    }

    @Test
    void emailDtoWithLinkCreatesTemplateVariablesForPasswordReset() {
        EmailDto dto = new EmailDto("Marko", "marko@banka.com", EmailType.CLIENT_PASSWORD_RESET, "http://reset.link");

        assertThat(dto.getTemplateVariables()).isNotNull();
        assertThat(dto.getTemplateVariables().getResetLink()).isEqualTo("http://reset.link");
        assertThat(dto.getTemplateVariables().getActivationLink()).isNull();
    }

    @Test
    void emailDtoWithLinkCreatesTemplateVariablesForClientCreated() {
        EmailDto dto = new EmailDto("Marko", "marko@banka.com", EmailType.CLIENT_CREATED, "http://activate.link");

        assertThat(dto.getTemplateVariables()).isNotNull();
        assertThat(dto.getTemplateVariables().getActivationLink()).isEqualTo("http://activate.link");
        assertThat(dto.getTemplateVariables().getResetLink()).isNull();
    }

    // ── ResetLinkDto ─────────────────────────────────────────────────────────

    @Test
    void resetLinkDtoClientCreatedSetsActivationLink() {
        ResetLinkDto dto = new ResetLinkDto("http://activate.link", EmailType.CLIENT_CREATED);

        assertThat(dto.getActivationLink()).isEqualTo("http://activate.link");
        assertThat(dto.getResetLink()).isNull();
    }

    @Test
    void resetLinkDtoClientPasswordResetSetsResetLink() {
        ResetLinkDto dto = new ResetLinkDto("http://reset.link", EmailType.CLIENT_PASSWORD_RESET);

        assertThat(dto.getResetLink()).isEqualTo("http://reset.link");
        assertThat(dto.getActivationLink()).isNull();
    }
}
