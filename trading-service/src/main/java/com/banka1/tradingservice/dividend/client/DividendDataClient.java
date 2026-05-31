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
import java.util.List;

/**
 * WP-14 (Celina 3.7): REST klijent ka market-service-u (konsolidovani
 * stock-service) za podatke o dividendi.
 *
 * <p>Povlaci {@code GET /stocks/internal/dividend-data} — po jednom redu za
 * svaku STOCK hartiju sa cenom na dan obracuna, valutom berze i dividendnom
 * stopom. Koristi se iskljucivo iz {@code DividendDistributionService} u
 * kvartalnom cron kontekstu (nema korisnikovog JWT-a), pa se uvek autentifikuje
 * sluzbenim SERVICE token-om — isti pattern kao {@code AccountServiceClient}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DividendDataClient {

    private final JWTService jwtService;

    @Value("${services.market.url:http://market-service:8085}")
    private String baseUrl;

    private WebClient webClient(String bearerToken) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Vraca dividendne podatke za sve STOCK hartije u sistemu.
     *
     * <p>Tolerantno na greske: ako market-service ne odgovori, vraca praznu
     * listu — {@code DividendDistributionService} tada nema sta da isplati i
     * obracun se gracizno zavrsi bez isplata.
     *
     * @return lista dividendnih podataka, ili prazna lista ako poziv padne
     */
    public List<DividendData> fetchAll() {
        try {
            List<DividendData> data = webClient(jwtService.generateJwtToken()).get()
                    .uri("/stocks/internal/dividend-data")
                    .retrieve()
                    .bodyToFlux(DividendData.class)
                    .collectList()
                    .timeout(Duration.ofSeconds(10))
                    .block();
            return data == null ? List.of() : data;
        } catch (Exception ex) {
            log.warn("Dividend data fetch failed: {}", ex.toString());
            return List.of();
        }
    }

    /**
     * Subset of stock-service {@code StockDividendDataResponse}. Jackson ignorise
     * nepoznata polja (default Spring Boot konfiguracija).
     *
     * @param listingId     ID listinga
     * @param ticker        ticker hartije
     * @param price         cena listinga na dan obracuna
     * @param currency      valuta berze
     * @param dividendYield dividendna stopa (decimalna vrednost)
     */
    public record DividendData(
            Long listingId,
            String ticker,
            BigDecimal price,
            String currency,
            BigDecimal dividendYield) {
    }
}
