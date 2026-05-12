package com.banka1.tradingservice.funds.service;

import com.banka1.tradingservice.funds.client.AccountServiceClient;
import com.banka1.tradingservice.funds.domain.ClientFundPosition;
import com.banka1.tradingservice.funds.domain.ClientFundTransaction;
import com.banka1.tradingservice.funds.domain.ClientFundTransactionStatus;
import com.banka1.tradingservice.funds.domain.InvestmentFund;
import com.banka1.tradingservice.funds.dto.CreateFundRequest;
import com.banka1.tradingservice.funds.dto.InvestmentFundDto;
import com.banka1.tradingservice.funds.dto.InvestmentRequest;
import com.banka1.tradingservice.funds.dto.RedemptionRequest;
import com.banka1.tradingservice.funds.repository.ClientFundPositionRepository;
import com.banka1.tradingservice.funds.repository.ClientFundTransactionRepository;
import com.banka1.tradingservice.funds.repository.InvestmentFundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Servis za investicione fondove (PR_04).
 *
 * <p>Spec (Celina 4.txt):
 * <ul>
 *   <li>Supervizori kreiraju fondove i upravljaju njima.
 *   <li>Klijenti mogu uplatiti i povuci sredstva (subscribe/redeem).
 *   <li>Pri uplati: skida se sa klijentovog tekuceg, dodaje se na fundu, kreira/azurira ClientFundPosition.
 *   <li>Pri isplati: ako likvidnaSredstva pokriva, odmah; inace SAGA likvidira hartije.
 *   <li>vrednostFonda i profit su izvedeni — racunaju se ovde u DTO mapper-u.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvestmentFundService {

    private final InvestmentFundRepository fundRepository;
    private final ClientFundPositionRepository positionRepository;
    private final ClientFundTransactionRepository transactionRepository;
    private final RabbitTemplate rabbitTemplate;
    private final FundAccountNumberGenerator accountNumberGenerator;
    /**
     * AccountServiceClient (PR_14 C14.8) — wrap-uje se u {@link ObjectProvider} jer
     * u local profilu (test) bean ne postoji i fund creation onda preskace REST poziv
     * (samo upisuje u trading-service tabelu). Production profil sa @Profile("!local")
     * ce uvek biti dostupan i fund_account ce biti kreiran u account-service.
     */
    private final ObjectProvider<AccountServiceClient> accountServiceClientProvider;

    private static final String SAGA_EVENTS_EXCHANGE = "saga.events";

    @Transactional
    public InvestmentFundDto createFund(CreateFundRequest req, Long managerId) {
        String accountNumber = accountNumberGenerator.generate();

        InvestmentFund fund = new InvestmentFund();
        fund.setNaziv(req.getNaziv());
        fund.setOpis(req.getOpis());
        fund.setMinimumContribution(req.getMinimumContribution());
        fund.setManagerId(managerId);
        fund.setLikvidnaSredstva(BigDecimal.ZERO);
        fund.setAccountNumber(accountNumber);

        InvestmentFund saved = fundRepository.save(fund);

        // PR_14 C14.8: traci pravi Account red u account-service-u za ovaj fond.
        // Bez ovoga, FUND_INVEST/FUND_REDEEM SAGA-e ne mogu da credit-uju/debit-uju
        // racun fonda. Idempotentno: ako Account vec postoji, account-service vraca
        // postojeci ne pravi greske.
        AccountServiceClient client = accountServiceClientProvider.getIfAvailable();
        if (client != null) {
            try {
                client.createSystemAccount(
                        accountNumber,
                        // ownerId konvencija: -F<fundId> kao Long; jednostavna ekstenzija
                        // postojeceg pattern-a "drzava=-2, banka=-1, exchange=-3".
                        -1000L - saved.getId(),
                        "RSD",
                        "Investicioni fond: " + saved.getNaziv(),
                        BigDecimal.ZERO);
                log.info("Account fonda {} (id={}) kreiran u account-service-u", accountNumber, saved.getId());
            } catch (Exception ex) {
                log.error("Account fonda {} (id={}) NIJE kreiran u account-service: {} — fond je upisan ali "
                                + "invest/redeem nece raditi dok admin ne kreira account rucno.",
                        accountNumber, saved.getId(), ex.toString());
                // Ne propagiramo dalje — fund je vec upisan; admin retry kasnije.
            }
        } else {
            log.warn("AccountServiceClient nije dostupan (verovatno local profil). Fond {} kreiran bez account-a; "
                    + "invest/redeem nece raditi dok admin ne kreira account rucno.", accountNumber);
        }

        log.info("Created InvestmentFund {} ('{}') by manager {}", saved.getId(), saved.getNaziv(), managerId);
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<InvestmentFundDto> discovery() {
        return fundRepository.findByDeletedFalseOrderByNazivAsc()
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public InvestmentFundDto details(Long fundId) {
        return fundRepository.findById(fundId)
                .map(this::toDto)
                .orElseThrow(() -> new IllegalArgumentException("Fond " + fundId + " ne postoji."));
    }

    /**
     * Klijent uplacuje sredstva u fond. Spec: "Uplatilac prilikom uplacivanja novca treba
     * da izabere racun sa kog ide novac. Nakon prolaska transakcije, treba kreirati/azurirati poziciju".
     *
     * <p>Implementacija: ovde se belezi pending ClientFundTransaction. Posle commit-a,
     * publishuje se saga event {@code fund.subscribe.requested}; saga-orchestrator izvrsava
     * banking-core debit + odmah completes transakciju. Nije potrebna SAGA-grade orchestracija
     * jer subscribe ima samo jedan distribuirani korak (banking-core debit).
     */
    @Transactional
    public ClientFundTransaction invest(Long fundId, Long clientId, InvestmentRequest req) {
        InvestmentFund fund = fundRepository.findByIdForUpdate(fundId)
                .orElseThrow(() -> new IllegalArgumentException("Fond " + fundId + " ne postoji."));

        if (req.getAmount().compareTo(fund.getMinimumContribution()) < 0) {
            throw new IllegalArgumentException(
                    "Iznos manji od minimumContribution (" + fund.getMinimumContribution() + ").");
        }

        ClientFundTransaction tx = new ClientFundTransaction();
        tx.setClientId(clientId);
        tx.setFundId(fundId);
        tx.setAmount(req.getAmount());
        tx.setInflow(true);
        tx.setStatus(ClientFundTransactionStatus.PENDING);
        tx.setClientAccountNumber(req.getFromAccountNumber());

        ClientFundTransaction saved = transactionRepository.save(tx);

        registerAfterCommit(() -> rabbitTemplate.convertAndSend(
                SAGA_EVENTS_EXCHANGE, "fund.subscribe.requested",
                new FundSubscribeRequestedEvent(saved.getId(), clientId, fundId,
                        req.getAmount(), req.getFromAccountNumber(), fund.getAccountNumber())
        ));

        return saved;
    }

    /**
     * Klijent povlaci sredstva. Spec: "ako je zeljeni iznos pokriven likvidnim sredstvima,
     * novac se odmah prenosi. U suprotnom, vrsi se automatska likvidacija dovoljnog broja hartija".
     *
     * <p>Pri likvidaciji se startuje SAGA FUND_LIQUIDATION_FOR_REDEMPTION:
     * 1. Liquidate stocks (market-service prodaje za potreban iznos),
     * 2. Transfer funds to client (banking-core).
     */
    @Transactional
    public ClientFundTransaction redeem(Long fundId, Long clientId, RedemptionRequest req) {
        InvestmentFund fund = fundRepository.findByIdForUpdate(fundId)
                .orElseThrow(() -> new IllegalArgumentException("Fond " + fundId + " ne postoji."));

        ClientFundPosition pos = positionRepository.findByClientIdAndFundIdForUpdate(clientId, fundId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Klijent " + clientId + " nema poziciju u fondu " + fundId));

        if (pos.getTotalInvested().compareTo(req.getAmount()) < 0) {
            throw new IllegalArgumentException(
                    "Trazena isplata veca od ulozenog iznosa klijenta (" + pos.getTotalInvested() + ").");
        }

        ClientFundTransaction tx = new ClientFundTransaction();
        tx.setClientId(clientId);
        tx.setFundId(fundId);
        tx.setAmount(req.getAmount());
        tx.setInflow(false);
        tx.setStatus(ClientFundTransactionStatus.PENDING);
        tx.setClientAccountNumber(req.getToAccountNumber());

        ClientFundTransaction saved = transactionRepository.save(tx);

        boolean liquidEnough = fund.getLikvidnaSredstva().compareTo(req.getAmount()) >= 0;
        String routingKey = liquidEnough
                ? "fund.redeem.requested"
                : "fund.redeem.with-liquidation.requested";

        registerAfterCommit(() -> rabbitTemplate.convertAndSend(
                SAGA_EVENTS_EXCHANGE, routingKey,
                new FundRedeemRequestedEvent(saved.getId(), clientId, fundId,
                        req.getAmount(), req.getToAccountNumber(), fund.getAccountNumber(),
                        liquidEnough)
        ));

        return saved;
    }

    @Transactional(readOnly = true)
    public List<ClientFundPosition> myPositions(Long clientId) {
        return positionRepository.findByClientId(clientId);
    }

    @Transactional(readOnly = true)
    public List<InvestmentFundDto> supervisedBy(Long managerId) {
        return fundRepository.findByManagerIdAndDeletedFalse(managerId)
                .stream().map(this::toDto).toList();
    }

    /**
     * Spec napomena: "Prebacivanje vlasnistva fondova kada admin oduzme isSupervisor permisiju".
     * Reassign-uje sve fondove sa starog menadzera na novog.
     */
    @Transactional
    public void reassignManager(Long oldManagerId, Long newManagerId) {
        List<InvestmentFund> funds = fundRepository.findByManagerIdAndDeletedFalse(oldManagerId);
        for (InvestmentFund f : funds) {
            f.setManagerId(newManagerId);
        }
        log.info("Reassigned {} fund(s) from manager {} to {}", funds.size(), oldManagerId, newManagerId);
    }

    // -------------------------- internal --------------------------

    private InvestmentFundDto toDto(InvestmentFund f) {
        // Izvedeni totalValue/profit zahtevaju cross-modul query ka market-service-u za vrednost
        // hartija u portfoliu fonda. Za sad vracamo samo likvidnaSredstva kao baseline; pravi
        // izracun se dodaje u FundValueCalculator (PR_05/PR_08).
        BigDecimal investedSum = positionRepository.findByFundId(f.getId()).stream()
                .map(ClientFundPosition::getTotalInvested)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalValue = f.getLikvidnaSredstva();  // bez vrednosti hartija — TBD market-service integracija
        BigDecimal profit = totalValue.subtract(investedSum);

        return InvestmentFundDto.builder()
                .id(f.getId()).naziv(f.getNaziv()).opis(f.getOpis())
                .minimumContribution(f.getMinimumContribution())
                .managerId(f.getManagerId())
                .likvidnaSredstva(f.getLikvidnaSredstva())
                .accountNumber(f.getAccountNumber())
                .datumKreiranja(f.getDatumKreiranja())
                .totalValue(totalValue)
                .profit(profit)
                .build();
    }

    private void registerAfterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }

    public record FundSubscribeRequestedEvent(
            Long transactionId, Long clientId, Long fundId, BigDecimal amount,
            String fromAccountNumber, String fundAccountNumber) {}

    public record FundRedeemRequestedEvent(
            Long transactionId, Long clientId, Long fundId, BigDecimal amount,
            String toAccountNumber, String fundAccountNumber, boolean liquidEnough) {}
}
