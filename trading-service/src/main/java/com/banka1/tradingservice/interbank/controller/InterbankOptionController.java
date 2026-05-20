package com.banka1.tradingservice.interbank.controller;

import com.banka1.tradingservice.interbank.model.InterbankOptionReservation;
import com.banka1.tradingservice.interbank.repository.InterbankOptionReservationRepository;
import com.banka1.tradingservice.interbank.service.InterbankStockReservationService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Interbank OTC opcione operacije (PR_32 Phase 12/15, Tim 2 §15).
 *
 * <p>Tim 2 IMPORTANT-2 (PR_34): mapping negotiationId->reservationId sada
 * persistovan u {@code interbank_option_reservations} tabelu (bilo
 * ConcurrentHashMap pre PR_34). Trading-service restart vise ne gubi mapping,
 * a {@code reserveOption} je idempotentan — partner retry sa istim
 * negotiationId-em vraca isti reservationId umesto da kreira drugu rezervaciju.
 *
 * <ul>
 *   <li>{@code POST /internal/interbank/options/{negotiationId}/reserve} —
 *       rezervise akcije; ako vec postoji RESERVED red, vraca 204 bez novog
 *       insert-a (idempotentno).</li>
 *   <li>{@code POST /internal/interbank/options/{negotiationId}/exercise} —
 *       commit-uje rezervaciju, flip status u EXERCISED. Idempotent.</li>
 *   <li>{@code DELETE /internal/interbank/options/{negotiationId}/release} —
 *       oslobadja rezervaciju, flip status u RELEASED. Idempotent.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/internal/interbank/options")
@PreAuthorize("hasRole('SERVICE')")
@RequiredArgsConstructor
public class InterbankOptionController {

    private final InterbankStockReservationService reservationService;
    private final InterbankOptionReservationRepository reservationRepo;

    public record ReserveOptionReq(
            @NotBlank String sellerForeignId,
            @NotBlank String ticker,
            @Positive int quantity
    ) {}

    @PostMapping("/{negotiationId}/reserve")
    @Transactional
    public ResponseEntity<Void> reserveOption(
            @PathVariable String negotiationId,
            @RequestBody ReserveOptionReq req) {
        // Tim 2 IMPORTANT-2 idempotency: ako vec postoji rezervacija za ovaj
        // negotiationId, vrati 204 bez novog insert-a. Originalna stock
        // rezervacija ostaje neporemecena (ne overrride-uje se).
        var existing = reservationRepo.findById(negotiationId);
        if (existing.isPresent()) {
            log.info("Interbank reserveOption idempotent: negotiation={} status={} resId={}",
                    negotiationId, existing.get().getStatus(), existing.get().getReservationId());
            return ResponseEntity.noContent().build();
        }

        Long sellerUserId = parseUserId(req.sellerForeignId());
        // Koristi negotiationId kao transactionIdLocal za rezervaciju, routing 0
        // (intra-protocol option lifecycle koraci nisu deo TX 2PC routing protokola).
        UUID reservationId = reservationService.reserveStock(
                sellerUserId,
                req.ticker(),
                req.quantity(),
                0,
                negotiationId);
        reservationRepo.save(InterbankOptionReservation.builder()
                .negotiationId(negotiationId)
                .reservationId(reservationId.toString())
                .status(InterbankOptionReservation.STATUS_RESERVED)
                .sellerUserId(sellerUserId)
                .ticker(req.ticker())
                .quantity(req.quantity())
                .build());
        log.info("Interbank reserveOption: negotiation={} seller={} ticker={} qty={} resId={}",
                negotiationId, sellerUserId, req.ticker(), req.quantity(), reservationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{negotiationId}/exercise")
    @Transactional
    public ResponseEntity<Void> exerciseOption(@PathVariable String negotiationId) {
        var resOpt = reservationRepo.findById(negotiationId);
        if (resOpt.isEmpty()) {
            log.warn("Interbank exerciseOption: negotiation {} unknown — no-op", negotiationId);
            return ResponseEntity.noContent().build();
        }
        InterbankOptionReservation res = resOpt.get();
        if (InterbankOptionReservation.STATUS_EXERCISED.equals(res.getStatus())) {
            log.info("Interbank exerciseOption idempotent: negotiation {} vec EXERCISED",
                    negotiationId);
            return ResponseEntity.noContent().build();
        }
        if (InterbankOptionReservation.STATUS_RELEASED.equals(res.getStatus())) {
            log.warn("Interbank exerciseOption: negotiation {} u RELEASED stanju — no-op",
                    negotiationId);
            return ResponseEntity.noContent().build();
        }
        reservationService.commitStock(UUID.fromString(res.getReservationId()));
        res.setStatus(InterbankOptionReservation.STATUS_EXERCISED);
        reservationRepo.save(res);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{negotiationId}/release")
    @Transactional
    public ResponseEntity<Void> releaseOption(@PathVariable String negotiationId) {
        var resOpt = reservationRepo.findById(negotiationId);
        if (resOpt.isEmpty()) {
            log.warn("Interbank releaseOption: negotiation {} unknown — no-op", negotiationId);
            return ResponseEntity.noContent().build();
        }
        InterbankOptionReservation res = resOpt.get();
        if (InterbankOptionReservation.STATUS_RELEASED.equals(res.getStatus())) {
            log.info("Interbank releaseOption idempotent: negotiation {} vec RELEASED",
                    negotiationId);
            return ResponseEntity.noContent().build();
        }
        if (InterbankOptionReservation.STATUS_EXERCISED.equals(res.getStatus())) {
            log.warn("Interbank releaseOption: negotiation {} vec EXERCISED — no-op",
                    negotiationId);
            return ResponseEntity.noContent().build();
        }
        reservationService.releaseStock(UUID.fromString(res.getReservationId()));
        res.setStatus(InterbankOptionReservation.STATUS_RELEASED);
        reservationRepo.save(res);
        return ResponseEntity.noContent().build();
    }

    private Long parseUserId(String foreignId) {
        // Per Tim 2 §3.2 spec, foreign-bank-id koristi prefiks "C-" za klijente i
        // "E-" za zaposlene (analogno za njihovu stranu). Trading-service interni
        // userId je goli Long, pa strpamo prefiks pre parsiranja.
        if (foreignId == null) {
            throw new IllegalArgumentException("sellerForeignId must not be null");
        }
        String numericPart = foreignId;
        if (foreignId.startsWith("C-") || foreignId.startsWith("E-")) {
            numericPart = foreignId.substring(2);
        }
        try {
            return Long.parseLong(numericPart);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid sellerForeignId, expected numeric or 'C-N'/'E-N' format: " + foreignId);
        }
    }
}
