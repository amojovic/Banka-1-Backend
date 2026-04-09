package com.banka1.stock_service.client;

import com.banka1.stock_service.config.StockMarketDataProperties;
import com.banka1.stock_service.dto.AlphaVantageCompanyOverviewResponse;
import com.banka1.stock_service.dto.AlphaVantageDailyResponse;
import com.banka1.stock_service.dto.AlphaVantageDailyValue;
import com.banka1.stock_service.dto.AlphaVantageForexExchangeRateResponse;
import com.banka1.stock_service.dto.AlphaVantageQuoteResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * HTTP client responsible for fetching stock data from the Alpha Vantage API.
 *
 * <p>The client deliberately contains only provider communication, response validation,
 * and normalization into internal DTO records.
 * All database updates and cross-entity mapping stay in the service layer.
 */
@Component
public class AlphaVantageClient {

    private static final String QUERY_PATH = "/query";
    private static final String FUNCTION_GLOBAL_QUOTE = "GLOBAL_QUOTE";
    private static final String FUNCTION_TIME_SERIES_DAILY = "TIME_SERIES_DAILY";
    private static final String FUNCTION_OVERVIEW = "OVERVIEW";
    private static final String FUNCTION_CURRENCY_EXCHANGE_RATE = "CURRENCY_EXCHANGE_RATE";

    private final RestClient stockMarketDataRestClient;
    private final StockMarketDataProperties stockMarketDataProperties;

    /**
     * @param stockMarketDataRestClient RestClient configured for the market-data base URL
     * @param stockMarketDataProperties provider configuration and API key
     */
    public AlphaVantageClient(
            @Qualifier("stockMarketDataRestClient") RestClient stockMarketDataRestClient,
            StockMarketDataProperties stockMarketDataProperties
    ) {
        this.stockMarketDataRestClient = stockMarketDataRestClient;
        this.stockMarketDataProperties = stockMarketDataProperties;
    }

    /**
     * Fetches the latest quote for one ticker.
     *
     * @param ticker stock ticker
     * @return normalized quote response
     */
    public AlphaVantageQuoteResponse fetchQuote(String ticker) {
        Map<String, Object> body = executeFunction(FUNCTION_GLOBAL_QUOTE, ticker);
        Map<String, Object> quoteNode = readRequiredMap(body, "Global Quote");
        String symbol = readRequiredString(quoteNode, "01. symbol");
        BigDecimal price = readRequiredDecimal(quoteNode, "05. price");

        validateExpectedSymbol(symbol, ticker, "quote");
        return new AlphaVantageQuoteResponse(
                symbol,
                price,
                readOptionalDecimal(quoteNode, "11. ask").orElse(price),
                readOptionalDecimal(quoteNode, "12. bid").orElse(price),
                readRequiredDecimal(quoteNode, "09. change"),
                readRequiredLong(quoteNode, "06. volume"),
                readRequiredLocalDate(quoteNode, "07. latest trading day")
        );
    }

