package app.exception;

import lombok.Getter;

/**
 * Izuzetak koji predstavlja poslovnu gresku u aplikaciji.
 * Nosi {@link ErrorCode} koji sadrzi HTTP status, masinsko-citljivi kod i naslov greske.
 */
@Getter
public class BusinessException extends RuntimeException {

    /** Kod greske sa HTTP statusom i opisom. */
    private final ErrorCode errorCode;

    /** Opciona dodatna poruka sa detaljima o gresci. */
    private final String details;

    /**
     * Kreira poslovnu gresku sa zadatim kodom i detaljima.
     *
     * @param errorCode kod greske
     * @param details   detalji greske
     */
    public BusinessException(ErrorCode errorCode, String details) {
        super(errorCode.getTitle() + ": " + details);
        this.errorCode = errorCode;
        this.details = details;
    }
}
