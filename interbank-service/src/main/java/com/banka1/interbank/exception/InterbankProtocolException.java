package com.banka1.interbank.exception;

/**
 * Tim 2 MINOR-5 (P2.4): outbound 400 Bad Request iz partner banke (payload je
 * sintacki neispravan, ne mozemo pretvoriti u retry). Default fallback za
 * neocekivane 4xx/5xx status code-ove gde nemamo specijalnu klasu.
 */
public class InterbankProtocolException extends RuntimeException {

    private final int statusCode;

    public InterbankProtocolException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
