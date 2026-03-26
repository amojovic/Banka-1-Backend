package com.banka1.transfer.client.impl;

import com.banka1.transfer.dto.client.VerificationResponseDto;
import com.banka1.transfer.dto.client.VerificationValidateRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VerificationClientImplTest {

    private VerificationClientImpl verificationClient;

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        verificationClient = new VerificationClientImpl(restClient);
    }

    @Test
    void validateCode_Success() {
        VerificationResponseDto expected = new VerificationResponseDto(true, "SUCCESS", 3);

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.body(any(VerificationValidateRequestDto.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(VerificationResponseDto.class)).thenReturn(expected);

        VerificationResponseDto result = verificationClient.validateCode("session-123", "123456");

        assertTrue(result.valid());
        assertEquals(3, result.remainingAttempts());
        verify(restClient).post();
    }
}