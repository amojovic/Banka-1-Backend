package com.banka1.clientService.controller;

import com.banka1.clientService.advice.GlobalExceptionHandler;
import com.banka1.clientService.dto.requests.ActivateDto;
import com.banka1.clientService.dto.requests.ForgotPasswordDto;
import com.banka1.clientService.dto.requests.LoginRequestDto;
import com.banka1.clientService.dto.responses.LoginResponseDto;
import com.banka1.clientService.exception.BusinessException;
import com.banka1.clientService.exception.ErrorCode;
import com.banka1.clientService.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
class AuthControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private AuthService authService;

    @Test
    void loginValidCredentialsReturnsToken() throws Exception {
        when(authService.login(any())).thenReturn(new LoginResponseDto("jwt.token.value", null, null, null, null));

        mockMvc.perform(post("/clients/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt.token.value"));
    }

    @Test
    void loginInvalidCredentialsReturns401() throws Exception {
        when(authService.login(any()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_CREDENTIALS, ""));

        mockMvc.perform(post("/clients/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ERR_AUTH_002"));
    }

    @Test
    void loginMissingEmailReturns400() throws Exception {
        LoginRequestDto dto = new LoginRequestDto();
        dto.setPassword("lozinka123");

        mockMvc.perform(post("/clients/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.email").exists());
    }

    @Test
    void loginMissingPasswordReturns400() throws Exception {
        LoginRequestDto dto = new LoginRequestDto();
        dto.setEmail("marko@banka.com");

        mockMvc.perform(post("/clients/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").exists());
    }

    @Test
    void loginInvalidEmailFormatReturns400() throws Exception {
        LoginRequestDto dto = new LoginRequestDto();
        dto.setEmail("nije-email");
        dto.setPassword("lozinka123");

        mockMvc.perform(post("/clients/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.email").exists());
    }

    // ── check-activate ───────────────────────────────────────────────────────

    @Test
    void checkActivateValidTokenReturnsId() throws Exception {
        when(authService.check("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).thenReturn(7L);

        mockMvc.perform(get("/clients/auth/check-activate")
                        .param("token", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));
    }

    @Test
    void checkActivateInvalidTokenReturns401() throws Exception {
        when(authService.check(any())).thenThrow(new BusinessException(ErrorCode.INVALID_TOKEN, ""));

        mockMvc.perform(get("/clients/auth/check-activate")
                        .param("token", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ERR_AUTH_001"));
    }

    // ── activate ─────────────────────────────────────────────────────────────

    @Test
    void activateValidRequestReturnsOk() throws Exception {
        when(authService.editPassword(any(), eq(true))).thenReturn("Uspesno aktiviranje klijenta");

        mockMvc.perform(post("/clients/auth/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validActivateDto())))
                .andExpect(status().isOk());
    }

    @Test
    void activateMissingIdReturns400() throws Exception {
        ActivateDto dto = validActivateDto();
        dto.setId(null);

        mockMvc.perform(post("/clients/auth/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.id").exists());
    }

    @Test
    void activateTokenWrongLengthReturns400() throws Exception {
        ActivateDto dto = validActivateDto();
        dto.setConfirmationToken("tooshort");

        mockMvc.perform(post("/clients/auth/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.confirmationToken").exists());
    }

    @Test
    void activateWeakPasswordReturns400() throws Exception {
        ActivateDto dto = validActivateDto();
        dto.setPassword("weakpass");

        mockMvc.perform(post("/clients/auth/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").exists());
    }

    @Test
    void activateInvalidTokenFromServiceReturns401() throws Exception {
        when(authService.editPassword(any(), eq(true)))
                .thenThrow(new BusinessException(ErrorCode.INVALID_TOKEN, ""));

        mockMvc.perform(post("/clients/auth/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validActivateDto())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ERR_AUTH_001"));
    }

    // ── reset-password ───────────────────────────────────────────────────────

    @Test
    void resetPasswordValidRequestReturnsOk() throws Exception {
        when(authService.editPassword(any(), eq(false))).thenReturn("Uspesna promena lozinke");

        mockMvc.perform(post("/clients/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validActivateDto())))
                .andExpect(status().isOk());
    }

    @Test
    void resetPasswordInvalidTokenReturns401() throws Exception {
        when(authService.editPassword(any(), eq(false)))
                .thenThrow(new BusinessException(ErrorCode.INVALID_TOKEN, ""));

        mockMvc.perform(post("/clients/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validActivateDto())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resetPasswordInactiveUserReturns403() throws Exception {
        when(authService.editPassword(any(), eq(false)))
                .thenThrow(new BusinessException(ErrorCode.USER_INACTIVE, ""));

        mockMvc.perform(post("/clients/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validActivateDto())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ERR_AUTH_003"));
    }

    // ── forgot-password ──────────────────────────────────────────────────────

    @Test
    void forgotPasswordValidRequestReturnsOk() throws Exception {
        when(authService.forgotPassword(any())).thenReturn("Poslat mejl");

        mockMvc.perform(post("/clients/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForgotPasswordDto("marko@banka.com"))))
                .andExpect(status().isOk());
    }

    @Test
    void forgotPasswordInvalidEmailReturns400() throws Exception {
        mockMvc.perform(post("/clients/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForgotPasswordDto("nije-email"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.email").exists());
    }

    @Test
    void forgotPasswordUserNotFoundReturns404() throws Exception {
        when(authService.forgotPassword(any()))
                .thenThrow(new BusinessException(ErrorCode.USER_NOT_FOUND, ""));

        mockMvc.perform(post("/clients/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForgotPasswordDto("marko@banka.com"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ERR_AUTH_004"));
    }

    @Test
    void forgotPasswordInactiveUserReturns403() throws Exception {
        when(authService.forgotPassword(any()))
                .thenThrow(new BusinessException(ErrorCode.USER_INACTIVE, ""));

        mockMvc.perform(post("/clients/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForgotPasswordDto("marko@banka.com"))))
                .andExpect(status().isForbidden());
    }

    // ── resend-activation ────────────────────────────────────────────────────

    @Test
    void resendActivationValidRequestReturnsOk() throws Exception {
        when(authService.resendActivation("marko@banka.com")).thenReturn("Poslat mejl");

        mockMvc.perform(post("/clients/auth/resend-activation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForgotPasswordDto("marko@banka.com"))))
                .andExpect(status().isOk());
    }

    @Test
    void resendActivationUserNotFoundReturns404() throws Exception {
        when(authService.resendActivation(any()))
                .thenThrow(new BusinessException(ErrorCode.USER_NOT_FOUND, ""));

        mockMvc.perform(post("/clients/auth/resend-activation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForgotPasswordDto("marko@banka.com"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void resendActivationInvalidEmailReturns400() throws Exception {
        mockMvc.perform(post("/clients/auth/resend-activation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForgotPasswordDto("nije-email"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.email").exists());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ActivateDto validActivateDto() {
        return new ActivateDto(1L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Password12");
    }

    private LoginRequestDto validRequest() {
        LoginRequestDto dto = new LoginRequestDto();
        dto.setEmail("marko@banka.com");
        dto.setPassword("lozinka123");
        return dto;
    }
}
