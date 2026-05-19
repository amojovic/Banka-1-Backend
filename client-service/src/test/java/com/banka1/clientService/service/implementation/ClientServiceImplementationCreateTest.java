package com.banka1.clientService.service.implementation;

import com.banka1.clientService.domain.Klijent;
import com.banka1.clientService.dto.requests.ClientCreateRequestDto;
import com.banka1.clientService.exception.BusinessException;
import com.banka1.clientService.exception.ErrorCode;
import com.banka1.clientService.mappers.ClientMapper;
import com.banka1.clientService.rabbitMQ.RabbitClient;
import com.banka1.clientService.repository.ClientConfirmationTokenRepository;
import com.banka1.clientService.repository.KlijentRepository;
import com.banka1.clientService.service.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Jedinicni testovi za {@link ClientServiceImplementation#createClient} fokusirani na
 * proveru duplikata email adrese pre upisa u bazu.
 */
@ExtendWith(MockitoExtension.class)
class ClientServiceImplementationCreateTest {

    @Mock
    private KlijentRepository klijentRepository;

    @Mock
    private ClientMapper clientMapper;

    @Mock
    private RabbitClient rabbitClient;

    @Mock
    private ClientConfirmationTokenRepository confirmationTokenRepository;

    @Mock
    private TokenService tokenService;

    @InjectMocks
    private ClientServiceImplementation clientService;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private ClientCreateRequestDto sampleDto() {
        ClientCreateRequestDto dto = new ClientCreateRequestDto();
        dto.setEmail("postojeci@banka.com");
        return dto;
    }

    @Test
    void createClient_duplicateEmail_throwsConflictAndDoesNotSave() {
        ClientCreateRequestDto dto = sampleDto();
        when(klijentRepository.existsByEmail("postojeci@banka.com")).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, () -> clientService.createClient(dto));

        assertEquals(ErrorCode.EMAIL_ALREADY_EXISTS, ex.getErrorCode());
        verify(klijentRepository, never()).save(any());
        verify(confirmationTokenRepository, never()).save(any());
    }

    @Test
    void createClient_uniqueEmail_passesPreCheckAndPersists() {
        ClientCreateRequestDto dto = sampleDto();
        Klijent entity = new Klijent();
        entity.setIme("Pera");
        entity.setEmail("postojeci@banka.com");

        when(klijentRepository.existsByEmail("postojeci@banka.com")).thenReturn(false);
        when(clientMapper.toEntity(dto)).thenReturn(entity);
        when(klijentRepository.save(entity)).thenReturn(entity);
        when(tokenService.generateRandomToken()).thenReturn("raw-token");
        when(tokenService.sha256Hex("raw-token")).thenReturn("hashed-token");

        TransactionSynchronizationManager.initSynchronization();
        clientService.createClient(dto);

        verify(klijentRepository).save(entity);
        verify(confirmationTokenRepository).save(any());
    }
}
