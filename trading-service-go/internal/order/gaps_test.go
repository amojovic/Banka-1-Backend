package order

import (
	"context"
	"errors"
	"testing"
	"time"

	"banka1/trading-service-go/internal/actuary"
	"banka1/trading-service-go/internal/api"
	"banka1/trading-service-go/internal/clients"
	"banka1/trading-service-go/internal/portfolio"

	"github.com/shopspring/decimal"
)

// ---------------------------------------------------------------------------
// convertAmount / convertAmountNoComm error + nil-rate branches
// ---------------------------------------------------------------------------

func TestConvertAmount_RateError(t *testing.T) {
	h := newHarness()
	h.market.calcErr = errors.New("boom")
	if _, err := h.svc.convertAmount(ctx(), "USD", "RSD", decimal.RequireFromString("10")); err == nil {
		t.Error("expected rate error")
	}
}

func TestConvertAmount_NilRate_ReturnsInput(t *testing.T) {
	h := newHarness()
	h.market.calc = nil // nil rate -> returns input
	got, err := h.svc.convertAmount(ctx(), "USD", "RSD", decimal.RequireFromString("10"))
	if err != nil || !got.Equal(decimal.RequireFromString("10")) {
		t.Errorf("got %s,%v", got, err)
	}
}

func TestConvertAmount_Converted(t *testing.T) {
	h := newHarness()
	conv := decimal.RequireFromString("1180")
	h.market.calc = &clients.ExchangeRate{ConvertedAmount: &conv}
	got, err := h.svc.convertAmount(ctx(), "USD", "RSD", decimal.RequireFromString("10"))
	if err != nil || !got.Equal(conv) {
		t.Errorf("got %s,%v", got, err)
	}
}

func TestConvertAmountNoComm_RateError(t *testing.T) {
	h := newHarness()
	h.market.calcNoCommErr = errors.New("boom")
	if _, err := h.svc.convertAmountNoComm(ctx(), "USD", "RSD", decimal.RequireFromString("10")); err == nil {
		t.Error("expected rate error")
	}
}

func TestConvertAmountNoComm_NilRate(t *testing.T) {
	h := newHarness()
	got, err := h.svc.convertAmountNoComm(ctx(), "USD", "RSD", decimal.RequireFromString("10"))
	if err != nil || !got.Equal(decimal.RequireFromString("10")) {
		t.Errorf("got %s,%v", got, err)
	}
}

func TestConvertAmountNoComm_Converted(t *testing.T) {
	h := newHarness()
	conv := decimal.RequireFromString("1170")
	h.market.calcNoComm = &clients.ExchangeRate{ConvertedAmount: &conv}
	got, err := h.svc.convertAmountNoComm(ctx(), "USD", "RSD", decimal.RequireFromString("10"))
	if err != nil || !got.Equal(conv) {
		t.Errorf("got %s,%v", got, err)
	}
}

// ---------------------------------------------------------------------------
// commission cap conversion failure (uncapped) + cap applied
// ---------------------------------------------------------------------------

func TestCommission_CapConversionFails_Uncapped(t *testing.T) {
	h := newHarness()
	h.market.calcErr = errors.New("boom") // cap conversion fails -> uncapped fee
	fee := h.svc.commission(ctx(), TypeMarket, decimal.RequireFromString("10"), "RSD")
	// 10 * 0.14 = 1.4, uncapped.
	if !fee.Equal(decimal.RequireFromString("1.4")) {
		t.Errorf("fee = %s, want 1.4", fee)
	}
}

func TestCommission_CapApplied(t *testing.T) {
	h := newHarness()
	cap := decimal.RequireFromString("700")
	h.market.calc = &clients.ExchangeRate{ConvertedAmount: &cap}
	fee := h.svc.commission(ctx(), TypeMarket, decimal.RequireFromString("1000000"), "RSD")
	if !fee.Equal(decimal.RequireFromString("700")) {
		t.Errorf("fee = %s, want 700 (capped)", fee)
	}
}

// ---------------------------------------------------------------------------
// calculateApproximatePrice error propagation
// ---------------------------------------------------------------------------

func TestCalculateApproximatePrice_RefPriceError(t *testing.T) {
	l := &clients.StockListing{ID: 1, ContractSize: ip(1)}
	_, err := calculateApproximatePrice(TypeMarket, DirectionBuy, l, 1, nil, nil) // nil ask
	if err == nil {
		t.Error("expected reference-price error")
	}
}

// ---------------------------------------------------------------------------
// buildBaseOrder error paths
// ---------------------------------------------------------------------------

