package com.banka1.employeeService.controller;

import com.banka1.employeeService.advice.GlobalExceptionHandler;
import com.banka1.employeeService.domain.enums.Role;
import com.banka1.employeeService.dto.requests.ActivateDto;
import com.banka1.employeeService.dto.requests.ForgotPasswordDto;
import com.banka1.employeeService.dto.requests.LoginRequestDto;
import com.banka1.employeeService.dto.requests.LogoutRequestDto;
import com.banka1.employeeService.dto.requests.ResendActivationDto;
import com.banka1.employeeService.dto.responses.TokenResponseDto;
import com.banka1.employeeService.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
class AuthControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private AuthService authService;

    @Test
    void loginReturnsTokensForValidRequest() throws Exception {
        LoginRequestDto request = new LoginRequestDto("user@banka.com", "Password12");
        TokenResponseDto response = new TokenResponseDto("jwt-token", "refresh-token", Role.BASIC, new HashSet<>());

        when(authService.login(any(LoginRequestDto.class))).thenReturn(response);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jwt").value("jwt-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"));
    }

    @Test
    void loginReturnsValidationErrorsForInvalidPayload() throws Exception {
        LoginRequestDto request = new LoginRequestDto("", "");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ERR_VALIDATION"))
                .andExpect(jsonPath("$.validationErrors.email").exists())
                .andExpect(jsonPath("$.validationErrors.password").exists());
    }

    @Test
    void forgotPasswordReturnsSuccessMessage() throws Exception {
        ForgotPasswordDto request = new ForgotPasswordDto("user@banka.com");

        when(authService.forgotPassword(any(ForgotPasswordDto.class))).thenReturn("Poslat mejl");

        mockMvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$").value("Poslat mejl"));
    }

    @Test
    void activateDelegatesToServiceWithActivationFlag() throws Exception {
        ActivateDto request = new ActivateDto(5L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Password12");

        when(authService.editPassword(any(ActivateDto.class), eq(true))).thenReturn("Uspesno aktiviranje usera");

        mockMvc.perform(post("/auth/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("Uspesno aktiviranje usera"));

        verify(authService).editPassword(any(ActivateDto.class), eq(true));
    }

    @Test
    void checkActivateReturnsConfirmationTokenId() throws Exception {
        when(authService.check("validtoken123456789012345678901234567890123")).thenReturn(7L);

        mockMvc.perform(get("/auth/checkActivate")
                        .param("confirmationToken", "validtoken123456789012345678901234567890123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(7));
    }

    @Test
    void checkResetPasswordReturnsConfirmationTokenId() throws Exception {
        when(authService.check("validtoken123456789012345678901234567890123")).thenReturn(11L);

        mockMvc.perform(get("/auth/checkResetPassword")
                        .param("confirmationToken", "validtoken123456789012345678901234567890123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(11));
    }

    @Test
    void resetPasswordDelegatesToServiceWithFalseFlag() throws Exception {
        ActivateDto request = new ActivateDto(3L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "NewPass12");

        when(authService.editPassword(any(ActivateDto.class), eq(false))).thenReturn("Uspesna promena lozinke");

        mockMvc.perform(post("/auth/resetPassword")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("Uspesna promena lozinke"));

        verify(authService).editPassword(any(ActivateDto.class), eq(false));
    }

    @Test
    void logoutReturnsNoContentAndDelegatesToService() throws Exception {
        LogoutRequestDto request = new LogoutRequestDto("some-refresh-token");

        doNothing().when(authService).logout(anyString());

        mockMvc.perform(delete("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        verify(authService).logout("some-refresh-token");
    }

    @Test
    void logoutReturnsValidationErrorForBlankToken() throws Exception {
        LogoutRequestDto request = new LogoutRequestDto("");

        mockMvc.perform(delete("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resendActivationReturnsAcceptedAndDelegatesToService() throws Exception {
        ResendActivationDto request = new ResendActivationDto("inactive@banka.com");

        when(authService.resendActivation("inactive@banka.com")).thenReturn("Poslat mejl");

        mockMvc.perform(post("/auth/resend-activation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$").value("Poslat mejl"));

        verify(authService).resendActivation("inactive@banka.com");
    }

    @Test
    void resendActivationReturnsValidationErrorForInvalidEmail() throws Exception {
        ResendActivationDto request = new ResendActivationDto("not-an-email");

        mockMvc.perform(post("/auth/resend-activation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
