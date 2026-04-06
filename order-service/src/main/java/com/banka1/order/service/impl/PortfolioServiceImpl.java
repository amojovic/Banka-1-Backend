package com.banka1.order.service.impl;

import com.banka1.order.client.AccountClient;
import com.banka1.order.client.StockClient;
import com.banka1.order.client.impl.StockClientImpl;
import com.banka1.order.dto.SetPublicQuantityRequestDto;
import com.banka1.order.dto.PortfolioResponse;
import com.banka1.order.dto.StockListingDto;
import com.banka1.order.entity.Portfolio;
import com.banka1.order.entity.enums.ListingType;
import com.banka1.order.entity.enums.OptionType;
import com.banka1.order.repository.PortfolioRepository;
import com.banka1.order.service.PortfolioService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Service implementation for managing user portfolios.
 *
 * Responsibilities:
 * - Aggregates portfolio positions per user
 * - Manages public exposure of stock positions (OTC visibility)
 * - Handles option exercise operations
 * - Maps internal Portfolio entities to API responses
 *
 * Integrations:
 * - stock-service (market data, listings)
 * - account-service (future/optional financial operations)
 */
@Service
@RequiredArgsConstructor
public class PortfolioServiceImpl implements PortfolioService {

    private final PortfolioRepository portfolioRepository;
    private final StockClient stockClient;
    private final AccountClient accountClient;

    /**
     * Retrieves all portfolio positions for a given user and maps them
     * into response DTOs enriched with market and profit information.
     *
     * @param userId ID of the user whose portfolio is being fetched
     * @return list of portfolio positions in response format
     */
    @Override
    public List<PortfolioResponse> getPortfolio(Long userId) {

        List<Portfolio> holdings = portfolioRepository.findByUserId(userId);

        BigDecimal totalProfit = holdings.stream()
                .map(this::mapToResponse)
                .map(PortfolioResponse::getProfit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<PortfolioResponse> response = holdings.stream()
                .map(this::mapToResponse)
                .toList();

        response.forEach(r -> r.setTotalProfit(totalProfit));

        return response;
    }

    /**
     * Sets the number of shares that will be publicly visible for OTC trading.
     * Only applicable to STOCK positions.
     *
     * Business rules:
     * - Only STOCK positions can be made public
     * - publicQuantity cannot exceed total quantity
     * - If publicQuantity > 0, position becomes publicly visible
     *
     * @param portfolioId portfolio position identifier
     * @param request request containing desired public quantity
     * @throws IllegalArgumentException if portfolio not found or invalid type/quantity
     */
    @Override
    public void setPublicQuantity(Long portfolioId, SetPublicQuantityRequestDto request) {
        Portfolio portfolio = portfolioRepository.findById(portfolioId)
                .orElseThrow(() -> new IllegalArgumentException("Portfolio not found"));

        if (portfolio.getListingType() != ListingType.STOCK) {
            throw new IllegalArgumentException("Only STOCK positions can be made public");
        }

        if (request.getPublicQuantity() > portfolio.getQuantity()) {
            throw new IllegalArgumentException("Public quantity cannot exceed total quantity");
        }

        portfolio.setPublicQuantity(request.getPublicQuantity());
        portfolio.setIsPublic(request.getPublicQuantity() > 0);

        portfolioRepository.save(portfolio);
    }

    /**
     * Executes an OPTION position from the portfolio.
     * This system does not directly modify account balances;
     * instead, it calculates realized profit and updates the portfolio state.
     *
     * Business rules:
     * - Only OPTION type positions can be exercised
     * - Uses current market price from stock-service
     * - Option is in-the-money if market price > average purchase price
     * - Each contract represents quantity * contractSize shares
     *
     * Result:
     * - Calculates realized profit for reporting purposes
     * - Marks the position as closed (quantity = 0)
     *
     * @param portfolioId portfolio position ID
     * @param userId user executing the option
     * @throws IllegalArgumentException if portfolio is invalid or not in-the-money
     */
    @Override
    public void exerciseOption(Long portfolioId, Long userId) {

        Portfolio portfolio = portfolioRepository.findById(portfolioId)
                .orElseThrow(() -> new IllegalArgumentException("Portfolio not found"));

        if (portfolio.getListingType() != ListingType.OPTION) {
            throw new IllegalArgumentException("Only OPTION positions can be exercised");
        }

        StockListingDto listing = stockClient.getListing(portfolio.getListingId());

        BigDecimal marketPrice = listing.getPrice();

        BigDecimal strikePrice = listing.getStrikePrice();
        LocalDateTime settlementDate = listing.getSettlementDate();
        
        if (settlementDate.isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Option already expired");
        }

        boolean inTheMoney;

        if (listing.getOptionType() == OptionType.CALL) {
            inTheMoney = marketPrice.compareTo(strikePrice) > 0;
        } else {
            inTheMoney = marketPrice.compareTo(strikePrice) < 0;
        }

        if (!inTheMoney) {
            throw new IllegalArgumentException("Option is not in-the-money");
        }

        int shares = portfolio.getQuantity() * listing.getContractSize();

        BigDecimal profitPerShare = marketPrice.subtract(strikePrice);

        BigDecimal profit = profitPerShare
                .multiply(BigDecimal.valueOf(shares));

        //FALI INTEGRACIJA SA ACCOUNT SERVICE, NEMA ENDPOINT

        portfolio.setQuantity(0);

        portfolioRepository.save(portfolio);
    }

    /**
     * Maps a Portfolio entity into a PortfolioResponse DTO.
     *
     * This method currently returns partial data only:
     * - Basic portfolio information
     * - Placeholder values for market-dependent fields
     *
     * TODO:
     * - Fetch current price and ticker from stock-service
     * - Calculate profit using:
     *   (currentPrice - averagePurchasePrice) * quantity
     *
     * @param portfolio entity to be mapped
     * @return mapped response DTO
     */
    private PortfolioResponse mapToResponse(Portfolio portfolio) {

        StockListingDto listing = stockClient.getListing(portfolio.getListingId());

        BigDecimal currentPrice = listing.getPrice();
        BigDecimal avgPrice = portfolio.getAveragePurchasePrice();

        BigDecimal profit = currentPrice
                .subtract(avgPrice)
                .multiply(BigDecimal.valueOf(portfolio.getQuantity()));

        PortfolioResponse response = new PortfolioResponse();

        response.setListingType(portfolio.getListingType());
        response.setQuantity(portfolio.getQuantity());
        response.setPublicQuantity(portfolio.getPublicQuantity());
        response.setLastModified(portfolio.getLastModified());

        response.setTicker(listing.getTicker());
        response.setCurrentPrice(currentPrice);
        response.setProfit(profit);
        response.setYearlyTaxPaid(BigDecimal.ZERO);
        response.setMonthlyTaxDue(BigDecimal.ZERO);

        //FALI INTEGRACIJA SA SERVISOM ZA POREZ

        return response;
    }
}