    /**
     * Fetches the recent daily time series for one ticker.
     *
     * @param ticker stock ticker
     * @return normalized daily response with parsed time-series values
     */
    public AlphaVantageDailyResponse fetchDaily(String ticker) {
        Map<String, Object> body = executeFunction(FUNCTION_TIME_SERIES_DAILY, ticker, "outputsize", "compact");
        Map<String, Object> metaDataNode = readRequiredMap(body, "Meta Data");
        Map<String, Object> timeSeriesNode = readRequiredMap(body, "Time Series (Daily)");
        String symbol = readRequiredString(metaDataNode, "2. Symbol");

        validateExpectedSymbol(symbol, ticker, "daily");

        List<AlphaVantageDailyValue> values = timeSeriesNode.entrySet()
                .stream()
                .map(entry -> toDailyValue(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(AlphaVantageDailyValue::date).reversed())
                .toList();

        if (values.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Alpha Vantage daily response contains no values.");
        }

        return new AlphaVantageDailyResponse(symbol, values);
    }

    /**
     * Fetches company overview data for one ticker.
     *
     * @param ticker stock ticker
     * @return normalized company overview response
     */
    public AlphaVantageCompanyOverviewResponse fetchCompanyOverview(String ticker) {
        Map<String, Object> body = executeFunction(FUNCTION_OVERVIEW, ticker);
        String symbol = readRequiredString(body, "Symbol");

        validateExpectedSymbol(symbol, ticker, "overview");

        return new AlphaVantageCompanyOverviewResponse(
                symbol,
                readOptionalString(body, "Name").orElse(null),
                readOptionalLong(body, "SharesOutstanding").orElse(null),
                readOptionalDecimal(body, "DividendYield").orElse(null)
        );
    }

    /**
     * Fetches the latest exchange rate for one ordered currency pair.
     *
     * @param baseCurrency base currency code
     * @param quoteCurrency quote currency code
     * @return normalized FX exchange-rate response
     */
    public AlphaVantageForexExchangeRateResponse fetchExchangeRate(String baseCurrency, String quoteCurrency) {
        Map<String, Object> body = executeCurrencyExchangeRate(baseCurrency, quoteCurrency);
        Map<String, Object> rateNode = readRequiredMap(body, "Realtime Currency Exchange Rate");
        String providerBaseCurrency = readRequiredString(rateNode, "1. From_Currency Code");
        String providerQuoteCurrency = readRequiredString(rateNode, "3. To_Currency Code");

        validateExpectedCurrency(providerBaseCurrency, baseCurrency, "FX base currency");
        validateExpectedCurrency(providerQuoteCurrency, quoteCurrency, "FX quote currency");

        return new AlphaVantageForexExchangeRateResponse(
                providerBaseCurrency,
                providerQuoteCurrency,
                readRequiredDecimal(rateNode, "5. Exchange Rate"),
                readRequiredLocalDateTime(rateNode, "6. Last Refreshed")
        );
    }

    private Map<String, Object> executeFunction(String function, String ticker, String... extraQueryParamPairs) {
        String apiKey = requireApiKey();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = stockMarketDataRestClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path(QUERY_PATH)
                                .queryParam("function", function)
                                .queryParam("symbol", ticker)
                                .queryParam("apikey", apiKey);

                        for (int index = 0; index < extraQueryParamPairs.length; index += 2) {
                            uriBuilder.queryParam(extraQueryParamPairs[index], extraQueryParamPairs[index + 1]);
                        }

                        return uriBuilder.build();
                    })
                    .retrieve()
                    .body(Map.class);

