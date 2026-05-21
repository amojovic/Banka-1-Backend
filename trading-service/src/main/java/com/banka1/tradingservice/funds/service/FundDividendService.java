package com.banka1.tradingservice.funds.service;

import com.banka1.order.client.AccountClient;
import com.banka1.order.dto.client.PaymentDto;
import com.banka1.tradingservice.funds.client.AccountServiceClient;
import com.banka1.tradingservice.funds.client.MarketPriceClient;
import com.banka1.tradingservice.funds.domain.ClientFundPosition;
import com.banka1.tradingservice.funds.domain.FundDividendDistribution;
import com.banka1.tradingservice.funds.domain.FundDividendDistributionStatus;
import com.banka1.tradingservice.funds.domain.FundDividendPayout;
import com.banka1.tradingservice.funds.domain.FundDividendPayoutStatus;
import com.banka1.tradingservice.funds.domain.FundDividendStrategy;
import com.banka1.tradingservice.funds.domain.FundHolding;
import com.banka1.tradingservice.funds.domain.InvestmentFund;
import com.banka1.tradingservice.funds.dto.FundDividendDistributionDto;
import com.banka1.tradingservice.funds.dto.FundDividendPayoutDto;
import com.banka1.tradingservice.funds.dto.RecordFundDividendRequest;
import com.banka1.tradingservice.funds.repository.ClientFundPositionRepository;
import com.banka1.tradingservice.funds.repository.FundDividendDistributionRepository;
import com.banka1.tradingservice.funds.repository.FundDividendPayoutRepository;
import com.banka1.tradingservice.funds.repository.FundHoldingRepository;
import com.banka1.tradingservice.funds.repository.InvestmentFundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class FundDividendService {

    private static final String FUND_BASE_CURRENCY = "RSD";

    private final InvestmentFundRepository fundRepository;
    private final FundHoldingRepository holdingRepository;
    private final FundDividendDistributionRepository distributionRepository;
    private final FundDividendPayoutRepository payoutRepository;
    private final ClientFundPositionRepository positionRepository;
    private final FundHoldingService fundHoldingService;
    private final InvestmentFundService investmentFundService;
    private final FundValueSnapshotService snapshotService;
    private final MarketPriceClient marketPriceClient;
    private final AccountServiceClient accountServiceClient;
    private final AccountClient accountClient;

    @Transactional
    public FundDividendDistributionDto recordDividend(Long fundId, RecordFundDividendRequest request) {
        InvestmentFund fund = fundRepository.findByIdForUpdate(fundId)
                .orElseThrow(() -> new IllegalArgumentException("Fond " + fundId + " ne postoji."));
        String ticker = request.getStockTicker().toUpperCase(Locale.ROOT);

        Optional<FundDividendDistribution> existing = distributionRepository.findByFundIdAndStockTickerAndPaymentDate(
                fundId, ticker, request.getPaymentDate());
        if (existing.isPresent()) {
            throw new IllegalStateException("Dividenda za fond " + fundId + ", ticker " + ticker
                    + " i datum " + request.getPaymentDate() + " je vec evidentirana.");
        }

        FundHolding holding = holdingRepository.findByFundIdAndStockTickerAndDeletedFalse(fundId, ticker)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Fond " + fundId + " ne poseduje holding za ticker " + ticker + "."));
        if (holding.getQuantity() == null || holding.getQuantity() <= 0) {
            throw new IllegalArgumentException("Holding za ticker " + ticker + " nema raspolozivu kolicinu.");
        }

        FundDividendStrategy strategy = request.getStrategy() != null ? request.getStrategy() : fund.getDividendStrategy();
        BigDecimal grossSource = request.getDividendPerShare()
                .multiply(BigDecimal.valueOf(holding.getQuantity()))
                .setScale(8, RoundingMode.HALF_UP);
        BigDecimal grossRsd = convertToRsd(grossSource, request.getCurrency()).setScale(2, RoundingMode.HALF_UP);

        creditFundDividend(fund, grossRsd);

        FundDividendDistribution distribution = new FundDividendDistribution();
        distribution.setFundId(fundId);
        distribution.setStockTicker(ticker);
        distribution.setPaymentDate(request.getPaymentDate() != null ? request.getPaymentDate() : LocalDate.now());
        distribution.setDividendPerShare(request.getDividendPerShare());
        distribution.setSourceCurrency(request.getCurrency().toUpperCase(Locale.ROOT));
        distribution.setHoldingQuantity(holding.getQuantity());
        distribution.setGrossAmountSource(grossSource);
        distribution.setGrossAmountRsd(grossRsd);
        distribution.setStrategy(strategy);
        distribution.setStatus(FundDividendDistributionStatus.COMPLETED);

        List<FundDividendPayout> payoutRows = switch (strategy) {
            case REINVEST -> handleReinvestment(distribution, fund, holding, grossRsd);
            case PAYOUT_CLIENTS -> handleClientPayouts(distribution, fund, grossRsd);
        };

        FundDividendDistribution saved = distributionRepository.save(distribution);
        for (FundDividendPayout payout : payoutRows) {
            payout.setDistributionId(saved.getId());
        }
        if (!payoutRows.isEmpty()) {
            payoutRepository.saveAll(payoutRows);
        }

        snapshotService.recordSnapshot(fundId, LocalDate.now());
        return toDto(saved, payoutRows);
    }

    private List<FundDividendPayout> handleReinvestment(FundDividendDistribution distribution,
                                                        InvestmentFund fund,
                                                        FundHolding holding,
                                                        BigDecimal grossRsd) {
        List<FundDividendPayout> payouts = List.of();
        BigDecimal currentPriceUsd = marketPriceClient.currentPrice(holding.getStockTicker())
                .orElse(holding.getAvgUnitPrice());
        if (currentPriceUsd == null || currentPriceUsd.signum() <= 0) {
            distribution.setStatus(FundDividendDistributionStatus.COMPLETED_WITH_WARNINGS);
            distribution.setNote("Dividenda knjizena u likvidnost; nema validne trzisne cene za reinvestiranje.");
            distribution.setReinvestedShares(0);
            distribution.setReinvestedAmountRsd(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            return payouts;
        }
        BigDecimal priceRsd = convertToRsd(currentPriceUsd, "USD").setScale(8, RoundingMode.HALF_UP);
        int sharesToBuy = grossRsd.divide(priceRsd, 0, RoundingMode.DOWN).intValue();
        if (sharesToBuy <= 0) {
            distribution.setNote("Dividenda knjizena u likvidnost; iznos nije dovoljan za kupovinu cele akcije.");
            distribution.setReinvestedShares(0);
            distribution.setReinvestedAmountRsd(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            return payouts;
        }
        BigDecimal reinvestAmount = priceRsd.multiply(BigDecimal.valueOf(sharesToBuy)).setScale(2, RoundingMode.HALF_UP);
        fundHoldingService.addOrUpdate(fund.getId(), holding.getStockTicker(), sharesToBuy, currentPriceUsd);
        investmentFundService.debitLiquidity(fund.getId(), reinvestAmount, "Fund dividend reinvestment");
        accountServiceClient.debitAccount(fund.getAccountNumber(), reinvestAmount, -1000L - fund.getId());
        distribution.setReinvestedShares(sharesToBuy);
        distribution.setReinvestedAmountRsd(reinvestAmount);
        distribution.setDistributedAmountRsd(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        distribution.setNote("Dividenda reinvestirana u dodatne hartije.");
        return payouts;
    }

    private List<FundDividendPayout> handleClientPayouts(FundDividendDistribution distribution,
                                                         InvestmentFund fund,
                                                         BigDecimal grossRsd) {
        List<ClientFundPosition> clientPositions = positionRepository.findByFundId(fund.getId()).stream()
                .filter(position -> !InvestmentFundService.BANK_INVESTOR_ID.equals(position.getClientId()))
                .sorted(Comparator.comparing(ClientFundPosition::getClientId))
                .toList();

        if (clientPositions.isEmpty()) {
            distribution.setStatus(FundDividendDistributionStatus.COMPLETED_WITH_WARNINGS);
            distribution.setDistributedAmountRsd(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            distribution.setNote("Fond nema klijentske ucesnike; dividenda ostaje u likvidnim sredstvima fonda.");
            return List.of();
        }

        BigDecimal fundValueBeforeDividend = fund.getLikvidnaSredstva()
                .add(fundHoldingService.calculateHoldingsValue(fund.getId()))
                .subtract(grossRsd)
                .max(BigDecimal.ZERO);
        BigDecimal totalInvested = clientPositions.stream()
                .map(ClientFundPosition::getTotalInvested)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal denominator = totalInvested.max(fundValueBeforeDividend);
        if (denominator.signum() <= 0) {
            distribution.setStatus(FundDividendDistributionStatus.COMPLETED_WITH_WARNINGS);
            distribution.setDistributedAmountRsd(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            distribution.setNote("Nema dovoljno osnova za proporcionalnu raspodelu dividende.");
            return List.of();
        }

        BigDecimal distributed = BigDecimal.ZERO;
        List<FundDividendPayout> result = new ArrayList<>();
        for (int i = 0; i < clientPositions.size(); i++) {
            ClientFundPosition position = clientPositions.get(i);
            BigDecimal ownershipRatio = position.getTotalInvested()
                    .divide(denominator, 8, RoundingMode.HALF_UP);
            BigDecimal payoutAmount = i == clientPositions.size() - 1
                    ? grossRsd.subtract(distributed)
                    : grossRsd.multiply(ownershipRatio).setScale(2, RoundingMode.HALF_UP);
            if (payoutAmount.signum() <= 0) {
                continue;
            }
            FundDividendPayout payout = new FundDividendPayout();
            payout.setClientId(position.getClientId());
            payout.setOwnershipRatio(ownershipRatio);
            payout.setAmountRsd(payoutAmount);
            String clientAccountNumber = accountClient.getDefaultRsdAccountNumberForOwner(position.getClientId());
            payout.setClientAccountNumber(clientAccountNumber);
            if (clientAccountNumber == null || clientAccountNumber.isBlank()) {
                payout.setStatus(FundDividendPayoutStatus.SKIPPED);
                payout.setFailureReason("Klijent nema podrazumevani RSD racun.");
                distribution.setStatus(FundDividendDistributionStatus.COMPLETED_WITH_WARNINGS);
            } else {
                try {
                    accountClient.transaction(new PaymentDto(
                            fund.getAccountNumber(),
                            clientAccountNumber,
                            payoutAmount,
                            payoutAmount,
                            BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                            -1000L - fund.getId()
                    ));
                    investmentFundService.debitLiquidity(fund.getId(), payoutAmount, "Fund dividend payout");
                    payout.setStatus(FundDividendPayoutStatus.COMPLETED);
                    distributed = distributed.add(payoutAmount);
                } catch (Exception ex) {
                    payout.setStatus(FundDividendPayoutStatus.FAILED);
                    payout.setFailureReason(ex.getMessage());
                    distribution.setStatus(FundDividendDistributionStatus.COMPLETED_WITH_WARNINGS);
                }
            }
            result.add(payout);
        }
        distribution.setDistributedAmountRsd(distributed.setScale(2, RoundingMode.HALF_UP));
        if (distribution.getNote() == null) {
            distribution.setNote("Dividenda raspodeljena klijentima proporcionalno udelu.");
        }
        return result;
    }

    private void creditFundDividend(InvestmentFund fund, BigDecimal grossRsd) {
        fund.setLikvidnaSredstva(fund.getLikvidnaSredstva().add(grossRsd).setScale(2, RoundingMode.HALF_UP));
        fundRepository.save(fund);
        accountServiceClient.creditAccount(fund.getAccountNumber(), grossRsd, -1000L - fund.getId());
    }

    private BigDecimal convertToRsd(BigDecimal amount, String fromCurrency) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        if (fromCurrency == null || FUND_BASE_CURRENCY.equalsIgnoreCase(fromCurrency)) {
            return amount;
        }
        return marketPriceClient.convertNoCommission(amount, fromCurrency.toUpperCase(Locale.ROOT), FUND_BASE_CURRENCY)
                .orElse(amount);
    }

    private FundDividendDistributionDto toDto(FundDividendDistribution distribution, List<FundDividendPayout> payouts) {
        return FundDividendDistributionDto.builder()
                .id(distribution.getId())
                .fundId(distribution.getFundId())
                .stockTicker(distribution.getStockTicker())
                .paymentDate(distribution.getPaymentDate())
                .dividendPerShare(distribution.getDividendPerShare())
                .sourceCurrency(distribution.getSourceCurrency())
                .holdingQuantity(distribution.getHoldingQuantity())
                .grossAmountSource(distribution.getGrossAmountSource())
                .grossAmountRsd(distribution.getGrossAmountRsd())
                .strategy(distribution.getStrategy())
                .status(distribution.getStatus())
                .reinvestedShares(distribution.getReinvestedShares())
                .reinvestedAmountRsd(distribution.getReinvestedAmountRsd())
                .distributedAmountRsd(distribution.getDistributedAmountRsd())
                .note(distribution.getNote())
                .processedAt(distribution.getProcessedAt())
                .payouts(payouts.stream().map(payout -> FundDividendPayoutDto.builder()
                        .clientId(payout.getClientId())
                        .clientAccountNumber(payout.getClientAccountNumber())
                        .ownershipRatio(payout.getOwnershipRatio())
                        .amountRsd(payout.getAmountRsd())
                        .status(payout.getStatus())
                        .failureReason(payout.getFailureReason())
                        .build()).toList())
                .build();
    }
}
