package com.banka1.tradingservice.otc.repository;

import com.banka1.tradingservice.otc.domain.OtcContractExpiryReminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OtcContractExpiryReminderRepository extends JpaRepository<OtcContractExpiryReminder, Long> {

    boolean existsByContractIdAndReminderDays(Long contractId, Integer reminderDays);
}
