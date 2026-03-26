package com.banka1.transfer.service.impl;


import com.banka1.transfer.client.*;
import com.banka1.transfer.domain.Transfer;
import com.banka1.transfer.dto.client.*;
import com.banka1.transfer.dto.requests.TransferRequestDto;
import com.banka1.transfer.dto.responses.TransferResponseDto;
import com.banka1.transfer.exception.BusinessException;
import com.banka1.transfer.mapper.TransferMapper;
import com.banka1.transfer.rabbitmq.EmailDto;
import com.banka1.transfer.rabbitmq.RabbitClient;
import com.banka1.transfer.repository.TransferRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferServiceImplTest {

    @Mock private TransferRepository transferRepository;
    @Mock private TransferMapper transferMapper;
    @Mock private AccountClient accountClient;
    @Mock private ExchangeClient exchangeClient;
    @Mock private VerificationClient verificationClient;
    @Mock private RabbitClient rabbitClient;
    @Mock private ClientClient clientClient;

    @InjectMocks
    private TransferServiceImpl transferService;

    @BeforeEach
    void setUp() {
        // Omogućava testiranje koda koji koristi TransactionSynchronizationManager
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.initSynchronization();
        }
    }

    @AfterEach
    void tearDown() {
        // Čišćenje nakon testa
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void executeTransfer_Success_SameCurrency() {
        // Arrange
        TransferRequestDto request = new TransferRequestDto();
        request.setFromAccountNumber("111");
        request.setToAccountNumber("222");
        request.setAmount(new BigDecimal("100"));
        request.setVerificationSessionId("session1");
        request.setVerificationCode("123456");

        AccountDto fromAcc = new AccountDto("111", 1L, "RSD", new BigDecimal("1000"), "ACTIVE", "CURRENT");
        AccountDto toAcc = new AccountDto("222", 1L, "RSD", new BigDecimal("500"), "ACTIVE", "CURRENT");
        ClientInfoResponseDto clientInfo = new ClientInfoResponseDto(1L, "Pera", "Peric", "pera@gmail.com");

        when(transferRepository.existsByVerificationSessionId("session1")).thenReturn(false);
        when(verificationClient.validateCode("session1", "123456")).thenReturn(new VerificationResponseDto(true, "SUCCESS", 3));
        when(accountClient.getAccountDetails("111")).thenReturn(fromAcc);
        when(accountClient.getAccountDetails("222")).thenReturn(toAcc);
        when(transferMapper.toEntity(any(), any(), any(), any(), any(), any())).thenReturn(new Transfer());
        when(transferRepository.save(any(Transfer.class))).thenReturn(new Transfer());
        when(clientClient.getClientDetails(1L)).thenReturn(clientInfo);
        when(transferMapper.toDto(any())).thenReturn(new TransferResponseDto());

        // Act
        TransferResponseDto result = transferService.executeTransfer(request);

        // Assert
        assertNotNull(result);
        verify(exchangeClient, never()).calculateExchange(any(), any(), any()); // Nema menjačnice
        verify(accountClient, times(1)).executeTransfer(any(PaymentDto.class));

        // Simulacija završetka transakcije da bi se poslao mejl
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertFalse(synchronizations.isEmpty());
        synchronizations.forEach(TransactionSynchronization::afterCommit);

        verify(rabbitClient, times(1)).sendEmailNotification(any(EmailDto.class));
    }

    @Test
    void executeTransfer_Success_DifferentCurrency() {
        // Arrange
        TransferRequestDto request = new TransferRequestDto();
        request.setFromAccountNumber("111");
        request.setToAccountNumber("222");
        request.setAmount(new BigDecimal("100"));
        request.setVerificationSessionId("session1");
        request.setVerificationCode("123456");

        // Različite valute
        AccountDto fromAcc = new AccountDto("111", 1L, "RSD", new BigDecimal("1000"), "ACTIVE", "CURRENT");
        AccountDto toAcc = new AccountDto("222", 1L, "EUR", new BigDecimal("500"), "ACTIVE", "CURRENT");

        ExchangeResponseDto exchangeResp = new ExchangeResponseDto("RSD", "EUR", new BigDecimal("100"), new BigDecimal("0.85"), new BigDecimal("117.2"), BigDecimal.ZERO);

        when(transferRepository.existsByVerificationSessionId("session1")).thenReturn(false);
        when(verificationClient.validateCode("session1", "123456")).thenReturn(new VerificationResponseDto(true, "SUCCESS", 3));
        when(accountClient.getAccountDetails("111")).thenReturn(fromAcc);
        when(accountClient.getAccountDetails("222")).thenReturn(toAcc);
        when(exchangeClient.calculateExchange("RSD", "EUR", new BigDecimal("100"))).thenReturn(exchangeResp);
        when(transferMapper.toEntity(any(), any(), any(), any(), any(), any())).thenReturn(new Transfer());
        when(transferRepository.save(any(Transfer.class))).thenReturn(new Transfer());
        when(clientClient.getClientDetails(1L)).thenReturn(new ClientInfoResponseDto(1L, "Pera", "P", "p@p.com"));

        // Act
        transferService.executeTransfer(request);

        // Assert
        verify(exchangeClient, times(1)).calculateExchange("RSD", "EUR", new BigDecimal("100"));
    }

    @Test
    void executeTransfer_Fail_Idempotency() {
        TransferRequestDto request = new TransferRequestDto();
        request.setVerificationSessionId("session-processed");

        when(transferRepository.existsByVerificationSessionId("session-processed")).thenReturn(true);

        assertThrows(BusinessException.class, () -> transferService.executeTransfer(request));
    }

    @Test
    void executeTransfer_Fail_SameAccount() {
        TransferRequestDto request = new TransferRequestDto();
        request.setFromAccountNumber("111");
        request.setToAccountNumber("111"); // Isti račun
        request.setVerificationSessionId("session1");

        when(transferRepository.existsByVerificationSessionId("session1")).thenReturn(false);

        assertThrows(BusinessException.class, () -> transferService.executeTransfer(request));
    }

    @Test
    void executeTransfer_Fail_Invalid2FA() {
        TransferRequestDto request = new TransferRequestDto();
        request.setFromAccountNumber("111");
        request.setToAccountNumber("222");
        request.setVerificationSessionId("session1");
        request.setVerificationCode("000");

        when(transferRepository.existsByVerificationSessionId("session1")).thenReturn(false);
        when(verificationClient.validateCode("session1", "000")).thenReturn(new VerificationResponseDto(false, "FAIL", 0));

        assertThrows(BusinessException.class, () -> transferService.executeTransfer(request));
    }

    @Test
    void executeTransfer_Fail_OwnershipMismatch() {
        TransferRequestDto request = new TransferRequestDto();
        request.setFromAccountNumber("111");
        request.setToAccountNumber("222");
        request.setVerificationSessionId("session1");

        // Vlasnik 1 šalje Vlasniku 2
        AccountDto fromAcc = new AccountDto("111", 1L, "RSD", BigDecimal.TEN, "ACTIVE", "CURRENT");
        AccountDto toAcc = new AccountDto("222", 2L, "RSD", BigDecimal.TEN, "ACTIVE", "CURRENT");

        when(transferRepository.existsByVerificationSessionId("session1")).thenReturn(false);
        when(verificationClient.validateCode(any(), any())).thenReturn(new VerificationResponseDto(true, "OK", 3));
        when(accountClient.getAccountDetails("111")).thenReturn(fromAcc);
        when(accountClient.getAccountDetails("222")).thenReturn(toAcc);

        assertThrows(BusinessException.class, () -> transferService.executeTransfer(request));
    }

    @Test
    void getClientTransfers_ReturnsPage() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Transfer> transferPage = new PageImpl<>(List.of(new Transfer()));

        when(transferRepository.findByClientId(1L, pageable)).thenReturn(transferPage);
        when(transferMapper.toDto(any())).thenReturn(new TransferResponseDto());

        Page<TransferResponseDto> result = transferService.getClientTransfers(1L, pageable);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
    }

    @Test
    void getTransferDetails_Success() {
        Transfer transfer = new Transfer();
        transfer.setOrderNumber("TRF-123");

        when(transferRepository.findByOrderNumber("TRF-123")).thenReturn(Optional.of(transfer));
        when(transferMapper.toDto(transfer)).thenReturn(new TransferResponseDto());

        TransferResponseDto result = transferService.getTransferDetails("TRF-123");

        assertNotNull(result);
    }

    @Test
    void getTransferDetails_NotFound() {
        when(transferRepository.findByOrderNumber("TRF-999")).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> transferService.getTransferDetails("TRF-999"));
    }

    @Test
    void getTransfersByAccountNumber_ReturnsPage() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Transfer> transferPage = new PageImpl<>(List.of(new Transfer()));

        when(transferRepository.findByFromAccountNumberOrToAccountNumber("111", "111", pageable)).thenReturn(transferPage);
        when(transferMapper.toDto(any())).thenReturn(new TransferResponseDto());

        Page<TransferResponseDto> result = transferService.getTransfersByAccountNumber("111", pageable);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
    }
}