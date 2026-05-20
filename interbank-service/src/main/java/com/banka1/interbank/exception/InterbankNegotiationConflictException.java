package com.banka1.interbank.exception;

/**
 * Tim 2 MINOR-5 (P2.4): outbound 409 Conflict iz partner banke (turn-violation
 * na PUT counter-offer, sklopljeni ugovor na DELETE, etc.). Caller (FE wrapper
 * controller) hvata i propagira 409 dalje umesto generickog 502.
 */
public class InterbankNegotiationConflictException extends RuntimeException {

    public InterbankNegotiationConflictException(String message) {
        super(message);
    }
}
