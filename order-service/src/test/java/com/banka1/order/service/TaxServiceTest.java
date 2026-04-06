package com.banka1.order.service;

import com.banka1.order.client.AccountClient;
import com.banka1.order.client.ExchangeClient;
import com.banka1.order.dto.ExchangeRateDto;
import com.banka1.order.dto.AccountDetailsDto;
import com.banka1.order.dto.TaxDebtResponse;
import com.banka1.order.dto.client.PaymentDto;
import com.banka1.order.dto.response.UpdatedBalanceResponseDto;
import com.banka1.order.entity.Order;
import com.banka1.order.entity.Portfolio;
import com.banka1.order.entity.Transaction;
import com.banka1.order.rabbitmq.OrderNotificationProducer;
import com.banka1.order.repository.OrderRepository;
import com.banka1.order.repository.PortfolioRepository;
import com.banka1.order.repository.TransactionRepository;
import com.banka1.order.service.impl.TaxServiceImpl;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaxServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PortfolioRepository portfolioRepository;

    @Mock
    private AccountClient accountClient;

    @Mock
    private ExchangeClient exchangeClient;

    @Mock
    private OrderNotificationProducer notificationProducer;

    @InjectMocks
    private TaxServiceImpl taxService;

    private Transaction tx;
    private Order order;
    private Portfolio portfolio;

    @BeforeEach
    void setUp() {
        tx = new Transaction();
        tx.setId(1L);
        tx.setOrderId(10L);
        tx.setQuantity(2);
        tx.setPricePerUnit(new BigDecimal("150.00"));
        tx.setTimestamp(LocalDateTime.now());

        order = new Order();
        order.setId(10L);
        order.setUserId(5L);
        order.setListingId(100L);
        order.setAccountId(20L);
        order.setDirection(com.banka1.order.entity.enums.OrderDirection.SELL);

        portfolio = new Portfolio();
        portfolio.setUserId(5L);
        portfolio.setListingId(100L);
        portfolio.setAveragePurchasePrice(new BigDecimal("100.00"));
    }

    @Test
    void collectMonthlyTax_happyPath() {
        when(transactionRepository.findByTimestampBetween(any(), any())).thenReturn(List.of(tx));
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(portfolioRepository.findByUserIdAndListingId(5L, 100L)).thenReturn(Optional.of(portfolio));

        AccountDetailsDto seller = new AccountDetailsDto();
        seller.setAccountNumber("ACC-SELL-1");
        seller.setCurrency("RSD");
        when(accountClient.getAccountDetailsById(20L)).thenReturn(seller);

        ExchangeRateDto conv = new ExchangeRateDto();
        conv.setConvertedAmount(new BigDecimal("15.00"));
        conv.setExchangeRate(BigDecimal.ONE);
        when(exchangeClient.calculate("RSD", "RSD", new BigDecimal("15.00"))).thenReturn(conv);

        AccountDetailsDto govt = new AccountDetailsDto();
        govt.setAccountNumber("ACC-GOVT");
        when(accountClient.getGovernmentBankAccountRsd()).thenReturn(govt);

        when(accountClient.transaction(any(PaymentDto.class))).thenReturn(new UpdatedBalanceResponseDto(new BigDecimal("1000"), new BigDecimal("500")));

        taxService.collectMonthlyTax();

        verify(accountClient, times(1)).transaction(any(PaymentDto.class));
        verify(notificationProducer, times(1)).sendTaxCollected(any());
    }

    @Test
    void collectMonthlyTaxManually_shouldDelegateToCollectMonthlyTax() {
        when(transactionRepository.findByTimestampBetween(any(), any()))
                .thenReturn(List.of(tx));

        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(portfolioRepository.findByUserIdAndListingId(5L, 100L))
                .thenReturn(Optional.of(portfolio));

        AccountDetailsDto seller = new AccountDetailsDto();
        seller.setAccountNumber("ACC-SELL-1");
        seller.setCurrency("RSD");
        when(accountClient.getAccountDetailsById(20L)).thenReturn(seller);

        ExchangeRateDto conv = new ExchangeRateDto();
        conv.setConvertedAmount(new BigDecimal("15.00"));
        conv.setExchangeRate(BigDecimal.ONE);
        when(exchangeClient.calculate("RSD", "RSD", new BigDecimal("15.00")))
                .thenReturn(conv);

        AccountDetailsDto govt = new AccountDetailsDto();
        govt.setAccountNumber("ACC-GOVT");
        when(accountClient.getGovernmentBankAccountRsd()).thenReturn(govt);

        when(accountClient.transaction(any(PaymentDto.class)))
                .thenReturn(new UpdatedBalanceResponseDto(new BigDecimal("1000"), new BigDecimal("500")));

        taxService.collectMonthlyTaxManually();

        verify(accountClient, times(1)).transaction(any(PaymentDto.class));
        verify(notificationProducer, times(1)).sendTaxCollected(any());
    }

    @Test
    void getUserDebt_shouldCalculateCorrectly() {
        when(transactionRepository.findAll()).thenReturn(List.of(tx));
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(portfolioRepository.findByUserIdAndListingId(5L, 100L))
                .thenReturn(Optional.of(portfolio));

        TaxDebtResponse response = taxService.getUserDebt(5L);

        BigDecimal expected = new BigDecimal("15.00");

        assert response.getUserId().equals(5L);
        assert response.getDebtRsd().compareTo(expected) == 0;
    }

    @Test
    void getUserDebt_shouldReturnZeroWhenUserNotFound() {
        when(transactionRepository.findAll()).thenReturn(List.of(tx));
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        TaxDebtResponse response = taxService.getUserDebt(999L);

        assert response.getUserId().equals(999L);
        assert response.getDebtRsd().compareTo(BigDecimal.ZERO) == 0;
    }

    @Test
    void getAllDebts_shouldAggregateCorrectly() {
        when(transactionRepository.findAll()).thenReturn(List.of(tx));
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(portfolioRepository.findByUserIdAndListingId(5L, 100L))
                .thenReturn(Optional.of(portfolio));

        List<TaxDebtResponse> result = taxService.getAllDebts();

        assert result.size() == 1;
        assert result.get(0).getUserId().equals(5L);
        assert result.get(0).getDebtRsd().compareTo(new BigDecimal("15.00")) == 0;
    }

    @Test
    void shouldIgnoreBuyOrders() {
        order.setDirection(com.banka1.order.entity.enums.OrderDirection.BUY);

        when(transactionRepository.findAll()).thenReturn(List.of(tx));
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        TaxDebtResponse response = taxService.getUserDebt(5L);

        assert response.getDebtRsd().compareTo(BigDecimal.ZERO) == 0;
    }

    @Test
    void shouldSkipWhenPortfolioMissing() {
        when(transactionRepository.findAll()).thenReturn(List.of(tx));
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(portfolioRepository.findByUserIdAndListingId(5L, 100L))
                .thenReturn(Optional.empty());

        TaxDebtResponse response = taxService.getUserDebt(5L);

        assert response.getDebtRsd().compareTo(BigDecimal.ZERO) == 0;
    }
}

