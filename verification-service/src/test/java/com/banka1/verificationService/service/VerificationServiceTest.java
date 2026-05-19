package com.banka1.verificationService.service;

import com.banka1.verificationService.config.VerificationOtpProperties;
import com.banka1.verificationService.dto.request.GenerateRequest;
import com.banka1.verificationService.dto.request.ValidateRequest;
import com.banka1.verificationService.dto.response.GenerateResponse;
import com.banka1.verificationService.dto.response.StatusResponse;
import com.banka1.verificationService.dto.response.ValidateResponse;
import com.banka1.verificationService.exception.BusinessException;
import com.banka1.verificationService.exception.ErrorCode;
import com.banka1.verificationService.model.entity.VerificationSession;
import com.banka1.verificationService.model.enums.OperationType;
import com.banka1.verificationService.model.enums.VerificationStatus;
import com.banka1.verificationService.repository.VerificationSessionRepository;
import com.company.observability.starter.domain.UserIdExtractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit testovi za {@link VerificationService} u TOTP rezimu (WP-6, Celina 2.1).
 *
 * <p>{@code TotpService} se mock-uje (deterministicke TOTP vektore pokriva
 * {@link TotpServiceTest}); ovde se proverava da {@code VerificationService}
 * ispravno generise per-session secret, racuna i salje kod, verifikuje uneti
 * kod kroz {@code TotpService}, i odrzava brojac pokusaja + status prelaze.
 * {@code VerificationOtpProperties} je realna instanca sa default-ima
 * (TTL 5 min, 3 pokusaja).
 */
@ExtendWith(MockitoExtension.class)
class VerificationServiceTest {

    @Mock
    private VerificationSessionRepository repository;

    @Mock
    private OtpHashingService otpHashingService;

    @Mock
    private TotpService totpService;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private UserIdExtractor userIdExtractor;

    private VerificationOtpProperties otpProperties;

    private VerificationService verificationService;

