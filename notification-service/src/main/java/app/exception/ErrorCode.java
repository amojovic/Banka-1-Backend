package app.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Enum koji centralizuje sve poslovne greske aplikacije.
 * Svaka konstanta nosi HTTP status, masinsko-citljivi kod i kratak naslov.
 */
@Getter
public enum ErrorCode {

    // ── Greske vezane za notifikacije (ERR_NOTIFICATION_xxx) ──────────────────────────

    /** Payload notifikacije je obavezan. */
    NOTIFICATION_PAYLOAD_REQUIRED(HttpStatus.BAD_REQUEST, "ERR_NOTIFICATION_001", "Payload notifikacije je obavezan"),

    /** Tip notifikacije je obavezan. */
    NOTIFICATION_TYPE_REQUIRED(HttpStatus.BAD_REQUEST, "ERR_NOTIFICATION_002", "Tip notifikacije je obavezan"),

    /** Email primalac je obavezan. */
    RECIPIENT_EMAIL_REQUIRED(HttpStatus.BAD_REQUEST, "ERR_NOTIFICATION_003", "Email primalac je obavezan"),

    /** Sadrzaj email-a ne moze da se resolvira. */
    EMAIL_CONTENT_RESOLUTION_FAILED(HttpStatus.BAD_REQUEST, "ERR_NOTIFICATION_004", "Sadržaj email-a ne može da se resolvira"),

    /** Nepoznat routing key. */
    UNSUPPORTED_ROUTING_KEY(HttpStatus.BAD_REQUEST, "ERR_NOTIFICATION_005", "Nepoznat routing key");

    /** HTTP status koji se vraca klijentu kada se baci ova greska. */
    private final HttpStatus httpStatus;

    /** Stabilan masinsko-citljivi identifikator greske. */
    private final String code;

    /** Kratak ljudski citljivi naslov greske. */
    private final String title;

    ErrorCode(HttpStatus httpStatus, String code, String title) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.title = title;
    }
}
