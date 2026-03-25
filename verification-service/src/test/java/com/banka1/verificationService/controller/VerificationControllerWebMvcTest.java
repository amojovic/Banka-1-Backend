package com.banka1.verificationService.controller;

import com.banka1.verificationService.advice.GlobalExceptionHandler;
import com.banka1.verificationService.dto.request.GenerateRequest;
import com.banka1.verificationService.dto.request.ValidateRequest;
import com.banka1.verificationService.dto.response.GenerateResponse;
import com.banka1.verificationService.dto.response.ValidateResponse;
import com.banka1.verificationService.model.enums.VerificationStatus;
import com.banka1.verificationService.service.VerificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VerificationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
class VerificationControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VerificationService verificationService;

    @Test
    void generateUsesRootGenerateRoute() throws Exception {
        when(verificationService.generate(any(GenerateRequest.class))).thenReturn(new GenerateResponse(15L));

        mockMvc.perform(post("/generate")
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
    void validateUsesRootValidateRoute() throws Exception {
        when(verificationService.validate(any(ValidateRequest.class)))
                .thenReturn(new ValidateResponse(true, VerificationStatus.VERIFIED, 0));

        mockMvc.perform(post("/validate")
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
    void getStatusUsesRootStatusRoute() throws Exception {
        when(verificationService.getStatus(15L)).thenReturn(VerificationStatus.PENDING);

        mockMvc.perform(get("/15/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("PENDING"));
    }

    @Test
    void validateRejectsNonSixDigitCode() throws Exception {
        mockMvc.perform(post("/validate")
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
        mockMvc.perform(post("/generate")
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
