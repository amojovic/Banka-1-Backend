package com.banka1.bankingcore.config;

import com.banka1.bankingcore.security.JWTService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

/**
 * Definise RestClient bean-ove ka okolnim servisima (PR_14 C14.4).
 *
 * <p>Banking-core zove account-service za debit/credit operacije, a market-service
 * za interne FX kalkulacije kod cross-currency settlement-a.
 *
 * <p>Aktivan u svim profilima da bi lokalni run imao pristup account/market servisima.
 */
@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final JWTService jwtService;

    @Value("${banka.security.expiration-time:3600000}")
    private long tokenValidityMillis;

    @Bean
    public ClientHttpRequestInterceptor jwtAuthInterceptor() {
        return new ServiceJwtAuthInterceptor(jwtService, tokenValidityMillis);
    }

    @Bean
    public RestClient.Builder restClientBuilder(ClientHttpRequestInterceptor jwtAuthInterceptor) {
        return RestClient.builder()
                .requestInterceptor(jwtAuthInterceptor);
    }

    @Bean
    public RestClient accountRestClient(RestClient.Builder builder, @Value("${services.account.url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }

    @Bean
    public RestClient marketRestClient(RestClient.Builder builder, @Value("${services.exchange.url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }
}
