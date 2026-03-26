package com.banka1.transfer.client.mock;

import com.banka1.transfer.client.ClientClient;
import com.banka1.transfer.dto.client.ClientInfoResponseDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
@Slf4j
@Component
@Profile("local") // Radi samo kad je local profil!
public class MockClientClient implements ClientClient {
    @Override
    public ClientInfoResponseDto getClientDetails(Long clientId) {
        log.info("MOCK: Fetching client details for ID {}", clientId);
        return new ClientInfoResponseDto(clientId, "Petar", "Petrović", "petar.mock@primer.com");
    }
}
