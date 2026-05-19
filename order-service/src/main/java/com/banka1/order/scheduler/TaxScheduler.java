package com.banka1.order.scheduler;

import com.banka1.order.service.TaxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler that runs monthly tax collection.
 *
 * Automatically triggers tax calculation and collection for all capital gains
 * from the previous calendar month.
 *
 * Scheduled Operations:
 * <ul>
 *   <li>Collect capital gains tax for previous month (00:00 on the 1st of each month)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaxScheduler {

    private final TaxService taxService;

    /**
     * Runs at 00:00 on the first day of every month and collects taxes for the previous month.
     * Cron: "0 0 0 1 * *" (midnight on the first of each month)
     *
     * Process:
     * <ol>
     *   <li>Identifies all SELL transactions from the previous calendar month</li>
     *   <li>Calculates capital gains (profit from each sale)</li>
     *   <li>Applies 15% tax rate</li>
     *   <li>Converts to RSD if transaction was in foreign currency</li>
     *   <li>Creates TaxCharge records</li>
     *   <li>Transfers tax amounts to state account via account-service</li>
     *   <li>Publishes tax.collected notifications via RabbitMQ</li>
     * </ol>
     */
    @Scheduled(cron = "0 0 0 1 * *")
    public void runMonthlyTaxCollection() {
        log.info("Starting monthly tax collection job");
        // WP-12: collectMonthlyTaxScheduled dodatno publikuje TAX_RUN_SCHEDULED
        // audit dogadjaj sa SYSTEM aktorom.
        taxService.collectMonthlyTaxScheduled();
    }
}

