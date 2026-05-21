package com.banka1.interbank.exception;

/**
 * Tim 2 follow-up audit (N=3+ banaka): authenticated sender bank nije ni
 * buyer ni seller u trazenom pregovoru. Pre ovog check-a, bilo koja partner
 * banka sa validnim X-Api-Key tokenom mogla je obrisati ili prihvatiti tudji
 * pregovor. Mapira se na 403 Forbidden u
 * {@link InterbankGlobalExceptionHandler}.
 *
 * <p>U 2-bank setup-u (Banka 1 + Banka 2) ovo je no-op (jedini autentifikovan
 * sender je upravo partner druge strane), ali odrzava semantiku kad se doda
 * Banka 3+.
 */
public class InterbankSenderNotPartyException extends RuntimeException {

    public InterbankSenderNotPartyException(String message) {
        super(message);
    }
}