func TestBuildBaseOrder_NilContractSize(t *testing.T) {
	h := newHarness()
	l := &clients.StockListing{ID: 1, Ask: dp("10")}
	_, err := h.svc.buildBaseOrder(7, 1, TypeMarket, 2, l, nil, nil, DirectionBuy, nil, nil, 5, false, false)
	if err == nil {
		t.Error("expected nil-contract-size error")
	}
}

func TestBuildBaseOrder_RefPriceError(t *testing.T) {
	h := newHarness()
	l := &clients.StockListing{ID: 1, ContractSize: ip(1)} // nil ask
	_, err := h.svc.buildBaseOrder(7, 1, TypeMarket, 2, l, nil, nil, DirectionBuy, nil, nil, 5, false, false)
	if err == nil {
		t.Error("expected ref-price error")
	}
}

// ---------------------------------------------------------------------------
// CreateSellOrder additional branches
// ---------------------------------------------------------------------------

func TestCreateSellOrder_ListingError(t *testing.T) {
	h := newHarness()
	h.market.listingErr = errors.New("boom")
	_, err := h.svc.CreateSellOrder(ctx(), clientUser(7), api.CreateSellOrderRequest{
		ListingID: int64Ptr(1), Quantity: intPtr(5), AccountID: int64Ptr(5),
	})
	if err == nil {
		t.Error("expected listing error")
	}
}

func TestCreateSellOrder_TradingAccessDenied(t *testing.T) {
	h := newHarness()
	l := listing(1)
	l.ListingType = sp("FOREX")
	h.market.listings[1] = l
	h.portfolios.positions[[2]int64{7, 1}] = &portfolio.Portfolio{ID: 1, UserID: 7, ListingID: 1, Quantity: 100}
	_, err := h.svc.CreateSellOrder(ctx(), clientUser(7), api.CreateSellOrderRequest{
		ListingID: int64Ptr(1), Quantity: intPtr(5), AccountID: int64Ptr(5),
	})
	if err == nil {
		t.Error("expected trading-access denial")
	}
}

func TestCreateSellOrder_ExchangeWindowError(t *testing.T) {
	h := newHarness()
	l := listing(1)
	l.ExchangeID = nil
	h.market.listings[1] = l
	h.portfolios.positions[[2]int64{7, 1}] = &portfolio.Portfolio{ID: 1, UserID: 7, ListingID: 1, Quantity: 100}
	_, err := h.svc.CreateSellOrder(ctx(), clientUser(7), api.CreateSellOrderRequest{
		ListingID: int64Ptr(1), Quantity: intPtr(5), AccountID: int64Ptr(5),
	})
	if err == nil {
		t.Error("expected exchange-window error")
	}
}

// ---------------------------------------------------------------------------
// CreateBuyOrder additional branches: agent funding, trading access
// ---------------------------------------------------------------------------

func TestCreateBuyOrder_AgentFunding(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.actuaries.infos[1] = &actuary.ActuaryInfo{EmployeeID: 1}
	bankID := int64(50)
	h.account.bank = &clients.BankAccount{ID: &bankID}
	h.account.details[50] = acct(0, "", "100000000")
	resp, err := h.svc.CreateBuyOrder(ctx(), agentUser(1), api.CreateBuyOrderRequest{
		ListingID: int64Ptr(1), Quantity: intPtr(2),
	})
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if resp.AccountID != 50 {
		t.Errorf("accountID = %d, want 50", resp.AccountID)
	}
}

func TestCreateBuyOrder_BankAccountError(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.account.bankErr = errors.New("boom")
	_, err := h.svc.CreateBuyOrder(ctx(), agentUser(1), api.CreateBuyOrderRequest{
		ListingID: int64Ptr(1), Quantity: intPtr(2), PurchaseFor: strPtr("BANK"),
	})
	if err == nil {
		t.Error("expected bank-account error")
	}
}

func TestCreateBuyOrder_TradingAccessDenied(t *testing.T) {
	h := newHarness()
	l := listing(1)
	l.ListingType = sp("FOREX")
	h.market.listings[1] = l
	_, err := h.svc.CreateBuyOrder(ctx(), clientUser(7), api.CreateBuyOrderRequest{
		ListingID: int64Ptr(1), Quantity: intPtr(2), AccountID: int64Ptr(5),
	})
	if err == nil {
		t.Error("expected trading-access denial")
	}
}

// ---------------------------------------------------------------------------
// matchesDateRange branches
// ---------------------------------------------------------------------------