    @BeforeEach
    void setUp() {
        otpProperties = new VerificationOtpProperties();
        verificationService = new VerificationService(
                repository, otpHashingService, totpService, otpProperties, rabbitTemplate, userIdExtractor);
        ReflectionTestUtils.setField(verificationService, "exchange", "test-exchange");
        ReflectionTestUtils.setField(verificationService, "routingKey", "verification.generated");
        ReflectionTestUtils.setField(verificationService, "verificationRoutingKey", "verification.generated");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void mockSecurityContextWithClientId(long clientId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim("id", clientId)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void generateCreatesPendingSessionWithTotpSecretAndPublishesCurrentCode() {
        GenerateRequest request = new GenerateRequest();
        request.setClientId(55L);
        request.setOperationType(OperationType.PAYMENT);
        request.setRelatedEntityId("payment-123");
        request.setClientEmail("client@example.com");

        mockSecurityContextWithClientId(55L);
        when(repository.findByClientIdAndOperationTypeAndRelatedEntityIdAndStatus(
                55L, OperationType.PAYMENT, "payment-123", VerificationStatus.PENDING
        )).thenReturn(List.of());
        when(totpService.generateSecret()).thenReturn("BASE32SECRET");
        when(totpService.currentCode("BASE32SECRET")).thenReturn("654321");
        when(repository.saveAndFlush(any(VerificationSession.class))).thenAnswer(invocation -> {
            VerificationSession session = invocation.getArgument(0);
            session.setId(99L);
            return session;
        });

        GenerateResponse response = verificationService.generate(request);

        ArgumentCaptor<VerificationSession> sessionCaptor = ArgumentCaptor.forClass(VerificationSession.class);
        verify(repository).saveAndFlush(sessionCaptor.capture());
        VerificationSession savedSession = sessionCaptor.getValue();
        assertThat(savedSession.getClientId()).isEqualTo(55L);
        assertThat(savedSession.getOperationType()).isEqualTo(OperationType.PAYMENT);
        assertThat(savedSession.getRelatedEntityId()).isEqualTo("payment-123");
        assertThat(savedSession.getStatus()).isEqualTo(VerificationStatus.PENDING);
        assertThat(savedSession.getAttemptCount()).isZero();
        // TOTP secret persisted; legacy 'code' hash column left null.
        assertThat(savedSession.getTotpSecret()).isEqualTo("BASE32SECRET");
        assertThat(savedSession.getCode()).isNull();
        assertThat(Duration.between(savedSession.getCreatedAt(), savedSession.getExpiresAt()))
                .isEqualTo(Duration.ofMinutes(5));

        // The delivered code is the current TOTP code, never the secret.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> eventCaptor = ArgumentCaptor.forClass((Class) Map.class);
        verify(rabbitTemplate).convertAndSend(eq("test-exchange"), eq("verification.generated"), eventCaptor.capture());
        Map<String, Object> event = eventCaptor.getValue();
        assertThat(event.get("userEmail")).isEqualTo("client@example.com");
        @SuppressWarnings("unchecked")
        Map<String, String> templateVars = (Map<String, String>) event.get("templateVariables");
        assertThat(templateVars.get("code")).isEqualTo("654321");
        assertThat(templateVars.get("code")).matches("^\\d{6}$");
        assertThat(templateVars.get("code")).isNotEqualTo(savedSession.getTotpSecret());
        assertThat(response.getSessionId()).isEqualTo(99L);
    }

    @Test
    void generateUsesConfiguredTtlMinutes() {
        otpProperties.setTtlMinutes(10);

        GenerateRequest request = new GenerateRequest();
        request.setClientId(55L);
        request.setOperationType(OperationType.TRANSFER);
        request.setRelatedEntityId("transfer-9");
        request.setClientEmail("client@example.com");

        mockSecurityContextWithClientId(55L);
        when(repository.findByClientIdAndOperationTypeAndRelatedEntityIdAndStatus(
                any(), any(), any(), any())).thenReturn(List.of());
        when(totpService.generateSecret()).thenReturn("SECRET");
        when(totpService.currentCode("SECRET")).thenReturn("111222");
        when(repository.saveAndFlush(any(VerificationSession.class))).thenAnswer(invocation -> {
            VerificationSession session = invocation.getArgument(0);
            session.setId(1L);
            return session;
        });

        verificationService.generate(request);

        ArgumentCaptor<VerificationSession> sessionCaptor = ArgumentCaptor.forClass(VerificationSession.class);
        verify(repository).saveAndFlush(sessionCaptor.capture());
        assertThat(Duration.between(
                sessionCaptor.getValue().getCreatedAt(), sessionCaptor.getValue().getExpiresAt()))
                .isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void generateThrowsWhenUserNotAuthorized() {
        GenerateRequest request = new GenerateRequest();
        request.setClientId(55L);
        request.setOperationType(OperationType.PAYMENT);
        request.setRelatedEntityId("payment-123");
        request.setClientEmail("client@example.com");

        mockSecurityContextWithClientId(99L);

        assertThatThrownBy(() -> verificationService.generate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Pristup odbijen: Cannot generate verification for other client")
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void validateMarksSessionVerifiedWhenTotpCodeMatches() {
        VerificationSession session = pendingSession();
        session.setId(10L);
        when(repository.findById(10L)).thenReturn(Optional.of(session));
        when(totpService.verify("totp-secret", "123456")).thenReturn(true);

        ValidateRequest request = new ValidateRequest();
        request.setSessionId(10L);
        request.setCode("123456");

        ValidateResponse response = verificationService.validate(request);

        assertThat(response.isValid()).isTrue();
        assertThat(response.getStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(response.getRemainingAttempts()).isZero();
        assertThat(session.getStatus()).isEqualTo(VerificationStatus.VERIFIED);
        verify(repository).save(session);
    }

    @Test
    void validateIncrementsAttemptsForWrongTotpCode() {
        VerificationSession session = pendingSession();
        session.setId(11L);
        when(repository.findById(11L)).thenReturn(Optional.of(session));
        when(totpService.verify("totp-secret", "000000")).thenReturn(false);

        ValidateRequest request = new ValidateRequest();
        request.setSessionId(11L);
        request.setCode("000000");

        ValidateResponse response = verificationService.validate(request);

        assertThat(response.isValid()).isFalse();
        assertThat(response.getStatus()).isEqualTo(VerificationStatus.PENDING);
        assertThat(response.getRemainingAttempts()).isEqualTo(2);
        assertThat(session.getAttemptCount()).isEqualTo(1);
        verify(repository).save(session);
    }

    @Test
    void validateCancelsSessionAfterThirdFailedAttempt() {
        VerificationSession session = pendingSession();
        session.setId(12L);
        session.setAttemptCount(2);
        when(repository.findById(12L)).thenReturn(Optional.of(session));
        when(totpService.verify("totp-secret", "000000")).thenReturn(false);

        ValidateRequest request = new ValidateRequest();
        request.setSessionId(12L);
        request.setCode("000000");

        ValidateResponse response = verificationService.validate(request);

        assertThat(response.isValid()).isFalse();
        assertThat(response.getStatus()).isEqualTo(VerificationStatus.CANCELLED);
        assertThat(response.getRemainingAttempts()).isZero();
        assertThat(session.getAttemptCount()).isEqualTo(3);
        assertThat(session.getStatus()).isEqualTo(VerificationStatus.CANCELLED);
        verify(repository).save(session);
    }

    @Test
    void validateRespectsConfiguredMaxAttempts() {
        // max-attempts=5: a wrong code at attemptCount 4 -> 5 cancels the session.
        otpProperties.setMaxAttempts(5);
        VerificationSession session = pendingSession();
        session.setId(20L);
        session.setAttemptCount(4);
        when(repository.findById(20L)).thenReturn(Optional.of(session));
        when(totpService.verify("totp-secret", "000000")).thenReturn(false);

        ValidateRequest request = new ValidateRequest();
        request.setSessionId(20L);
        request.setCode("000000");

        ValidateResponse response = verificationService.validate(request);

        assertThat(session.getAttemptCount()).isEqualTo(5);
        assertThat(session.getStatus()).isEqualTo(VerificationStatus.CANCELLED);
        assertThat(response.getRemainingAttempts()).isZero();
    }

    @Test
    void validateFallsBackToLegacyHashWhenSessionHasNoTotpSecret() {
        // Sessions created before the WP-6 migration carry an HMAC hash, not a secret.
        VerificationSession session = pendingSession();
        session.setId(30L);
        session.setTotpSecret(null);
        session.setCode("legacy-hash");
        when(repository.findById(30L)).thenReturn(Optional.of(session));
        when(otpHashingService.matches("123456", "legacy-hash")).thenReturn(true);

        ValidateRequest request = new ValidateRequest();
        request.setSessionId(30L);
        request.setCode("123456");

        ValidateResponse response = verificationService.validate(request);

        assertThat(response.isValid()).isTrue();
        assertThat(session.getStatus()).isEqualTo(VerificationStatus.VERIFIED);
    }

    @Test
    void validateRejectsExpiredSessionAndMarksItExpired() {
        VerificationSession session = pendingSession();
        session.setId(13L);
        session.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(repository.findById(13L)).thenReturn(Optional.of(session));

        ValidateRequest request = new ValidateRequest();
        request.setSessionId(13L);
        request.setCode("123456");

        assertThatThrownBy(() -> verificationService.validate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Verifikacioni kod je istekao: Session ID: 13")
                .extracting("errorCode").isEqualTo(ErrorCode.VERIFICATION_CODE_EXPIRED);

        assertThat(session.getStatus()).isEqualTo(VerificationStatus.EXPIRED);
        verify(repository).save(session);
    }

    @Test
    void validateRejectsCancelledSession() {
        VerificationSession session = pendingSession();
        session.setId(14L);
        session.setStatus(VerificationStatus.CANCELLED);
        when(repository.findById(14L)).thenReturn(Optional.of(session));

        ValidateRequest request = new ValidateRequest();
        request.setSessionId(14L);
        request.setCode("123456");

        assertThatThrownBy(() -> verificationService.validate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Sesija verifikacije je otkazana: Session ID: 14")
                .extracting("errorCode").isEqualTo(ErrorCode.VERIFICATION_SESSION_CANCELLED);
    }

    @Test
    void getStatusAutoExpiresPendingSession() {
        VerificationSession session = pendingSession();
        session.setId(15L);
        session.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(repository.findById(15L)).thenReturn(Optional.of(session));

        StatusResponse statusResponse = verificationService.getStatus(15L);

        assertThat(statusResponse.getStatus()).isEqualTo(VerificationStatus.EXPIRED);
        verify(repository).save(session);
    }

    @Test
    void getStatusThrowsWhenSessionIsMissing() {
        when(repository.findById(77L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> verificationService.getStatus(77L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Sesija verifikacije nije pronađena: Session ID: 77")
                .extracting("errorCode").isEqualTo(ErrorCode.VERIFICATION_SESSION_NOT_FOUND);
    }

    @Test
    void validateRejectsAlreadyExpiredSession() {
        VerificationSession session = pendingSession();
        session.setId(16L);
        session.setStatus(VerificationStatus.EXPIRED);
        when(repository.findById(16L)).thenReturn(Optional.of(session));

        ValidateRequest request = new ValidateRequest();
        request.setSessionId(16L);
        request.setCode("123456");

        assertThatThrownBy(() -> verificationService.validate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Verifikacioni kod je istekao: Session ID: 16")
                .extracting("errorCode").isEqualTo(ErrorCode.VERIFICATION_CODE_EXPIRED);
    }

    @Test
    void validateRejectsAlreadyVerifiedSession() {
        VerificationSession session = pendingSession();
        session.setId(17L);
        session.setStatus(VerificationStatus.VERIFIED);
        when(repository.findById(17L)).thenReturn(Optional.of(session));

        ValidateRequest request = new ValidateRequest();
        request.setSessionId(17L);
        request.setCode("123456");

        assertThatThrownBy(() -> verificationService.validate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Sesija verifikacije je već verifikovana: Session ID: 17")
                .extracting("errorCode").isEqualTo(ErrorCode.VERIFICATION_SESSION_ALREADY_VERIFIED);
    }

    private VerificationSession pendingSession() {
        LocalDateTime now = LocalDateTime.now();
        return VerificationSession.builder()
                .id(1L)
                .clientId(44L)
                .totpSecret("totp-secret")
                .operationType(OperationType.TRANSFER)
                .relatedEntityId("transfer-1")
                .createdAt(now)
                .expiresAt(now.plusMinutes(5))
                .attemptCount(0)
                .status(VerificationStatus.PENDING)
                .build();
    }
}
