package com.banka1.interbank.exception;

import com.banka1.interbank.service.InterbankException;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * PR_32 Phase 10 Task 10.2: centralni handler za OTC §3 rute (per Tim 2 §6.3
 * response codes).
 *
 * <p>Mapping:
 * <ul>
 *   <li>{@link NegotiationNotFoundException} → 404</li>
 *   <li>{@link TurnViolationException} +
 *       {@link NegotiationClosedException} → <strong>409 Conflict</strong>
 *       (KRITICNO — NE 400 per Tim 2 §6.3 update)</li>
 *   <li>{@link InvalidNegotiationException} + Bean Validation greske +
 *       malformed JSON + IllegalArgumentException → 400 Bad Request</li>
 *   <li>{@link AccessDeniedException} +
 *       {@link AuthorizationDeniedException} → 403 (HOTFIX 36 iz PR_31)</li>
 *   <li>{@link InterbankException} (2PC fail) → 500</li>
 * </ul>
 *
 * <p>NE hvata generic {@code Exception.class} — to ostavlja Spring-ovom
 * default-u (500 ali bez body-ja). Razlog: ne zelimo da slucajno "progutamo"
 * Spring-ove protokolarne ekscepcije (nesto poput HandlerMappingException) i
 * vratimo nasao body koji ce zbuniti partner banku.
 */
@RestControllerAdvice
@Slf4j
public class InterbankGlobalExceptionHandler {

    @ExceptionHandler(NegotiationNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NegotiationNotFoundException e) {
        log.debug("Negotiation not found: {}", e.getMessage());
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }

    /**
     * KRITICNO — Tim 2 §6.3 update: turn violation i closed negotiation MORAJU
     * vracati 409 Conflict, NE 400. Ranija verzija spec-a je rekla 400; Tim 2
     * je posle protokol-mediation sesije izmenio na 409.
     */
    @ExceptionHandler({TurnViolationException.class, NegotiationClosedException.class})
    public ResponseEntity<Map<String, String>> conflict(RuntimeException e) {
        log.debug("Conflict: {}", e.getMessage());
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({
            InvalidNegotiationException.class,
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            ConstraintViolationException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<Map<String, String>> badRequest(Exception e) {
        log.debug("Bad request: {}", e.getMessage());
        String msg = e.getMessage() == null ? "Invalid request" : e.getMessage();
        return ResponseEntity.status(400).body(Map.of("error", msg));
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<Void> forbidden() {
        return ResponseEntity.status(403).build();
    }

    /**
     * Tim 2 audit follow-up: sender bank nije ni buyer ni seller u trazenom
     * pregovoru. 403 Forbidden.
     */
    @ExceptionHandler(InterbankSenderNotPartyException.class)
    public ResponseEntity<Map<String, String>> senderNotParty(InterbankSenderNotPartyException e) {
        log.warn("Sender not party: {}", e.getMessage());
        return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InterbankException.class)
    public ResponseEntity<Map<String, String>> interbankFail(InterbankException e) {
        log.error("Interbank operation failed", e);
        return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
    }

    // ===== Tim 2 MINOR-5 (P2.4) outbound typed exceptions ====================

    /**
     * Outbound poziv je naseo 401 — partner odbacuje nas outbound token.
     * Tretiramo kao 502 Bad Gateway (FE ne treba da pomisli da je nas problem
     * autentifikacioni; problem je sa partnerom).
     */
    @ExceptionHandler(InterbankAuthException.class)
    public ResponseEntity<Map<String, String>> outboundAuthFail(InterbankAuthException e) {
        log.error("Outbound auth failed", e);
        return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
    }

    /** Partner vratio 409 — propagiramo kao 409. */
    @ExceptionHandler(InterbankNegotiationConflictException.class)
    public ResponseEntity<Map<String, String>> outboundConflict(InterbankNegotiationConflictException e) {
        log.warn("Outbound conflict: {}", e.getMessage());
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    }

    /** Partner vratio 404 — propagiramo kao 404. */
    @ExceptionHandler(InterbankNegotiationNotFoundException.class)
    public ResponseEntity<Map<String, String>> outboundNotFound(InterbankNegotiationNotFoundException e) {
        log.warn("Outbound not found: {}", e.getMessage());
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }

    /** Partner vratio neki drugi 4xx/5xx — propagiramo originalni status. */
    @ExceptionHandler(InterbankProtocolException.class)
    public ResponseEntity<Map<String, String>> outboundProtocolFail(InterbankProtocolException e) {
        log.warn("Outbound protocol error (status={}): {}", e.getStatusCode(), e.getMessage());
        int status = e.getStatusCode() >= 500 ? 502 : e.getStatusCode();
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }
}