func TestMatchesDateRange_ZeroCreatedAt(t *testing.T) {
	o := &Order{} // zero CreatedAt
	if !matchesDateRange(o, nil, nil) {
		t.Error("zero createdAt with no bounds should match")
	}
	from := time.Now()
	if matchesDateRange(o, &from, nil) {
		t.Error("zero createdAt with a from-bound should not match")
	}
}

func TestMatchesDateRange_BeforeFrom(t *testing.T) {
	o := &Order{CreatedAt: time.Date(2020, 1, 1, 0, 0, 0, 0, time.UTC)}
	from := time.Date(2021, 1, 1, 0, 0, 0, 0, time.UTC)
	if matchesDateRange(o, &from, nil) {
		t.Error("created before from should not match")
	}
}

func TestMatchesDateRange_AfterTo(t *testing.T) {
	o := &Order{CreatedAt: time.Date(2022, 1, 1, 0, 0, 0, 0, time.UTC)}
	to := time.Date(2021, 1, 1, 0, 0, 0, 0, time.UTC)
	if matchesDateRange(o, nil, &to) {
		t.Error("created after to should not match")
	}
}

func TestMatchesStatusFilter(t *testing.T) {
	if !matchesStatusFilter(&Order{Status: StatusDone}, "ALL") {
		t.Error("ALL matches any")
	}
	if !matchesStatusFilter(&Order{Status: StatusDone}, "") {
		t.Error("empty matches any")
	}
	if matchesStatusFilter(&Order{Status: StatusDone}, StatusApproved) {
		t.Error("mismatched status should not match")
	}
}

func TestMatchesListingTypeFilter(t *testing.T) {
	cache := map[int64]*clients.StockListing{1: {ListingType: sp("STOCK")}}
	if !matchesListingTypeFilter(&Order{ListingID: 1}, nil, cache) {
		t.Error("nil filter matches")
	}
	if !matchesListingTypeFilter(&Order{ListingID: 1}, sp("STOCK"), cache) {
		t.Error("matching type should match")
	}
	if matchesListingTypeFilter(&Order{ListingID: 1}, sp("BOND"), cache) {
		t.Error("non-matching type should not match")
	}
}

// ---------------------------------------------------------------------------
// releaseReservedState (combined) + releaseSellReservation negative qty
// ---------------------------------------------------------------------------

func TestReleaseReservedState_BuyOnlyExposure(t *testing.T) {
	h := newHarness()
	h.actuaries.infos[1] = &actuary.ActuaryInfo{EmployeeID: 1, ReservedLimit: decimal.RequireFromString("100")}
	o := &Order{UserID: 1, Direction: DirectionBuy, Quantity: 4, RemainingPortions: 0, ReservedLimitExposure: decimal.RequireFromString("100")}
	if err := h.svc.releaseReservedState(ctx(), nil, o, 4); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
}

func TestReleaseSellReservation_NegativeQty_Noop(t *testing.T) {
	h := newHarness()
	o := &Order{Direction: DirectionSell}
	if err := h.svc.releaseSellReservation(ctx(), nil, o, 0); err != nil {
		t.Errorf("unexpected: %v", err)
	}
}

// ---------------------------------------------------------------------------
// reserveSellQuantityIfNeeded find error
// ---------------------------------------------------------------------------

func TestReserveSellQuantityIfNeeded_FindError(t *testing.T) {
	h := newHarness()
	h.portfolios.findUpdErr = errors.New("boom")
	err := h.svc.reserveSellQuantityIfNeeded(ctx(), nil, &Order{Direction: DirectionSell, UserID: 7, ListingID: 1, RemainingPortions: 5})
	if err == nil {
		t.Error("expected find error")
	}
}

// ---------------------------------------------------------------------------
// determineFundingAccountID: actuary bank error
// ---------------------------------------------------------------------------

func TestDetermineFundingAccountID_ActuaryBankError(t *testing.T) {
	h := newHarness()
	h.actuaries.infos[1] = &actuary.ActuaryInfo{EmployeeID: 1}
	h.account.bankErr = errors.New("boom")
	if _, err := h.svc.determineFundingAccountID(ctx(), 1, nil, "USD"); err == nil {
		t.Error("expected bank error")
	}
}

func TestDetermineFundingAccountID_EmployeeBankError(t *testing.T) {
	h := newHarness()
	h.employees.employees[1] = &clients.Employee{ID: 1}
	h.account.bankErr = errors.New("boom")
	if _, err := h.svc.determineFundingAccountID(ctx(), 1, nil, "USD"); err == nil {
		t.Error("expected bank error")
	}
}

