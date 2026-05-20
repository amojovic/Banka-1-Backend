package com.banka1.tradingservice.otc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "otc_contract_expiry_reminders",
        uniqueConstraints = @UniqueConstraint(name = "uk_otc_contract_expiry_reminder",
                columnNames = {"contract_id", "reminder_days"}),
        indexes = @Index(name = "idx_otc_expiry_reminders_sent_at", columnList = "sent_at"))
@Getter
@Setter
public class OtcContractExpiryReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "reminder_days", nullable = false)
    private Integer reminderDays;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt = LocalDateTime.now();
}
