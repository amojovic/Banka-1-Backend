package com.banka1.stock_service.service.implementation;

import com.banka1.stock_service.domain.StockExchange;
import com.banka1.stock_service.dto.StockExchangeMarketPhase;
import com.banka1.stock_service.dto.StockExchangeResponse;
import com.banka1.stock_service.dto.StockExchangeStatusResponse;
import com.banka1.stock_service.dto.StockExchangeToggleResponse;
import com.banka1.stock_service.repository.StockExchangeRepository;
import com.banka1.stock_service.service.HolidayService;
import com.banka1.stock_service.service.StockExchangeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Default implementation of stock exchange listing, runtime market-status checks, and testing toggles.
 *
 * <p>The most important business logic in this class is the {@code is-open} calculation exposed through
 * {@link #getStockExchangeStatus(Long)}.
 * That calculation follows the feature specification in this order:
 *
 * <ol>
 *     <li>load the exchange and read its configured {@code timeZone}</li>
 *     <li>convert the current instant into exchange-local date and time</li>
 *     <li>determine whether the local date is a trading day</li>
 *     <li>determine whether the local time is in pre-market, regular market, or post-market</li>
 *     <li>apply the {@code isActive} testing override</li>
 * </ol>
 *
 * <p>Example:
 * if the current UTC instant is {@code 2026-04-06T14:30:00Z} and the exchange timezone is
 * {@code America/New_York}, the exchange-local time is {@code 10:30}.
 * For an exchange with regular hours {@code 09:30-16:00}, this is inside the regular market session.
 */
@Service
public class StockExchangeServiceImpl implements StockExchangeService {

    private final StockExchangeRepository stockExchangeRepository;
    private final HolidayService holidayService;
    private final Clock clock;

    /**
     * Creates the production service using the system UTC clock.
     *
     * @param stockExchangeRepository repository for stock exchanges
     * @param holidayService holiday resolver
     */
    @Autowired
    public StockExchangeServiceImpl(
            StockExchangeRepository stockExchangeRepository,
            HolidayService holidayService
    ) {
        this(stockExchangeRepository, holidayService, Clock.systemUTC());
    }

    /**
     * Creates the service with an explicit clock for deterministic TESTS.
     *
     * @param stockExchangeRepository repository for stock exchanges
     * @param holidayService holiday resolver
     * @param clock time source used to derive exchange-local times
     */
    StockExchangeServiceImpl(
            StockExchangeRepository stockExchangeRepository,
            HolidayService holidayService,
            Clock clock
    ) {
        this.stockExchangeRepository = stockExchangeRepository;
        this.holidayService = holidayService;
        this.clock = clock;
    }

    /**
     * Returns the persisted stock-exchange catalog sorted by exchange name.
     *
     * <p>This method does not calculate runtime status. It only maps the stored entity data
     * into the response DTO used by the listing endpoint.
     *
     * @return all configured stock exchanges
     */
    @Override
    @Transactional(readOnly = true)
    public List<StockExchangeResponse> getStockExchanges() {
        return stockExchangeRepository.findAllByOrderByExchangeNameAsc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Calculates the current market status for one exchange.
     *
     * <p>This is the main implementation of the {@code GET /api/stock-exchanges/{id}/is-open} feature.
     * The method intentionally keeps all status decisions in one place so the behavior is easy to trace.
     *
     * <p>Detailed flow:
     *
     * <ol>
     *     <li>load the exchange by id</li>
     *     <li>convert {@code Instant.now(clock)} into the exchange-local timezone</li>
     *     <li>extract {@code localDate} and {@code localTime}</li>
     *     <li>ask {@link HolidayService} whether that local date is a holiday</li>
     *     <li>derive {@code workingDay = !weekend && !holiday}</li>
     *     <li>calculate the natural market phase from the configured time windows</li>
     *     <li>if {@code isActive == false}, bypass the normal check and treat the exchange as open</li>
     * </ol>
     *
     * <p>Example 1:
     * if an exchange uses {@code America/New_York}, regular hours are {@code 09:30-16:00},
     * and the current UTC instant resolves to local time {@code 10:30} on a weekday,
     * the resulting phase is {@link StockExchangeMarketPhase#REGULAR_MARKET}.
     *
     * <p>Example 2:
     * if the same exchange resolves to local time {@code 09:00} and has
     * {@code preMarketOpenTime=07:00} and {@code preMarketCloseTime=09:30},
     * the resulting phase is {@link StockExchangeMarketPhase#PRE_MARKET}.
     *
     * <p>Example 3:
     * if the exchange is inactive ({@code isActive == false}),
     * the method returns it as effectively open for testing even if the natural phase would be
     * {@link StockExchangeMarketPhase#CLOSED}.
     *
     * @param id stock exchange identifier
     * @return calculated exchange status for the current instant
     * @throws ResponseStatusException when the exchange does not exist
     */
    @Override
    @Transactional(readOnly = true)
    public StockExchangeStatusResponse getStockExchangeStatus(Long id) {
        StockExchange exchange = findExchange(id);
        ZonedDateTime localDateTime = resolveExchangeDateTime(exchange);
        LocalDate localDate = localDateTime.toLocalDate();
        LocalTime localTime = localDateTime.toLocalTime();

        // Holiday resolution is intentionally abstracted behind HolidayService so a future
        // deterministic calendar source can be added without changing the session logic.
        boolean holiday = holidayService.isHoliday(exchange.getPolity(), localDate);

        boolean workingDay = isWorkingDay(localDate, holiday);
        StockExchangeMarketPhase naturalPhase = resolveMarketPhase(exchange, localTime, workingDay);
        boolean bypassEnabled = !Boolean.TRUE.equals(exchange.getIsActive());
        StockExchangeMarketPhase effectivePhase = bypassEnabled
                ? StockExchangeMarketPhase.REGULAR_MARKET
                : naturalPhase;

        return new StockExchangeStatusResponse(
                exchange.getId(),
                exchange.getExchangeName(),
                exchange.getExchangeAcronym(),
                exchange.getExchangeMICCode(),
                exchange.getPolity(),
                exchange.getTimeZone(),
                localDate,
                localTime,
                exchange.getOpenTime(),
                exchange.getCloseTime(),
                exchange.getPreMarketOpenTime(),
                exchange.getPreMarketCloseTime(),
                exchange.getPostMarketOpenTime(),
                exchange.getPostMarketCloseTime(),
                Boolean.TRUE.equals(exchange.getIsActive()),
                workingDay,
                holiday,
                bypassEnabled || effectivePhase != StockExchangeMarketPhase.CLOSED,
                effectivePhase == StockExchangeMarketPhase.REGULAR_MARKET,
                bypassEnabled,
                effectivePhase
        );
    }

    /**
     * Flips the {@code isActive} flag of one exchange.
     *
     * <p>This exists purely for testing the status-check feature.
     * When an exchange is toggled to inactive, {@link #getStockExchangeStatus(Long)}
     * treats it as effectively open regardless of time or trading day.
     *
     * <p>Example:
     * if an exchange is stored with {@code isActive=true}, this method changes it to {@code false}.
     * A later status check will then return {@code open=true} and
     * {@code testModeBypassEnabled=true}.
     *
     * @param id stock exchange identifier
     * @return updated active state after the toggle
     * @throws ResponseStatusException when the exchange does not exist
     */
    @Override
    @Transactional
    public StockExchangeToggleResponse toggleStockExchangeActive(Long id) {
        StockExchange exchange = findExchange(id);
        exchange.setIsActive(!Boolean.TRUE.equals(exchange.getIsActive()));
        StockExchange savedExchange = stockExchangeRepository.save(exchange);
        return new StockExchangeToggleResponse(
                savedExchange.getId(),
                savedExchange.getExchangeName(),
                savedExchange.getExchangeMICCode(),
                Boolean.TRUE.equals(savedExchange.getIsActive())
        );
    }

    /**
     * Loads an exchange by id or throws HTTP 404 if it does not exist.
     *
     * <p>Keeping this lookup in one helper avoids repeating not-found handling in the public methods.
     *
     * @param id stock exchange identifier
     * @return existing exchange entity
     * @throws ResponseStatusException with {@link HttpStatus#NOT_FOUND} when no exchange is found
     */
    private StockExchange findExchange(Long id) {
        return stockExchangeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Stock exchange with id %d was not found.".formatted(id)
                ));
    }

    /**
     * Converts the current instant into the local timezone of the target exchange.
     *
     * <p>This is the first important step of the {@code is-open} logic.
     * The service never compares market hours against raw server time. It always compares
     * against exchange-local time derived from the configured {@code timeZone}.
     *
     * <p>Example:
     * if the current instant is {@code 2026-04-06T14:30:00Z} and the timezone is
     * {@code America/New_York}, the returned local date-time is {@code 2026-04-06T10:30-04:00}.
     *
     * @param exchange stock exchange whose timezone should be used
     * @return current exchange-local date-time
     */
    private ZonedDateTime resolveExchangeDateTime(StockExchange exchange) {
        Instant currentInstant = Instant.now(clock);
        return currentInstant.atZone(ZoneId.of(exchange.getTimeZone()));
    }

    /**
     * Determines whether a local exchange date is a trading day.
     *
     * <p>The current rule is:
     * a day is a working day only if it is not Saturday, not Sunday, and not a holiday.
     *
     * <p>Because the current {@link HolidayService} implementation is a no-op stub,
     * the runtime behavior today is effectively "not weekend".
     * The method is still written against the holiday flag so a real holiday source can be added later
     * without changing the rest of the status logic.
     *
     * <p>Example:
     * a Monday with {@code holiday=false} returns {@code true},
     * while a Saturday or a weekday holiday returns {@code false}.
     *
     * @param localDate exchange-local date
     * @param holiday precomputed holiday flag for that local date
     * @return {@code true} when the exchange should be treated as open for trading that day
     */
    private boolean isWorkingDay(LocalDate localDate, boolean holiday) {
        DayOfWeek dayOfWeek = localDate.getDayOfWeek();
        return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY && !holiday;
    }

    /**
     * Resolves the natural market phase for a given exchange-local time.
     *
     * <p>"Natural" means before the {@code isActive == false} test override is applied.
     *
     * <p>Decision order:
     *
     * <ol>
     *     <li>if it is not a working day, return {@link StockExchangeMarketPhase#CLOSED}</li>
     *     <li>if local time is inside the pre-market window, return {@link StockExchangeMarketPhase#PRE_MARKET}</li>
     *     <li>if local time is inside the regular market window, return {@link StockExchangeMarketPhase#REGULAR_MARKET}</li>
     *     <li>if local time is inside the post-market window, return {@link StockExchangeMarketPhase#POST_MARKET}</li>
     *     <li>otherwise return {@link StockExchangeMarketPhase#CLOSED}</li>
     * </ol>
     *
     * <p>Example:
     * for an exchange with
     * {@code pre=07:00-09:30},
     * {@code regular=09:30-16:00},
     * {@code post=16:00-20:00}:
     *
     * <ul>
     *     <li>{@code 08:45 -> PRE_MARKET}</li>
     *     <li>{@code 10:30 -> REGULAR_MARKET}</li>
     *     <li>{@code 17:15 -> POST_MARKET}</li>
     *     <li>{@code 21:00 -> CLOSED}</li>
     * </ul>
     *
     * <p>If pre-market or post-market times are missing in the data, those phases simply cannot match
     * because the corresponding windows are treated as absent.
     *
     * @param exchange stock exchange with configured session times
     * @param localTime exchange-local time
     * @param workingDay whether the local date is a trading day
     * @return natural market phase for the given instant
     */
    private StockExchangeMarketPhase resolveMarketPhase(StockExchange exchange, LocalTime localTime, boolean workingDay) {
        if (!workingDay) {
            return StockExchangeMarketPhase.CLOSED;
        }
        if (isWithinWindow(localTime, exchange.getPreMarketOpenTime(), exchange.getPreMarketCloseTime())) {
            return StockExchangeMarketPhase.PRE_MARKET;
        }
        if (isWithinWindow(localTime, exchange.getOpenTime(), exchange.getCloseTime())) {
            return StockExchangeMarketPhase.REGULAR_MARKET;
        }
        if (isWithinWindow(localTime, exchange.getPostMarketOpenTime(), exchange.getPostMarketCloseTime())) {
            return StockExchangeMarketPhase.POST_MARKET;
        }
        return StockExchangeMarketPhase.CLOSED;
    }

    /**
     * Checks whether a time belongs to a configured session window.
     *
     * <p>The comparison is inclusive at the start and exclusive at the end:
     * {@code start <= candidate < end}.
     *
     * <p>Examples:
     *
     * <ul>
     *     <li>candidate {@code 09:30}, start {@code 09:30}, end {@code 16:00} -> {@code true}</li>
     *     <li>candidate {@code 15:59}, start {@code 09:30}, end {@code 16:00} -> {@code true}</li>
     *     <li>candidate {@code 16:00}, start {@code 09:30}, end {@code 16:00} -> {@code false}</li>
     * </ul>
     *
     * <p>The method also supports overnight windows where {@code end < start}.
     * In that case the window is treated as spanning midnight.
     *
     * <p>If any input is {@code null}, or if {@code start == end}, the method returns {@code false}.
     *
     * @param candidate exchange-local time being checked
     * @param start session start
     * @param end session end
     * @return {@code true} when the candidate belongs to the window
     */
    private boolean isWithinWindow(LocalTime candidate, LocalTime start, LocalTime end) {
        if (candidate == null || start == null || end == null || start.equals(end)) {
            return false;
        }
        if (end.isAfter(start)) {
            return !candidate.isBefore(start) && candidate.isBefore(end);
        }
        return !candidate.isBefore(start) || candidate.isBefore(end);
    }

    /**
     * Maps the persistent entity into the catalog/listing DTO.
     *
     * <p>This method is intentionally simple: it exposes stored metadata exactly as configured,
     * without deriving any runtime status.
     *
     * @param exchange persistent stock exchange entity
     * @return listing DTO for API responses
     */
    private StockExchangeResponse toResponse(StockExchange exchange) {
        return new StockExchangeResponse(
                exchange.getId(),
                exchange.getExchangeName(),
                exchange.getExchangeAcronym(),
                exchange.getExchangeMICCode(),
                exchange.getPolity(),
                exchange.getCurrency(),
                exchange.getTimeZone(),
                exchange.getOpenTime(),
                exchange.getCloseTime(),
                exchange.getPreMarketOpenTime(),
                exchange.getPreMarketCloseTime(),
                exchange.getPostMarketOpenTime(),
                exchange.getPostMarketCloseTime(),
                Boolean.TRUE.equals(exchange.getIsActive())
        );
    }
}
