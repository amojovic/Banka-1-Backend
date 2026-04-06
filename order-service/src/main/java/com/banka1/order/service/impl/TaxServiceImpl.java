package com.banka1.order.service.impl;

import com.banka1.order.client.AccountClient;
import com.banka1.order.client.ExchangeClient;
import com.banka1.order.dto.ExchangeRateDto;
import com.banka1.order.dto.AccountDetailsDto;
import com.banka1.order.dto.client.PaymentDto;
import com.banka1.order.entity.Order;
import com.banka1.order.entity.Portfolio;
import com.banka1.order.entity.Transaction;
import com.banka1.order.rabbitmq.OrderNotificationProducer;
import com.banka1.order.repository.OrderRepository;
import com.banka1.order.repository.PortfolioRepository;
import com.banka1.order.repository.TransactionRepository;
import com.banka1.order.service.TaxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaxServiceImpl implements TaxService {

    private final TransactionRepository transactionRepository;
    private final OrderRepository orderRepository;
    private final PortfolioRepository portfolioRepository;
    private final AccountClient accountClient;
    private final ExchangeClient exchangeClient;
    private final OrderNotificationProducer notificationProducer;

    private static final BigDecimal TAX_RATE = new BigDecimal("0.15");

    @Override
    public void collectMonthlyTax() {
        // compute previous calendar month range
        LocalDate now = LocalDate.now(ZoneId.systemDefault());
        LocalDate firstDayOfThisMonth = now.withDayOfMonth(1);
        LocalDate firstDayOfPrevMonth = firstDayOfThisMonth.minusMonths(1);

        LocalDateTime start = firstDayOfPrevMonth.atStartOfDay();
        LocalDateTime end = firstDayOfThisMonth.atStartOfDay();

        log.info("Collecting taxes for period {} - {}", start, end);

        List<Transaction> txs = transactionRepository.findByTimestampBetween(start, end);

        for (Transaction tx : txs) {
            try {
                // fetch parent order
                Order order = orderRepository.findById(tx.getOrderId()).orElse(null);
                if (order == null) continue;

                // only SELL orders generate capital gains tax
                if (order.getDirection() == null || !order.getDirection().name().equals("SELL")) continue;

                // fetch portfolio to get average purchase price
                Portfolio portfolio = portfolioRepository.findByUserIdAndListingId(order.getUserId(), order.getListingId()).orElse(null);
                if (portfolio == null) {
                    log.warn("No portfolio found for user {} listing {} – skipping tax for transaction {}", order.getUserId(), order.getListingId(), tx.getId());
                    continue;
                }

                BigDecimal avgPurchase = portfolio.getAveragePurchasePrice();
                BigDecimal sellPrice = tx.getPricePerUnit();

                BigDecimal gainPerShare = sellPrice.subtract(avgPurchase);
                if (gainPerShare.compareTo(BigDecimal.ZERO) <= 0) continue; // no taxable gain

                BigDecimal taxableGain = gainPerShare.multiply(BigDecimal.valueOf(tx.getQuantity()));
                BigDecimal tax = taxableGain.multiply(TAX_RATE);

                // get seller account details (by internal id stored on order)
                AccountDetailsDto sellerAccount = accountClient.getAccountDetailsById(order.getAccountId());

                // convert tax to RSD using exchange-service (no commission)
                String fromCurrency = sellerAccount.getCurrency();
                ExchangeRateDto conversion = exchangeClient.calculate(fromCurrency, "RSD", tax);
                BigDecimal taxInRsd = conversion.getConvertedAmount();

                // get government bank RSD account
                AccountDetailsDto govtAccount = accountClient.getGovernmentBankAccountRsd();

                // prepare payment dto: from seller account number to government RSD account
                PaymentDto payment = new PaymentDto(
                        sellerAccount.getAccountNumber(),
                        govtAccount.getAccountNumber(),
                        tax,
                        taxInRsd,
                        BigDecimal.ZERO,
                        order.getUserId()
                );

                // execute transaction via account-service to deduct tax from seller and credit government account
                var updatedBalances = accountClient.transaction(payment);

                // notify via rabbit with additional balance info
                var payload = new HashMap<String, Object>();
                payload.put("userId", order.getUserId());
                payload.put("listingId", order.getListingId());
                payload.put("transactionId", tx.getId());
                payload.put("tax", tax);
                payload.put("taxRsd", taxInRsd);
                payload.put("senderBalance", updatedBalances != null ? updatedBalances.getSenderBalance() : null);
                payload.put("receiverBalance", updatedBalances != null ? updatedBalances.getReceiverBalance() : null);

                notificationProducer.sendTaxCollected(payload);

                log.info("Collected tax {} (RSD {}) for transaction {}. Updated balances: sender={}, receiver={}", tax, taxInRsd, tx.getId(),
                        updatedBalances != null ? updatedBalances.getSenderBalance() : "N/A",
                        updatedBalances != null ? updatedBalances.getReceiverBalance() : "N/A");

            } catch (Exception e) {
                log.error("Failed to collect tax for transaction {}", tx.getId(), e);
            }
        }
    }
}

