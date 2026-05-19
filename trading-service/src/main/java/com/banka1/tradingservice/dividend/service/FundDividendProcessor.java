package com.banka1.tradingservice.dividend.service;

import com.banka1.tradingservice.dividend.client.DividendAccountClient;
import com.banka1.tradingservice.dividend.client.DividendDataClient;
import com.banka1.tradingservice.funds.client.AccountServiceClient;
import com.banka1.tradingservice.funds.client.MarketPriceClient;
import com.banka1.tradingservice.funds.domain.ClientFundPosition;
import com.banka1.tradingservice.funds.domain.ClientFundTransaction;
import com.banka1.tradingservice.funds.domain.ClientFundTransactionStatus;
import com.banka1.tradingservice.funds.domain.FundDividendPolicy;
import com.banka1.tradingservice.funds.domain.FundHolding;
import com.banka1.tradingservice.funds.domain.InvestmentFund;
import com.banka1.tradingservice.funds.repository.ClientFundPositionRepository;
import com.banka1.tradingservice.funds.repository.ClientFundTransactionRepository;
import com.banka1.tradingservice.funds.repository.FundHoldingRepository;
import com.banka1.tradingservice.funds.repository.InvestmentFundRepository;
import com.banka1.tradingservice.funds.service.FundHoldingService;
import com.banka1.tradingservice.funds.service.InvestmentFundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * WP-17 (Celina 4.3): obrada dividende koju investicioni fond primi po hartiji
 * koju drzi.
 *
 * <p>Nadovezuje se na WP-14: posto {@link DividendDistributionService} isplati
 * dividendu svim {@code Portfolio} STOCK drzaocima, za svaku dividendnu hartiju
 * dodatno obradjuje i {@link FundHolding} pozicije te hartije.
 *
 * <h2>Zasto zaseban {@code @Transactional} bean</h2>
 * <p>Isti razlog kao {@link DividendPayoutExecutor}: {@code distribute()}
 * (orkestracija) poziva {@link #processFundHolding} u petlji, a Spring
 * proxy-based {@code @Transactional} se NE primenjuje na self-invocation. Kroz
 * zaseban bean svaka obrada jednog fonda dobija sopstvenu transakciju — greska
 * na jednom fondu rollback-uje samo njega.
 *
 * <h2>Tok obrade jednog {@link FundHolding}</h2>
 * <ol>
 *   <li><b>Obracun:</b> {@code gross = quantity * price * (dividendYield / 4)},
 *       u valuti listinga (ista WP-14 formula, fond kao drzalac).</li>
 *   <li><b>Priliv (uvek):</b> {@code grossRsd} (bruto konvertovan u RSD bez
 *       provizije) se dodaje na {@code likvidnaSredstva} fonda i kreditira na
 *       RSD racun fonda. Dividenda fonda je prihod fonda — bez 15% poreza na
 *       nivou fonda (spec ne definise poresku obradu fondova; vidi izvestaj).</li>
 *   <li><b>Politika:</b> {@link FundDividendPolicy#REINVEST} reinvestira priliv
 *       u istu hartiju; {@link FundDividendPolicy#DISTRIBUTE} raspodeljuje
 *       priliv klijentima srazmerno udelu.</li>
 * </ol>
 *
 * <h2>Poznata pojednostavljenja (vidi WP-17 izvestaj)</h2>
 * <ul>
 *   <li><b>Bez poreza na nivou fonda:</b> spec ne propisuje poresku obradu
 *       dividende fonda; priliv se tretira kao pun bruto.</li>
 *   <li><b>Reinvest = instant-fill simulacija:</b> kupovina dodatnih jedinica
 *       hartije nema order book matching — isto kao sto
 *       {@code FundLiquidationService} simulira prodaju.</li>
 *   <li><b>Tolerantnost na nedostupan account-service:</b> u profilu bez
 *       {@code AccountServiceClient}-a (local/test) priliv azurira likvidnost
 *       fonda i evidenciju, ali preskace REST kreditiranje racuna — konzistentno
 *       sa {@code InvestmentFundService}.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundDividendProcessor {

    private static final String RSD = "RSD";
    private static final BigDecimal QUARTERS = new BigDecimal("4");
    /** Skala likvidnosti/pozicija fonda — {@code NUMERIC(19,2)} u semi. */
    private static final int FUND_SCALE = 2;

    private final FundHoldingRepository fundHoldingRepository;
    private final InvestmentFundRepository fundRepository;
    private final ClientFundPositionRepository positionRepository;
    private final ClientFundTransactionRepository transactionRepository;
    private final FundHoldingService fundHoldingService;
    private final MarketPriceClient marketPriceClient;
    private final DividendAccountClient dividendAccountClient;
    /**
     * {@code AccountServiceClient} kreditira RSD racun fonda. Wrap-ovan u
     * {@link ObjectProvider} jer u local/test profilu bean ne postoji —
     * tada se REST kreditiranje preskace (kao u {@code InvestmentFundService}).
     */
    private final ObjectProvider<AccountServiceClient> accountServiceClientProvider;

    /**
     * Obradjuje dividendu jedne hartije za sve fondove koji je drze.
     *
     * <p>Pronalazi sve aktivne {@link FundHolding} redove date hartije (preko
     * svih fondova) i delegira svaki na {@link #processFundHolding} — svaki u
     * sopstvenoj transakciji.
     *
     * @param stock dividendni podaci hartije (ista struktura kao u WP-14)
     * @param asOf  datum obracuna
     * @return broj fond-holding pozicija kojima je dividenda obradjena
     */
    public int processStockForFunds(DividendDataClient.DividendData stock, LocalDate asOf) {
        List<FundHolding> holdings = fundHoldingRepository.findByStockTickerAndDeletedFalse(stock.ticker());
        if (holdings.isEmpty()) {
            return 0;
        }
        int processed = 0;
        for (FundHolding holding : holdings) {
            if (processOneFundHoldingSafely(stock, holding, asOf)) {
                processed++;
            }
        }
        return processed;
    }

    private boolean processOneFundHoldingSafely(DividendDataClient.DividendData stock,
                                                FundHolding holding, LocalDate asOf) {
        try {
            return processFundHolding(stock, holding.getId(), asOf);
        } catch (Exception ex) {
            log.error("Fund dividend: obrada pala za fundId={} ticker={} datum={}",
                    holding.getFundId(), holding.getStockTicker(), asOf, ex);
            return false;
        }
    }

    /**
     * Obradjuje dividendu jednog fond-holdinga, u sopstvenoj transakciji.
     *
     * <p>Holding se ponovo cita u okviru transakcije ({@code holdingId}) kako bi
     * priliv/reinvest video sveze stanje i pessimisticki lock na fondu vazio.
     *
     * @param stock     dividendni podaci hartije
     * @param holdingId ID {@link FundHolding} reda
     * @param asOf      datum obracuna
     * @return {@code true} ako je dividenda obradjena; {@code false} ako je
     *         preskocena (holding nestao, fond nestao, ili bruto nije pozitivan)
     */
    @Transactional
    public boolean processFundHolding(DividendDataClient.DividendData stock, Long holdingId, LocalDate asOf) {
        FundHolding holding = fundHoldingRepository.findById(holdingId).orElse(null);
        if (holding == null || holding.isDeleted()) {
            log.debug("Fund dividend: holding {} nestao/obrisan — preskacem.", holdingId);
            return false;
        }
        Long fundId = holding.getFundId();
        InvestmentFund fund = fundRepository.findByIdForUpdate(fundId).orElse(null);
        if (fund == null) {
            log.debug("Fund dividend: fond {} ne postoji/obrisan — preskacem holding {}.", fundId, holdingId);
            return false;
        }

        String currency = stock.currency();
        BigDecimal gross = computeGross(holding.getQuantity(), stock.price(), stock.dividendYield());
        if (gross.signum() <= 0) {
            log.debug("Fund dividend: bruto <= 0 za fundId={} ticker={} — preskacem.", fundId, stock.ticker());
            return false;
        }
        BigDecimal grossRsd = convertToRsd(gross, currency).setScale(FUND_SCALE, RoundingMode.HALF_UP);

        // --- Priliv (uvek): dividenda utece u likvidnost fonda + na RSD racun fonda. ---
        fund.setLikvidnaSredstva(safe(fund.getLikvidnaSredstva()).add(grossRsd));
        fundRepository.save(fund);
        creditFundAccount(fund, grossRsd);
        log.info("Fund dividend INFLOW: fundId={} ticker={} bruto={} {} -> +{} RSD likvidnost (politika={})",
                fundId, stock.ticker(), gross, currency, grossRsd, fund.getDividendPolicy());

        // --- Politika obrade priliva. ---
        if (fund.getDividendPolicy() == FundDividendPolicy.DISTRIBUTE) {
            distributeToClients(fund, grossRsd, asOf);
        } else {
            reinvest(fund, holding, stock, gross, currency);
        }
        return true;
    }

    /**
     * {@link FundDividendPolicy#REINVEST}: kupuje dodatne cele jedinice iste
     * hartije za primljenu dividendu i tereti likvidnost fonda za trosak.
     *
     * <p>Broj jedinica = {@code floor(gross / price)} u valuti listinga. Ako
     * dividenda ne pokriva ni jednu celu jedinicu, reinvesticija se preskace —
     * priliv ostaje kao likvidnost fonda (dozvoljeno spec ponasanje).
     */
    private void reinvest(InvestmentFund fund, FundHolding holding,
                          DividendDataClient.DividendData stock, BigDecimal gross, String currency) {
        BigDecimal price = stock.price();
        int shares = gross.divide(price, 0, RoundingMode.FLOOR).intValue();
        if (shares <= 0) {
            log.info("Fund dividend REINVEST: fundId={} ticker={} bruto={} {} < cena jedinice {} — "
                            + "0 celih jedinica, priliv ostaje kao likvidnost.",
                    fund.getId(), stock.ticker(), gross, currency, price);
            return;
        }
        BigDecimal costListing = price.multiply(BigDecimal.valueOf(shares));
        BigDecimal costRsd = convertToRsd(costListing, currency).setScale(FUND_SCALE, RoundingMode.HALF_UP);

        fundHoldingService.addOrUpdate(fund.getId(), holding.getStockTicker(), shares, price);
        fund.setLikvidnaSredstva(safe(fund.getLikvidnaSredstva()).subtract(costRsd).max(BigDecimal.ZERO));
        fundRepository.save(fund);
        log.info("Fund dividend REINVEST: fundId={} ticker={} kupljeno {}× @ {} {} (trosak {} RSD)",
                fund.getId(), stock.ticker(), shares, price, currency, costRsd);
    }

    /**
     * {@link FundDividendPolicy#DISTRIBUTE}: deli priliv pozicijama klijenata
     * srazmerno udelu ({@code position.totalInvested / Σ totalInvested}),
     * ukljucujuci poziciju banke ({@code clientId = -1}).
     *
     * <p>Posto je priliv vec kreditirao likvidnost fonda, raspodeljeni ukupni
     * iznos se zatim oduzima nazad iz likvidnosti — novac napusta fond ka
     * klijentima, neto efekat na likvidnost fonda je nula.
     *
     * <p>Zaokruzivanje: poslednja pozicija prima rezidual ({@code grossRsd}
     * minus zbir vec dodeljenog) da raspodela bude tacna do dinara.
     */
    private void distributeToClients(InvestmentFund fund, BigDecimal grossRsd, LocalDate asOf) {
        Long fundId = fund.getId();
        List<ClientFundPosition> positions = positionRepository.findByFundId(fundId);
        BigDecimal totalInvested = positions.stream()
                .map(p -> safe(p.getTotalInvested()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (positions.isEmpty() || totalInvested.signum() <= 0) {
            log.warn("Fund dividend DISTRIBUTE: fond {} nema pozicija sa ulogom — priliv {} RSD "
                    + "ostaje kao likvidnost fonda.", fundId, grossRsd);
            return;
        }

        BigDecimal distributedSoFar = BigDecimal.ZERO;
        int n = positions.size();
        for (int i = 0; i < n; i++) {
            ClientFundPosition pos = positions.get(i);
            BigDecimal share;
            if (i == n - 1) {
                // Poslednja pozicija prima rezidual — eliminise zaokruzivacku gresku.
                share = grossRsd.subtract(distributedSoFar);
            } else {
                share = grossRsd.multiply(safe(pos.getTotalInvested()))
                        .divide(totalInvested, FUND_SCALE, RoundingMode.HALF_UP);
            }
            distributedSoFar = distributedSoFar.add(share);
            payClientShare(fund, pos, share, asOf);
        }

        // Raspodeljeni novac napusta fond — oduzmi nazad iz likvidnosti.
        fund.setLikvidnaSredstva(safe(fund.getLikvidnaSredstva()).subtract(grossRsd).max(BigDecimal.ZERO));
        fundRepository.save(fund);
        log.info("Fund dividend DISTRIBUTE: fundId={} raspodeljeno {} RSD na {} pozicija "
                + "(likvidnost fonda neto nepromenjena).", fundId, grossRsd, n);
    }

    /**
     * Isplacuje jednoj poziciji njen srazmerni deo dividende: kreditira RSD
     * racun (klijent ili banka) i belezi {@link ClientFundTransaction} outflow
     * radi audit traga.
     */
    private void payClientShare(InvestmentFund fund, ClientFundPosition pos,
                                BigDecimal shareRsd, LocalDate asOf) {
        if (shareRsd == null || shareRsd.signum() <= 0) {
            return;
        }
        Long clientId = pos.getClientId();
        boolean forBank = InvestmentFundService.BANK_INVESTOR_ID.equals(clientId);
        String accountNumber = resolveRsdAccountNumber(clientId, forBank);

        ClientFundTransaction tx = new ClientFundTransaction();
        tx.setClientId(clientId);
        tx.setFundId(fund.getId());
        tx.setAmount(shareRsd);
        tx.setInflow(false);
        tx.setStatus(ClientFundTransactionStatus.COMPLETED);
        tx.setClientAccountNumber(accountNumber == null ? "" : accountNumber);
        if (accountNumber == null) {
            tx.setStatus(ClientFundTransactionStatus.FAILED);
            tx.setFailureReason("RSD racun nije razresen za isplatu dividende");
        }
        transactionRepository.save(tx);

        if (accountNumber != null) {
            creditClientAccount(accountNumber, shareRsd, clientId);
            log.info("Fund dividend DISTRIBUTE: fundId={} {}={} primio {} RSD (racun {})",
                    fund.getId(), forBank ? "banka" : "clientId", clientId, shareRsd, accountNumber);
        } else {
            log.warn("Fund dividend DISTRIBUTE: RSD racun za {}={} nije razresen — udeo {} RSD "
                            + "evidentiran kao FAILED bez kreditiranja.",
                    forBank ? "banka" : "clientId", clientId, shareRsd);
        }
    }

    /**
     * Razresava broj RSD racuna primaoca raspodele. Za poziciju banke
     * ({@code forBank}) to je bankin RSD racun (Profit Banke); za klijenta
     * njegov RSD tekuci racun.
     *
     * @return broj RSD racuna, ili {@code null} ako se ne moze razresiti
     */
    private String resolveRsdAccountNumber(Long clientId, boolean forBank) {
        if (forBank) {
            DividendAccountClient.OwnerAccount bank = dividendAccountClient.bankRsdAccount();
            return bank == null ? null : bank.accountNumber();
        }
        DividendAccountClient.OwnerAccount rsd = dividendAccountClient.accountInCurrency(clientId, RSD);
        if (rsd != null) {
            return rsd.accountNumber();
        }
        return dividendAccountClient.defaultRsdAccountNumber(clientId);
    }

    private void creditClientAccount(String accountNumber, BigDecimal amountRsd, Long ownerId) {
        dividendAccountClient.creditAccount(accountNumber, amountRsd, ownerId);
    }

    /**
     * Kreditira RSD racun fonda za primljeni priliv dividende. Tolerantno na
     * nedostupan {@code AccountServiceClient} (local/test profil) — tada se REST
     * poziv preskace, konzistentno sa {@code InvestmentFundService}.
     */
    private void creditFundAccount(InvestmentFund fund, BigDecimal amountRsd) {
        if (amountRsd == null || amountRsd.signum() <= 0) {
            return;
        }
        AccountServiceClient client = accountServiceClientProvider.getIfAvailable();
        if (client == null) {
            log.debug("Fund dividend: AccountServiceClient nije dostupan — priliv {} RSD evidentiran "
                    + "u likvidnosti fonda {} bez REST kreditiranja racuna.", amountRsd, fund.getId());
            return;
        }
        Long ownerId = -1000L - fund.getId();
        client.creditAccount(fund.getAccountNumber(), amountRsd, ownerId);
    }

    /**
     * Obracunava bruto dividendu fonda: {@code quantity * price * (dividendYield / 4)}.
     * Ista formula kao {@link DividendPayoutExecutor#computeGross}, sa fondom kao
     * drzaocem.
     *
     * @return bruto u valuti listinga, zaokruzen na 4 decimale;
     *         {@link BigDecimal#ZERO} ako je bilo koji ulaz nepozitivan/null
     */
    BigDecimal computeGross(Integer quantity, BigDecimal price, BigDecimal dividendYield) {
        if (quantity == null || quantity <= 0 || price == null || dividendYield == null) {
            return BigDecimal.ZERO;
        }
        if (price.signum() <= 0 || dividendYield.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal quarterlyYield = dividendYield.divide(QUARTERS, 10, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(quantity)
                .multiply(price)
                .multiply(quarterlyYield)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal convertToRsd(BigDecimal amount, String currency) {
        if (currency == null || RSD.equalsIgnoreCase(currency)) {
            return amount;
        }
        return marketPriceClient.convertNoCommission(amount, currency, RSD).orElse(amount);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
