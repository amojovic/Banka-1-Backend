package com.banka1.verificationService.model.entity;

import com.banka1.verificationService.model.enums.OperationType;
import com.banka1.verificationService.model.enums.VerificationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * JPA entitet koji predstavlja sesiju verifikacije u bazi podataka.
 * Cuva hash verifikacionog koda, metapodatke sesije i status za 2FA operacije.
 */
@Entity
@Table(name = "verification_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VerificationSession {

    /** Jedinstveni identifikator sesije verifikacije, auto-generisan od strane baze podataka. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ID klijenta koji zahteva verifikaciju. */
    @Column(nullable = false)
    private Long clientId;

    /** Hash verifikacionog koda; nikada se ne cuva obican tekst. */
    @Column(nullable = false)
    private String code;

    /** Tip operacije koja se verifikuje (npr. PAYMENT, TRANSFER). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OperationType operationType;

    /** ID povezanog entiteta (npr. ID transakcije); obavezan za jednoznacnu sesiju. */
    @Column(nullable = false)
    private String relatedEntityId;

    /** Vreme kada je sesija kreirana. */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** Vreme kada sesija istice, tipicno 5 minuta nakon kreiranja. */
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /** Broj neuspelih pokusaja validacije; sesija se otkazuje nakon 3. */
    @Column(nullable = false)
    private int attemptCount;

    /** Trenutni status sesije verifikacije. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VerificationStatus status;
}