// ---------------------------------------------------------------------------
// transferFunds: convert (account currency) error
// ---------------------------------------------------------------------------

func TestTransferFunds_ConvertError(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(7, "RSD", "100000")
	h.market.calcErr = errors.New("boom") // convertAmount fails on currency mismatch
	o := &Order{AccountID: 5, Direction: DirectionBuy}
	if _, err := h.svc.transferFunds(ctx(), o, "USD", decimal.RequireFromString("50")); err == nil {
		t.Error("expected convert error")
	}
}

func TestTransferFunds_BankConvertError(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(0, "RSD", "100000")
	h.market.calcNoCommErr = errors.New("boom")
	pf := PurchaseForBank
	o := &Order{AccountID: 5, Direction: DirectionBuy, PurchaseFor: &pf}
	if _, err := h.svc.transferFunds(ctx(), o, "USD", decimal.RequireFromString("50")); err == nil {
		t.Error("expected bank convert error")
	}
}

func TestTransferFunds_ExchangeBuyError(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(7, "", "100000")
	h.account.exBuyErr = errors.New("boom")
	o := &Order{AccountID: 5, Direction: DirectionBuy}
	if _, err := h.svc.transferFunds(ctx(), o, "", decimal.RequireFromString("50")); err == nil {
		t.Error("expected exchange-buy error")
	}
}

func TestTransferFunds_ExchangeSellError(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(7, "", "100000")
	h.account.exSellErr = errors.New("boom")
	o := &Order{AccountID: 5, Direction: DirectionSell}
	if _, err := h.svc.transferFunds(ctx(), o, "", decimal.RequireFromString("50")); err == nil {
		t.Error("expected exchange-sell error")
	}
}

func TestTransferFunds_MarginBuyError(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(7, "", "100000")
	h.account.marginBuyErr = errors.New("boom")
	o := &Order{AccountID: 5, Direction: DirectionBuy, Margin: true, UserID: 7}
	if _, err := h.svc.transferFunds(ctx(), o, "", decimal.RequireFromString("50")); err == nil {
		t.Error("expected margin-buy error")
	}
}

func TestTransferFunds_MarginSellError(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(7, "", "100000")
	h.account.marginSellErr = errors.New("boom")
	o := &Order{AccountID: 5, Direction: DirectionSell, Margin: true, UserID: 7}
	if _, err := h.svc.transferFunds(ctx(), o, "", decimal.RequireFromString("50")); err == nil {
		t.Error("expected margin-sell error")
	}
}

// ---------------------------------------------------------------------------
// transferSellCommission: user-account error + convert error
// ---------------------------------------------------------------------------

func TestTransferSellCommission_UserAccountError(t *testing.T) {
	h := newHarness()
	h.account.details[0] = acct(0, "", "0")
	h.account.detailsErr[5] = errors.New("boom")
	o := &Order{AccountID: 5}
	if err := h.svc.transferSellCommission(ctx(), o, "", decimal.RequireFromString("5")); err == nil {
		t.Error("expected user-account error")
	}
}

func TestTransferSellCommission_ConvertError(t *testing.T) {
	h := newHarness()
	h.account.details[0] = acct(0, "", "0")
	h.account.details[5] = acct(7, "RSD", "1000")
	h.market.calcErr = errors.New("boom")
	o := &Order{AccountID: 5}
	if err := h.svc.transferSellCommission(ctx(), o, "USD", decimal.RequireFromString("5")); err == nil {
		t.Error("expected convert error")
	}
}

// ---------------------------------------------------------------------------
// finalizeActuaryExposure convert error + UpdateReservedAndUsedLimit error
// ---------------------------------------------------------------------------

func TestFinalizeActuaryExposure_ConvertError(t *testing.T) {
	h := newHarness()
	bankID := int64(5)
	h.account.bank = &clients.BankAccount{ID: &bankID}
	h.actuaries.infos[1] = &actuary.ActuaryInfo{EmployeeID: 1, ReservedLimit: decimal.RequireFromString("100")}
	h.market.calcNoCommErr = errors.New("boom")
	o := &Order{AccountID: 5, UserID: 1, ReservedLimitExposure: decimal.RequireFromString("100")}
	if err := h.svc.finalizeActuaryExposure(ctx(), nil, o, "USD", decimal.RequireFromString("30")); err == nil {
		t.Error("expected convert error")
	}
}

