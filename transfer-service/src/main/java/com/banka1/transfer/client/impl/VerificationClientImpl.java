package com.banka1.transfer.client.impl;

import com.banka1.transfer.client.VerificationClient;
import com.banka1.transfer.dto.client.VerificationResponseDto;
import com.banka1.transfer.dto.client.VerificationValidateRequestDto;
import com.banka1.transfer.exception.BusinessException;
import com.banka1.transfer.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Implementacija klijenta za verifikaciju putem POST zahteva.
 */
@Component
@Profile("!local")
@RequiredArgsConstructor
public class VerificationClientImpl implements VerificationClient {

    private final RestClient verificationRestClient;

    @Override
    public VerificationResponseDto validateCode(Long sessionId, String code) {
        try {
            return verificationRestClient.post()
                    .uri("/validate")
                    .body(new VerificationValidateRequestDto(sessionId, code))
                    .retrieve()
                    .body(VerificationResponseDto.class);
        } catch (HttpClientErrorException e) {
            throw new BusinessException(ErrorCode.INVALID_VERIFICATION, e.getResponseBodyAsString());
        }
    }
}
