package com.banka1.clientService.advice;

import com.banka1.clientService.exception.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Centralizovani handler za izuzetke koji prevodi greske u strukturirane HTTP odgovore.
 * Eliminise potrebu za try/catch blokom u svakom kontroleru.
 */
@RestControllerAdvice
@Component("clientServiceGlobalExceptionHandler")
public class GlobalExceptionHandler {

    /**
     * Hendluje poslovne izuzetke i vraca odgovarajuci HTTP status iz {@link com.banka1.clientService.exception.ErrorCode}.
     *
     * @param ex poslovni izuzetak
     * @return odgovor sa kodom, naslovom i detaljima greske
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(BusinessException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", ex.getErrorCode().getCode());
        body.put("title", ex.getErrorCode().getTitle());
        body.put("details", ex.getDetails());
        return ResponseEntity.status(ex.getErrorCode().getHttpStatus()).body(body);
    }

    /**
     * Hendluje krsenje unique ogranicenja i slicne DB greske sa statusom 409 Conflict.
     *
     * @param ex izuzetak integriteta podataka
     * @return odgovor sa porukom greske
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrity(DataIntegrityViolationException ex) {
        Map<String, String> body = Map.of("error", "Kršenje ograničenja u bazi podataka");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Hendluje greske pretrage elemenata koji ne postoje sa statusom 404 Not Found.
     *
     * @param ex izuzetak nepostojeceg elementa
     * @return odgovor sa porukom greske
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException ex) {
        Map<String, String> body = Map.of("error", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * Hendluje greske validacije Bean Validation sa statusom 400 Bad Request.
     * Vraca mapu polja i njihovih gresaka.
     *
     * @param ex izuzetak validacije
     * @return odgovor sa mapom gresaka po polju
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(err -> fieldErrors.put(err.getField(), err.getDefaultMessage()));
        Map<String, Object> body = new HashMap<>();
        body.put("errors", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Hendluje neocekivane greske sa statusom 500 Internal Server Error.
     *
     * @param ex izuzetak
     * @return odgovor sa generaljnom porukom greske
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneral(Exception ex) {
        Map<String, String> body = Map.of("error", "Interna greška servera: " + ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
