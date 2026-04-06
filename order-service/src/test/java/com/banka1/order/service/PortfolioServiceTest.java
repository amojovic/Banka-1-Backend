package com.banka1.order.service;

import com.banka1.order.client.AccountClient;
import com.banka1.order.client.StockClient;
import com.banka1.order.dto.SetPublicQuantityRequestDto;
import com.banka1.order.dto.StockListingDto;
import com.banka1.order.entity.Portfolio;
import com.banka1.order.entity.enums.ListingType;
import com.banka1.order.entity.enums.OptionType;
import com.banka1.order.repository.PortfolioRepository;
import com.banka1.order.service.impl.PortfolioServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceTest {

    @Mock
    private PortfolioRepository portfolioRepository;

    @Mock
    private StockClient stockClient;

    @Mock
    private AccountClient accountClient;

    @InjectMocks
    private PortfolioServiceImpl portfolioService;

    private Portfolio stockPortfolio;
    private Portfolio optionPortfolio;

    @BeforeEach
    void setUp() {
        stockPortfolio = new Portfolio();
        stockPortfolio.setId(1L);
        stockPortfolio.setUserId(1L);
        stockPortfolio.setListingId(100L);
        stockPortfolio.setListingType(ListingType.STOCK);
        stockPortfolio.setQuantity(10);
        stockPortfolio.setAveragePurchasePrice(BigDecimal.valueOf(100));

        optionPortfolio = new Portfolio();
        optionPortfolio.setId(2L);
        optionPortfolio.setUserId(1L);
        optionPortfolio.setListingId(200L);
        optionPortfolio.setListingType(ListingType.OPTION);
        optionPortfolio.setQuantity(1);
    }

    @Test
    void getPortfolio_returnsCalculatedProfit() {
        when(portfolioRepository.findByUserId(1L)).thenReturn(List.of(stockPortfolio));

        StockListingDto listing = new StockListingDto();
        listing.setPrice(BigDecimal.valueOf(150));
        listing.setTicker("AAPL");

        when(stockClient.getListing(100L)).thenReturn(listing);

        var result = portfolioService.getPortfolio(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTicker()).isEqualTo("AAPL");
        assertThat(result.get(0).getProfit()).isEqualByComparingTo("500");
    }

    @Test
    void setPublicQuantity_updatesStockPosition() {
        when(portfolioRepository.findById(1L)).thenReturn(Optional.of(stockPortfolio));

        SetPublicQuantityRequestDto request = new SetPublicQuantityRequestDto();
        request.setPublicQuantity(5);

        portfolioService.setPublicQuantity(1L, request);

        assertThat(stockPortfolio.getPublicQuantity()).isEqualTo(5);
        assertThat(stockPortfolio.getIsPublic()).isTrue();
        verify(portfolioRepository).save(stockPortfolio);
    }

    @Test
    void setPublicQuantity_throwsForNonStock() {
        optionPortfolio.setListingType(ListingType.OPTION);

        when(portfolioRepository.findById(2L)).thenReturn(Optional.of(optionPortfolio));

        SetPublicQuantityRequestDto request = new SetPublicQuantityRequestDto();
        request.setPublicQuantity(1);

        assertThatThrownBy(() -> portfolioService.setPublicQuantity(2L, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exerciseOption_executesSuccessfully_whenInTheMoney() {
        when(portfolioRepository.findById(2L)).thenReturn(Optional.of(optionPortfolio));

        StockListingDto listing = new StockListingDto();
        listing.setPrice(BigDecimal.valueOf(200));
        listing.setStrikePrice(BigDecimal.valueOf(100));
        listing.setContractSize(100);
        listing.setSettlementDate(LocalDateTime.now().plusDays(1));
        listing.setOptionType(OptionType.CALL);

        when(stockClient.getListing(200L)).thenReturn(listing);

        portfolioService.exerciseOption(2L, 1L);

        assertThat(optionPortfolio.getQuantity()).isEqualTo(0);
        verify(portfolioRepository).save(optionPortfolio);
    }

    @Test
    void exerciseOption_throwsWhenNotInTheMoney() {
        when(portfolioRepository.findById(2L)).thenReturn(Optional.of(optionPortfolio));

        StockListingDto listing = new StockListingDto();
        listing.setPrice(BigDecimal.valueOf(50));
        listing.setStrikePrice(BigDecimal.valueOf(100));
        listing.setContractSize(100);
        listing.setSettlementDate(LocalDateTime.now().plusDays(1));
        listing.setOptionType(OptionType.CALL);

        when(stockClient.getListing(200L)).thenReturn(listing);

        assertThatThrownBy(() -> portfolioService.exerciseOption(2L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}