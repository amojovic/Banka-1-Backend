package com.banka1.tradingservice.dividend.service;

import com.banka1.order.entity.Portfolio;
import com.banka1.order.repository.OrderRepository;
import com.banka1.tradingservice.dividend.client.DividendAccountClient;
import com.banka1.tradingservice.dividend.client.DividendDataClient;
import com.banka1.tradingservice.dividend.domain.DividendPayout;
import com.banka1.tradingservice.dividend.repository.DividendPayoutRepository;
import com.banka1.tradingservice.funds.client.MarketPriceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * WP-14 (Celina 3.7): transakcioni izvrsilac isplate dividende jednom drzaocu.
 *
 * <p>Izdvojen iz {@link DividendDistributionService} u zaseban bean namerno —
 * {@code distribute()} (orkestracija) poziva {@link #payoutForHolder} u petlji, a
 * Spring proxy-based {@code @Transactional} se NE primenjuje na self-invocation.
 * Pozivom kroz ovaj zaseban bean svaka isplata zaista dobija sopstvenu
 * transakciju: greska na jednoj isplati rollback-uje samo nju, ostali drzaoci
 * se isplate normalno.
 *
 * <h2>Formula i poreska logika</h2>
 * <ul>
 *   <li>{@code gross = quantity * price * (dividendYield / 4)}, u valuti listinga;</li>
 *   <li>licna pozicija: 15% poreza u RSD (bruto konvertovan u RSD bez provizije),
 *       neto drzaocu, porez drzavnom RSD racunu;</li>
 *   <li>pozicija banke ({@code forBank}): bez poreza, pun bruto je Profit Banke.</li>
 * </ul>
 *
 * <h2>Bank-held split (WP-14 bank-held)</h2>
 * <p>Portfolio agregira poziciju po {@code (userId, listingId)} bez per-poziciju
 * markera vlasnistva banke. Bank-held kolicina se izvodi iz istorije naloga:
 * zbir izvrsenih {@code PurchaseFor.BANK} BUY kolicina za drzaoca i listing.
 * Razlika do ukupne kolicine tretira se kao licna pozicija (oporezovana).
 * Clampovanjem na {@code [0, holder.quantity]} obezbedujemo konzistentnost
 * sa aktuelnim stanjem portfolija.
 *
 * <h2>Razresavanje racuna drzaoca (WP-14b)</h2>
 * <p>Neto se isplacuje u valuti listinga ako drzalac ima racun u toj valuti —
 * tada nema FX konverzije. Ako nema, pada se na RSD racun drzaoca i neto se
 * konvertuje u RSD. {@code DividendPayout.accountId} belezi racun koji je
 * stvarno kreditiran (ili {@code null} ako nijedan nije razresen).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DividendPayoutExecutor {

    private static final String RSD = "RSD";
    private static final BigDecimal QUARTERS = new BigDecimal("4");
    private static final int MONETARY_SCALE = 4;

    /**
     * Stopa poreza na kapitalnu dobit, default 15% (spec Celina 3). Citana iz
     * {@code banka.tax.capital-gains-rate} radi konzistentnosti sa
     * {@code TaxServiceImpl}.
     */
    @Value("${banka.tax.capital-gains-rate:0.15}")
    private BigDecimal taxRate;

    private final DividendAccountClient accountClient;
    private final MarketPriceClient marketPriceClient;
    private final DividendPayoutRepository payoutRepository;
    private final OrderRepository orderRepository;

    /**
     * Obracunava i isplacuje dividendu jednom drzaocu, u sopstvenoj transakciji.
     *
     * <p>Kolicina pozicije se deli na bank-held (izvedeno iz naloga sa
     * {@code PurchaseFor.BANK}) i licnu. Svaki deo se isplacuje zasebno — bank-held
     * bez poreza u Profit Banke, licni sa 15% u drzavni racun.
     *
     * @param stock  dividendni podaci hartije
     * @param holder portfolio pozicija drzaoca
     * @param asOf   datum obracuna
     * @return {@code true} ako je bar jedna isplata izvrsena; {@code false} ako su
     *         obe preskocene (vec isplacene ili bruto nije pozitivan)
     */
    @Transactional
    public boolean payoutForHolder(DividendDataClient.DividendData stock, Portfolio holder, LocalDate asOf) {
        Long userId = holder.getUserId();
        Long listingId = stock.listingId();
        int total = holder.getQuantity();

        long rawBank = orderRepository.bankHeldBuyQuantity(userId, listingId);
        int bankQty = (int) Math.min(rawBank, total);
        bankQty = Math.max(0, bankQty);
        int personalQty = total - bankQty;

        boolean anyPaid = false;

        if (personalQty > 0) {
            if (payoutRepository.existsByUserIdAndListingIdAndPaymentDateAndForBank(
                    userId, listingId, asOf, false)) {
                log.debug("Dividend (licna): vec isplaceno userId={} listingId={} datum={}",
                        userId, listingId, asOf);
            } else {
                BigDecimal gross = computeGross(personalQty, stock.price(), stock.dividendYield());
                if (gross.signum() > 0) {
                    payPersonal(stock, holder, asOf, gross, personalQty);
                    anyPaid = true;
                }
            }
        }

        if (bankQty > 0) {
            if (payoutRepository.existsByUserIdAndListingIdAndPaymentDateAndForBank(
                    userId, listingId, asOf, true)) {
                log.debug("Dividend (banka): vec isplaceno userId={} listingId={} datum={}",
                        userId, listingId, asOf);
            } else {
                BigDecimal gross = computeGross(bankQty, stock.price(), stock.dividendYield());
                if (gross.signum() > 0) {
                    payBankHeld(stock, holder, asOf, gross, bankQty);
                    anyPaid = true;
                }
            }
        }

        return anyPaid;
    }

    /**
     * Isplata dela pozicije koji banka drzi: bez poreza, pun bruto ide na bankin RSD
     * racun (Profit Banke).
     */
    private void payBankHeld(DividendDataClient.DividendData stock, Portfolio holder, LocalDate asOf,
                             BigDecimal gross, int quantity) {
        String currency = stock.currency();
        BigDecimal grossRsd = convertToRsd(gross, currency);
        DividendAccountClient.OwnerAccount bankAccount = accountClient.bankRsdAccount();

        payoutRepository.save(DividendPayout.builder()
                .userId(holder.getUserId())
                .stockTicker(stock.ticker())
                .listingId(stock.listingId())
                .quantity(quantity)
                .grossAmount(scale(gross))
                .currency(currency)
                .taxAmountRsd(BigDecimal.ZERO)
                .netAmount(scale(gross))
                .accountId(bankAccount == null ? null : bankAccount.id())
                .paymentDate(asOf)
                .forBank(true)
                .build());

        if (bankAccount != null) {
            accountClient.creditAccount(bankAccount.accountNumber(), grossRsd, holder.getUserId());
        } else {
            log.warn("Dividend: bankin RSD racun nije razresen — bank-held userId={} listingId={} "
                    + "evidentirana bez kreditiranja.", holder.getUserId(), stock.listingId());
        }
        log.info("Dividend (banka): userId={} ticker={} kolicina={} bruto={} {} -> Profit Banke {} RSD",
                holder.getUserId(), stock.ticker(), quantity, gross, currency, grossRsd);
    }

    /**
     * Isplata licnog dela pozicije: 15% poreza u RSD na drzavni racun, neto drzaocu.
     *
     * <p>WP-14b: neto se prvo pokusava isplatiti na racun drzaoca u valuti
     * listinga (bez FX konverzije — {@code netListing}); ako drzalac nema takav
     * racun, pada se na RSD racun i neto se konvertuje u RSD ({@code netRsd}).
     */
    private void payPersonal(DividendDataClient.DividendData stock, Portfolio holder, LocalDate asOf,
                             BigDecimal gross, int quantity) {
        Long userId = holder.getUserId();
        String currency = stock.currency();
        BigDecimal grossRsd = convertToRsd(gross, currency);
        BigDecimal taxRsd = grossRsd.multiply(taxRate).setScale(MONETARY_SCALE, RoundingMode.HALF_UP);
        BigDecimal netRsd = grossRsd.subtract(taxRsd);

        BigDecimal taxInListingCurrency = convertFromRsd(taxRsd, currency);
        BigDecimal netListing = scale(gross.subtract(taxInListingCurrency));

        PayoutTarget target = resolvePersonalTarget(userId, currency);
        String stateAccount = accountClient.stateRsdAccountNumber();

        payoutRepository.save(DividendPayout.builder()
                .userId(userId)
                .stockTicker(stock.ticker())
                .listingId(stock.listingId())
                .quantity(quantity)
                .grossAmount(scale(gross))
                .currency(currency)
                .taxAmountRsd(taxRsd)
                .netAmount(netListing)
                .accountId(target.accountId())
                .paymentDate(asOf)
                .forBank(false)
                .build());

        if (target.accountNumber() != null) {
            BigDecimal creditAmount = target.inListingCurrency() ? netListing : netRsd;
            accountClient.creditAccount(target.accountNumber(), creditAmount, userId);
        } else {
            log.warn("Dividend: racun drzaoca {} nije razresen (ni {} ni RSD) — isplata listingId={} "
                    + "evidentirana bez kreditiranja.", userId, currency, stock.listingId());
        }
        if (taxRsd.signum() > 0 && stateAccount != null) {
            accountClient.creditAccount(stateAccount, taxRsd, userId);
        } else if (taxRsd.signum() > 0) {
            log.warn("Dividend: drzavni RSD racun nije razresen — porez {} RSD (userId={} listingId={}) "
                    + "evidentiran bez kreditiranja.", taxRsd, userId, stock.listingId());
        }
        log.info("Dividend (licna): userId={} ticker={} kolicina={} bruto={} {} porez={} RSD neto={} {}",
                userId, stock.ticker(), quantity, gross, currency, taxRsd,
                target.inListingCurrency() ? netListing : netRsd,
                target.inListingCurrency() ? currency : RSD);
    }

    /**
     * WP-14b: razresava racun drzaoca po fallback lancu. Prvo trazi racun u
     * valuti listinga (isplata bez FX konverzije), pa pada na RSD racun.
     *
     * <p>Kada je valuta listinga vec RSD, oba koraka su isti racun — radi se
     * samo jedan lookup.
     *
     * @param userId   ID drzaoca
     * @param currency valuta listinga
     * @return cilj isplate; {@code accountNumber} je {@code null} ako nijedan
     *         racun nije razresen
     */
    private PayoutTarget resolvePersonalTarget(Long userId, String currency) {
        boolean listingIsRsd = currency == null || RSD.equalsIgnoreCase(currency);

        if (!listingIsRsd) {
            DividendAccountClient.OwnerAccount listingAccount =
                    accountClient.accountInCurrency(userId, currency);
            if (listingAccount != null) {
                return new PayoutTarget(listingAccount.id(), listingAccount.accountNumber(), true);
            }
        }

        DividendAccountClient.OwnerAccount rsdAccount = accountClient.accountInCurrency(userId, RSD);
        if (rsdAccount != null) {
            return new PayoutTarget(rsdAccount.id(), rsdAccount.accountNumber(), false);
        }
        String rsdNumber = accountClient.defaultRsdAccountNumber(userId);
        return new PayoutTarget(null, rsdNumber, false);
    }

    /**
     * Razreseni cilj isplate dividende.
     *
     * @param accountId        interni ID racuna ({@code null} ako nije poznat)
     * @param accountNumber    broj racuna ({@code null} ako racun nije razresen)
     * @param inListingCurrency {@code true} ako je racun u valuti listinga
     *                          (kreditira se {@code netListing} bez konverzije);
     *                          {@code false} za RSD fallback ({@code netRsd})
     */
    private record PayoutTarget(Long accountId, String accountNumber, boolean inListingCurrency) {
    }

    /**
     * Obracunava bruto dividendu: {@code quantity * price * (dividendYield / 4)}.
     *
     * @param quantity      broj jedinica koje drzalac ima
     * @param price         cena listinga na dan obracuna
     * @param dividendYield dividendna stopa hartije
     * @return bruto iznos, zaokruzen na 4 decimale; {@link BigDecimal#ZERO} ako
     *         je bilo koji ulaz {@code null} ili nepozitivan
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
                .setScale(MONETARY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal convertToRsd(BigDecimal amount, String currency) {
        if (currency == null || RSD.equalsIgnoreCase(currency)) {
            return scale(amount);
        }
        return scale(marketPriceClient.convertNoCommission(amount, currency, RSD).orElse(amount));
    }

    private BigDecimal convertFromRsd(BigDecimal amountRsd, String currency) {
        if (currency == null || RSD.equalsIgnoreCase(currency)) {
            return scale(amountRsd);
        }
        return scale(marketPriceClient.convertNoCommission(amountRsd, RSD, currency).orElse(amountRsd));
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(MONETARY_SCALE, RoundingMode.HALF_UP);
    }
}
