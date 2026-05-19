package com.banka1.transaction_service.service.implementation;

import com.banka1.transaction_service.domain.Payment;
import com.banka1.transaction_service.domain.enums.CurrencyCode;
import com.banka1.transaction_service.domain.enums.TransactionStatus;
import com.banka1.transaction_service.dto.response.InfoResponseDto;
import com.banka1.transaction_service.rabbitMQ.EmailDto;
import com.banka1.transaction_service.rabbitMQ.EmailType;
import com.banka1.transaction_service.rabbitMQ.RabbitClient;
import com.banka1.transaction_service.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-7b: pokriva {@code finish()} — afterCommit publish TRANSACTION_COMPLETED /
 * TRANSACTION_DENIED notifikacije sa {@code templateVariables} koje sablon
 * notification-service-a ocekuje ({@code amount}, i {@code reason} za odbijene).
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceInternalImplementationTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private RabbitClient rabbitClient;

    @InjectMocks
    private TransactionServiceInternalImplementation service;

    @BeforeEach
    void setUp() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.initSynchronization();
        }
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

    private Payment payment(Long senderClientId, BigDecimal initialAmount) {
        Payment payment = new Payment();
        payment.setId(10L);
        payment.setFromAccountNumber("1110001000000000011");
        payment.setToAccountNumber("1110001000000000022");
        payment.setSenderClientId(senderClientId);
        payment.setInitialAmount(initialAmount);
        payment.setStatus(TransactionStatus.IN_PROGRESS);
        return payment;
    }

    private InfoResponseDto info() {
        return new InfoResponseDto(CurrencyCode.RSD, CurrencyCode.RSD, 1L, 2L,
                "pera@example.com", "pera");
    }

    @Test
    void finishCompletedPublishesNotificationWithAmountTemplateVariableAfterCommit() {
        Payment payment = payment(1L, new BigDecimal("2500.00"));
        when(paymentRepository.findById(10L)).thenReturn(Optional.of(payment));

        service.finish(null, info(), 10L, TransactionStatus.COMPLETED);

        assertThat(payment.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        // Pre commit-a nista nije poslato.
        verify(rabbitClient, never()).sendEmailNotification(any());

        TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();

        ArgumentCaptor<EmailDto> emailCaptor = ArgumentCaptor.forClass(EmailDto.class);
        verify(rabbitClient).sendEmailNotification(emailCaptor.capture());
        EmailDto sent = emailCaptor.getValue();
        assertThat(sent.getEmailType()).isEqualTo(EmailType.TRANSACTION_COMPLETED);
        assertThat(sent.getUserEmail()).isEqualTo("pera@example.com");
        assertThat(sent.getUsername()).isEqualTo("pera");
        // WP-7b: TRANSACTION_COMPLETED sablon renderuje {{amount}}.
        assertThat(sent.getTemplateVariables()).containsEntry("amount", "2500.00");
        assertThat(sent.getTemplateVariables()).doesNotContainKey("reason");
        // WP-7: in-app recipient propagacija ka klijentu-posiljaocu.
        assertThat(sent.getRecipientUserId()).isEqualTo(1L);
        assertThat(sent.getRecipientType()).isEqualTo("CLIENT");
    }

    @Test
    void finishDeniedPublishesNotificationWithAmountAndReasonTemplateVariables() {
        Payment payment = payment(7L, new BigDecimal("999.99"));
        when(paymentRepository.findById(10L)).thenReturn(Optional.of(payment));

        service.finish(null, info(), 10L, TransactionStatus.DENIED);

        assertThat(payment.getStatus()).isEqualTo(TransactionStatus.DENIED);

        TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();

        ArgumentCaptor<EmailDto> emailCaptor = ArgumentCaptor.forClass(EmailDto.class);
        verify(rabbitClient).sendEmailNotification(emailCaptor.capture());
        EmailDto sent = emailCaptor.getValue();
        assertThat(sent.getEmailType()).isEqualTo(EmailType.TRANSACTION_DENIED);
        // WP-7b: TRANSACTION_DENIED sablon renderuje {{amount}} i {{reason}}.
        assertThat(sent.getTemplateVariables()).containsEntry("amount", "999.99");
        assertThat(sent.getTemplateVariables()).containsKey("reason");
        assertThat(sent.getTemplateVariables().get("reason")).isNotBlank();
        // WP-7: in-app recipient propagacija ka klijentu-posiljaocu.
        assertThat(sent.getRecipientUserId()).isEqualTo(7L);
        assertThat(sent.getRecipientType()).isEqualTo("CLIENT");
    }
}
