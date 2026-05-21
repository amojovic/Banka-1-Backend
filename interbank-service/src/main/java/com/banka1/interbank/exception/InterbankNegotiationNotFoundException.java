package com.banka1.interbank.exception;

/**
 * Tim 2 MINOR-5 (P2.4): outbound 404 Not Found iz partner banke (nepoznat
 * negotiation id na PUT/GET/DELETE accept).
 */
public class InterbankNegotiationNotFoundException extends RuntimeException {

    public InterbankNegotiationNotFoundException(String message) {
        super(message);
    }
}
