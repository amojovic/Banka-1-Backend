package com.banka1.stock_service.client;

import com.banka1.stock_service.config.StockMarketDataProperties;
import com.banka1.stock_service.dto.AlphaVantageCompanyOverviewResponse;
import com.banka1.stock_service.dto.AlphaVantageDailyResponse;
import com.banka1.stock_service.dto.AlphaVantageForexExchangeRateResponse;
import com.banka1.stock_service.dto.AlphaVantageQuoteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link AlphaVantageClient}.
 */
class AlphaVantageClientTest {

    private MockRestServiceServer server;
    private AlphaVantageClient alphaVantageClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        RestClient restClient = builder
                .baseUrl("https://www.alphavantage.co")
                .build();

        alphaVantageClient = new AlphaVantageClient(
                restClient,
                new StockMarketDataProperties(
                        "https://www.alphavantage.co",
                        "demo-key",
                        30
                )
        );
    }

    @Test
    void fetchQuoteParsesSuccessfulResponseAndFallsBackToPriceForMissingBidAsk() {
        server.expect(requestTo(containsString("/query?function=GLOBAL_QUOTE&symbol=AAPL&apikey=demo-key")))
                .andRespond(withSuccess("""
                        {
                          "Global Quote": {
                            "01. symbol": "AAPL",
                            "05. price": "212.40000000",
                            "06. volume": "25000",
                            "07. latest trading day": "2026-04-08",
                            "09. change": "4.60000000"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        AlphaVantageQuoteResponse response = alphaVantageClient.fetchQuote("AAPL");

        assertThat(response.symbol()).isEqualTo("AAPL");
        assertThat(response.price()).isEqualByComparingTo(new BigDecimal("212.40000000"));
        assertThat(response.ask()).isEqualByComparingTo(new BigDecimal("212.40000000"));
        assertThat(response.bid()).isEqualByComparingTo(new BigDecimal("212.40000000"));
        assertThat(response.change()).isEqualByComparingTo(new BigDecimal("4.60000000"));
        assertThat(response.volume()).isEqualTo(25_000L);
        assertThat(response.latestTradingDay()).hasToString("2026-04-08");
    }

    @Test
    void fetchDailyParsesAndOrdersTimeSeriesResponse() {
        server.expect(requestTo(containsString("/query?function=TIME_SERIES_DAILY&symbol=AAPL&apikey=demo-key&outputsize=compact")))
                .andRespond(withSuccess("""
                        {
                          "Meta Data": {
                            "2. Symbol": "AAPL"
                          },
                          "Time Series (Daily)": {
                            "2026-04-07": {
                              "4. close": "208.10000000",
                              "5. volume": "19500"
                            },
                            "2026-04-08": {
                              "4. close": "212.40000000",
                              "5. volume": "25000"
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        AlphaVantageDailyResponse response = alphaVantageClient.fetchDaily("AAPL");

        assertThat(response.symbol()).isEqualTo("AAPL");
        assertThat(response.values()).hasSize(2);
        assertThat(response.values().get(0).date()).hasToString("2026-04-08");
        assertThat(response.values().get(0).closePrice()).isEqualByComparingTo(new BigDecimal("212.40000000"));
        assertThat(response.values().get(1).date()).hasToString("2026-04-07");
    }

    @Test
    void fetchCompanyOverviewParsesOptionalFundamentals() {
        server.expect(requestTo(containsString("/query?function=OVERVIEW&symbol=AAPL&apikey=demo-key")))
                .andRespond(withSuccess("""
                        {
                          "Symbol": "AAPL",
                          "Name": "Apple Inc.",
                          "SharesOutstanding": "15550061000",
                          "DividendYield": "0.0044"
                        }
                        """, MediaType.APPLICATION_JSON));

        AlphaVantageCompanyOverviewResponse response = alphaVantageClient.fetchCompanyOverview("AAPL");

        assertThat(response.symbol()).isEqualTo("AAPL");
        assertThat(response.name()).isEqualTo("Apple Inc.");
        assertThat(response.sharesOutstanding()).isEqualTo(15_550_061_000L);
        assertThat(response.dividendYield()).isEqualByComparingTo(new BigDecimal("0.0044"));
    }

    @Test
    void fetchExchangeRateParsesSuccessfulResponse() {
        server.expect(requestTo(containsString(
                        "/query?function=CURRENCY_EXCHANGE_RATE&from_currency=EUR&to_currency=USD&apikey=demo-key"
                )))
                .andRespond(withSuccess("""
                        {
                          "Realtime Currency Exchange Rate": {
                            "1. From_Currency Code": "EUR",
                            "3. To_Currency Code": "USD",
                            "5. Exchange Rate": "1.08350000",
                            "6. Last Refreshed": "2026-04-08 10:15:00"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        AlphaVantageForexExchangeRateResponse response = alphaVantageClient.fetchExchangeRate("EUR", "USD");

        assertThat(response.baseCurrency()).isEqualTo("EUR");
        assertThat(response.quoteCurrency()).isEqualTo("USD");
        assertThat(response.exchangeRate()).isEqualByComparingTo(new BigDecimal("1.08350000"));
        assertThat(response.lastRefreshed()).hasToString("2026-04-08T10:15");
    }

    @Test
    void fetchQuoteThrowsBadGatewayWhenProviderReturnsErrorPayload() {
        server.expect(requestTo(containsString("/query?function=GLOBAL_QUOTE&symbol=AAPL&apikey=demo-key")))
                .andRespond(withSuccess("""
                        {
                          "Note": "API rate limit exceeded"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> alphaVantageClient.fetchQuote("AAPL"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void fetchQuoteThrowsGatewayTimeoutWhenProviderCallTimesOut() {
        server.expect(requestTo(containsString("/query?function=GLOBAL_QUOTE&symbol=AAPL&apikey=demo-key")))
                .andRespond(request -> {
                    throw new ResourceAccessException("timeout");
                });

        assertThatThrownBy(() -> alphaVantageClient.fetchQuote("AAPL"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.GATEWAY_TIMEOUT));
    }
}