func TestFinalizeActuaryExposure_FindError(t *testing.T) {
	h := newHarness()
	bankID := int64(5)
	h.account.bank = &clients.BankAccount{ID: &bankID}
	h.actuaries.findUpdErr = errors.New("boom")
	o := &Order{AccountID: 5, UserID: 1}
	if err := h.svc.finalizeActuaryExposure(ctx(), nil, o, "RSD", decimal.RequireFromString("30")); err == nil {
		t.Error("expected find error")
	}
}

// ---------------------------------------------------------------------------
// determineOrderStatusAndReserveExposure: find error + convert error
// ---------------------------------------------------------------------------

func TestDetermineOrderStatusAndReserveExposure_FindError(t *testing.T) {
	h := newHarness()
	h.actuaries.findUpdErr = errors.New("boom")
	if _, _, err := h.svc.determineOrderStatusAndReserveExposure(ctx(), nil, 1, decimal.RequireFromString("10"), "RSD"); err == nil {
		t.Error("expected find error")
	}
}

func TestDetermineOrderStatusAndReserveExposure_ConvertError(t *testing.T) {
	h := newHarness()
	lim := decimal.RequireFromString("1000")
	h.actuaries.infos[1] = &actuary.ActuaryInfo{EmployeeID: 1, Limit: &lim}
	h.market.calcNoCommErr = errors.New("boom")
	if _, _, err := h.svc.determineOrderStatusAndReserveExposure(ctx(), nil, 1, decimal.RequireFromString("10"), "USD"); err == nil {
		t.Error("expected convert error")
	}
}

// ---------------------------------------------------------------------------
// releaseAgentExposure find error
// ---------------------------------------------------------------------------

func TestReleaseAgentExposure_FindError(t *testing.T) {
	h := newHarness()
	h.actuaries.findUpdErr = errors.New("boom")
	o := &Order{UserID: 1, Quantity: 4, RemainingPortions: 4, ReservedLimitExposure: decimal.RequireFromString("100")}
	if err := h.svc.releaseAgentExposure(ctx(), nil, o, 2); err == nil {
		t.Error("expected find error")
	}
}

// ---------------------------------------------------------------------------
// enrichStoredOrderResponse exec-price error
// ---------------------------------------------------------------------------

func TestEnrichStoredOrderResponse_ExecPriceError(t *testing.T) {
	h := newHarness()
	h.repo.txnErr = errors.New("boom")
	o := &Order{ID: 1, ListingID: 1, OrderType: TypeMarket, Direction: DirectionBuy, ContractSize: 1, Quantity: 2, PricePerUnit: decimal.RequireFromString("10")}
	if _, err := h.svc.enrichStoredOrderResponse(ctx(), o, listing(1)); err == nil {
		t.Error("expected exec-price error")
	}
}

func TestEnrichStoredOrderResponse_NilListing(t *testing.T) {
	h := newHarness()
	o := &Order{ID: 1, ListingID: 1, OrderType: TypeMarket, Direction: DirectionBuy, ContractSize: 1, Quantity: 2, PricePerUnit: decimal.RequireFromString("10")}
	resp, err := h.svc.enrichStoredOrderResponse(ctx(), o, nil)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if resp.Ticker != nil {
		t.Error("nil listing should leave ticker nil")
	}
}

// ---------------------------------------------------------------------------
// poolTxRunner (constructs a runner; we only verify it is non-nil and the
// closure shape — a nil pool would panic on actual use, so we just build it).
// ---------------------------------------------------------------------------

func TestPoolTxRunner_BuildsRunner(t *testing.T) {
	r := poolTxRunner(nil)
	if r == nil {
		t.Fatal("expected non-nil runner")
	}
}

// ---------------------------------------------------------------------------
// Additional error-branch coverage
// ---------------------------------------------------------------------------

func TestReleaseReservedState_SellReservationError(t *testing.T) {
	h := newHarness()
	h.portfolios.findUpdErr = errors.New("boom")
	o := &Order{Direction: DirectionSell, UserID: 7, ListingID: 1}
	if err := h.svc.releaseReservedState(ctx(), nil, o, 2); err == nil {
		t.Error("expected sell-reservation error")
	}
}

func TestCheckMarginRequirements_MarginCostError(t *testing.T) {
	h := newHarness()
	u := AuthUser{UserID: 7, Permissions: []string{"MARGIN_TRADE"}}
	l := &clients.StockListing{ID: 1} // nil price -> margin cost error
	if err := h.svc.checkMarginRequirements(ctx(), u, 5, l, 1); err == nil {
		t.Error("expected margin-cost error")
	}
}

