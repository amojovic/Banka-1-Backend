package com.banka1.order.service.impl;

import com.banka1.order.client.AccountClient;
import com.banka1.order.client.ExchangeClient;
import com.banka1.order.dto.ExchangeRateDto;
import com.banka1.order.dto.AccountDetailsDto;
import com.banka1.order.dto.TaxDebtResponse;
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

import static java.util.stream.Collectors.toList;

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
                if (order == null || order.getUserId() == null) continue;

                // only SELL orders generate capital gains tax
                if (order.getDirection() != com.banka1.order.entity.enums.OrderDirection.SELL) continue;

                // fetch portfolio to get average purchase price
                Portfolio portfolio = portfolioRepository.findByUserIdAndListingId(order.getUserId(), order.getListingId()).orElse(null);
                if (portfolio == null) {
                    log.warn("No portfolio found for user {} listing {} – skipping tax for transaction {}", order.getUserId(), order.getListingId(), tx.getId());
                    continue;
                }

                BigDecimal sellPrice = tx.getPricePerUnit();
                BigDecimal avgPurchase = portfolio.getAveragePurchasePrice();

                if (sellPrice == null || avgPurchase == null) continue;

                BigDecimal gainPerShare = sellPrice.subtract(avgPurchase);
                if (gainPerShare.compareTo(BigDecimal.ZERO) <= 0) continue; // no taxable gain

                if (tx.getQuantity() == null || tx.getQuantity() <= 0) continue;
                BigDecimal taxableGain = gainPerShare.multiply(BigDecimal.valueOf(tx.getQuantity()));
                BigDecimal tax = taxableGain.multiply(TAX_RATE);

                // get seller account details (by internal id stored on order)
                AccountDetailsDto sellerAccount = accountClient.getAccountDetailsById(order.getAccountId());

                // convert tax to RSD using exchange-service (no commission)
                String fromCurrency = sellerAccount.getCurrency();
                BigDecimal taxInRsd;

                try {
                    ExchangeRateDto conversion =
                            exchangeClient.calculate(fromCurrency, "RSD", tax);

                    taxInRsd = conversion != null
                            ? conversion.getConvertedAmount()
                            : tax;

                } catch (Exception ex) {
                    log.warn("Exchange failed for tx {}, fallback to original tax", tx.getId());
                    taxInRsd = tax;
                }

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

    @Override
    public void collectMonthlyTaxManually() {
        log.info("Manually triggering monthly tax collection");
        collectMonthlyTax();
    }

    @Override
    public List<TaxDebtResponse> getAllDebts() {

        log.info("Fetching all tax debts");

        List<Transaction> txs = transactionRepository.findAll();

        HashMap<Long, BigDecimal> debtMap = new HashMap<>();

        for (Transaction tx : txs) {
            try {
                Order order = orderRepository.findById(tx.getOrderId()).orElse(null);
                if (order == null) continue;

                if (order.getDirection() == null || !order.getDirection().name().equals("SELL")) continue;

                Portfolio portfolio = portfolioRepository
                        .findByUserIdAndListingId(order.getUserId(), order.getListingId())
                        .orElse(null);

                if (portfolio == null) continue;

                BigDecimal gainPerShare =
                        tx.getPricePerUnit().subtract(portfolio.getAveragePurchasePrice());

                if (gainPerShare.compareTo(BigDecimal.ZERO) <= 0) continue;

                BigDecimal taxableGain =
                        gainPerShare.multiply(BigDecimal.valueOf(tx.getQuantity()));

                BigDecimal tax = taxableGain.multiply(TAX_RATE);

                debtMap.merge(order.getUserId(), tax, BigDecimal::add);

            } catch (Exception e) {
                log.error("Error calculating debt for transaction {}", tx.getId(), e);
            }
        }

        return debtMap.entrySet()
                .stream()
                .map(e -> new TaxDebtResponse(
                        e.getKey(),
                        e.getValue()
                ))
                .toList();
    }

    @Override
    public TaxDebtResponse getUserDebt(Long userId) {

        log.info("Fetching tax debt for user {}", userId);

        List<Transaction> txs = transactionRepository.findAll();

        BigDecimal totalDebt = BigDecimal.ZERO;

        for (Transaction tx : txs) {
            try {
                Order order = orderRepository.findById(tx.getOrderId()).orElse(null);
                if (order == null) continue;

                if (!order.getUserId().equals(userId)) continue;

                if (order.getDirection() == null || !order.getDirection().name().equals("SELL")) continue;

                Portfolio portfolio = portfolioRepository
                        .findByUserIdAndListingId(order.getUserId(), order.getListingId())
                        .orElse(null);

                if (portfolio == null) continue;

                BigDecimal gainPerShare =
                        tx.getPricePerUnit().subtract(portfolio.getAveragePurchasePrice());

                if (gainPerShare.compareTo(BigDecimal.ZERO) <= 0) continue;

                BigDecimal taxableGain =
                        gainPerShare.multiply(BigDecimal.valueOf(tx.getQuantity()));

                BigDecimal tax = taxableGain.multiply(TAX_RATE);

                totalDebt = totalDebt.add(tax);

            } catch (Exception e) {
                log.error("Error calculating user debt for transaction {}", tx.getId(), e);
            }
        }

        return new TaxDebtResponse
                (userId,totalDebt);
    }
}

