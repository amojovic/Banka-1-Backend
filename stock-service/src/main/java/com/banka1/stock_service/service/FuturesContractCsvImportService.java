package com.banka1.stock_service.service;

import com.banka1.stock_service.config.FuturesContractSeedProperties;
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
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Imports futures dummy data from {@code future_data.csv} and upserts it into the database.
 *
 * <p>Each CSV row is converted into one {@link FuturesContract}, one linked {@link Listing},
 * and one {@link ListingDailyPriceInfo} snapshot for the dummy trading day.
 *
 * <p>The import is idempotent and keyed by the generated contract ticker.
 *
 * <ul>
 *     <li>if at least one required record is missing, the row is counted as created</li>
 *     <li>if all records exist but at least one imported value changed, the row is counted as updated</li>
 *     <li>if all imported values already match, the row is counted as unchanged</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class FuturesContractCsvImportService {

    private static final List<String> REQUIRED_HEADERS = List.of(
            "contract_name",
            "contract_size",
            "contract_unit",
            "maintenance_margin",
            "type"
    );
    private static final int MONEY_SCALE = 8;
    private static final BigDecimal TEN = new BigDecimal("10");
    private static final BigDecimal ONE_PERCENT = new BigDecimal("0.01");
    private static final BigDecimal CHANGE_RATE = new BigDecimal("0.015");
    private static final BigDecimal MIN_MONETARY_VALUE = new BigDecimal("0.00000001");
    private static final LocalDate BASE_SETTLEMENT_DATE = LocalDate.of(2026, 6, 15);
    private static final LocalDate DUMMY_PRICE_DATE = LocalDate.of(2026, 4, 8);
    private static final LocalDateTime DUMMY_LAST_REFRESH = LocalDateTime.of(2026, 4, 8, 12, 0);
    private static final String DEFAULT_FUTURES_EXCHANGE_MIC = "XCME";
    private static final String METALS_EXCHANGE_MIC = "XLME";

    private final FuturesContractRepository futuresContractRepository;
    private final ListingRepository listingRepository;
    private final ListingDailyPriceInfoRepository listingDailyPriceInfoRepository;
    private final StockExchangeRepository stockExchangeRepository;
    private final FuturesContractSeedProperties futuresContractSeedProperties;
    private final ResourceLoader resourceLoader;

    /**
     * Imports the configured CSV source from application properties.
     *
     * <p>This is the entry point used by the startup seed flow.
     *
     * @return import summary with created, updated, and unchanged counters
     */
    @Transactional
    public FuturesContractImportResponse importFromConfiguredCsv() {
        return importFromLocation(futuresContractSeedProperties.csvLocation());
    }

    /**
     * Imports futures contracts from the provided Spring resource location.
     *
     * <p>Example locations:
     *
     * <ul>
     *     <li>{@code classpath:seed/future_data.csv}</li>
     *     <li>{@code file:./custom/future_data.csv}</li>
     * </ul>
     *
     * @param csvLocation Spring resource location, for example {@code classpath:seed/future_data.csv}
     * @return import summary
     */
    @Transactional
    public FuturesContractImportResponse importFromLocation(String csvLocation) {
        Resource resource = resourceLoader.getResource(csvLocation);
        return importFromResource(resource, csvLocation);
    }

    /**
     * Imports futures contracts from the provided resource.
     *
     * <p>This method intentionally splits the process into two steps:
     *
     * <ol>
     *     <li>parse and validate CSV rows into an intermediate row model</li>
     *     <li>persist those rows as create/update/unchanged database operations</li>
     * </ol>
     *
     * @param resource CSV resource
     * @param source source label used in the response
     * @return import summary
     */
    @Transactional
    public FuturesContractImportResponse importFromResource(Resource resource, String source) {
        List<FuturesContractCsvRow> rows = parseCsv(resource, source);
        return persistRows(rows, source);
    }

    /**
     * Persists parsed CSV rows into the database using ticker as the stable business key.
     *
     * @param rows validated parsed rows
     * @param source human-readable source label
     * @return import summary
     */
    private FuturesContractImportResponse persistRows(List<FuturesContractCsvRow> rows, String source) {
        Map<String, StockExchange> exchangesByMic = loadStockExchangesByMic();
        StockExchange fallbackExchange = resolveFallbackExchange(exchangesByMic);

        int createdCount = 0;
        int updatedCount = 0;
        int unchangedCount = 0;

        for (FuturesContractCsvRow row : rows) {
            boolean rowCreated = false;
            boolean rowUpdated = false;

            FuturesContract contract = futuresContractRepository.findByTicker(row.ticker()).orElse(null);
            if (contract == null) {
                contract = new FuturesContract();
                applyContractRow(contract, row);
                contract = futuresContractRepository.saveAndFlush(contract);
                rowCreated = true;
            } else if (applyContractRowIfChanged(contract, row)) {
                futuresContractRepository.save(contract);
                rowUpdated = true;
            }

            StockExchange stockExchange = resolveStockExchange(row, exchangesByMic, fallbackExchange);
            Listing listing = listingRepository.findByListingTypeAndSecurityId(ListingType.FUTURES, contract.getId())
                    .orElse(null);
            if (listing == null) {
                listing = new Listing();
                applyListingRow(listing, contract, stockExchange, row);
                listing = listingRepository.saveAndFlush(listing);
                rowCreated = true;
            } else if (applyListingRowIfChanged(listing, contract, stockExchange, row)) {
                listingRepository.save(listing);
                rowUpdated = true;
            }

            ListingDailyPriceInfo dailyPriceInfo = listingDailyPriceInfoRepository
                    .findByListingIdAndDate(listing.getId(), row.listingDate())
                    .orElse(null);
            if (dailyPriceInfo == null) {
                dailyPriceInfo = new ListingDailyPriceInfo();
                applyDailyPriceRow(dailyPriceInfo, listing, row);
                listingDailyPriceInfoRepository.save(dailyPriceInfo);
                rowCreated = true;
            } else if (applyDailyPriceRowIfChanged(dailyPriceInfo, listing, row)) {
                listingDailyPriceInfoRepository.save(dailyPriceInfo);
                rowUpdated = true;
            }

            if (rowCreated) {
                createdCount++;
            } else if (rowUpdated) {
                updatedCount++;
            } else {
                unchangedCount++;
            }
        }

        return new FuturesContractImportResponse(
                source,
                rows.size(),
                createdCount,
                updatedCount,
                unchangedCount
        );
    }

    /**
     * Reads and validates a CSV resource and converts it into intermediate row objects.
     *
     * <p>Validation performed here includes:
     *
     * <ul>
     *     <li>resource existence</li>
     *     <li>non-empty header row</li>
     *     <li>presence of all required {@code future_data.csv} headers</li>
     *     <li>consistent column count per row</li>
     *     <li>duplicate generated ticker detection inside the same CSV file</li>
     *     <li>positive integer parsing for {@code Contract Size}</li>
     *     <li>positive decimal parsing for {@code maintenance_margin}</li>
     *     <li>non-blank contract-unit and type validation</li>
     * </ul>
     *
     * <p>The parser keeps row numbers in error messages so invalid files are easy to debug.
     *
     * @param resource CSV resource to read
     * @param source human-readable source label used in error messages
     * @return parsed CSV rows ready for persistence
     */
    private List<FuturesContractCsvRow> parseCsv(Resource resource, String source) {
        if (!resource.exists()) {
            throw new IllegalStateException("Futures contract CSV resource does not exist: " + source);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)
        )) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                throw new IllegalArgumentException("Futures contract CSV is empty: " + source);
            }

            List<String> headerValues = parseCsvLine(headerLine, 1, source);
            Map<String, Integer> headerIndexes = indexHeaders(headerValues, source);
            validateHeaders(headerIndexes, source);

            List<FuturesContractCsvRow> rows = new ArrayList<>();
            Set<String> tickers = new HashSet<>();
            int lineNumber = 1;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }

                List<String> values = parseCsvLine(line, lineNumber, source);
                if (values.stream().allMatch(String::isBlank)) {
                    continue;
                }

                if (values.size() != headerValues.size()) {
                    throw new IllegalArgumentException(
                            "CSV row " + lineNumber + " in " + source + " has " + values.size()
                                    + " columns, expected " + headerValues.size()
                    );
                }

                FuturesContractCsvRow row = mapRow(values, headerIndexes, lineNumber, source);
                if (!tickers.add(row.ticker())) {
                    throw new IllegalArgumentException(
                            "Duplicate futures ticker '" + row.ticker() + "' found in " + source
                                    + " on row " + lineNumber
                    );
                }
                rows.add(row);
            }

            return rows;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read futures contract CSV resource: " + source, exception);
        }
    }

    /**
     * Builds a header-name to column-index map from the first CSV row.
     *
     * <p>Headers are trimmed before indexing.
     * Duplicate header names are rejected because they would make column resolution ambiguous.
     *
     * @param headers parsed header row values
     * @param source source label used in error messages
     * @return header-index map
     */
    private Map<String, Integer> indexHeaders(List<String> headers, String source) {
        Map<String, Integer> headerIndexes = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String normalizedHeader = headers.get(i).trim();
            if (headerIndexes.putIfAbsent(normalizedHeader, i) != null) {
                throw new IllegalArgumentException("Duplicate CSV header '" + normalizedHeader + "' in " + source);
            }
        }
        return headerIndexes;
    }

    /**
     * Validates that all required business columns from {@code future_data.csv} exist.
     *
     * @param headerIndexes indexed CSV headers
     * @param source source label used in error messages
     */
    private void validateHeaders(Map<String, Integer> headerIndexes, String source) {
        for (String requiredHeader : REQUIRED_HEADERS) {
            if (!headerIndexes.containsKey(requiredHeader)) {
                throw new IllegalArgumentException(
                        "Missing required CSV header '" + requiredHeader + "' in " + source
                );
            }
        }
    }

    /**
     * Converts one parsed CSV row into the intermediate row record used by the importer.
     *
     * <p>The resulting record is fully validated and already contains the generated dummy market values
     * used for the linked listing and daily snapshot.
     *
     * @param values row values
     * @param headerIndexes indexed CSV headers
     * @param lineNumber current CSV row number for error reporting
     * @param source source label used in error messages
     * @return parsed row model
     */
    private FuturesContractCsvRow mapRow(
            List<String> values,
            Map<String, Integer> headerIndexes,
            int lineNumber,
            String source
    ) {
        String contractName = requiredValue(values, headerIndexes, "contract_name", lineNumber, source);
        int contractSize = parseContractSize(
                requiredValue(values, headerIndexes, "contract_size", lineNumber, source),
                lineNumber,
                source
        );
        String contractUnit = parseContractUnit(
                requiredValue(values, headerIndexes, "contract_unit", lineNumber, source),
                lineNumber,
                source
        );
        BigDecimal maintenanceMargin = parseMaintenanceMargin(
                requiredValue(values, headerIndexes, "maintenance_margin", lineNumber, source),
                lineNumber,
                source
        );
        String futuresType = parseFuturesType(
                requiredValue(values, headerIndexes, "type", lineNumber, source),
                lineNumber,
                source
        );
        String name = toDisplayLabel(contractName);
        String ticker = generateTicker(contractName, futuresType, lineNumber, source);
        LocalDate settlementDate = BASE_SETTLEMENT_DATE.plusWeeks(lineNumber - 2L);
        BigDecimal price = derivePrice(maintenanceMargin, contractSize);
        BigDecimal spread = deriveSpread(price);
        BigDecimal ask = price.add(spread).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal bid = normalizeMonetaryValue(price.subtract(spread));
        BigDecimal change = normalizeMonetaryValue(price.multiply(CHANGE_RATE));
        long volume = deriveVolume(maintenanceMargin, contractSize);

        return new FuturesContractCsvRow(
                ticker,
                name,
                contractSize,
                contractUnit,
                settlementDate,
                futuresType,
                price,
                ask,
                bid,
                change,
                volume,
                DUMMY_PRICE_DATE,
                DUMMY_LAST_REFRESH
        );
    }

    /**
     * Resolves a required CSV value by exact header name.
     *
     * <p>If the column does not exist or the resolved value is blank, the file is rejected.
     *
     * @param values current row values
     * @param headerIndexes indexed CSV headers
     * @param header exact header name
     * @param lineNumber current row number for error reporting
     * @param source source label used in error messages
     * @return non-blank required value
     */
    private String requiredValue(
            List<String> values,
            Map<String, Integer> headerIndexes,
            String header,
            int lineNumber,
            String source
    ) {
        Integer index = headerIndexes.get(header);
        if (index == null || index >= values.size()) {
            throw new IllegalArgumentException("Missing required CSV header '" + header + "' in " + source);
        }

        String value = values.get(index).trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing value for column '" + header + "' on row " + lineNumber + " in " + source
            );
        }
        return value;
    }

    /**
     * Parses a required positive contract size.
     *
     * <p>Example valid values:
     *
     * <ul>
     *     <li>{@code 1000}</li>
     *     <li>{@code 5000}</li>
     * </ul>
     *
     * <p>Zero and negative values are rejected because a futures contract must represent
     * a positive amount of the underlying unit.
     *
     * @param rawValue raw CSV value
     * @param lineNumber row number used in error messages
     * @param source source label used in error messages
     * @return parsed positive contract size
     */
    private int parseContractSize(String rawValue, int lineNumber, String source) {
        try {
            int contractSize = Integer.parseInt(rawValue);
            if (contractSize <= 0) {
                throw new IllegalArgumentException(
                        "Contract Size must be positive on row " + lineNumber + " in " + source
                );
            }
            return contractSize;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Invalid contract size '" + rawValue + "' on row " + lineNumber + " in " + source,
                    exception
            );
        }
    }

    /**
     * Validates and normalizes the contract unit from the dummy CSV file.
     *
     * @param rawValue raw CSV value
     * @param lineNumber row number used in error messages
     * @param source source label used in error messages
     * @return validated display-ready contract unit
     */
    private String parseContractUnit(String rawValue, int lineNumber, String source) {
        String contractUnit = toDisplayLabel(rawValue);
        if (contractUnit.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid contract unit '" + rawValue + "' on row " + lineNumber + " in " + source
            );
        }
        if (contractUnit.length() > 32) {
            throw new IllegalArgumentException(
                    "Contract unit '" + contractUnit + "' on row " + lineNumber + " in " + source
                            + " exceeds 32 characters."
            );
        }
        return contractUnit;
    }

    /**
     * Parses a positive maintenance margin from the dummy CSV file.
     *
     * @param rawValue raw CSV value
     * @param lineNumber row number used in error messages
     * @param source source label used in error messages
     * @return parsed positive maintenance margin
     */
    private BigDecimal parseMaintenanceMargin(String rawValue, int lineNumber, String source) {
        try {
            BigDecimal maintenanceMargin = new BigDecimal(rawValue);
            if (maintenanceMargin.signum() <= 0) {
                throw new IllegalArgumentException(
                        "maintenance_margin must be positive on row " + lineNumber + " in " + source
                );
            }
            return maintenanceMargin;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Invalid maintenance_margin '" + rawValue + "' on row " + lineNumber + " in " + source,
                    exception
            );
        }
    }

    /**
     * Validates the futures type from the dummy CSV file.
     *
     * @param rawValue raw CSV value
     * @param lineNumber row number used in error messages
     * @param source source label used in error messages
     * @return normalized futures type
     */
    private String parseFuturesType(String rawValue, int lineNumber, String source) {
        String futuresType = rawValue.trim().toUpperCase(Locale.ROOT);
        if (futuresType.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing futures type on row " + lineNumber + " in " + source
            );
        }
        return futuresType;
    }

    /**
     * Parses one CSV line while respecting quoted values and escaped quotes.
     *
     * <p>Important behavior:
     *
     * <ul>
     *     <li>commas outside quotes split columns</li>
     *     <li>commas inside quotes are preserved as part of the value</li>
     *     <li>double quotes inside a quoted field are unescaped</li>
     *     <li>all returned values are trimmed</li>
     * </ul>
     *
     * <p>Example:
     * the line {@code "Brent, ICE",BRENTNOV26,1000,Barrel,2026-11-20}
     * is parsed so that {@code Brent, ICE} stays a single field.
     *
     * @param line raw CSV line
     * @param lineNumber current CSV row number for error reporting
     * @param source source label used in error messages
     * @return parsed row values
     */
    private List<String> parseCsvLine(String line, int lineNumber, String source) {
        List<String> values = new ArrayList<>();
        StringBuilder currentValue = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char currentCharacter = line.charAt(i);

            if (currentCharacter == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    currentValue.append('"');
                    i++;
                    continue;
                }

                inQuotes = !inQuotes;
                continue;
            }

            if (currentCharacter == ',' && !inQuotes) {
                values.add(currentValue.toString().trim());
                currentValue.setLength(0);
                continue;
            }

            currentValue.append(currentCharacter);
        }

        if (inQuotes) {
            throw new IllegalArgumentException("Unclosed quoted CSV value on row " + lineNumber + " in " + source);
        }

        values.add(currentValue.toString().trim());
        return values;
    }

    /**
     * Loads all currently persisted stock exchanges indexed by MIC code.
     *
     * @return MIC-indexed stock exchanges
     */
    private Map<String, StockExchange> loadStockExchangesByMic() {
        return stockExchangeRepository.findAllByOrderByExchangeNameAsc()
                .stream()
                .collect(
                        LinkedHashMap::new,
                        (map, exchange) -> map.put(exchange.getExchangeMICCode(), exchange),
                        Map::putAll
                );
    }

    /**
     * Resolves the fallback exchange used when a preferred MIC code is unavailable.
     *
     * @param exchangesByMic MIC-indexed stock exchanges
     * @return fallback exchange
     */
    private StockExchange resolveFallbackExchange(Map<String, StockExchange> exchangesByMic) {
        if (exchangesByMic.isEmpty()) {
            throw new IllegalStateException(
                    "At least one stock exchange must exist before futures dummy data can be imported."
            );
        }
        return exchangesByMic.values().stream().findFirst().orElseThrow();
    }

    /**
     * Resolves the preferred stock exchange for one futures row.
     *
     * <p>Most dummy futures are attached to {@code XCME}, while metal contracts prefer {@code XLME}.
     *
     * @param row parsed CSV row
     * @param exchangesByMic MIC-indexed stock exchanges
     * @param fallbackExchange fallback exchange used when the preferred MIC is unavailable
     * @return resolved stock exchange
     */
    private StockExchange resolveStockExchange(
            FuturesContractCsvRow row,
            Map<String, StockExchange> exchangesByMic,
            StockExchange fallbackExchange
    ) {
        return exchangesByMic.getOrDefault(row.preferredExchangeMic(), fallbackExchange);
    }

    /**
     * Copies parsed contract values into a persistent futures entity.
     *
     * @param entity target entity
     * @param row parsed CSV row
     */
    private void applyContractRow(FuturesContract entity, FuturesContractCsvRow row) {
        entity.setTicker(row.ticker());
        entity.setName(row.name());
        entity.setContractSize(row.contractSize());
        entity.setContractUnit(row.contractUnit());
        entity.setSettlementDate(row.settlementDate());
    }

    /**
     * Updates an existing futures entity only when at least one imported value changed.
     *
     * @param entity existing entity from the database
     * @param row parsed CSV row
     * @return {@code true} if the entity was changed and should be persisted
     */
    private boolean applyContractRowIfChanged(FuturesContract entity, FuturesContractCsvRow row) {
        if (contractMatches(entity, row)) {
            return false;
        }

        applyContractRow(entity, row);
        return true;
    }

    /**
     * Compares all imported contract fields between the existing entity and the parsed row.
     *
     * @param entity existing entity from the database
     * @param row parsed CSV row
     * @return {@code true} when all imported fields already match
     */
    private boolean contractMatches(FuturesContract entity, FuturesContractCsvRow row) {
        return Objects.equals(entity.getTicker(), row.ticker())
                && Objects.equals(entity.getName(), row.name())
                && Objects.equals(entity.getContractSize(), row.contractSize())
                && Objects.equals(entity.getContractUnit(), row.contractUnit())
                && Objects.equals(entity.getSettlementDate(), row.settlementDate());
    }

    /**
     * Copies parsed market data into a persistent listing entity.
     *
     * @param entity target listing
     * @param contract linked futures contract
     * @param stockExchange resolved stock exchange
     * @param row parsed CSV row
     */
    private void applyListingRow(
            Listing entity,
            FuturesContract contract,
            StockExchange stockExchange,
            FuturesContractCsvRow row
    ) {
        entity.setSecurityId(contract.getId());
        entity.setListingType(ListingType.FUTURES);
        entity.setStockExchange(stockExchange);
        entity.setTicker(row.ticker());
        entity.setName(row.name());
        entity.setLastRefresh(row.lastRefresh());
        entity.setPrice(row.price());
        entity.setAsk(row.ask());
        entity.setBid(row.bid());
        entity.setChange(row.change());
        entity.setVolume(row.volume());
    }

    /**
     * Updates a listing entity only when at least one imported value changed.
     *
     * @param entity existing listing
     * @param contract linked futures contract
     * @param stockExchange resolved stock exchange
     * @param row parsed CSV row
     * @return {@code true} if the listing changed
     */
    private boolean applyListingRowIfChanged(
            Listing entity,
            FuturesContract contract,
            StockExchange stockExchange,
            FuturesContractCsvRow row
    ) {
        if (listingMatches(entity, contract, stockExchange, row)) {
            return false;
        }

        applyListingRow(entity, contract, stockExchange, row);
        return true;
    }

    /**
     * Compares all imported listing fields between the existing entity and the parsed row.
     *
     * @param entity existing listing
     * @param contract linked futures contract
     * @param stockExchange resolved stock exchange
     * @param row parsed CSV row
     * @return {@code true} when all imported listing fields already match
     */
    private boolean listingMatches(
            Listing entity,
            FuturesContract contract,
            StockExchange stockExchange,
            FuturesContractCsvRow row
    ) {
        return Objects.equals(entity.getSecurityId(), contract.getId())
                && entity.getListingType() == ListingType.FUTURES
                && entity.getStockExchange() != null
                && Objects.equals(entity.getStockExchange().getId(), stockExchange.getId())
                && Objects.equals(entity.getTicker(), row.ticker())
                && Objects.equals(entity.getName(), row.name())
                && Objects.equals(entity.getLastRefresh(), row.lastRefresh())
                && Objects.equals(entity.getPrice(), row.price())
                && Objects.equals(entity.getAsk(), row.ask())
                && Objects.equals(entity.getBid(), row.bid())
                && Objects.equals(entity.getChange(), row.change())
                && Objects.equals(entity.getVolume(), row.volume());
    }

    /**
     * Copies parsed market data into a persistent daily-price entity.
     *
     * @param entity target daily snapshot
     * @param listing linked listing
     * @param row parsed CSV row
     */
    private void applyDailyPriceRow(ListingDailyPriceInfo entity, Listing listing, FuturesContractCsvRow row) {
        entity.setListing(listing);
        entity.setDate(row.listingDate());
        entity.setPrice(row.price());
        entity.setAsk(row.ask());
        entity.setBid(row.bid());
        entity.setChange(row.change());
        entity.setVolume(row.volume());
    }

    /**
     * Updates a daily snapshot only when at least one imported value changed.
     *
     * @param entity existing daily snapshot
     * @param listing linked listing
     * @param row parsed CSV row
     * @return {@code true} if the daily snapshot changed
     */
    private boolean applyDailyPriceRowIfChanged(
            ListingDailyPriceInfo entity,
            Listing listing,
            FuturesContractCsvRow row
    ) {
        if (dailyPriceMatches(entity, listing, row)) {
            return false;
        }

        applyDailyPriceRow(entity, listing, row);
        return true;
    }

    /**
     * Compares all imported daily-price fields between the existing entity and the parsed row.
     *
     * @param entity existing daily snapshot
     * @param listing linked listing
     * @param row parsed CSV row
     * @return {@code true} when all imported daily fields already match
     */
    private boolean dailyPriceMatches(
            ListingDailyPriceInfo entity,
            Listing listing,
            FuturesContractCsvRow row
    ) {
        return entity.getListing() != null
                && Objects.equals(entity.getListing().getId(), listing.getId())
                && Objects.equals(entity.getDate(), row.listingDate())
                && Objects.equals(entity.getPrice(), row.price())
                && Objects.equals(entity.getAsk(), row.ask())
                && Objects.equals(entity.getBid(), row.bid())
                && Objects.equals(entity.getChange(), row.change())
                && Objects.equals(entity.getVolume(), row.volume());
    }

    /**
     * Generates a stable ticker from the contract name and futures type.
     *
     * @param contractName raw contract name from CSV
     * @param futuresType normalized futures type
     * @param lineNumber row number used in error messages
     * @param source source label used in error messages
     * @return generated ticker
     */
    private String generateTicker(String contractName, String futuresType, int lineNumber, String source) {
        String generatedTicker = (contractName + futuresType)
                .replaceAll("[^A-Za-z0-9]", "")
                .toUpperCase(Locale.ROOT);
        if (generatedTicker.isBlank()) {
            throw new IllegalArgumentException(
                    "Unable to generate futures ticker on row " + lineNumber + " in " + source
            );
        }
        return generatedTicker.length() > 32 ? generatedTicker.substring(0, 32) : generatedTicker;
    }

    /**
     * Formats CSV labels into a display-friendly title case.
     *
     * @param rawValue raw CSV text
     * @return display-ready label
     */
    private String toDisplayLabel(String rawValue) {
        String normalizedValue = rawValue.trim().toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder(normalizedValue.length());
        boolean capitalizeNext = true;

        for (int i = 0; i < normalizedValue.length(); i++) {
            char currentCharacter = normalizedValue.charAt(i);
            if (Character.isLetterOrDigit(currentCharacter)) {
                builder.append(capitalizeNext
                        ? Character.toUpperCase(currentCharacter)
                        : currentCharacter);
                capitalizeNext = false;
                continue;
            }

            builder.append(currentCharacter);
            capitalizeNext = currentCharacter == ' '
                    || currentCharacter == '-'
                    || currentCharacter == '/';
        }

        return builder.toString();
    }

    /**
     * Derives the dummy current price from the provided maintenance margin.
     *
     * <p>The seed file stores maintenance margin instead of price, while the domain model derives
     * maintenance margin as {@code contractSize * price * 10%}. The importer reverses that formula
     * to obtain a deterministic dummy price.
     *
     * @param maintenanceMargin parsed maintenance margin
     * @param contractSize parsed contract size
     * @return derived dummy price
     */
    private BigDecimal derivePrice(BigDecimal maintenanceMargin, int contractSize) {
        return maintenanceMargin.multiply(TEN)
                .divide(BigDecimal.valueOf(contractSize), MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Derives a bid/ask spread from the current dummy price.
     *
     * @param price current dummy price
     * @return derived spread
     */
    private BigDecimal deriveSpread(BigDecimal price) {
        BigDecimal spread = price.multiply(ONE_PERCENT).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (spread.signum() == 0) {
            return MIN_MONETARY_VALUE;
        }
        if (spread.compareTo(price) >= 0) {
            return normalizeMonetaryValue(
                    price.divide(BigDecimal.valueOf(2), MONEY_SCALE, RoundingMode.HALF_UP)
            );
        }
        return spread;
    }

    /**
     * Derives a deterministic dummy volume from the maintenance margin and contract size.
     *
     * @param maintenanceMargin parsed maintenance margin
     * @param contractSize parsed contract size
     * @return derived dummy volume
     */
    private long deriveVolume(BigDecimal maintenanceMargin, int contractSize) {
        long maintenanceBasedVolume = maintenanceMargin.longValue();
        long contractBasedVolume = Math.max(1L, contractSize / 100L);
        return Math.max(1L, maintenanceBasedVolume + contractBasedVolume);
    }

    /**
     * Normalizes a monetary value to the scale used by listing tables.
     *
     * @param value raw monetary value
     * @return normalized value with non-negative lower bound
     */
    private BigDecimal normalizeMonetaryValue(BigDecimal value) {
        BigDecimal normalizedValue = value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (normalizedValue.signum() <= 0) {
            return MIN_MONETARY_VALUE;
        }
        return normalizedValue;
    }

    /**
     * Intermediate immutable representation of one validated futures CSV row.
     *
     * <p>This record exists so parsing and validation stay separated from persistence.
     * The importer first converts the file into a clean row model and only then applies it to JPA entities.
     */
    private record FuturesContractCsvRow(
            String ticker,
            String name,
            Integer contractSize,
            String contractUnit,
            LocalDate settlementDate,
            String futuresType,
            BigDecimal price,
            BigDecimal ask,
            BigDecimal bid,
            BigDecimal change,
            Long volume,
            LocalDate listingDate,
            LocalDateTime lastRefresh
    ) {

        /**
         * Resolves the preferred exchange MIC code for the parsed futures type.
         *
         * @return preferred exchange MIC code
         */
        private String preferredExchangeMic() {
            return "METALS".equals(futuresType) ? METALS_EXCHANGE_MIC : DEFAULT_FUTURES_EXCHANGE_MIC;
        }
    }
}