func TestCheckMarginRequirements_AccountError(t *testing.T) {
	h := newHarness()
	u := AuthUser{UserID: 7, Permissions: []string{"MARGIN_TRADE"}}
	h.account.detailsErr[5] = errors.New("boom")
	if err := h.svc.checkMarginRequirements(ctx(), u, 5, listing(1), 1); err == nil {
		t.Error("expected account error")
	}
}

func TestValidateClientAccount_GenericError(t *testing.T) {
	h := newHarness()
	h.account.detailsErr[5] = errors.New("boom")
	if err := h.svc.validateClientAccount(ctx(), 7, 5); err == nil {
		t.Error("expected generic error")
	}
}

func TestCheckFunds_ConvertError(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(7, "RSD", "1000")
	h.market.calcNoCommErr = errors.New("boom")
	if err := h.svc.checkFunds(ctx(), 5, decimal.RequireFromString("10"), "USD"); err == nil {
		t.Error("expected convert error")
	}
}

func TestApproveOrder_ListingError(t *testing.T) {
	h := newHarness()
	o := seedPending(h, 1, DirectionBuy)
	h.market.listingErr = errors.New("boom")
	if _, err := h.svc.ApproveOrder(ctx(), 99, o.ID); err == nil {
		t.Error("expected listing error")
	}
}

func TestApproveOrder_TransferFeeError(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	bankID := int64(50)
	h.account.bank = &clients.BankAccount{ID: &bankID}
	h.account.details[50] = acct(0, "", "100000000")
	h.account.detailsErr[5] = errors.New("boom") // funding account lookup in transferFee fails
	o := seedPending(h, 1, DirectionBuy)
	if _, err := h.svc.ApproveOrder(ctx(), 99, o.ID); err == nil {
		t.Error("expected transfer-fee error")
	}
}

func TestApproveOrder_EmployeeUser_NoConversionFee(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	bankID := int64(50)
	h.account.bank = &clients.BankAccount{ID: &bankID}
	h.account.details[50] = acct(0, "", "100000000")
	h.account.details[5] = acct(0, "", "100000000")
	h.employees.employees[1] = &clients.Employee{ID: 1} // employee -> applyConversionFee false
	o := seedPending(h, 1, DirectionBuy)
	if _, err := h.svc.ApproveOrder(ctx(), 99, o.ID); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
}

func TestConfirmOrder_FundingAccountError(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.employees.employees[1] = &clients.Employee{ID: 1}
	h.account.bankErr = errors.New("boom") // determineFundingAccountID -> bank error
	o := seedDraft(h, 1, DirectionBuy)
	if _, err := h.svc.ConfirmOrder(ctx(), agentUser(1), o.ID); err == nil {
		t.Error("expected funding-account error")
	}
}

func TestConfirmOrder_CheckFundsError(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	// client funding account lookup fails inside checkFunds
	o := seedDraft(h, 7, DirectionBuy)
	if _, err := h.svc.ConfirmOrder(ctx(), clientUser(7), o.ID); err == nil {
		t.Error("expected check-funds error (account not found)")
	}
}

func TestCancelOrder_ListingError(t *testing.T) {
	h := newHarness()
	o := seedPending(h, 7, DirectionBuy)
	h.market.listingErr = errors.New("boom")
	if _, err := h.svc.CancelOrder(ctx(), clientUser(7), o.ID); err == nil {
		t.Error("expected listing error")
	}
}

func TestCreateSellOrder_PortfolioFindError(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.portfolios.findErr = errors.New("boom")
	_, err := h.svc.CreateSellOrder(ctx(), clientUser(7), api.CreateSellOrderRequest{
		ListingID: int64Ptr(1), Quantity: intPtr(5), AccountID: int64Ptr(5),
	})
	if err == nil {
		t.Error("expected portfolio find error")
	}
}

func TestRepo_FindByIDForUpdate_Success(t *testing.T) {
	r := &Repository{}
	o, err := r.FindByIDForUpdate(ctx(), &fakeDB{row: &fakeRow{vals: orderRow(1)}}, 1)
	if err != nil || o == nil {
		t.Errorf("got %v,%v", o, err)
	}
}

func TestRepo_FindByIDForUpdate_ScanError(t *testing.T) {
	r := &Repository{}
	if _, err := r.FindByIDForUpdate(ctx(), &fakeDB{row: &fakeRow{scanErr: errors.New("boom")}}, 1); err == nil {
		t.Error("expected scan error")
	}
}

