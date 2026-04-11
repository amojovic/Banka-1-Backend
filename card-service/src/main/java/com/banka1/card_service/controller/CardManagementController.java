package com.banka1.card_service.controller;

import com.banka1.card_service.dto.card_management.request.UpdateCardLimitDTO;
import com.banka1.card_service.dto.card_management.response.CardDetailDTO;
import com.banka1.card_service.dto.card_management.response.CardInternalSummaryDTO;
import com.banka1.card_service.dto.card_management.response.CardSummaryDTO;
import com.banka1.card_service.service.CardLifecycleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Card lifecycle management API exposed by the service behind the API gateway.
 *
 * <p>The gateway owns the external {@code /api/cards/...} prefix. Internally the service
 * only exposes the route suffixes so the same external path can be shared by clients
 * and employees without duplicating controller mappings.
 *
 * <p>Client and employee access rules are enforced at method level:
 * clients may list, inspect, block, and update limits only for their own cards,
 * while employees may inspect any card, list cards by account, and perform
 * administrative lifecycle transitions such as unblock and deactivate.
 */
@RestController
@RequiredArgsConstructor
public class CardManagementController {

    private final CardLifecycleService cardLifecycleService;
    private final CardControllerSupport controllerSupport;

    // ############################################################
    // CLIENT ENDPOINTS
    // Includes client-owned routes and shared routes clients can use
    // ############################################################

    /**
     * Returns all cards owned by the authenticated client.
     * Card numbers in the response are masked.
     * The {@code clientId} in the path must match the authenticated client's ID.
     * Employee can see anybody's cards; Clients can see only their own.
     *
     * @param jwt JWT of the authenticated client
     * @param clientId client ID path variable
     * @return list of masked card summaries
     */
    @GetMapping("/client/{clientId}")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'BASIC')")
    public ResponseEntity<List<CardSummaryDTO>> getCardsForClient(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable Long clientId
    ) {
        if (controllerSupport.isClient(authentication)) {
            controllerSupport.verifyOwnership(jwt, clientId);
        }
        return ResponseEntity.ok(cardLifecycleService.getCardsForClient(clientId));
    }

    /**
     * Returns full details for a single card identified by card number.
     * Clients may access only their own cards, while employees may access any card.
     *
     * @param jwt JWT of the authenticated caller
     * @param authentication current Spring Security authentication
     * @param cardNumber card number to look up
     * @return full card details
     */
    @GetMapping("/{cardNumber}")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'BASIC')")
    public ResponseEntity<CardDetailDTO> getCardDetails(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable String cardNumber
    ) {
        verifyOwnershipIfClient(authentication, jwt, cardNumber);
        return ResponseEntity.ok(cardLifecycleService.getCardByCardNumber(cardNumber));
    }

    /**
     * Blocks the card identified by card number.
     * Clients may block only their own cards, while employees may block any card.
     * Allowed transition: ACTIVE {@code ->} BLOCKED.
     *
     * @param jwt JWT of the authenticated caller
     * @param authentication current Spring Security authentication
     * @param cardNumber card number to block
     * @return 200 OK on success
     */
    @PutMapping("/{cardNumber}/block")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'BASIC')")
    public ResponseEntity<Void> blockCard(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable String cardNumber
    ) {
        verifyOwnershipIfClient(authentication, jwt, cardNumber);
        cardLifecycleService.blockCard(cardNumber);
        return ResponseEntity.ok().build();
    }

    /**
     * Updates the spending limit on an existing card.
     * Clients may update only their own cards, while employees may update any card.
     *
     * @param jwt JWT of the authenticated caller
     * @param authentication current Spring Security authentication
     * @param cardNumber card number to update
     * @param body request body containing the new limit
     * @return 200 OK on success
     */
    @PutMapping("/{cardNumber}/limit")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'BASIC')")
    public ResponseEntity<Void> updateCardLimit(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable String cardNumber,
            @RequestBody @Valid UpdateCardLimitDTO body
    ) {
        verifyOwnershipIfClient(authentication, jwt, cardNumber);
        cardLifecycleService.updateCardLimit(cardNumber, body.getCardLimit());
        return ResponseEntity.ok().build();
    }

    // ############################################################
    // EMPLOYEE ENDPOINTS
    // Administrative routes available only to employee roles
    // ############################################################

    /**
     * Returns all cards associated with the given bank account number.
     * Card numbers in the response are masked.
     * This endpoint is available only to employees.
     *
     * @param accountNumber bank account number
     * @return list of masked card summaries
     */
    @GetMapping("/account/{accountNumber}")
    @PreAuthorize("hasRole('BASIC')")
    public ResponseEntity<List<CardSummaryDTO>> getCardsByAccount(@PathVariable String accountNumber) {
        return ResponseEntity.ok(cardLifecycleService.getCardsByAccountNumber(accountNumber));
    }

    // ############################################################
    // INTERNAL SERVICE ENDPOINTS
    // Restricted to trusted service-to-service callers (SERVICE role)
    // ############################################################

    /**
     * Returns all cards linked to the given account number in the richer internal shape
     * consumed by account-service when it composes account detail responses.
     * Only trusted services authenticated with the {@code SERVICE} role may call this endpoint.
     * Card numbers in the response are masked.
     *
     * @param accountNumber bank account number
     * @return list of internal card summaries
     */
    @GetMapping("/internal/account/{accountNumber}")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<List<CardInternalSummaryDTO>> getCardsByAccountInternal(@PathVariable String accountNumber) {
        return ResponseEntity.ok(cardLifecycleService.getInternalCardsByAccountNumber(accountNumber));
    }

    /**
     * Unblocks a blocked card.
     * Only employees may call this endpoint.
     * Allowed transition: BLOCKED {@code ->} ACTIVE.
     *
     * @param cardNumber card number to unblock
     * @return 200 OK on success
     */
    @PutMapping("/{cardNumber}/unblock")
    @PreAuthorize("hasRole('BASIC')")
    public ResponseEntity<Void> unblockCard(@PathVariable String cardNumber) {
        cardLifecycleService.unblockCard(cardNumber);
        return ResponseEntity.ok().build();
    }

    /**
     * Permanently deactivates a card.
     * Only employees may call this endpoint.
     * Allowed transitions: ACTIVE {@code ->} DEACTIVATED or BLOCKED {@code ->} DEACTIVATED.
     * Deactivation is irreversible.
     *
     * @param cardNumber card number to deactivate
     * @return 200 OK on success
     */
    @PutMapping("/{cardNumber}/deactivate")
    @PreAuthorize("hasRole('BASIC')")
    public ResponseEntity<Void> deactivateCard(@PathVariable String cardNumber) {
        cardLifecycleService.deactivateCard(cardNumber);
        return ResponseEntity.ok().build();
    }

    /**
     * Applies ownership validation only for client callers.
     * Employee callers are allowed to operate on any card and therefore skip this check.
     *
     * @param authentication current Spring Security authentication
     * @param jwt JWT of the authenticated caller
     * @param cardNumber card number whose owner should be checked
     */
    private void verifyOwnershipIfClient(Authentication authentication, Jwt jwt, String cardNumber) {
        if (controllerSupport.isClient(authentication)) {
            controllerSupport.verifyOwnership(jwt, cardLifecycleService.getClientIdByCardNumber(cardNumber));
        }
    }
}
