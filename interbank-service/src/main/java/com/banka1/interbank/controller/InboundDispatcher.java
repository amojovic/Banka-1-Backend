package com.banka1.interbank.controller;

import com.banka1.interbank.protocol.dto.CommitTransactionBody;
import com.banka1.interbank.protocol.dto.InterbankMessagePayload;
import com.banka1.interbank.protocol.dto.InterbankTransactionPayload;
import com.banka1.interbank.protocol.dto.RollbackTransactionBody;
import com.banka1.interbank.protocol.dto.TransactionVote;
import com.banka1.interbank.service.InterbankException;
import com.banka1.interbank.service.InterbankMessageService;
import com.banka1.interbank.service.TransactionExecutorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PR_32 Phase 7: pomocni Spring komponent koji obradjuje jedan INBOUND message tip u jednoj
 * {@code @Transactional} boundary-i.
 *
 * <p><strong>KRITICAN GOTCHA:</strong> Spring {@code @Transactional} na metodama koje se pozivaju
 * iz iste klase (controller {@code @PostMapping} pa interno {@code handleNewTx}) NE prolazi
 * kroz AOP proxy. Da bi idempotency-cache persist + executor-call bilo u jednom DB TX-u, ovaj
 * dispatcher je izolovan kao zaseban bean — controller poziva njegovu public metodu kroz
 * Spring-managed reference, pa se AOP wrapping primeni normalno.
 *
 * <p>Sve tri public metode su {@code @Transactional} — ako persist idempotency-cache redak
 * fail-uje (npr. neko drugi je upravo insert-ovao isti key, unique-constraint violation),
 * cela transakcija (ukljucujuci side-efekte executor-a poput rezervacija refs) se rollback-uje.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InboundDispatcher {

    private final TransactionExecutorService executor;
    private final InterbankMessageService messageService;
    private final ObjectMapper mapper;

    /**
     * Obradi NEW_TX poruku: deserialize message-> {@link InterbankTransactionPayload}, pozovi
     * {@code executor.prepareLocal(...)}, persist idempotency cache sa vote-om kao response
     * body, vrati 200 OK + vote.
     *
     * <p>Greske:
     * <ul>
     *   <li>{@link InterbankException} → 500 + {"error": msg} (rezervacija/validacija fail
     *       koja nije pokrivena NoVoteReason).</li>
     *   <li>Jackson treeToValue parse error → 400 + {"error": msg} (payload se ne moze deserialize-ovati).</li>
     * </ul>
     */
    @Transactional
    public ResponseEntity<?> handleNewTx(InterbankMessagePayload msg) {
        try {
            var tx = mapper.treeToValue(msg.message(), InterbankTransactionPayload.class);
            TransactionVote vote = executor.prepareLocal(tx);
            String body = mapper.writeValueAsString(vote);
            messageService.persistInbound(msg, 200, body);
            return ResponseEntity.ok(vote);
        } catch (InterbankException e) {
            log.error("NEW_TX prepare failed", e);
            return persistAndReturnError(msg, 500, e.getMessage());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("NEW_TX bad payload", e);
            return persistAndReturnError(msg, 400, e.getMessage());
        } catch (IllegalArgumentException e) {
            // Tim 2 bug T1-A: null/empty postings, missing transactionId, ostali
            // tip-level validacioni problemi. 400 + razlog, ne 500.
            log.warn("NEW_TX validation failed: {}", e.getMessage());
            return persistAndReturnError(msg, 400, e.getMessage());
        } catch (Exception e) {
            log.error("NEW_TX unexpected error", e);
            return persistAndReturnError(msg, 500, e.getMessage());
        }
    }

    /**
     * Obradi COMMIT_TX poruku: deserialize → {@link CommitTransactionBody}, pozovi
     * {@code executor.commitLocal(...)}, persist 204 cache, vrati 204 No Content.
     */
    @Transactional
    public ResponseEntity<Void> handleCommitTx(InterbankMessagePayload msg) {
        try {
            var body = mapper.treeToValue(msg.message(), CommitTransactionBody.class);
            executor.commitLocal(body.transactionId());
            messageService.persistInbound(msg, 204, null);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("COMMIT_TX failed", e);
            persistErrorSilent(msg, 500, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Obradi ROLLBACK_TX poruku: deserialize → {@link RollbackTransactionBody}, pozovi
     * {@code executor.rollbackLocal(...)}, persist 204 cache, vrati 204 No Content.
     */
    @Transactional
    public ResponseEntity<Void> handleRollbackTx(InterbankMessagePayload msg) {
        try {
            var body = mapper.treeToValue(msg.message(), RollbackTransactionBody.class);
            executor.rollbackLocal(body.transactionId());
            messageService.persistInbound(msg, 204, null);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("ROLLBACK_TX failed", e);
            persistErrorSilent(msg, 500, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Tim 2 §2.2 IMPORTANT-1: persist error response u idempotency cache i
     * vrati ResponseEntity. Bez ovoga partner retry istog IdempotenceKey-a posle
     * 5xx re-izvrsava {@code prepareLocal} umesto da vrati cached error
     * (rizik dvostruke rezervacije / dvostrukog NO vote-a).
     */
    private ResponseEntity<?> persistAndReturnError(InterbankMessagePayload msg,
                                                    int status,
                                                    String errorMessage) {
        Map<String, String> body = Map.of("error", errorMessage == null ? "" : errorMessage);
        persistErrorSilent(msg, status, body);
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Persist error response cache bez rethrow-a — ako cache save padne, log
     * i nastavi (originalna greska je vec poslata pozivocu, ne smemo da je
     * obscure-ujemo cache failure-om).
     */
    private void persistErrorSilent(InterbankMessagePayload msg, int status, Object body) {
        try {
            String json = body instanceof String s ? s : mapper.writeValueAsString(body);
            messageService.persistInbound(msg, status, json);
        } catch (Exception cacheEx) {
            log.warn("Failed to persist error response for {} (status={}): {}",
                    msg.idempotenceKey(), status, cacheEx.getMessage());
        }
    }
}
