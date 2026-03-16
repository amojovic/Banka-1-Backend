package com.banka1.clientService.advice;

import com.banka1.clientService.exception.BusinessException;
import com.banka1.clientService.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    static class ValidatedRequest {
        @NotBlank(message = "Ime je obavezno")
        private String ime;

        @Email(message = "Nevalidan format email-a")
        @NotBlank(message = "Email je obavezan")
        private String email;

        public String getIme() { return ime; }
        public void setIme(String ime) { this.ime = ime; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }

    @RestController
    static class TestController {

        @GetMapping("/test-dupe")
        public void throwDataIntegrity() {
            throw new DataIntegrityViolationException("dup key");
        }

        @GetMapping("/test-missing")
        public void throwNoSuchElement() {
            throw new NoSuchElementException("Klijent nije pronadjen");
        }

        @GetMapping("/test-unexpected")
        public void throwUnexpected() {
            throw new RuntimeException("unexpected error");
        }

        @GetMapping("/test-business-not-found")
        public void throwBusinessNotFound() {
            throw new BusinessException(ErrorCode.CLIENT_NOT_FOUND, "ID: 99");
        }

        @GetMapping("/test-business-conflict")
        public void throwBusinessConflict() {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS, "email@test.com");
        }

        @PostMapping("/test-validation")
        public String validateBody(@RequestBody @Valid ValidatedRequest req) {
            return req.getIme();
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void dataIntegrityViolationReturns409() throws Exception {
        mockMvc.perform(get("/test-dupe"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Kršenje ograničenja u bazi podataka"));
    }

    @Test
    void noSuchElementReturns404() throws Exception {
        mockMvc.perform(get("/test-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Klijent nije pronadjen"));
    }

    @Test
    void unexpectedExceptionReturns500() throws Exception {
        mockMvc.perform(get("/test-unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Interna greška servera: unexpected error"));
    }

    @Test
    void businessExceptionNotFoundReturns404() throws Exception {
        mockMvc.perform(get("/test-business-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ERR_CLIENT_001"))
                .andExpect(jsonPath("$.title").value("Klijent nije pronađen"))
                .andExpect(jsonPath("$.details").value("ID: 99"));
    }

    @Test
    void businessExceptionConflictReturns409() throws Exception {
        mockMvc.perform(get("/test-business-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ERR_CLIENT_002"))
                .andExpect(jsonPath("$.title").value("Email adresa je već u upotrebi"));
    }

    @Test
    void methodArgumentNotValidReturns400WithErrors() throws Exception {
        mockMvc.perform(post("/test-validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ime\":\"\",\"email\":\"nije-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ime").exists())
                .andExpect(jsonPath("$.errors.email").exists());
    }
}