            validateStandardErrorPayload(body);
            return Objects.requireNonNull(body, "body must not be null");
        } catch (ResourceAccessException exception) {
            throw new ResponseStatusException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "Alpha Vantage request timed out.",
                    exception
            );
        } catch (RestClientException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Failed to connect to Alpha Vantage.",
                    exception
            );
        }
    }

    private Map<String, Object> executeCurrencyExchangeRate(String baseCurrency, String quoteCurrency) {
        String apiKey = requireApiKey();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = stockMarketDataRestClient.get()
                    .uri(uriBuilder -> uriBuilder.path(QUERY_PATH)
                            .queryParam("function", FUNCTION_CURRENCY_EXCHANGE_RATE)
                            .queryParam("from_currency", baseCurrency)
                            .queryParam("to_currency", quoteCurrency)
                            .queryParam("apikey", apiKey)
                            .build())
                    .retrieve()
                    .body(Map.class);

            validateStandardErrorPayload(body);
            return Objects.requireNonNull(body, "body must not be null");
        } catch (ResourceAccessException exception) {
            throw new ResponseStatusException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "Alpha Vantage request timed out.",
                    exception
            );
        } catch (RestClientException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Failed to connect to Alpha Vantage.",
                    exception
            );
        }
    }

    private AlphaVantageDailyValue toDailyValue(Object dateKey, Object rawValue) {
        Map<String, Object> dailyNode = castToMap(rawValue, "Time Series (Daily) entry");
        LocalDate date;

        try {
            date = LocalDate.parse(String.valueOf(dateKey));
        } catch (DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Alpha Vantage daily response contains invalid dates.");
        }

        return new AlphaVantageDailyValue(
                date,
                readRequiredDecimal(dailyNode, "4. close"),
                readRequiredLong(dailyNode, "5. volume")
        );
    }

    private void validateStandardErrorPayload(Map<String, Object> body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Alpha Vantage returned no response.");
        }

        String providerMessage = readProviderMessage(body);
        if (providerMessage != null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Alpha Vantage returned an error: " + providerMessage
            );
        }
    }

    private String readProviderMessage(Map<String, Object> body) {
        for (String key : List.of("Error Message", "Note", "Information")) {
            Object value = body.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private void validateExpectedSymbol(String actualSymbol, String expectedTicker, String endpointName) {
        if (!expectedTicker.equalsIgnoreCase(actualSymbol)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Alpha Vantage returned unexpected symbol %s for %s endpoint.".formatted(actualSymbol, endpointName)
            );
        }
    }

    private void validateExpectedCurrency(String actualCurrency, String expectedCurrency, String fieldName) {
        if (!expectedCurrency.equalsIgnoreCase(actualCurrency)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Alpha Vantage returned unexpected value %s for %s.".formatted(actualCurrency, fieldName)
            );
        }
    }

    private String requireApiKey() {
        String apiKey = stockMarketDataProperties.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Stock market data API key is not configured.");
        }
        return apiKey;
    }

    private Map<String, Object> readRequiredMap(Map<String, Object> node, String fieldName) {
        Object field = node.get(fieldName);
        if (field == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Alpha Vantage response missing object field " + fieldName + "."
            );
        }
        return castToMap(field, fieldName);
    }

    private Map<String, Object> castToMap(Object rawValue, String fieldName) {
        if (rawValue instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typedMap = (Map<String, Object>) rawMap;
            return typedMap;
        }

        throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Alpha Vantage response field " + fieldName + " is not an object."
        );
    }

    private String readRequiredString(Map<String, Object> node, String fieldName) {
        return readOptionalString(node, fieldName)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Alpha Vantage response missing field " + fieldName + "."
                ));
    }

    private Optional<String> readOptionalString(Map<String, Object> node, String fieldName) {
        Object field = node.get(fieldName);
        if (field == null) {
            return Optional.empty();
        }

        String value = String.valueOf(field).trim();
        if (value.isBlank() || "None".equalsIgnoreCase(value) || "null".equalsIgnoreCase(value)) {
            return Optional.empty();
        }

        return Optional.of(value);
    }

    private BigDecimal readRequiredDecimal(Map<String, Object> node, String fieldName) {
        return readOptionalDecimal(node, fieldName)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Alpha Vantage response missing decimal field " + fieldName + "."
                ));
    }

    private Optional<BigDecimal> readOptionalDecimal(Map<String, Object> node, String fieldName) {
        return readOptionalString(node, fieldName).map(value -> {
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException exception) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Alpha Vantage response contains invalid decimal field " + fieldName + "."
                );
            }
        });
    }

    private Long readRequiredLong(Map<String, Object> node, String fieldName) {
        return readOptionalLong(node, fieldName)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Alpha Vantage response missing integer field " + fieldName + "."
                ));
    }

    private Optional<Long> readOptionalLong(Map<String, Object> node, String fieldName) {
        return readOptionalString(node, fieldName).map(value -> {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException exception) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Alpha Vantage response contains invalid integer field " + fieldName + "."
                );
            }
        });
    }

    private LocalDate readRequiredLocalDate(Map<String, Object> node, String fieldName) {
        String value = readRequiredString(node, fieldName);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Alpha Vantage response contains invalid date field " + fieldName + "."
            );
        }
    }

    private LocalDateTime readRequiredLocalDateTime(Map<String, Object> node, String fieldName) {
        String value = readRequiredString(node, fieldName);

        try {
            return LocalDateTime.parse(value.replace(" ", "T"));
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(value).atStartOfDay();
            } catch (DateTimeParseException exception) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Alpha Vantage response contains invalid datetime field " + fieldName + "."
                );
            }
        }
    }
}
