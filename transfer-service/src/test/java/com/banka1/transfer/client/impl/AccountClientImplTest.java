package com.banka1.transfer.client.impl;

import com.banka1.transfer.dto.client.AccountDto;
import com.banka1.transfer.dto.client.PaymentDto;
import com.banka1.transfer.dto.client.UpdatedBalanceResponseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountClientImplTest {

    private AccountClientImpl accountClient;

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        accountClient = new AccountClientImpl(restClient);
    }

    @Test
    void getAccountDetails_Success() {
        String accNo = "123";
        AccountDto expectedDto = new AccountDto(accNo, 1L, "RSD", BigDecimal.TEN, "ACTIVE", "PERSONAL");

        // Mocking the chain: restClient.get().uri(...).retrieve().body(...)
        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString(), anyString())).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(AccountDto.class)).thenReturn(expectedDto);

        AccountDto actualDto = accountClient.getAccountDetails(accNo);

        assertNotNull(actualDto);
        assertEquals(accNo, actualDto.accountNumber());
        verify(restClient).get();
    }

    @Test
    void executeTransfer_Success() {
        PaymentDto paymentDto = new PaymentDto("1", "2", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, 1L);
        UpdatedBalanceResponseDto expectedRes = new UpdatedBalanceResponseDto(BigDecimal.ZERO, BigDecimal.TEN);

        // Mocking the chain for POST
        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.body(any(PaymentDto.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(UpdatedBalanceResponseDto.class)).thenReturn(expectedRes);

        UpdatedBalanceResponseDto actualRes = accountClient.executeTransfer(paymentDto);

        assertNotNull(actualRes);
        verify(restClient).post();
    }
}