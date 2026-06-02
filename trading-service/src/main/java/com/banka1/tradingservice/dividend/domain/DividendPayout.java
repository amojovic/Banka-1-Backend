package com.banka1.tradingservice.dividend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * WP-14 (Celina 3.7): jedna isplata dividende jednom drzaocu akcije.
 *
 * <p>Sistem kvartalno (poslednji radni dan marta/juna/septembra/decembra)
 * isplacuje dividendu svim drzaocima hartije, srazmerno kolicini:
 * {@code Dividenda = Quantity * Price * (DividendYield / 4)}.
 *
 * <p>Dividenda se tretira kao kapitalna dobit i ulazi u mesecno pracenje poreza
 * po istoj stopi (15%): porez se obracunava u RSD, drzaocu se isplacuje neto u
 * valuti listinga. Pozicije koje aktuar drzi u ime banke ({@link #forBank})
 * NEMAJU porez — taj iznos ide u Profit Banke.
 *
 * <p>Unique constraint ukljucuje {@code for_bank}: isti drzalac moze imati jednu
 * licnu i jednu bank-held isplatu za istu hartiju na isti dan.
 *
 * <p>Tabelu definise Liquibase changeset {@code trading-otc/014-dividend-payouts.sql};
 * mapiranje mora tacno odgovarati semi jer trading-service radi sa
 * {@code spring.jpa.hibernate.ddl-auto=validate}.
 */
@Entity
@Table(
        name = "dividend_payouts",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_dividend_payout_user_listing_date",
                columnNames = {"user_id", "listing_id", "payment_date", "for_bank"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DividendPayout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ID drzaoca akcije (klijent ili aktuar) kome se isplacuje dividenda. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Ticker hartije za koju je dividenda isplacena. */
    @Column(name = "stock_ticker", length = 32)
    private String stockTicker;

    /** ID listing snapshot-a u stock-service-u. */
    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    /** Broj jedinica hartije koje je drzalac imao na dan obracuna. */
    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    /** Bruto iznos dividende (pre poreza), u valuti listinga. */
    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal grossAmount;

    /** Valuta isplate — valuta berze na kojoj je hartija listirana. */
    @Column(name = "currency", length = 8)
    private String currency;

    /**
     * Iznos poreza na kapitalnu dobit, u RSD. Za {@link #forBank} pozicije je 0
     * (banka ne placa porez na sopstvenu dividendu).
     */
    @Column(name = "tax_amount_rsd", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmountRsd;

    /** Neto iznos isplacen drzaocu, u valuti listinga ({@code gross - porez}). */
    @Column(name = "net_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal netAmount;

    /**
     * ID racuna na koji je dividenda isplacena. Moze biti {@code null} ako racun
     * nije mogao da se razresi (npr. drzalac nema RSD racun).
     */
    @Column(name = "account_id")
    private Long accountId;

    /** Datum obracuna/isplate dividende (poslednji radni dan kvartala). */
    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    /**
     * {@code true} kada je pozicija drzana u ime banke (aktuar za banku) — tada
     * nema 15% poreza, a iznos predstavlja Profit Banke.
     */
    @Column(name = "for_bank", nullable = false)
    private boolean forBank;

    @PrePersist
    void onCreate() {
        if (paymentDate == null) {
            paymentDate = LocalDate.now();
        }
        if (taxAmountRsd == null) {
            taxAmountRsd = BigDecimal.ZERO;
        }
    }
}
