package com.banka1.interbank.exception;

/**
 * Tim 2 IMPORTANT-6: bacaa se kad partner banka odgovori 401 na nas outbound
 * poziv. Differencira od ostalih outbound failures (5xx, timeout) tako da
 * caller moze odluciti da li retry-uje (5xx — da) ili eskalira ka operatoru
 * (401 — outbound token misconfig, retry je beskoristan).
 */
public class InterbankAuthException extends RuntimeException {

    public InterbankAuthException(String message) {
        super(message);
    }

    public InterbankAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
