package com.banka1.verificationService.controller;

import com.banka1.verificationService.dto.request.GenerateRequest;
import com.banka1.verificationService.dto.request.ValidateRequest;
import com.banka1.verificationService.dto.response.GenerateResponse;
import com.banka1.verificationService.dto.response.ValidateResponse;
import com.banka1.verificationService.model.enums.VerificationStatus;
import com.banka1.verificationService.service.VerificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST kontroler za rukovanje operacijama sesija verifikacije.
 * Pruža endpoint-e za generisanje, validaciju i proveru verifikacionih kodova.
 */
@RestController
@RequiredArgsConstructor
public class VerificationController {

    /** Servis koji rukuje poslovnom logikom za operacije verifikacije. */
    private final VerificationService verificationService;

    /**
     * Endpoint za generisanje nove sesije verifikacije i slanje koda klijentu.
     *
     * @param request detalji za sesiju verifikacije
     * @return odgovor sa ID-om sesije
     */
    @PostMapping("/generate")
    public ResponseEntity<GenerateResponse> generate(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody GenerateRequest request) {
        return ResponseEntity.ok(verificationService.generate(request));
    }

    /**
     * Endpoint za validaciju verifikacionog koda u odnosu na postojeću sesiju.
     *
     * @param request ID sesije i kod za validaciju
     * @return odgovor koji ukazuje na rezultat validacije i status sesije
     */
    @PostMapping("/validate")
    public ResponseEntity<ValidateResponse> validate(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody ValidateRequest request) {
        return ResponseEntity.ok(verificationService.validate(request));
    }

    /**
     * Endpoint za vraćanje trenutnog statusa sesije verifikacije.
     *
     * @param sessionId ID sesije za proveru
     * @return trenutni status verifikacije
     */
    @GetMapping("/{sessionId}/status")
    public ResponseEntity<VerificationStatus> getStatus(@AuthenticationPrincipal Jwt jwt,@PathVariable Long sessionId) {
        return ResponseEntity.ok(verificationService.getStatus(sessionId));
    }
}