func TestDeclineOrder_ListingError(t *testing.T) {
	h := newHarness()
	o := seedPending(h, 1, DirectionBuy)
	// declinePendingOrder succeeds, then the listing lookup for approx fails.
	h.market.listingErr = errors.New("boom")
	if _, err := h.svc.DeclineOrder(ctx(), 99, o.ID); err == nil {
		t.Error("expected listing error")
	}
}

func TestDeclinePendingOrder_ReleaseError(t *testing.T) {
	h := newHarness()
	h.portfolios.findUpdErr = errors.New("boom")
	o := &Order{ID: 1, Direction: DirectionSell, UserID: 1, ListingID: 1, RemainingPortions: 2}
	if err := h.svc.declinePendingOrder(ctx(), nil, o, 99); err == nil {
		t.Error("expected release error")
	}
}

func TestTransferWithConversion_ToAccountError(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(7, "USD", "1000")
	h.account.detailsErr[6] = errors.New("boom")
	if _, err := h.svc.transferWithConversionIfNeeded(ctx(), 5, 6, decimal.RequireFromString("5"), "USD", true); err == nil {
		t.Error("expected to-account error")
	}
}

func TestTransferWithConversion_RateError(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(7, "RSD", "1000")
	h.account.details[6] = acct(7, "USD", "0")
	h.market.calcErr = errors.New("boom")
	if _, err := h.svc.transferWithConversionIfNeeded(ctx(), 5, 6, decimal.RequireFromString("5"), "USD", true); err == nil {
		t.Error("expected rate error")
	}
}

func TestTransferWithConversion_NilRate_FallbackAmount(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(7, "RSD", "1000")
	h.account.details[6] = acct(7, "USD", "0")
	h.market.calc = nil // nil rate -> fromAmount stays targetAmount, commission 0
	amt, err := h.svc.transferWithConversionIfNeeded(ctx(), 5, 6, decimal.RequireFromString("5"), "USD", true)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if !amt.Equal(decimal.RequireFromString("5")) {
		t.Errorf("amt = %s, want 5", amt)
	}
}

func TestTransferWithConversion_PaymentError(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(7, "USD", "1000")
	h.account.details[6] = acct(7, "USD", "0") // same owner -> Transfer
	h.account.transferErr = errors.New("boom")
	if _, err := h.svc.transferWithConversionIfNeeded(ctx(), 5, 6, decimal.RequireFromString("5"), "USD", true); err == nil {
		t.Error("expected payment error")
	}
}

func TestExecuteOrderPortion_ActivateError(t *testing.T) {
	h := newHarness()
	l := listing(1)
	l.Ask = dp("100")
	h.market.listings[1] = l
	o := approvedOrder(h, 7, DirectionBuy, 2)
	o.OrderType = TypeStop
	o.StopValue = dp("50")
	_ = h.repo.Update(ctx(), nil, o)
	h.repo.updateErr = errors.New("boom") // activate persists the type flip -> Update fails
	if _, err := h.svc.executeOrderPortion(ctx(), nil, o.ID); err == nil {
		t.Error("expected activate update error")
	}
}

func TestExecuteOrderPortion_CreateTransactionError(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	o := approvedOrder(h, 7, DirectionBuy, 2)
	h.repo.insertTxErr = errors.New("boom")
	if _, err := h.svc.executeOrderPortion(ctx(), nil, o.ID); err == nil {
		t.Error("expected create-transaction error")
	}
}

func TestExecuteOrderPortion_FinalUpdateError(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.account.details[5] = acct(7, "", "1000000")
	o := approvedOrder(h, 7, DirectionBuy, 2)
	h.repo.updateErr = errors.New("boom") // final managed Update fails
	if _, err := h.svc.executeOrderPortion(ctx(), nil, o.ID); err == nil {
		t.Error("expected final update error")
	}
}

func TestCancelOrder_ReleaseError(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.portfolios.findUpdErr = errors.New("boom")
	o := seedPending(h, 7, DirectionSell)
	if _, err := h.svc.CancelOrder(ctx(), clientUser(7), o.ID); err == nil {
		t.Error("expected release error")
	}
}

func TestCancelOrder_UpdateError(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	o := seedPending(h, 7, DirectionBuy)
	h.repo.updateErr = errors.New("boom")
	if _, err := h.svc.CancelOrder(ctx(), clientUser(7), o.ID); err == nil {
		t.Error("expected update error")
	}
}

