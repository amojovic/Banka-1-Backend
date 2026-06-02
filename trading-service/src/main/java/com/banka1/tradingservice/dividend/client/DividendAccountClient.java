package com.banka1.tradingservice.dividend.client;

import com.banka1.tradingservice.security.JWTService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/**
 * WP-14 (Celina 3.7): REST klijent ka account-service-u (konsolidovani
 * banking-core-service) za isplatu dividende.
 *
 * <p>Sve operacije teku u kvartalnom cron kontekstu (nema korisnikovog JWT-a),
 * pa se autentifikuju sluzbenim SERVICE token-om. Klijent je tanak namenski
 * sloj — {@code funds.AccountServiceClient} ne izlaze lookup drzavnog/bankinog
 * racuna, a {@code order-service} {@code AccountClient} pripada drugom modulu.
 *
 * <p>Razresavanje racuna se oslanja na postojece interne endpointe:
 * <ul>
 *   <li>{@code GET /accounts/internal/default/{ownerId}} — RSD tekuci racun drzaoca;</li>
 *   <li>{@code GET /accounts/internal/by-owner/{ownerId}/currency/{currencyCode}} —
 *       racun drzaoca u zadatoj valuti listinga (WP-14b, za isplatu bez FX konverzije);</li>
 *   <li>{@code GET /internal/accounts/state/RSD} — drzavni RSD racun (porez);</li>
 *   <li>{@code GET /internal/accounts/bank/RSD} — bankin RSD racun (Profit Banke).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DividendAccountClient {

    private final JWTService jwtService;

    @Value("${services.account.url:http://banking-core-service:8084}")
    private String baseUrl;

    private WebClient webClient() {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateJwtToken())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Razresava broj RSD tekuceg racuna drzaoca.
     *
     * @param ownerId ID drzaoca (klijent ili aktuar)
     * @return broj RSD racuna, ili {@code null} ako drzalac nema RSD racun
     */
    public String defaultRsdAccountNumber(Long ownerId) {
        try {
            Map<?, ?> response = webClient().get()
                    .uri("/accounts/internal/default/{ownerId}", ownerId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
            return response == null ? null : (String) response.get("accountNumber");
        } catch (Exception ex) {
            log.warn("Dividend: ne mogu da razresim RSD racun za drzaoca {}: {}", ownerId, ex.toString());
            return null;
        }
    }

    /**
     * Razresava racun drzaoca u zadatoj valuti listinga (WP-14b).
     *
     * <p>Kvartalna isplata dividende prvo pokusava da kreditira drzaocev racun u
     * valuti hartije (bez FX konverzije); ovaj poziv vraca taj racun ako postoji.
     * Endpoint odgovara {@code 404} kada drzalac nema racun u toj valuti, sto se
     * ovde mapira u {@code null} (poziva se {@code defaultRsdAccountNumber} kao
     * fallback).
     *
     * @param ownerId  ID drzaoca (klijent ili aktuar)
     * @param currency ISO 4217 kod valute listinga (npr. {@code USD}, {@code RSD})
     * @return racun drzaoca u toj valuti, ili {@code null} ako ga nema / lookup padne
     */
    public OwnerAccount accountInCurrency(Long ownerId, String currency) {
        try {
            Map<?, ?> response = webClient().get()
                    .uri("/accounts/internal/by-owner/{ownerId}/currency/{currencyCode}", ownerId, currency)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
            if (response == null) {
                return null;
            }
            Object accountNumber = response.get("accountNumber");
            if (accountNumber == null) {
                return null;
            }
            return new OwnerAccount(toLong(response.get("id")), accountNumber.toString());
        } catch (Exception ex) {
            // 404 (drzalac nema racun u toj valuti) je ocekivan ishod — fallback na RSD.
            log.debug("Dividend: drzalac {} nema {} racun ({}); fallback na RSD.",
                    ownerId, currency, ex.toString());
            return null;
        }
    }

    /**
     * Razresava drzavni RSD racun (porez na kapitalnu dobit). Drzava je modelovana
     * kao firma sa vlasnikom {@code -2}; {@link OwnerAccount#ownerId()} nosi taj id
     * koji se prosledjuje kao {@code clientId} pri kreditiranju — account-service
     * proverava vlasnistvo ({@code vlasnik == clientId}), pa bez tacnog vlasnika
     * vraca {@code 400}.
     *
     * @return drzavni RSD racun, ili {@code null} ako lookup padne
     */
    public OwnerAccount stateRsdAccount() {
        return toOwnerAccount(accountDetails("/internal/accounts/state/RSD", "drzavni"));
    }

    /**
     * Razresava bankin RSD racun (Profit Banke) na koji se uplacuje dividenda
     * pozicija koje banka drzi. {@link OwnerAccount#ownerId()} nosi vlasnika racuna
     * koji se prosledjuje kao {@code clientId} pri kreditiranju (vidi
     * {@link #stateRsdAccount()}). {@code id} se belezi u {@code DividendPayout.accountId}.
     *
     * @return bankin RSD racun, ili {@code null} ako lookup padne
     */
    public OwnerAccount bankRsdAccount() {
        return toOwnerAccount(accountDetails("/internal/accounts/bank/RSD", "bankin"));
    }

    private OwnerAccount toOwnerAccount(AccountDetails details) {
        return details == null || details.accountNumber() == null
                ? null
                : new OwnerAccount(details.id(), details.accountNumber(), details.ownerId());
    }

    /**
     * Kreditira iznos na zadati racun. Iznos je u valuti tog racuna — kvartalna
     * isplata kreditira neto u valuti listinga (racun u toj valuti) ili u RSD
     * (RSD fallback racun); porez se uvek kreditira drzavi u RSD.
     *
     * @param accountNumber broj racuna primaoca
     * @param amount        iznos u valuti racuna primaoca
     * @param ownerId       ID vlasnika racuna (radi internih provera account-service-a)
     */
    public void creditAccount(String accountNumber, BigDecimal amount, Long ownerId) {
        webClient().post()
                .uri("/internal/accounts/credit")
                .bodyValue(Map.of(
                        "accountNumber", accountNumber,
                        "amount", amount,
                        "clientId", ownerId))
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(10))
                .block();
        log.info("Dividend: kreditirano accountNumber={} ownerId={} amount={}", accountNumber, ownerId, amount);
    }

    private AccountDetails accountDetails(String uri, String label) {
        try {
            return webClient().get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(AccountDetails.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
        } catch (Exception ex) {
            log.warn("Dividend: ne mogu da razresim {} RSD racun: {}", label, ex.toString());
            return null;
        }
    }

    private static Long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Subset of account-service {@code InternalAccountDetailsDto}. Jackson
     * ignorise nepoznata polja (default Spring Boot konfiguracija).
     *
     * @param id            interni ID racuna
     * @param accountNumber broj racuna
     * @param ownerId       ID vlasnika
     * @param currency      valuta racuna
     */
    public record AccountDetails(Long id, String accountNumber, Long ownerId, String currency) {
    }

    /**
     * Razreseni racun drzaoca: interni ID racuna i broj racuna. {@code id} se
     * upisuje u {@code DividendPayout.accountId}, {@code accountNumber} se koristi
     * za kreditiranje (WP-14b).
     *
     * @param id            interni ID racuna ({@code null} ako endpoint ne vrati id)
     * @param accountNumber broj racuna primaoca
     * @param ownerId       vlasnik racuna; prosledjuje se kao {@code clientId} pri
     *                      kreditiranju radi provere vlasnistva u account-service-u
     *                      ({@code null} za drzaocev racun — tamo je vlasnik sam drzalac)
     */
    public record OwnerAccount(Long id, String accountNumber, Long ownerId) {

        /** Konstruktor za drzaocev racun gde je vlasnik sam drzalac (ownerId se ne koristi). */
        public OwnerAccount(Long id, String accountNumber) {
            this(id, accountNumber, null);
        }
    }
}
