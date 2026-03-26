package com.banka1.verificationService.repository;

import com.banka1.verificationService.model.entity.VerificationSession;
import com.banka1.verificationService.model.enums.OperationType;
import com.banka1.verificationService.model.enums.VerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * JPA interfejs repozitorijuma za upravljanje entitetima VerificationSession.
 * Pruža CRUD operacije i metode upita za sesije verifikacije.
 */
public interface VerificationSessionRepository extends JpaRepository<VerificationSession, Long> {

    /**
     * Pronalazi sve PENDING sesije verifikacije za datog klijenta, tip operacije i povezani entitet.
     *
     * @param clientId ID klijenta
     * @param operationType tip operacije
     * @param relatedEntityId ID povezanog entiteta
     * @param status status sesije (PENDING)
     * @return lista sesija koje odgovaraju kriterijumima
     */
    List<VerificationSession> findByClientIdAndOperationTypeAndRelatedEntityIdAndStatus(
            Long clientId, OperationType operationType, String relatedEntityId, VerificationStatus status);
}
