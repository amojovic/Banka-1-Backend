package com.banka1.order.client;

import com.banka1.order.dto.AccountDetailsDto;
import com.banka1.order.dto.response.UpdatedBalanceResponseDto;
import com.banka1.order.dto.client.PaymentDto;

/**
 * Client interface for communicating with the account-service.
 * Used to retrieve account details needed during order processing.
 */
public interface AccountClient {

    /**
     * Fetches details of a bank account by its account number.
     *
     * @param accountNumber the account number to look up
     * @return account details including balance and currency
     */
    AccountDetailsDto getAccountDetails(String accountNumber);

    /**
     * Fetches details of a bank account by its internal ID.
     * @param id internal account id
     * @return account details
     */
    AccountDetailsDto getAccountDetailsById(Long id);

    /**
     * Executes an internal transaction between two accounts using account-service.
     * @param paymentDto payment details
     * @return updated balances response
     */
    UpdatedBalanceResponseDto transaction(PaymentDto paymentDto);

    /**
     * Returns the government's (company) dinar bank account (RSD).
     * Expected to call account-service endpoint: GET /employee/accounts/bank/RSD
     */
    AccountDetailsDto getGovernmentBankAccountRsd();
}
