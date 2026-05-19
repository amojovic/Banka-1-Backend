package com.banka1.tradingservice.funds.domain;

/**
 * WP-17 (Celina 4.3): politika obrade dividende koju fond primi po hartiji
 * koju drzi.
 *
 * <p>Kada hartija koju fond drzi isplati kvartalnu dividendu, iznos uvek prvo
 * <em>utece</em> u fond (uvecava {@code likvidnaSredstva} fonda i kreditira
 * RSD racun fonda — vidi {@code DividendDistributionService}). Ova enum-vrednost
 * odlucuje sta se dalje desava sa tim prilivom:
 *
 * <ul>
 *   <li>{@link #REINVEST} — priliv ostaje u fondu i sistem automatski reinvestira
 *       primljenu dividendu: kupuje dodatne jedinice <em>iste</em> hartije koja je
 *       isplatila dividendu (instant-fill simulacija, kao
 *       {@code FundLiquidationService} kod prodaje).</li>
 *   <li>{@link #DISTRIBUTE} — priliv se deli pozicijama klijenata srazmerno
 *       njihovom udelu u fondu (ukljucujuci poziciju banke,
 *       {@code clientId = -1}); posto je priliv vec kreditirao likvidnost fonda,
 *       raspodeljeni ukupni iznos se zatim oduzima nazad iz likvidnosti — neto
 *       efekat je da dividenda protece kroz fond do klijenata.</li>
 * </ul>
 *
 * <p>Default za novokreirane fondove je {@link #REINVEST} (vidi
 * {@code InvestmentFund.dividendPolicy} i changeset {@code 015}).
 */
public enum FundDividendPolicy {

    /** Primljena dividenda se reinvestira u istu hartiju. */
    REINVEST,

    /** Primljena dividenda se raspodeljuje klijentima srazmerno udelu. */
    DISTRIBUTE
}
