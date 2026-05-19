package com.banka1.tradingservice.otc.domain;

/**
 * WP-16 (Celina 4.2): tip akcije zabelezene u {@link OtcOfferRevision} redu —
 * kompletna istorija jednog OTC pregovora.
 *
 * <p>Svaka akcija na ponudi pravi po jedan revizioni red:
 * <ul>
 *   <li>{@code CREATE}    — inicijalna ponuda kupca; "stara" polja su {@code null},
 *                            "nova" polja su pocetne vrednosti.
 *   <li>{@code COUNTER}   — kontraponuda; popunjena su i "stara" i "nova" polja.
 *   <li>{@code ACCEPT}    — prihvatanje ponude; bez izmene vrednosti.
 *   <li>{@code REJECT}    — odbijanje ponude; bez izmene vrednosti.
 *   <li>{@code WITHDRAW}  — povlacenje sopstvene ponude; bez izmene vrednosti.
 * </ul>
 * Za {@code ACCEPT}/{@code REJECT}/{@code WITHDRAW} value polja mogu biti
 * {@code null} — zapis je akcija + aktor + trenutak.
 */
public enum OtcRevisionAction {
    /** Inicijalna ponuda kupca prodavcu. */
    CREATE,
    /** Kontraponuda kupca ili prodavca (izmena amount/price/premium/settlement). */
    COUNTER,
    /** Prihvatanje ponude od strane koja je na potezu. */
    ACCEPT,
    /** Odbijanje ponude. */
    REJECT,
    /** Povlacenje sopstvene ponude pre odgovora druge strane. */
    WITHDRAW
}