// TestNoopNotifier_ViaInterface exercises every NoopNotifier method through the
// Notifier interface so the empty-body calls are not inlined away (the concrete
// calls in notify_test.go are inlined and register no coverage).
func TestNoopNotifier_ViaInterface(t *testing.T) {
	var n Notifier = NoopNotifier{}
	p := api.OrderNotificationPayload{OrderID: 1}
	n.OrderApproved(ctx(), p)
	n.OrderDeclined(ctx(), p)
	n.OrderCreated(ctx(), p)
	n.OrderDone(ctx(), p)
	n.OrderPartialFill(ctx(), p)
	n.OrderAutoCancelled(ctx(), p)
	n.RecurringOrderSkipped(ctx(), api.RecurringOrderSkippedNotification{})
}

// ---------------------------------------------------------------------------
// FundCallback fake error paths driving warn branches in executeOrderPortion
// ---------------------------------------------------------------------------

func TestExecuteOrderPortion_FundCallbackErrors_StillFills(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.account.details[5] = acct(0, "", "1000000")
	h.funds.debitErr = errors.New("debit boom")
	h.funds.addErr = errors.New("add boom")
	o := approvedOrder(h, 1, DirectionBuy, 2)
	pf := PurchaseForInvestmentFund
	o.PurchaseFor = &pf
	o.FundID = int64Ptr(10)
	_ = h.repo.Update(ctx(), nil, o)
	fill, err := h.svc.executeOrderPortion(ctx(), nil, o.ID)
	if err != nil {
		t.Fatalf("fund callback errors are warnings, not failures: %v", err)
	}
	if fill == nil || !fill.executed {
		t.Error("expected fill despite callback warnings")
	}
}

func TestExecuteOrderPortion_FundMissingTicker(t *testing.T) {
	h := newHarness()
	l := listing(1)
	l.Ticker = nil // missing ticker -> AddHolding skipped (warn)
	h.market.listings[1] = l
	h.account.details[5] = acct(0, "", "1000000")
	o := approvedOrder(h, 1, DirectionBuy, 2)
	pf := PurchaseForInvestmentFund
	o.PurchaseFor = &pf
	o.FundID = int64Ptr(10)
	_ = h.repo.Update(ctx(), nil, o)
	fill, err := h.svc.executeOrderPortion(ctx(), nil, o.ID)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if h.funds.holdings != 0 {
		t.Error("missing ticker should skip AddHolding")
	}
	if fill == nil {
		t.Error("expected fill")
	}
}

// ---------------------------------------------------------------------------
// processExecutionAttempt: partial fill path (non-AON, capacity < remaining)
// ---------------------------------------------------------------------------

func TestProcessExecutionAttempt_PartialFill(t *testing.T) {
	h := newHarness()
	l := listing(1)
	vol := int64(1)
	l.Volume = &vol // capacity 1 < remaining
	h.market.listings[1] = l
	h.account.details[5] = acct(7, "", "1000000")
	o := &Order{
		UserID: 7, ListingID: 1, OrderType: TypeMarket, Direction: DirectionBuy,
		Quantity: 5, ContractSize: 1, PricePerUnit: decimal.RequireFromString("10"),
		RemainingPortions: 5, Status: StatusApproved, AccountID: 5, AllOrNone: false,
	}
	_ = h.repo.Insert(ctx(), nil, o)
	h.svc.processExecutionAttempt(o.ID)
	updated, _ := h.repo.FindByID(ctx(), nil, o.ID)
	if updated.RemainingPortions != 4 {
		t.Errorf("remaining = %d, want 4 (1 filled)", updated.RemainingPortions)
	}
	if h.notifier.partial != 1 {
		t.Errorf("partial notifications = %d, want 1", h.notifier.partial)
	}
}

func TestProcessExecutionAttempt_ReloadAfterCommitError(t *testing.T) {
	// The reload after commit fails: cover the reload-error branch.
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.account.details[5] = acct(7, "", "1000000")
	o := approvedOrder(h, 7, DirectionBuy, 2)
	er := &errOnNthFind{fakeOrderRepo: h.repo, failAt: 2} // preload ok, post-commit reload fails
	h.svc.repo = er
	h.svc.processExecutionAttempt(o.ID)
}

// errOnNthFind wraps the fake repo and fails FindByID on the Nth call.
type errOnNthFind struct {
	*fakeOrderRepo
	calls  int
	failAt int
}

func (e *errOnNthFind) FindByID(c context.Context, q Querier, id int64) (*Order, error) {
	e.calls++
	if e.calls == e.failAt {
		return nil, errors.New("reload boom")
	}
	return e.fakeOrderRepo.FindByID(c, q, id)
}
