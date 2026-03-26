package com.banka1.transfer.client.mock;

import com.banka1.transfer.client.VerificationClient;
import com.banka1.transfer.dto.client.VerificationResponseDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("local")
public class MockVerificationClient implements VerificationClient {
    @Override
    public VerificationResponseDto validateCode(Long sessionId, String code) {
        log.info("MOCK: Validating code {} for session {}", code, sessionId);
        // Simuliramo da je svaki kod "123456" ispravan
        boolean isValid = "123456".equals(code);
        return new VerificationResponseDto(isValid, isValid ? "VERIFIED" : "PENDING", 2);
    }
}
