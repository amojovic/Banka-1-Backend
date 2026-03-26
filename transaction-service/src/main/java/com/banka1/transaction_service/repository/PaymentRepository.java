package com.banka1.transaction_service.repository;

import com.banka1.transaction_service.domain.Payment;
import com.banka1.transaction_service.domain.enums.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Repository
public interface PaymentRepository extends JpaRepository<Payment,Long> {

    @Modifying
    @Query("""
    UPDATE Payment p
    SET p.status = :newStatus
    WHERE p.status = :oldStatus
    AND p.createdAt < :threshold
""")
    int markStuckPayments(
            TransactionStatus oldStatus,
            TransactionStatus newStatus,
            LocalDateTime threshold
    );


    @Query("""
    SELECT p
    FROM Payment p
    WHERE p.fromAccountNumber = :accountNumber
       OR p.toAccountNumber = :accountNumber
    ORDER BY p.createdAt DESC
""")
    Page<Payment> findByAccountNumber(
            @Param("accountNumber") String accountNumber,
            Pageable pageable
    );


    @Query("""
    SELECT p
    FROM Payment p
    WHERE (:accountNumber IS NULL
            OR p.fromAccountNumber = :accountNumber
            OR p.toAccountNumber = :accountNumber)
      AND (:status IS NULL OR p.status = :status)
      AND (:dateFrom IS NULL OR p.createdAt >= :dateFrom)
      AND (:dateTo IS NULL OR p.createdAt <= :dateTo)
      AND (:initialAmountMin IS NULL OR p.initialAmount >= :initialAmountMin)
      AND (:initialAmountMax IS NULL OR p.initialAmount <= :initialAmountMax)
      AND (:finalAmountMin IS NULL OR p.finalAmount >= :finalAmountMin)
      AND (:finalAmountMax IS NULL OR p.finalAmount <= :finalAmountMax)
    ORDER BY p.createdAt DESC
""")
    Page<Payment> searchPayments(
            @Param("accountNumber") String accountNumber,
            @Param("status") TransactionStatus status,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo,
            @Param("initialAmountMin") BigDecimal initialAmountMin,
            @Param("initialAmountMax") BigDecimal initialAmountMax,
            @Param("finalAmountMin") BigDecimal finalAmountMin,
            @Param("finalAmountMax") BigDecimal finalAmountMax,
            Pageable pageable
    );


}
