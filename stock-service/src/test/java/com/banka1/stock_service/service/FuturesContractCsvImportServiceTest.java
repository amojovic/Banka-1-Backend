package com.banka1.stock_service.service;

import com.banka1.stock_service.domain.FuturesContract;
import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingDailyPriceInfo;
import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.domain.StockExchange;
import com.banka1.stock_service.dto.FuturesContractImportResponse;
import com.banka1.stock_service.repository.FuturesContractRepository;
import com.banka1.stock_service.repository.ListingDailyPriceInfoRepository;
import com.banka1.stock_service.repository.ListingRepository;
import com.banka1.stock_service.repository.StockExchangeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link FuturesContractCsvImportService}.
 *
 * <p>The importer now populates futures contracts together with the linked
 * listing snapshot and one daily historical entry, so the tests validate
 * the full persistence flow against the in-memory test database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FuturesContractCsvImportServiceTest {

    @Autowired
    private FuturesContractCsvImportService futuresContractCsvImportService;

    @Autowired
    private FuturesContractRepository futuresContractRepository;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private ListingDailyPriceInfoRepository listingDailyPriceInfoRepository;

    @Autowired
    private StockExchangeRepository stockExchangeRepository;

    @Test
    void importFromResourceCreatesContractsListingsAndDailySnapshotsFromCsv() {
        saveExchange("Chicago Mercantile Exchange", "CME", "XCME");
        saveExchange("London Metal Exchange", "LME", "XLME");

        FuturesContractImportResponse response = futuresContractCsvImportService.importFromResource(
                csvResource("""
                        contract_name,contract_size,contract_unit,maintenance_margin,type
                        corn,5000,bushel,1600,AGRICULTURE
                        copper mini,12500,pound,1500,METALS
                        """),
                "test-futures.csv"
        );

        FuturesContract cornContract = futuresContractRepository.findByTicker("CORNAGRICULTURE").orElseThrow();
        Listing cornListing = listingRepository
                .findByListingTypeAndSecurityId(ListingType.FUTURES, cornContract.getId())
                .orElseThrow();
        ListingDailyPriceInfo cornDaily = listingDailyPriceInfoRepository
                .findByListingIdAndDate(cornListing.getId(), LocalDate.of(2026, 4, 8))
                .orElseThrow();

        FuturesContract copperMiniContract = futuresContractRepository.findByTicker("COPPERMINIMETALS").orElseThrow();
        Listing copperMiniListing = listingRepository
                .findByListingTypeAndSecurityId(ListingType.FUTURES, copperMiniContract.getId())
                .orElseThrow();

        assertThat(response.processedRows()).isEqualTo(2);
        assertThat(response.createdCount()).isEqualTo(2);
        assertThat(response.updatedCount()).isZero();
        assertThat(response.unchangedCount()).isZero();

        assertThat(cornContract.getName()).isEqualTo("Corn");
        assertThat(cornContract.getContractSize()).isEqualTo(5_000);
        assertThat(cornContract.getContractUnit()).isEqualTo("Bushel");
        assertThat(cornContract.getSettlementDate()).isEqualTo(LocalDate.of(2026, 6, 15));

        assertThat(cornListing.getTicker()).isEqualTo("CORNAGRICULTURE");
        assertThat(cornListing.getStockExchange().getExchangeMICCode()).isEqualTo("XCME");
        assertThat(cornListing.getPrice()).isEqualByComparingTo("3.20000000");
        assertThat(cornListing.getAsk()).isEqualByComparingTo("3.23200000");
        assertThat(cornListing.getBid()).isEqualByComparingTo("3.16800000");
        assertThat(cornListing.getChange()).isEqualByComparingTo("0.04800000");
        assertThat(cornListing.getVolume()).isEqualTo(1_650L);

        assertThat(cornDaily.getPrice()).isEqualByComparingTo("3.20000000");
        assertThat(cornDaily.getAsk()).isEqualByComparingTo("3.23200000");
        assertThat(cornDaily.getBid()).isEqualByComparingTo("3.16800000");
        assertThat(cornDaily.getChange()).isEqualByComparingTo("0.04800000");
        assertThat(cornDaily.getVolume()).isEqualTo(1_650L);

        assertThat(copperMiniContract.getContractUnit()).isEqualTo("Pound");
        assertThat(copperMiniListing.getStockExchange().getExchangeMICCode()).isEqualTo("XLME");
    }

    @Test
    void importFromConfiguredCsvLoadsDummySeedFileFromResources() {
        saveExchange("Chicago Mercantile Exchange", "CME", "XCME");
        saveExchange("London Metal Exchange", "LME", "XLME");

        FuturesContractImportResponse response = futuresContractCsvImportService.importFromConfiguredCsv();

        assertThat(response.source()).isEqualTo("classpath:seed/future_data.csv");
        assertThat(response.processedRows()).isEqualTo(52);
        assertThat(response.createdCount()).isEqualTo(52);
        assertThat(response.updatedCount()).isZero();
        assertThat(response.unchangedCount()).isZero();
        assertThat(futuresContractRepository.count()).isEqualTo(52);
        assertThat(listingRepository.count()).isEqualTo(52);
        assertThat(listingDailyPriceInfoRepository.count()).isEqualTo(52);

        FuturesContract crudeOil = futuresContractRepository.findByTicker("CRUDEOILENERGY").orElseThrow();
        assertThat(crudeOil.getContractSize()).isEqualTo(1_000);
        assertThat(crudeOil.getContractUnit()).isEqualTo("Barrel");
        assertThat(crudeOil.getSettlementDate()).isNotNull();
    }

    @Test
    void importFromResourceMarksRowsAsUnchangedOnRepeatedImport() {
        saveExchange("Chicago Mercantile Exchange", "CME", "XCME");
        saveExchange("London Metal Exchange", "LME", "XLME");

        ByteArrayResource resource = csvResource("""
                contract_name,contract_size,contract_unit,maintenance_margin,type
                corn,5000,bushel,1600,AGRICULTURE
                copper mini,12500,pound,1500,METALS
                """);

        futuresContractCsvImportService.importFromResource(resource, "test-futures.csv");
        FuturesContractImportResponse secondImport = futuresContractCsvImportService.importFromResource(
                resource,
                "test-futures.csv"
        );

        assertThat(secondImport.processedRows()).isEqualTo(2);
        assertThat(secondImport.createdCount()).isZero();
        assertThat(secondImport.updatedCount()).isZero();
        assertThat(secondImport.unchangedCount()).isEqualTo(2);
        assertThat(futuresContractRepository.count()).isEqualTo(2);
        assertThat(listingRepository.count()).isEqualTo(2);
        assertThat(listingDailyPriceInfoRepository.count()).isEqualTo(2);
    }

    @Test
    void importFromResourceUpdatesListingsWhenMaintenanceMarginChanges() {
        saveExchange("Chicago Mercantile Exchange", "CME", "XCME");

        futuresContractCsvImportService.importFromResource(
                csvResource("""
                        contract_name,contract_size,contract_unit,maintenance_margin,type
                        corn,5000,bushel,1600,AGRICULTURE
                        """),
                "test-futures.csv"
        );

        FuturesContractImportResponse response = futuresContractCsvImportService.importFromResource(
                csvResource("""
                        contract_name,contract_size,contract_unit,maintenance_margin,type
                        corn,5000,bushel,1800,AGRICULTURE
                        """),
                "test-futures.csv"
        );

        FuturesContract cornContract = futuresContractRepository.findByTicker("CORNAGRICULTURE").orElseThrow();
        Listing cornListing = listingRepository
                .findByListingTypeAndSecurityId(ListingType.FUTURES, cornContract.getId())
                .orElseThrow();
        ListingDailyPriceInfo cornDaily = listingDailyPriceInfoRepository
                .findByListingIdAndDate(cornListing.getId(), LocalDate.of(2026, 4, 8))
                .orElseThrow();

        assertThat(response.createdCount()).isZero();
        assertThat(response.updatedCount()).isEqualTo(1);
        assertThat(response.unchangedCount()).isZero();
        assertThat(cornListing.getPrice()).isEqualByComparingTo("3.60000000");
        assertThat(cornDaily.getPrice()).isEqualByComparingTo("3.60000000");
    }

    @Test
    void importFromResourceRejectsDuplicateGeneratedTickersInsideCsv() {
        assertThatThrownBy(() -> futuresContractCsvImportService.importFromResource(
                csvResource("""
                        contract_name,contract_size,contract_unit,maintenance_margin,type
                        corn,5000,bushel,1600,AGRICULTURE
                        c-o-rn,5000,bushel,1700,AGRICULTURE
                        """),
                "test-futures.csv"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate futures ticker 'CORNAGRICULTURE'");
    }

    /**
     * Persists one exchange needed by the futures dummy import flow.
     *
     * @param exchangeName full exchange name
     * @param acronym short exchange acronym
     * @param micCode exchange MIC code
     */
    private void saveExchange(String exchangeName, String acronym, String micCode) {
        StockExchange exchange = new StockExchange();
        exchange.setExchangeName(exchangeName);
        exchange.setExchangeAcronym(acronym);
        exchange.setExchangeMICCode(micCode);
        exchange.setPolity("United States");
        exchange.setCurrency("USD");
        exchange.setTimeZone("America/New_York");
        exchange.setOpenTime(LocalTime.of(9, 30));
        exchange.setCloseTime(LocalTime.of(16, 0));
        exchange.setIsActive(true);
        stockExchangeRepository.saveAndFlush(exchange);
    }

    /**
     * Creates a UTF-8 in-memory CSV resource for the importer tests.
     *
     * @param content CSV content
     * @return CSV resource
     */
    private ByteArrayResource csvResource(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }
}
