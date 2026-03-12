package com.banka1.userService.service.implementation;

import com.banka1.userService.domain.ConfirmationToken;
import com.banka1.userService.domain.RefreshToken;
import com.banka1.userService.domain.Zaposlen;
import com.banka1.userService.domain.enums.Pol;
import com.banka1.userService.domain.enums.Role;
import com.banka1.userService.dto.requests.ActivateDto;
import com.banka1.userService.dto.requests.ForgotPasswordDto;
import com.banka1.userService.dto.requests.LoginRequestDto;
import com.banka1.userService.dto.requests.RefreshTokenRequestDto;
import com.banka1.userService.dto.responses.TokenResponseDto;
import com.banka1.userService.exception.BusinessException;
import com.banka1.userService.rabbitMQ.RabbitClient;
import com.banka1.userService.repository.ConfirmationTokenRepository;
import com.banka1.userService.repository.TokenRepository;
import com.banka1.userService.repository.ZaposlenRepository;
import com.banka1.userService.security.JWTService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplementationTest {

    @Mock
    private ZaposlenRepository zaposlenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JWTService jwtService;

    @Mock
    private TokenRepository tokenRepository;

    @Mock
    private ConfirmationTokenRepository confirmationTokenRepository;

    @Mock
    private RabbitClient rabbitClient;

    @InjectMocks
    private AuthServiceImplementation authService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "urlResetPassword", "http://localhost/reset?token=");
        ReflectionTestUtils.setField(authService, "urlActivateAccount", "http://localhost/activate?token=");
        ReflectionTestUtils.setField(authService, "refreshTokenExpiration", 1L);
        ReflectionTestUtils.setField(authService, "confirmationTokenExpiration", 15L);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void loginReturnsTokensWhenCredentialsAreValid() {
        Zaposlen employee = activeEmployee();
        employee.setPassword("encoded-password");

        when(zaposlenRepository.findByEmail("pera@banka.com")).thenReturn(Optional.of(employee));
        when(passwordEncoder.matches("Password12", "encoded-password")).thenReturn(true);
        when(jwtService.generateRandomToken()).thenReturn("plain-refresh");
        when(jwtService.sha256Hex("plain-refresh")).thenReturn("hashed-refresh");
        when(jwtService.generateJwtToken(employee)).thenReturn("jwt-token");
        when(tokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TokenResponseDto response = authService.login(new LoginRequestDto("pera@banka.com", "Password12"));

        assertThat(response.getJwt()).isEqualTo("jwt-token");
        assertThat(response.getRefreshToken()).isEqualTo("plain-refresh");
        verify(tokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void refreshTokenThrowsWhenTokenIsExpired() {
        RefreshToken refreshToken = new RefreshToken(activeEmployee());
        refreshToken.setExpirationDateTime(LocalDateTime.now().minusMinutes(1));

        when(jwtService.sha256Hex("plain-refresh")).thenReturn("hashed-refresh");
        when(tokenRepository.findByValue("hashed-refresh")).thenReturn(Optional.of(refreshToken));

        assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenRequestDto("plain-refresh")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Pogresan token");
    }

    @Test
    void loginThrowsForInvalidCredentials() {
        Zaposlen employee = activeEmployee();
        employee.setPassword("encoded-password");

        when(zaposlenRepository.findByEmail("pera@banka.com")).thenReturn(Optional.of(employee));
        when(passwordEncoder.matches("WrongPassword", "encoded-password")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequestDto("pera@banka.com", "WrongPassword")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Greska pri loginovanju");
    }

    @Test
    void loginThrowsForInactiveUser() {
        Zaposlen employee = activeEmployee();
        employee.setAktivan(false);
        employee.setPassword("encoded-password");

        when(zaposlenRepository.findByEmail("pera@banka.com")).thenReturn(Optional.of(employee));
        when(passwordEncoder.matches("Password12", "encoded-password")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequestDto("pera@banka.com", "Password12")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Korisnik nije aktivan");
    }

    @Test
    void loginThrowsWhenUserNotFound() {
        when(zaposlenRepository.findByEmail("ghost@banka.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequestDto("ghost@banka.com", "Password12")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Greska pri loginovanju");
    }

    @Test
    void refreshTokenThrowsForDeletedUser() {
        Zaposlen deleted = activeEmployee();
        deleted.setDeleted(true);
        RefreshToken refreshToken = new RefreshToken(deleted);
        refreshToken.setExpirationDateTime(LocalDateTime.now().plusDays(1));

        when(jwtService.sha256Hex("plain-refresh")).thenReturn("hashed-refresh");
        when(tokenRepository.findByValue("hashed-refresh")).thenReturn(Optional.of(refreshToken));

        assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenRequestDto("plain-refresh")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Korisnik je obrisan");
    }

    @Test
    void refreshTokenThrowsWhenTokenNotFound() {
        when(jwtService.sha256Hex("bad-token")).thenReturn("hashed-bad");
        when(tokenRepository.findByValue("hashed-bad")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenRequestDto("bad-token")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Pogresan token");
    }

    @Test
    void checkThrowsForExpiredToken() {
        ConfirmationToken token = new ConfirmationToken();
        token.setValue("hashed-token");
        token.setExpirationDateTime(LocalDateTime.now().minusMinutes(1));
        token.setZaposlen(activeEmployee());

        String plainToken = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        when(jwtService.sha256Hex(plainToken)).thenReturn("hashed-token");
        when(confirmationTokenRepository.findByValue("hashed-token")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.check(plainToken))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Pogresan token");
    }

    @Test
    void checkThrowsForDeletedUser() {
        Zaposlen deleted = activeEmployee();
        deleted.setDeleted(true);

        ConfirmationToken token = new ConfirmationToken();
        token.setValue("hashed-token");
        token.setZaposlen(deleted);

        String plainToken = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        when(jwtService.sha256Hex(plainToken)).thenReturn("hashed-token");
        when(confirmationTokenRepository.findByValue("hashed-token")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.check(plainToken))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Korisnik je obrisan");
    }

    @Test
    void checkThrowsForNullToken() {
        assertThatThrownBy(() -> authService.check(null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Pogresan token");
    }

    @Test
    void checkThrowsForTooShortToken() {
        assertThatThrownBy(() -> authService.check("tooshort"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Pogresan token");
    }

    @Test
    void checkReturnsIdForValidConfirmationToken() {
        ConfirmationToken confirmationToken = new ConfirmationToken();
        confirmationToken.setId(11L);
        confirmationToken.setValue("hashed-token");
        confirmationToken.setZaposlen(activeEmployee());

        String plainToken = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        when(jwtService.sha256Hex(plainToken)).thenReturn("hashed-token");
        when(confirmationTokenRepository.findByValue("hashed-token")).thenReturn(Optional.of(confirmationToken));

        Long id = authService.check(plainToken);

        assertThat(id).isEqualTo(11L);
    }

    @Test
    void editPasswordActivatesUserAndDeletesConfirmationToken() {
        Zaposlen employee = activeEmployee();
        employee.setAktivan(false);

        ConfirmationToken confirmationToken = new ConfirmationToken();
        confirmationToken.setId(7L);
        confirmationToken.setValue("hashed-token");
        confirmationToken.setZaposlen(employee);

        ActivateDto request = new ActivateDto(7L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Password12");

        when(confirmationTokenRepository.findById(7L)).thenReturn(Optional.of(confirmationToken));
        when(jwtService.sha256Hex(request.getConfirmationToken())).thenReturn("hashed-token");
        when(passwordEncoder.encode("Password12")).thenReturn("encoded-password");

        String response = authService.editPassword(request, true);

        assertThat(response).isEqualTo("Uspesno aktiviranje usera");
        assertThat(employee.isAktivan()).isTrue();
        assertThat(employee.getPassword()).isEqualTo("encoded-password");
        verify(confirmationTokenRepository).delete(confirmationToken);
    }

    @Test
    void editPasswordChangesPasswordWithoutActivating() {
        Zaposlen employee = activeEmployee();

        ConfirmationToken confirmationToken = new ConfirmationToken();
        confirmationToken.setId(8L);
        confirmationToken.setValue("hashed-token");
        confirmationToken.setZaposlen(employee);

        ActivateDto request = new ActivateDto(8L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "NewPass12");

        when(confirmationTokenRepository.findById(8L)).thenReturn(Optional.of(confirmationToken));
        when(jwtService.sha256Hex(request.getConfirmationToken())).thenReturn("hashed-token");
        when(passwordEncoder.encode("NewPass12")).thenReturn("new-encoded");

        String response = authService.editPassword(request, false);

        assertThat(response).isEqualTo("Uspesna promena lozinke");
        assertThat(employee.getPassword()).isEqualTo("new-encoded");
        verify(confirmationTokenRepository).delete(confirmationToken);
    }

    @Test
    void editPasswordThrowsForInactiveUser() {
        Zaposlen employee = activeEmployee();
        employee.setAktivan(false);

        ConfirmationToken confirmationToken = new ConfirmationToken();
        confirmationToken.setId(7L);
        confirmationToken.setValue("hashed-token");
        confirmationToken.setZaposlen(employee);

        ActivateDto request = new ActivateDto(7L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Password12");

        when(confirmationTokenRepository.findById(7L)).thenReturn(Optional.of(confirmationToken));
        when(jwtService.sha256Hex(request.getConfirmationToken())).thenReturn("hashed-token");

        assertThatThrownBy(() -> authService.editPassword(request, false))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Korisnik nije aktivan");
    }

    @Test
    void forgotPasswordThrowsForInactiveUser() {
        Zaposlen employee = activeEmployee();
        employee.setAktivan(false);

        when(zaposlenRepository.findByEmail("pera@banka.com")).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> authService.forgotPassword(new ForgotPasswordDto("pera@banka.com")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Korisnik nije aktivan");
        verify(rabbitClient, never()).sendEmailNotification(any());
    }

    @Test
    void forgotPasswordCreatesConfirmationTokenWhenMissing() {
        Zaposlen employee = activeEmployee();
        employee.setConfirmationToken(null);

        when(zaposlenRepository.findByEmail("pera@banka.com")).thenReturn(Optional.of(employee));
        when(jwtService.generateRandomToken()).thenReturn("plain-reset");
        when(jwtService.sha256Hex("plain-reset")).thenReturn("hashed-reset");
        when(confirmationTokenRepository.save(any(ConfirmationToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String response = authService.forgotPassword(new ForgotPasswordDto("pera@banka.com"));

        assertThat(response).isEqualTo("Poslat mejl");
        assertThat(employee.getConfirmationToken()).isNotNull();
        assertThat(employee.getConfirmationToken().getValue()).isEqualTo("hashed-reset");
        verify(rabbitClient, never()).sendEmailNotification(any());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
    }

    @Test
    void forgotPasswordUpdatesExistingConfirmationToken() {
        Zaposlen employee = activeEmployee();
        ConfirmationToken existingToken = new ConfirmationToken();
        existingToken.setValue("old-hash");
        existingToken.setZaposlen(employee);
        employee.setConfirmationToken(existingToken);

        when(zaposlenRepository.findByEmail("pera@banka.com")).thenReturn(Optional.of(employee));
        when(jwtService.generateRandomToken()).thenReturn("new-plain");
        when(jwtService.sha256Hex("new-plain")).thenReturn("new-hash");

        String response = authService.forgotPassword(new ForgotPasswordDto("pera@banka.com"));

        assertThat(response).isEqualTo("Poslat mejl");
        assertThat(existingToken.getValue()).isEqualTo("new-hash");
        assertThat(existingToken.getExpirationDateTime()).isNotNull();
    }

    @Test
    void forgotPasswordThrowsWhenUserNotFound() {
        when(zaposlenRepository.findByEmail("ghost@banka.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.forgotPassword(new ForgotPasswordDto("ghost@banka.com")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Ne postoji korisnik sa ovim emailom");
    }

    @Test
    void logoutDeletesRefreshToken() {
        RefreshToken token = new RefreshToken(activeEmployee());

        when(jwtService.sha256Hex("plain")).thenReturn("hashed");
        when(tokenRepository.findByValue("hashed")).thenReturn(Optional.of(token));

        authService.logout("plain");

        verify(tokenRepository).delete(token);
    }

    @Test
    void logoutDoesNothingWhenTokenNotFound() {
        when(jwtService.sha256Hex("unknown")).thenReturn("hashed-unknown");
        when(tokenRepository.findByValue("hashed-unknown")).thenReturn(Optional.empty());

        authService.logout("unknown");

        verify(tokenRepository, never()).delete(any());
    }

    @Test
    void resendActivationSendsEmailForInactiveUser() {
        Zaposlen employee = activeEmployee();
        employee.setAktivan(false);
        employee.setConfirmationToken(null);

        when(zaposlenRepository.findByEmail("pera@banka.com")).thenReturn(Optional.of(employee));
        when(jwtService.generateRandomToken()).thenReturn("plain-activation");
        when(jwtService.sha256Hex("plain-activation")).thenReturn("hashed-activation");
        when(confirmationTokenRepository.save(any(ConfirmationToken.class))).thenAnswer(inv -> inv.getArgument(0));

        String response = authService.resendActivation("pera@banka.com");

        assertThat(response).isEqualTo("Poslat mejl");
        assertThat(employee.getConfirmationToken()).isNotNull();
        assertThat(employee.getConfirmationToken().getValue()).isEqualTo("hashed-activation");
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
    }

    @Test
    void resendActivationUpdatesExistingToken() {
        Zaposlen employee = activeEmployee();
        employee.setAktivan(false);
        ConfirmationToken existingToken = new ConfirmationToken("old-hash", employee);
        employee.setConfirmationToken(existingToken);

        when(zaposlenRepository.findByEmail("pera@banka.com")).thenReturn(Optional.of(employee));
        when(jwtService.generateRandomToken()).thenReturn("new-plain");
        when(jwtService.sha256Hex("new-plain")).thenReturn("new-hash");

        String response = authService.resendActivation("pera@banka.com");

        assertThat(response).isEqualTo("Poslat mejl");
        assertThat(existingToken.getValue()).isEqualTo("new-hash");
    }

    @Test
    void resendActivationReturnsMsgWhenAlreadyActive() {
        Zaposlen employee = activeEmployee();

        when(zaposlenRepository.findByEmail("pera@banka.com")).thenReturn(Optional.of(employee));

        String response = authService.resendActivation("pera@banka.com");

        assertThat(response).isEqualTo("Nalog je vec aktivan");
        verify(rabbitClient, never()).sendEmailNotification(any());
    }

    @Test
    void resendActivationThrowsForDeletedUser() {
        Zaposlen deleted = activeEmployee();
        deleted.setDeleted(true);

        when(zaposlenRepository.findByEmail("pera@banka.com")).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> authService.resendActivation("pera@banka.com"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Korisnik je obrisan");
    }

    @Test
    void resendActivationThrowsWhenUserNotFound() {
        when(zaposlenRepository.findByEmail("ghost@banka.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.resendActivation("ghost@banka.com"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Ne postoji korisnik sa ovim emailom");
    }

    @Test
    void refreshTokenReturnsNewTokensWhenValid() {
        Zaposlen employee = activeEmployee();
        RefreshToken refreshToken = new RefreshToken(employee);
        refreshToken.setExpirationDateTime(LocalDateTime.now().plusDays(1));

        when(jwtService.sha256Hex("plain-refresh")).thenReturn("hashed-refresh");
        when(tokenRepository.findByValue("hashed-refresh")).thenReturn(Optional.of(refreshToken));
        when(jwtService.generateRandomToken()).thenReturn("new-plain");
        when(jwtService.sha256Hex("new-plain")).thenReturn("new-hashed");
        when(jwtService.generateJwtToken(employee)).thenReturn("new-jwt");
        when(tokenRepository.save(refreshToken)).thenReturn(refreshToken);

        TokenResponseDto response = authService.refreshToken(new RefreshTokenRequestDto("plain-refresh"));

        assertThat(response.getJwt()).isEqualTo("new-jwt");
        assertThat(response.getRefreshToken()).isEqualTo("new-plain");
        verify(tokenRepository).save(refreshToken);
    }

    @Test
    void editPasswordThrowsForDeletedUser() {
        Zaposlen deleted = activeEmployee();
        deleted.setDeleted(true);

        ConfirmationToken confirmationToken = new ConfirmationToken();
        confirmationToken.setId(9L);
        confirmationToken.setValue("hashed-token");
        confirmationToken.setZaposlen(deleted);

        ActivateDto request = new ActivateDto(9L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Password12");

        when(confirmationTokenRepository.findById(9L)).thenReturn(Optional.of(confirmationToken));
        when(jwtService.sha256Hex(request.getConfirmationToken())).thenReturn("hashed-token");

        assertThatThrownBy(() -> authService.editPassword(request, true))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Ne moze se editovati obrisani korisnik");
    }

    @Test
    void editPasswordThrowsWhenTokenNotFound() {
        ActivateDto request = new ActivateDto(99L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Password12");

        when(confirmationTokenRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.editPassword(request, true))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Pogresan token");
    }

    @Test
    void editPasswordThrowsWhenConfirmationTokenValueMismatch() {
        Zaposlen employee = activeEmployee();

        ConfirmationToken confirmationToken = new ConfirmationToken();
        confirmationToken.setId(10L);
        confirmationToken.setValue("correct-hash");
        confirmationToken.setZaposlen(employee);

        ActivateDto request = new ActivateDto(10L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Password12");

        when(confirmationTokenRepository.findById(10L)).thenReturn(Optional.of(confirmationToken));
        when(jwtService.sha256Hex(request.getConfirmationToken())).thenReturn("wrong-hash");

        assertThatThrownBy(() -> authService.editPassword(request, true))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Pogresan token");
    }

    @Test
    void editPasswordThrowsWhenConfirmationTokenIsExpired() {
        Zaposlen employee = activeEmployee();

        ConfirmationToken confirmationToken = new ConfirmationToken();
        confirmationToken.setId(11L);
        confirmationToken.setValue("hashed-token");
        confirmationToken.setExpirationDateTime(LocalDateTime.now().minusMinutes(5));
        confirmationToken.setZaposlen(employee);

        ActivateDto request = new ActivateDto(11L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Password12");

        when(confirmationTokenRepository.findById(11L)).thenReturn(Optional.of(confirmationToken));
        when(jwtService.sha256Hex(request.getConfirmationToken())).thenReturn("hashed-token");

        assertThatThrownBy(() -> authService.editPassword(request, false))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Pogresan token");
    }

    @Test
    void generateThrowsAfterThreeTokenCollisions() {
        Zaposlen employee = activeEmployee();
        employee.setPassword("encoded");

        when(zaposlenRepository.findByEmail("pera@banka.com")).thenReturn(Optional.of(employee));
        when(passwordEncoder.matches("Password12", "encoded")).thenReturn(true);
        when(jwtService.generateRandomToken()).thenReturn("plain-token");
        when(jwtService.sha256Hex("plain-token")).thenReturn("hashed-token");
        when(tokenRepository.save(any(RefreshToken.class)))
                .thenThrow(new DataIntegrityViolationException("dup"))
                .thenThrow(new DataIntegrityViolationException("dup"))
                .thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> authService.login(new LoginRequestDto("pera@banka.com", "Password12")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Greska sa generisanjem tokena");
    }

    @Test
    void checkReturnsIdForValidTokenWithNullExpiration() {
        ConfirmationToken confirmationToken = new ConfirmationToken();
        confirmationToken.setId(12L);
        confirmationToken.setValue("hashed-token");
        confirmationToken.setExpirationDateTime(null);
        confirmationToken.setZaposlen(activeEmployee());

        String plainToken = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        when(jwtService.sha256Hex(plainToken)).thenReturn("hashed-token");
        when(confirmationTokenRepository.findByValue("hashed-token")).thenReturn(Optional.of(confirmationToken));

        Long id = authService.check(plainToken);
        assertThat(id).isEqualTo(12L);
    }

    @Test
    void checkThrowsForBlankToken() {
        assertThatThrownBy(() -> authService.check("   "))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Pogresan token");
    }

    @Test
    void checkThrowsWhenConfirmationTokenNotFound() {
        String plainToken = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        when(jwtService.sha256Hex(plainToken)).thenReturn("hashed-token");
        when(confirmationTokenRepository.findByValue("hashed-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.check(plainToken))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Pogresan token");
    }

    private Zaposlen activeEmployee() {
        Zaposlen employee = new Zaposlen();
        employee.setId(1L);
        employee.setIme("Pera");
        employee.setPrezime("Peric");
        employee.setDatumRodjenja(LocalDate.of(1990, 1, 1));
        employee.setPol(Pol.M);
        employee.setEmail("pera@banka.com");
        employee.setUsername("pera");
        employee.setPozicija("Agent");
        employee.setDepartman("Prodaja");
        employee.setAktivan(true);
        employee.setRole(Role.AGENT);
        return employee;
    }
}
