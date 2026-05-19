package com.banka1.clientService.service.implementation;

import com.banka1.clientService.domain.ClientConfirmationToken;
import com.banka1.clientService.domain.Klijent;
import com.banka1.clientService.domain.enums.ClientRole;
import com.banka1.clientService.domain.enums.Pol;
import com.banka1.clientService.dto.rabbitmq.EmailDto;
import com.banka1.clientService.dto.requests.ActivateDto;
import com.banka1.clientService.dto.requests.ForgotPasswordDto;
import com.banka1.clientService.dto.requests.LoginRequestDto;
import com.banka1.clientService.dto.responses.LoginResponseDto;
import com.banka1.clientService.exception.BusinessException;
import com.banka1.clientService.exception.ErrorCode;
import com.banka1.clientService.rabbitMQ.RabbitClient;
import com.banka1.clientService.repository.ClientConfirmationTokenRepository;
import com.banka1.clientService.repository.KlijentRepository;
import com.banka1.clientService.service.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplementationTest {

    @Mock
    private KlijentRepository klijentRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ClientConfirmationTokenRepository confirmationTokenRepository;

    @Mock
    private TokenService tokenService;

    @Mock
    private RabbitClient rabbitClient;

    private AuthServiceImplementation authService;

    @BeforeEach
    void setUp() throws Exception {
        authService = new AuthServiceImplementation(
                klijentRepository,
                passwordEncoder,
                "test_secret_key_at_least_32_characters_long"
        );
        ReflectionTestUtils.setField(authService, "rolesClaim", "roles");
        ReflectionTestUtils.setField(authService, "idClaim", "id");
        ReflectionTestUtils.setField(authService, "issuer", "banka1");
        ReflectionTestUtils.setField(authService, "expirationTime", 3600000L);
        ReflectionTestUtils.setField(authService, "confirmationTokenRepository", confirmationTokenRepository);
        ReflectionTestUtils.setField(authService, "tokenService", tokenService);
        ReflectionTestUtils.setField(authService, "rabbitClient", rabbitClient);
        ReflectionTestUtils.setField(authService, "urlActivateAccount", "http://localhost/activate?token=");
        ReflectionTestUtils.setField(authService, "urlResetPassword", "http://localhost/reset?token=");
        ReflectionTestUtils.setField(authService, "confirmationTokenExpiration", 5L);
        ReflectionTestUtils.setField(authService, "accountLockoutMaxAttempts", 5);
        ReflectionTestUtils.setField(authService, "accountLockoutDurationMinutes", 10L);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void loginSuccessReturnsToken() {
        LoginRequestDto dto = loginRequest("marko@banka.com", "lozinka123");
        Klijent klijent = klijent("marko@banka.com", "hashed");

        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));
        when(passwordEncoder.matches("lozinka123", "hashed")).thenReturn(true);

        LoginResponseDto response = authService.login(dto);

        assertThat(response.getToken()).isNotBlank();
    }

    @Test
    void loginEmailNotFoundThrowsInvalidCredentials() {
        LoginRequestDto dto = loginRequest("nepostoji@banka.com", "lozinka123");

        when(klijentRepository.findByEmail("nepostoji@banka.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void loginWrongPasswordThrowsInvalidCredentials() {
        LoginRequestDto dto = loginRequest("marko@banka.com", "pogresna");
        Klijent klijent = klijent("marko@banka.com", "hashed");

        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));
        when(passwordEncoder.matches("pogresna", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void loginNullPasswordThrowsInvalidCredentials() {
        LoginRequestDto dto = loginRequest("marko@banka.com", "bilo_sta");
        Klijent klijent = klijent("marko@banka.com", null);

        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));

        assertThatThrownBy(() -> authService.login(dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void loginSuccessTokenContainsCorrectRole() {
        LoginRequestDto dto = loginRequest("marko@banka.com", "lozinka123");
        Klijent klijent = klijent("marko@banka.com", "hashed");
        klijent.setRole(ClientRole.CLIENT_TRADING);

        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));
        when(passwordEncoder.matches("lozinka123", "hashed")).thenReturn(true);

        LoginResponseDto response = authService.login(dto);

        // JWT je u formatu header.payload.signature — dekodujemo payload
        String payload = new String(java.util.Base64.getUrlDecoder()
                .decode(response.getToken().split("\\.")[1]));
        assertThat(payload).contains("CLIENT_TRADING");
    }

    // ── login – extra cases ───────────────────────────────────────────────────

    @Test
    void loginInactiveClientThrowsUserInactive() {
        LoginRequestDto dto = loginRequest("marko@banka.com", "lozinka123");
        Klijent klijent = klijent("marko@banka.com", "hashed");
        klijent.setAktivan(false);

        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));

        assertThatThrownBy(() -> authService.login(dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_INACTIVE);
    }

    @Test
    void loginResponseContainsClientMetadata() {
        LoginRequestDto dto = loginRequest("marko@banka.com", "lozinka123");
        Klijent klijent = klijent("marko@banka.com", "hashed");

        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));
        when(passwordEncoder.matches("lozinka123", "hashed")).thenReturn(true);

        LoginResponseDto response = authService.login(dto);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getIme()).isEqualTo("Marko");
        assertThat(response.getPrezime()).isEqualTo("Markovic");
        assertThat(response.getEmail()).isEqualTo("marko@banka.com");
    }

    // ── lockout (Celina 1, Scenario 5) ────────────────────────────────────────

    @Test
    void loginFailedAttemptIncrementsCounter() {
        LoginRequestDto dto = loginRequest("marko@banka.com", "pogresna");
        Klijent klijent = klijent("marko@banka.com", "hashed");

        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));
        when(passwordEncoder.matches("pogresna", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(dto)).isInstanceOf(BusinessException.class);

        assertThat(klijent.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(klijent.getLockedUntil()).isNull();
        verify(klijentRepository).save(klijent);
    }

    @Test
    void loginFiveFailedAttemptsLocksAccount() {
        LoginRequestDto dto = loginRequest("marko@banka.com", "pogresna");
        Klijent klijent = klijent("marko@banka.com", "hashed");
        klijent.setFailedLoginAttempts(4);

        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));
        when(passwordEncoder.matches("pogresna", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        assertThat(klijent.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(klijent.getLockedUntil()).isNotNull();
        assertThat(klijent.getLockedUntil()).isAfter(LocalDateTime.now().plusMinutes(9));
        assertThat(klijent.getLockedUntil()).isBefore(LocalDateTime.now().plusMinutes(11));
    }

    @Test
    void loginFifthFailurePublishesLockEmailAfterCommit() {
        LoginRequestDto dto = loginRequest("marko@banka.com", "pogresna");
        Klijent klijent = klijent("marko@banka.com", "hashed");
        klijent.setFailedLoginAttempts(4);

        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));
        when(passwordEncoder.matches("pogresna", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(dto)).isInstanceOf(BusinessException.class);

        // mejl se salje tek u afterCommit — sinhronizacija mora biti registrovana
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        verify(rabbitClient, never()).sendEmailNotification(any());

        TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();
        verify(rabbitClient).sendEmailNotification(any(EmailDto.class));
    }

    @Test
    void loginBelowThresholdDoesNotPublishLockEmail() {
        LoginRequestDto dto = loginRequest("marko@banka.com", "pogresna");
        Klijent klijent = klijent("marko@banka.com", "hashed");
        klijent.setFailedLoginAttempts(2);

        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));
        when(passwordEncoder.matches("pogresna", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(dto)).isInstanceOf(BusinessException.class);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void loginWhileLockedThrowsAccountLocked() {
        LoginRequestDto dto = loginRequest("marko@banka.com", "lozinka123");
        Klijent klijent = klijent("marko@banka.com", "hashed");
        klijent.setFailedLoginAttempts(5);
        klijent.setLockedUntil(LocalDateTime.now().plusMinutes(5));

        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));

        assertThatThrownBy(() -> authService.login(dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);
    }

    @Test
    void loginAfterLockExpiryIsAllowed() {
        LoginRequestDto dto = loginRequest("marko@banka.com", "lozinka123");
        Klijent klijent = klijent("marko@banka.com", "hashed");
        klijent.setFailedLoginAttempts(5);
        klijent.setLockedUntil(LocalDateTime.now().minusMinutes(1));

        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));
        when(passwordEncoder.matches("lozinka123", "hashed")).thenReturn(true);

        LoginResponseDto response = authService.login(dto);

        assertThat(response.getToken()).isNotBlank();
        assertThat(klijent.getFailedLoginAttempts()).isZero();
        assertThat(klijent.getLockedUntil()).isNull();
    }

    @Test
    void loginSuccessResetsFailedAttemptCounter() {
        LoginRequestDto dto = loginRequest("marko@banka.com", "lozinka123");
        Klijent klijent = klijent("marko@banka.com", "hashed");
        klijent.setFailedLoginAttempts(3);

        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));
        when(passwordEncoder.matches("lozinka123", "hashed")).thenReturn(true);

        authService.login(dto);

        assertThat(klijent.getFailedLoginAttempts()).isZero();
        assertThat(klijent.getLockedUntil()).isNull();
        verify(klijentRepository).save(klijent);
    }

    @Test
    void editPasswordResetClearsLockAndCounter() {
        Klijent klijent = klijent("marko@banka.com", "old-hash");
        klijent.setAktivan(true);
        klijent.setFailedLoginAttempts(5);
        klijent.setLockedUntil(LocalDateTime.now().plusMinutes(5));
        ClientConfirmationToken token = confirmationToken("hashed-hex", null, klijent);
        token.setId(1L);
        ActivateDto dto = new ActivateDto(1L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Password12");

        when(confirmationTokenRepository.findById(1L)).thenReturn(Optional.of(token));
        when(tokenService.sha256Hex(dto.getConfirmationToken())).thenReturn("hashed-hex");
        when(passwordEncoder.encode("Password12")).thenReturn("new-hash");

        authService.editPassword(dto, false);

        assertThat(klijent.getFailedLoginAttempts()).isZero();
        assertThat(klijent.getLockedUntil()).isNull();
    }

    // ── check ─────────────────────────────────────────────────────────────────

    @Test
    void checkValidTokenReturnsTokenId() {
        String raw = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        Klijent klijent = klijent("marko@banka.com", "hashed");
        ClientConfirmationToken token = confirmationToken("hashed-hex", null, klijent);
        token.setId(5L);

        when(tokenService.sha256Hex(raw)).thenReturn("hashed-hex");
        when(confirmationTokenRepository.findByValue("hashed-hex")).thenReturn(Optional.of(token));

        assertThat(authService.check(raw)).isEqualTo(5L);
    }

    @Test
    void checkNullTokenThrowsInvalidToken() {
        assertThatThrownBy(() -> authService.check(null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void checkBlankTokenThrowsInvalidToken() {
        assertThatThrownBy(() -> authService.check("   "))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void checkWrongLengthTokenThrowsInvalidToken() {
        assertThatThrownBy(() -> authService.check("tooshort"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void checkTokenNotFoundThrowsInvalidToken() {
        String raw = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        when(tokenService.sha256Hex(raw)).thenReturn("hashed-hex");
        when(confirmationTokenRepository.findByValue("hashed-hex")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.check(raw))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void checkExpiredTokenThrowsInvalidToken() {
        String raw = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        Klijent klijent = klijent("marko@banka.com", "hashed");
        ClientConfirmationToken token = confirmationToken("hashed-hex", LocalDateTime.now().minusMinutes(1), klijent);

        when(tokenService.sha256Hex(raw)).thenReturn("hashed-hex");
        when(confirmationTokenRepository.findByValue("hashed-hex")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.check(raw))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void checkDeletedClientThrowsClientNotFound() {
        String raw = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        Klijent klijent = klijent("marko@banka.com", "hashed");
        klijent.setDeleted(true);
        ClientConfirmationToken token = confirmationToken("hashed-hex", null, klijent);

        when(tokenService.sha256Hex(raw)).thenReturn("hashed-hex");
        when(confirmationTokenRepository.findByValue("hashed-hex")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.check(raw))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CLIENT_NOT_FOUND);
    }

    // ── editPassword ──────────────────────────────────────────────────────────

    @Test
    void editPasswordActivateSuccessSetsAktivan() {
        Klijent klijent = klijent("marko@banka.com", null);
        klijent.setAktivan(false);
        ClientConfirmationToken token = confirmationToken("hashed-hex", null, klijent);
        token.setId(1L);
        ActivateDto dto = new ActivateDto(1L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Password12");

        when(confirmationTokenRepository.findById(1L)).thenReturn(Optional.of(token));
        when(tokenService.sha256Hex(dto.getConfirmationToken())).thenReturn("hashed-hex");
        when(passwordEncoder.encode("Password12")).thenReturn("new-hash");

        String result = authService.editPassword(dto, true);

        assertThat(result).isEqualTo("Uspesno aktiviranje klijenta");
        assertThat(klijent.isAktivan()).isTrue();
        assertThat(klijent.getPassword()).isEqualTo("new-hash");
    }

    @Test
    void editPasswordResetSuccessOnActiveClient() {
        Klijent klijent = klijent("marko@banka.com", "old-hash");
        klijent.setAktivan(true);
        ClientConfirmationToken token = confirmationToken("hashed-hex", null, klijent);
        token.setId(1L);
        ActivateDto dto = new ActivateDto(1L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Password12");

        when(confirmationTokenRepository.findById(1L)).thenReturn(Optional.of(token));
        when(tokenService.sha256Hex(dto.getConfirmationToken())).thenReturn("hashed-hex");
        when(passwordEncoder.encode("Password12")).thenReturn("new-hash");

        String result = authService.editPassword(dto, false);

        assertThat(result).isEqualTo("Uspesna promena lozinke");
        assertThat(klijent.getPassword()).isEqualTo("new-hash");
    }

    @Test
    void editPasswordTokenNotFoundThrowsInvalidToken() {
        ActivateDto dto = new ActivateDto(99L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Password12");
        when(confirmationTokenRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.editPassword(dto, true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void editPasswordWrongHashThrowsInvalidToken() {
        Klijent klijent = klijent("marko@banka.com", null);
        ClientConfirmationToken token = confirmationToken("correct-hash", null, klijent);
        token.setId(1L);
        ActivateDto dto = new ActivateDto(1L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Password12");

        when(confirmationTokenRepository.findById(1L)).thenReturn(Optional.of(token));
        when(tokenService.sha256Hex(dto.getConfirmationToken())).thenReturn("wrong-hash");

        assertThatThrownBy(() -> authService.editPassword(dto, true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void editPasswordExpiredTokenThrowsInvalidToken() {
        Klijent klijent = klijent("marko@banka.com", null);
        ClientConfirmationToken token = confirmationToken("hashed-hex", LocalDateTime.now().minusMinutes(1), klijent);
        token.setId(1L);
        ActivateDto dto = new ActivateDto(1L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Password12");

        when(confirmationTokenRepository.findById(1L)).thenReturn(Optional.of(token));
        when(tokenService.sha256Hex(dto.getConfirmationToken())).thenReturn("hashed-hex");

        assertThatThrownBy(() -> authService.editPassword(dto, true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void editPasswordDeletedClientThrowsClientNotFound() {
        Klijent klijent = klijent("marko@banka.com", null);
        klijent.setDeleted(true);
        ClientConfirmationToken token = confirmationToken("hashed-hex", null, klijent);
        token.setId(1L);
        ActivateDto dto = new ActivateDto(1L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Password12");

        when(confirmationTokenRepository.findById(1L)).thenReturn(Optional.of(token));
        when(tokenService.sha256Hex(dto.getConfirmationToken())).thenReturn("hashed-hex");

        assertThatThrownBy(() -> authService.editPassword(dto, true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CLIENT_NOT_FOUND);
    }

    @Test
    void editPasswordResetInactiveClientThrowsUserInactive() {
        Klijent klijent = klijent("marko@banka.com", "old-hash");
        klijent.setAktivan(false);
        ClientConfirmationToken token = confirmationToken("hashed-hex", null, klijent);
        token.setId(1L);
        ActivateDto dto = new ActivateDto(1L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Password12");

        when(confirmationTokenRepository.findById(1L)).thenReturn(Optional.of(token));
        when(tokenService.sha256Hex(dto.getConfirmationToken())).thenReturn("hashed-hex");

        assertThatThrownBy(() -> authService.editPassword(dto, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_INACTIVE);
    }

    // ── forgotPassword ────────────────────────────────────────────────────────

    @Test
    void forgotPasswordUserNotFoundThrowsUserNotFound() {
        when(klijentRepository.findByEmail("ghost@banka.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.forgotPassword(new ForgotPasswordDto("ghost@banka.com")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    void forgotPasswordInactiveUserThrowsUserInactive() {
        Klijent klijent = klijent("marko@banka.com", "hashed");
        klijent.setAktivan(false);
        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));

        assertThatThrownBy(() -> authService.forgotPassword(new ForgotPasswordDto("marko@banka.com")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_INACTIVE);
    }

    @Test
    void forgotPasswordCreatesNewTokenAndReturnsMejl() {
        Klijent klijent = klijent("marko@banka.com", "hashed");
        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));
        when(tokenService.generateRandomToken()).thenReturn("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        when(tokenService.sha256Hex(anyString())).thenReturn("hashed-hex");

        String result = authService.forgotPassword(new ForgotPasswordDto("marko@banka.com"));

        assertThat(result).isEqualTo("Poslat mejl");
        verify(confirmationTokenRepository).save(any(ClientConfirmationToken.class));
    }

    @Test
    void forgotPasswordUpdatesExistingTokenValue() {
        Klijent klijent = klijent("marko@banka.com", "hashed");
        ClientConfirmationToken existing = confirmationToken("old-hash", LocalDateTime.now().plusMinutes(5), klijent);
        klijent.setConfirmationToken(existing);

        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));
        when(tokenService.generateRandomToken()).thenReturn("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        when(tokenService.sha256Hex(anyString())).thenReturn("new-hash");

        authService.forgotPassword(new ForgotPasswordDto("marko@banka.com"));

        assertThat(existing.getValue()).isEqualTo("new-hash");
        assertThat(existing.getExpirationDateTime()).isAfter(LocalDateTime.now());
    }

    // ── resendActivation ──────────────────────────────────────────────────────

    @Test
    void resendActivationUserNotFoundThrowsUserNotFound() {
        when(klijentRepository.findByEmail("ghost@banka.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.resendActivation("ghost@banka.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    void resendActivationDeletedClientThrowsClientNotFound() {
        Klijent klijent = klijent("marko@banka.com", null);
        klijent.setDeleted(true);
        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));

        assertThatThrownBy(() -> authService.resendActivation("marko@banka.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CLIENT_NOT_FOUND);
    }

    @Test
    void resendActivationAlreadyActiveReturnsMessage() {
        Klijent klijent = klijent("marko@banka.com", "hashed");
        klijent.setAktivan(true);
        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));

        assertThat(authService.resendActivation("marko@banka.com")).isEqualTo("Nalog je vec aktivan");
    }

    @Test
    void resendActivationCreatesNewTokenAndReturnsMejl() {
        Klijent klijent = klijent("marko@banka.com", null);
        klijent.setAktivan(false);
        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));
        when(tokenService.generateRandomToken()).thenReturn("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        when(tokenService.sha256Hex(anyString())).thenReturn("hashed-hex");

        String result = authService.resendActivation("marko@banka.com");

        assertThat(result).isEqualTo("Poslat mejl");
        verify(confirmationTokenRepository).save(any(ClientConfirmationToken.class));
    }

    @Test
    void resendActivationUpdatesExistingTokenValue() {
        Klijent klijent = klijent("marko@banka.com", null);
        klijent.setAktivan(false);
        ClientConfirmationToken existing = confirmationToken("old-hash", null, klijent);
        klijent.setConfirmationToken(existing);

        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));
        when(tokenService.generateRandomToken()).thenReturn("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        when(tokenService.sha256Hex(anyString())).thenReturn("new-hash");

        authService.resendActivation("marko@banka.com");

        assertThat(existing.getValue()).isEqualTo("new-hash");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ClientConfirmationToken confirmationToken(String value, LocalDateTime expiration, Klijent klijent) {
        ClientConfirmationToken token = new ClientConfirmationToken(value, expiration, klijent);
        return token;
    }

    private LoginRequestDto loginRequest(String email, String password) {
        LoginRequestDto dto = new LoginRequestDto();
        dto.setEmail(email);
        dto.setPassword(password);
        return dto;
    }

    private Klijent klijent(String email, String password) {
        Klijent k = new Klijent();
        k.setId(1L);
        k.setIme("Marko");
        k.setPrezime("Markovic");
        k.setDatumRodjenja(946684800000L);
        k.setPol(Pol.M);
        k.setEmail(email);
        k.setJmbg("1234567890123");
        k.setPassword(password);
        k.setRole(ClientRole.CLIENT_BASIC);
        k.setAktivan(true);
        return k;
    }
}
