package com.banka1.userService.integration;

import com.banka1.userService.domain.ConfirmationToken;
import com.banka1.userService.domain.RefreshToken;
import com.banka1.userService.domain.Zaposlen;
import com.banka1.userService.domain.enums.Pol;
import com.banka1.userService.domain.enums.Role;
import com.banka1.userService.dto.requests.ForgotPasswordDto;
import com.banka1.userService.dto.requests.LoginRequestDto;
import com.banka1.userService.dto.requests.LogoutRequestDto;
import com.banka1.userService.dto.requests.RefreshTokenRequestDto;
import com.banka1.userService.dto.requests.ResendActivationDto;
import com.banka1.userService.rabbitMQ.RabbitClient;
import com.banka1.userService.repository.ConfirmationTokenRepository;
import com.banka1.userService.repository.TokenRepository;
import com.banka1.userService.repository.ZaposlenRepository;
import com.banka1.userService.security.JWTService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthEndpointsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private ZaposlenRepository zaposlenRepository;

    @Autowired
    private TokenRepository tokenRepository;

    @Autowired
    private ConfirmationTokenRepository confirmationTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JWTService jwtService;

    @MockitoBean
    private RabbitClient rabbitClient;

    @BeforeEach
    void setUp() {
        confirmationTokenRepository.deleteAll();
        tokenRepository.deleteAll();
        zaposlenRepository.deleteAll();
        doNothing().when(rabbitClient).sendEmailNotification(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void loginEndpointReturnsJwtAndRefreshTokenForPersistedUser() throws Exception {
        Zaposlen employee = activeEmployee("pera@banka.com", "pera", Role.AGENT);
        employee.setPassword(passwordEncoder.encode("Password12"));
        zaposlenRepository.save(employee);

        LoginRequestDto request = new LoginRequestDto("pera@banka.com", "Password12");

        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jwt").isString())
                .andExpect(jsonPath("$.refreshToken").isString());

        assertThat(tokenRepository.findAll()).hasSize(1);
    }

    @Test
    void refreshEndpointRotatesExistingRefreshToken() throws Exception {
        Zaposlen employee = zaposlenRepository.save(activeEmployee("mika@banka.com", "mika", Role.AGENT));
        String rawRefreshToken = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

        tokenRepository.save(new RefreshToken(
                jwtService.sha256Hex(rawRefreshToken),
                LocalDateTime.now().plusDays(1),
                employee
        ));

        RefreshTokenRequestDto request = new RefreshTokenRequestDto(rawRefreshToken);

        String rotatedToken = mockMvc.perform(post("/auth/refresh")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jwt").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(rotatedToken).doesNotContain(rawRefreshToken);
        assertThat(tokenRepository.findAll()).hasSize(1);
    }

    @Test
    void loginReturnsUnauthorizedForWrongPassword() throws Exception {
        Zaposlen employee = activeEmployee("wrong@banka.com", "wrongpw", Role.AGENT);
        employee.setPassword(passwordEncoder.encode("RealPassword12"));
        zaposlenRepository.save(employee);

        LoginRequestDto request = new LoginRequestDto("wrong@banka.com", "WrongPassword12");

        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginReturnsForbiddenForInactiveUser() throws Exception {
        Zaposlen employee = activeEmployee("inactive@banka.com", "inactiveuser", Role.AGENT);
        employee.setAktivan(false);
        employee.setPassword(passwordEncoder.encode("Password12"));
        zaposlenRepository.save(employee);

        LoginRequestDto request = new LoginRequestDto("inactive@banka.com", "Password12");

        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutDeletesRefreshTokenFromDatabase() throws Exception {
        Zaposlen employee = zaposlenRepository.save(activeEmployee("logout@banka.com", "logoutuser", Role.BASIC));
        String rawToken = "ccccccccccccccccccccccccccccccccccccccccccc";

        tokenRepository.save(new RefreshToken(
                jwtService.sha256Hex(rawToken),
                LocalDateTime.now().plusDays(1),
                employee
        ));
        assertThat(tokenRepository.findAll()).hasSize(1);

        LogoutRequestDto request = new LogoutRequestDto(rawToken);

        mockMvc.perform(delete("/auth/logout")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        assertThat(tokenRepository.findAll()).isEmpty();
    }

    @Test
    void logoutWithUnknownTokenReturnsNoContentSilently() throws Exception {
        LogoutRequestDto request = new LogoutRequestDto("nonexistenttoken12345678901234567890123");

        mockMvc.perform(delete("/auth/logout")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());
    }

    @Test
    void checkActivateReturnsConfirmationTokenId() throws Exception {
        Zaposlen employee = zaposlenRepository.save(activeEmployee("check@banka.com", "checkuser", Role.BASIC));
        String plainToken = "ddddddddddddddddddddddddddddddddddddddddddd";

        ConfirmationToken ct = new ConfirmationToken(jwtService.sha256Hex(plainToken), employee);
        confirmationTokenRepository.save(ct);

        mockMvc.perform(get("/auth/checkActivate")
                        .param("confirmationToken", plainToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isNumber());
    }

    @Test
    void checkActivateReturns400ForInvalidToken() throws Exception {
        mockMvc.perform(get("/auth/checkActivate")
                        .param("confirmationToken", "tooshort"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void forgotPasswordReturnsAcceptedForExistingActiveUser() throws Exception {
        Zaposlen employee = activeEmployee("forgot@banka.com", "forgotuser", Role.BASIC);
        zaposlenRepository.save(employee);

        ForgotPasswordDto request = new ForgotPasswordDto("forgot@banka.com");

        mockMvc.perform(post("/auth/forgot-password")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());

        assertThat(confirmationTokenRepository.findAll()).hasSize(1);
    }

    @Test
    void resendActivationReturnsAcceptedForInactiveUser() throws Exception {
        Zaposlen employee = activeEmployee("resend@banka.com", "resenduser", Role.BASIC);
        employee.setAktivan(false);
        zaposlenRepository.save(employee);

        ResendActivationDto request = new ResendActivationDto("resend@banka.com");

        mockMvc.perform(post("/auth/resend-activation")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());
    }

    @Test
    void resendActivationReturnsMsgWhenUserAlreadyActive() throws Exception {
        Zaposlen employee = activeEmployee("active@banka.com", "activeuser", Role.BASIC);
        zaposlenRepository.save(employee);

        ResendActivationDto request = new ResendActivationDto("active@banka.com");

        mockMvc.perform(post("/auth/resend-activation")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$").value("Nalog je vec aktivan"));
    }

    private Zaposlen activeEmployee(String email, String username, Role role) {
        Zaposlen employee = new Zaposlen();
        employee.setIme("Pera");
        employee.setPrezime("Peric");
        employee.setDatumRodjenja(LocalDate.of(1990, 1, 1));
        employee.setPol(Pol.M);
        employee.setEmail(email);
        employee.setUsername(username);
        employee.setPozicija("Agent");
        employee.setDepartman("Prodaja");
        employee.setAktivan(true);
        employee.setRole(role);
        return employee;
    }
}
