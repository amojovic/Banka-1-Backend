package com.banka1.card_service.rest_client;

import com.banka1.card_service.domain.enums.AccountOwnershipType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Internal account-service adapter used to resolve card/account context
 * for notification routing and request validation.
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final RestClient accountServiceClient;

    /**
     * Loads the owner and ownership type for the linked account.
     *
     * <p>Account-service exposes this data via the internal
     * {@code /internal/accounts/{accountNumber}/details} route.
     *
     * @param accountNumber linked account number
     * @return account card context
     */
    public AccountNotificationContextDto getAccountContext(String accountNumber) {
        InternalAccountDetailsDto details = accountServiceClient.get()
                .uri("/internal/accounts/{accountNumber}/details", accountNumber)
                .retrieve()
                .body(InternalAccountDetailsDto.class);
        return new AccountNotificationContextDto(
                details == null || details.accountType() == null
                        ? null
                        : AccountOwnershipType.valueOf(details.accountType()),
                details == null ? null : details.ownerId()
        );
    }

    /**
     * Backward-compatible alias used by lifecycle notification flows.
     *
     * @param accountNumber linked account number
     * @return account card context
     */
    public AccountNotificationContextDto getNotificationContext(String accountNumber) {
        return getAccountContext(accountNumber);
    }
}
