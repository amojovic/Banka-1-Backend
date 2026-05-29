package com.banka1.verificationService.controller;

import com.banka1.verificationService.advice.GlobalExceptionHandler;
import com.banka1.verificationService.dto.request.GenerateRequest;
import com.banka1.verificationService.dto.request.ValidateRequest;
import com.banka1.verificationService.dto.response.GenerateResponse;
import com.banka1.verificationService.dto.response.StatusResponse;
import com.banka1.verificationService.dto.response.ValidateResponse;
import com.banka1.verificationService.model.enums.VerificationStatus;
import com.banka1.verificationService.service.VerificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VerificationController.class)
@Import({GlobalExceptionHandler.class, TestSecurityConfig.class})
@ActiveProfiles("test")
class VerificationControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VerificationService verificationService;

    @Test
    void generateUsesVerificationGenerateRoute() throws Exception {
        when(verificationService.generate(any(GenerateRequest.class))).thenReturn(new GenerateResponse(15L));

        mockMvc.perform(post("/verification/generate")
                        .with(jwt().jwt(jwt -> jwt.claim("id", 12L)))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "clientId": 12,
                                  "operationType": "PAYMENT",
                                  "relatedEntityId": "payment-1",
                                  "clientEmail": "client@example.com"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(15));
    }

    @Test
    void validateUsesVerificationValidateRoute() throws Exception {
        when(verificationService.validate(any(ValidateRequest.class)))
                .thenReturn(new ValidateResponse(true, VerificationStatus.VERIFIED, 0));

        mockMvc.perform(post("/verification/validate")
                        .with(jwt().jwt(jwt -> jwt.claim("id", 12L)))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": 15,
                                  "code": "123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.status").value("VERIFIED"));
    }

    @Test
    void getStatusReturnsSessionIdAndStatus() throws Exception {
        when(verificationService.getStatus(15L)).thenReturn(new StatusResponse(15L, VerificationStatus.PENDING));

        mockMvc.perform(get("/verification/15/status")
                        .with(jwt().jwt(jwt -> jwt.claim("id", 12L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(15))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void validateRejectsNonSixDigitCode() throws Exception {
        mockMvc.perform(post("/verification/validate")
                        .with(jwt().jwt(jwt -> jwt.claim("id", 12L)))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": 15,
                                  "code": "12345"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ERR_VALIDATION"))
                .andExpect(jsonPath("$.validationErrors.code").exists());
    }

    @Test
    void generateRejectsMissingFields() throws Exception {
        mockMvc.perform(post("/verification/generate")
                        .with(jwt().jwt(jwt -> jwt.claim("id", 12L)))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "clientId": 12,
                                  "operationType": null,
                                  "relatedEntityId": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ERR_VALIDATION"))
                .andExpect(jsonPath("$.validationErrors.operationType").exists())
                .andExpect(jsonPath("$.validationErrors.relatedEntityId").exists());
    }
}
