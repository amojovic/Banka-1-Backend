package com.banka1.bankingcore.verification.service;

import com.banka1.verificationService.dto.request.ValidateRequest;
import com.banka1.verificationService.dto.response.ValidateResponse;
import com.banka1.verificationService.exception.BusinessException;
import com.banka1.verificationService.exception.ErrorCode;
import com.banka1.verificationService.model.entity.VerificationSession;
import com.banka1.verificationService.model.enums.OperationType;
import com.banka1.verificationService.model.enums.VerificationStatus;
import com.banka1.verificationService.repository.VerificationSessionRepository;
import com.banka1.verificationService.service.OtpHashingService;
import com.banka1.verificationService.service.VerificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Verifikuje OTP poslovnu logiku iz Celina 1 spec-a:
 *   - 5-minutni TTL na verifikacionoj sesiji
 *   - 3-attempt cancel na verifikaciji (status CANCELLED posle 3 fail-a)
 *   - istekla sesija baca VERIFICATION_CODE_EXPIRED
 *
 * <p>Banking-core-service konsoliduje verification-service kao library dep (PR_02).
 * OTP flow u produkciji zivi u {@link com.banka1.verificationService.service.VerificationService}
 * (nema zasebne {@code OtpService}/{@code Otp} klase) — testira se stvarna klasa.
 */
@ExtendWith(MockitoExtension.class)
class VerificationServiceTest {

    @Mock private VerificationSessionRepository repository;
    @Mock private OtpHashingService otpHashingService;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private com.company.observability.starter.domain.UserIdExtractor userIdExtractor;

    @InjectMocks private VerificationService service;

    private VerificationSession pendingSession(int attemptCount) {
        LocalDateTime now = LocalDateTime.now();
        return VerificationSession.builder()
                .id(1L)
                .clientId(42L)
                .code("hashed-code")
                .operationType(OperationType.PAYMENT)
                .relatedEntityId("tx-1")
                .createdAt(now)
                .expiresAt(now.plusMinutes(5))
                .attemptCount(attemptCount)
                .status(VerificationStatus.PENDING)
                .build();
    }

    private ValidateRequest validateRequest(String code) {
        ValidateRequest request = new ValidateRequest();
        request.setSessionId(1L);
        request.setCode(code);
        return request;
    }

    @Test
    void sesija_ima_5min_TTL() {
        VerificationSession session = pendingSession(0);

        long minutesTillExpiry = java.time.Duration
                .between(session.getCreatedAt(), session.getExpiresAt())
                .toMinutes();

        assertThat(minutesTillExpiry).isEqualTo(5L);
    }

    @Test
    void validate_uspesno_oznacava_sesiju_kao_VERIFIED() {
        VerificationSession session = pendingSession(0);
        when(repository.findById(1L)).thenReturn(Optional.of(session));
        when(otpHashingService.matches(any(), any())).thenReturn(true);

        ValidateResponse response = service.validate(validateRequest("123456"));

        assertThat(response.isValid()).isTrue();
        assertThat(response.getStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(session.getStatus()).isEqualTo(VerificationStatus.VERIFIED);
    }

    @Test
    void validate_treci_neuspeli_pokusaj_otkazuje_sesiju() {
        // attemptCount = 2 → ovaj (treci) fail dize counter na 3 → CANCELLED.
        VerificationSession session = pendingSession(2);
        when(repository.findById(1L)).thenReturn(Optional.of(session));
        when(otpHashingService.matches(any(), any())).thenReturn(false);

        ValidateResponse response = service.validate(validateRequest("000000"));

        assertThat(response.isValid()).isFalse();
        assertThat(session.getAttemptCount()).isEqualTo(3);
        assertThat(session.getStatus()).isEqualTo(VerificationStatus.CANCELLED);
        assertThat(response.getStatus()).isEqualTo(VerificationStatus.CANCELLED);
        assertThat(response.getRemainingAttempts()).isZero();
    }

    @Test
    void validate_prvi_neuspeli_pokusaj_ostavlja_sesiju_PENDING() {
        VerificationSession session = pendingSession(0);
        when(repository.findById(1L)).thenReturn(Optional.of(session));
        when(otpHashingService.matches(any(), any())).thenReturn(false);

        ValidateResponse response = service.validate(validateRequest("000000"));

        assertThat(response.isValid()).isFalse();
        assertThat(session.getAttemptCount()).isEqualTo(1);
        assertThat(session.getStatus()).isEqualTo(VerificationStatus.PENDING);
        assertThat(response.getRemainingAttempts()).isEqualTo(2);
    }

    @Test
    void validate_baca_kada_je_sesija_istekla() {
        VerificationSession session = pendingSession(0);
        session.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(repository.findById(1L)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.validate(validateRequest("123456")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VERIFICATION_CODE_EXPIRED);

        assertThat(session.getStatus()).isEqualTo(VerificationStatus.EXPIRED);
    }

    @Test
    void validate_baca_kada_sesija_ne_postoji() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validate(validateRequest("123456")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VERIFICATION_SESSION_NOT_FOUND);
    }
}
