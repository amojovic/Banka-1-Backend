package order

import (
	"errors"
	"testing"
	"time"

	"banka1/trading-service-go/internal/actuary"
	"banka1/trading-service-go/internal/clients"
	"banka1/trading-service-go/internal/portfolio"

	"github.com/shopspring/decimal"
)

// approvedOrder seeds an APPROVED order ready for execution.
func approvedOrder(h *harness, userID int64, direction string, qty int) *Order {
	o := &Order{
		UserID: userID, ListingID: 1, OrderType: TypeMarket, Direction: direction,
		Quantity: qty, ContractSize: 1, PricePerUnit: decimal.RequireFromString("10"),
		RemainingPortions: qty, Status: StatusApproved, AccountID: 5, AllOrNone: true,
	}
	_ = h.repo.Insert(ctx(), nil, o)
	return o
}

// ---------------------------------------------------------------------------
// processExecutionAttempt
// ---------------------------------------------------------------------------

func TestProcessExecutionAttempt_PreloadError(t *testing.T) {
	h := newHarness()
	h.repo.findErr = errors.New("boom")
	h.svc.processExecutionAttempt(1) // must not panic
}

func TestProcessExecutionAttempt_NotExecutable_Returns(t *testing.T) {
	h := newHarness()
	o := approvedOrder(h, 7, DirectionBuy, 2)
	o.Status = StatusDone
	_ = h.repo.Update(ctx(), nil, o)
	h.svc.processExecutionAttempt(o.ID) // returns without filling
	if len(h.repo.insertedTxns) != 0 {
		t.Error("done order should not execute")
	}
}

func TestProcessExecutionAttempt_BuyFillsAndDone(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.account.details[5] = acct(7, "", "100000")
	o := approvedOrder(h, 7, DirectionBuy, 2)
	h.svc.processExecutionAttempt(o.ID)
	updated, _ := h.repo.FindByID(ctx(), nil, o.ID)
	if updated.Status != StatusDone {
		t.Errorf("status = %q, want DONE", updated.Status)
	}
	if h.notifier.done != 1 {
		t.Errorf("done notifications = %d, want 1", h.notifier.done)
	}
}

func TestProcessExecutionAttempt_TxError_Reschedules(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	o := approvedOrder(h, 7, DirectionBuy, 2)
	h.account.detailsErr[5] = errors.New("boom") // transferFunds fails inside tx
	h.svc.processExecutionAttempt(o.ID)          // logs + reschedules retry (no panic)
}

// ---------------------------------------------------------------------------
// executeOrderPortion
// ---------------------------------------------------------------------------

func TestExecuteOrderPortion_NotExecutable(t *testing.T) {
	h := newHarness()
	o := approvedOrder(h, 7, DirectionBuy, 2)
	o.Status = StatusDone
	_ = h.repo.Update(ctx(), nil, o)
	fill, err := h.svc.executeOrderPortion(ctx(), nil, o.ID)
	if err != nil || fill != nil {
		t.Errorf("got %v,%v want nil,nil", fill, err)
	}
}

func TestExecuteOrderPortion_MissingQuote_Noop(t *testing.T) {
	h := newHarness()
	l := listing(1)
	l.Ask = nil // BUY needs ask
	l.Price = dp("10")
	h.market.listings[1] = l
	o := approvedOrder(h, 7, DirectionBuy, 2)
	fill, err := h.svc.executeOrderPortion(ctx(), nil, o.ID)
	if err != nil || fill != nil {
		t.Errorf("got %v,%v want nil,nil", fill, err)
	}
}

func TestExecuteOrderPortion_NotEligibleLimit_Noop(t *testing.T) {
	h := newHarness()
	l := listing(1)
	l.Ask = dp("100")
	h.market.listings[1] = l
	o := approvedOrder(h, 7, DirectionBuy, 2)
	o.OrderType = TypeLimit
	o.LimitValue = dp("10") // ask 100 > limit 10 -> not executable
	_ = h.repo.Update(ctx(), nil, o)
	fill, err := h.svc.executeOrderPortion(ctx(), nil, o.ID)
	if err != nil || fill != nil {
		t.Errorf("got %v,%v want nil,nil", fill, err)
	}
}

func TestExecuteOrderPortion_AONExceedsCapacity_Noop(t *testing.T) {
	h := newHarness()
	l := listing(1)
	vol := int64(1)
	l.Volume = &vol
	h.market.listings[1] = l
	o := approvedOrder(h, 7, DirectionBuy, 5) // AON, remaining 5 but capacity 1
	fill, err := h.svc.executeOrderPortion(ctx(), nil, o.ID)
	if err != nil || fill != nil {
		t.Errorf("AON over capacity should be noop, got %v,%v", fill, err)
	}
}

func TestExecuteOrderPortion_SellUpdatesPortfolio(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.account.details[5] = acct(7, "", "100000")
	h.account.details[0] = acct(0, "", "0") // bank account for sell commission leg
	h.portfolios.positions[[2]int64{7, 1}] = &portfolio.Portfolio{ID: 1, UserID: 7, ListingID: 1, Quantity: 10, ReservedQuantity: 2}
	o := approvedOrder(h, 7, DirectionSell, 2)
	fill, err := h.svc.executeOrderPortion(ctx(), nil, o.ID)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if fill == nil || !fill.done {
		t.Errorf("expected a done fill, got %v", fill)
	}
}

func TestExecuteOrderPortion_FundBuy_Callbacks(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
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
	if fill == nil || !fill.executed {
		t.Fatal("expected executed fill")
	}
	if h.funds.debits != 1 || h.funds.holdings != 1 {
		t.Errorf("fund callbacks: debits=%d holdings=%d", h.funds.debits, h.funds.holdings)
	}
}

func TestExecuteOrderPortion_TransferFundsError(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.account.detailsErr[5] = errors.New("boom")
	o := approvedOrder(h, 7, DirectionBuy, 2)
	_, err := h.svc.executeOrderPortion(ctx(), nil, o.ID)
	if err == nil {
		t.Error("expected transfer error")
	}
}

func TestExecuteOrderPortion_FindForUpdateError(t *testing.T) {
	h := newHarness()
	h.repo.findErr = errors.New("boom")
	_, err := h.svc.executeOrderPortion(ctx(), nil, 1)
	if err == nil {
		t.Error("expected find error")
	}
}

// ---------------------------------------------------------------------------
// activateIfEligible
// ---------------------------------------------------------------------------

func TestActivateIfEligible_StopBuy_Activates(t *testing.T) {
	h := newHarness()
	l := listing(1)
	l.Ask = dp("100")
	o := &Order{ID: 1, OrderType: TypeStop, Direction: DirectionBuy, StopValue: dp("50")}
	ok, err := h.svc.activateIfEligible(ctx(), nil, o, l)
	if err != nil || !ok {
		t.Errorf("expected activation, got %v,%v", ok, err)
	}
	if o.OrderType != TypeMarket {
		t.Errorf("STOP should become MARKET, got %q", o.OrderType)
	}
}

func TestActivateIfEligible_StopLimit_Activates(t *testing.T) {
	h := newHarness()
	l := listing(1)
	l.Ask = dp("100")
	o := &Order{ID: 1, OrderType: TypeStopLimit, Direction: DirectionBuy, StopValue: dp("50"), LimitValue: dp("110")}
	ok, err := h.svc.activateIfEligible(ctx(), nil, o, l)
	if err != nil || !ok {
		t.Errorf("expected activation, got %v,%v", ok, err)
	}
	if o.OrderType != TypeLimit {
		t.Errorf("STOP_LIMIT should become LIMIT, got %q", o.OrderType)
	}
}

func TestActivateIfEligible_StopNotActivated(t *testing.T) {
	h := newHarness()
	l := listing(1)
	l.Ask = dp("10")
	o := &Order{ID: 1, OrderType: TypeStop, Direction: DirectionBuy, StopValue: dp("50")}
	ok, err := h.svc.activateIfEligible(ctx(), nil, o, l)
	if err != nil || ok {
		t.Errorf("expected no activation, got %v,%v", ok, err)
	}
}

func TestActivateIfEligible_StopQuoteUnavailable(t *testing.T) {
	h := newHarness()
	l := listing(1)
	l.Ask = nil
	o := &Order{ID: 1, OrderType: TypeStop, Direction: DirectionBuy, StopValue: dp("50")}
	ok, err := h.svc.activateIfEligible(ctx(), nil, o, l)
	if err != nil || ok {
		t.Errorf("expected no activation, got %v,%v", ok, err)
	}
}

func TestActivateIfEligible_MarketDefault(t *testing.T) {
	h := newHarness()
	o := &Order{ID: 1, OrderType: TypeMarket, Direction: DirectionBuy}
	ok, err := h.svc.activateIfEligible(ctx(), nil, o, listing(1))
	if err != nil || !ok {
		t.Errorf("MARKET always eligible, got %v,%v", ok, err)
	}
}

func TestActivateIfEligible_StopSellActivates(t *testing.T) {
	h := newHarness()
	l := listing(1)
	l.Bid = dp("40")
	o := &Order{ID: 1, OrderType: TypeStop, Direction: DirectionSell, StopValue: dp("50")}
	ok, err := h.svc.activateIfEligible(ctx(), nil, o, l)
	if err != nil || !ok {
		t.Errorf("SELL stop bid<stop should activate, got %v,%v", ok, err)
	}
}

// ---------------------------------------------------------------------------
// transferFunds
// ---------------------------------------------------------------------------

func TestTransferFunds_BuyExchange(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(7, "", "100000")
	o := &Order{AccountID: 5, Direction: DirectionBuy}
	amt, err := h.svc.transferFunds(ctx(), o, "", decimal.RequireFromString("50"))
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if !amt.Equal(decimal.RequireFromString("50")) {
		t.Errorf("amt = %s", amt)
	}
	if len(h.account.exBuys) != 1 {
		t.Errorf("expected exchange buy, got %d", len(h.account.exBuys))
	}
}

func TestTransferFunds_SellExchange(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(7, "", "100000")
	o := &Order{AccountID: 5, Direction: DirectionSell}
	_, err := h.svc.transferFunds(ctx(), o, "", decimal.RequireFromString("50"))
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(h.account.exSells) != 1 {
		t.Errorf("expected exchange sell, got %d", len(h.account.exSells))
	}
}

func TestTransferFunds_BankNoComm(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(0, "", "100000")
	pf := PurchaseForBank
	o := &Order{AccountID: 5, Direction: DirectionBuy, PurchaseFor: &pf}
	_, err := h.svc.transferFunds(ctx(), o, "", decimal.RequireFromString("50"))
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
}

func TestTransferFunds_MarginBuy(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(7, "", "100000")
	o := &Order{AccountID: 5, Direction: DirectionBuy, Margin: true, UserID: 7}
	_, err := h.svc.transferFunds(ctx(), o, "", decimal.RequireFromString("50"))
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(h.account.marginBuys) != 1 {
		t.Errorf("expected margin buy, got %d", len(h.account.marginBuys))
	}
}

func TestTransferFunds_MarginSell(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(7, "", "100000")
	o := &Order{AccountID: 5, Direction: DirectionSell, Margin: true, UserID: 7}
	_, err := h.svc.transferFunds(ctx(), o, "", decimal.RequireFromString("50"))
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(h.account.marginSells) != 1 {
		t.Errorf("expected margin sell, got %d", len(h.account.marginSells))
	}
}

func TestTransferFunds_AccountError(t *testing.T) {
	h := newHarness()
	h.account.detailsErr[5] = errors.New("boom")
	_, err := h.svc.transferFunds(ctx(), &Order{AccountID: 5, Direction: DirectionBuy}, "", decimal.RequireFromString("50"))
	if err == nil {
		t.Error("expected account error")
	}
}

// ---------------------------------------------------------------------------
// updatePortfolio
// ---------------------------------------------------------------------------

func TestUpdatePortfolio_BuyNew(t *testing.T) {
	h := newHarness()
	o := &Order{UserID: 7, ListingID: 1, Direction: DirectionBuy}
	if err := h.svc.updatePortfolio(ctx(), nil, o, listing(1), 5, decimal.RequireFromString("10")); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if !h.portfolios.inserted {
		t.Error("expected insert of new position")
	}
}

func TestUpdatePortfolio_BuyMerge(t *testing.T) {
	h := newHarness()
	h.portfolios.positions[[2]int64{7, 1}] = &portfolio.Portfolio{ID: 1, UserID: 7, ListingID: 1, Quantity: 10, AveragePurchasePrice: decimal.RequireFromString("8")}
	o := &Order{UserID: 7, ListingID: 1, Direction: DirectionBuy}
	if err := h.svc.updatePortfolio(ctx(), nil, o, listing(1), 5, decimal.RequireFromString("12")); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	p := h.portfolios.positions[[2]int64{7, 1}]
	if p.Quantity != 15 {
		t.Errorf("merged quantity = %d, want 15", p.Quantity)
	}
}

func TestUpdatePortfolio_SellDeletesOnZero(t *testing.T) {
	h := newHarness()
	h.portfolios.positions[[2]int64{7, 1}] = &portfolio.Portfolio{ID: 1, UserID: 7, ListingID: 1, Quantity: 5, ReservedQuantity: 5}
	o := &Order{UserID: 7, ListingID: 1, Direction: DirectionSell}
	if err := h.svc.updatePortfolio(ctx(), nil, o, listing(1), 5, decimal.RequireFromString("10")); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if !h.portfolios.deleted {
		t.Error("expected delete on zero position")
	}
}

func TestUpdatePortfolio_SellDecrement(t *testing.T) {
	h := newHarness()
	h.portfolios.positions[[2]int64{7, 1}] = &portfolio.Portfolio{ID: 1, UserID: 7, ListingID: 1, Quantity: 10, ReservedQuantity: 5, PublicQuantity: 8}
	o := &Order{UserID: 7, ListingID: 1, Direction: DirectionSell}
	if err := h.svc.updatePortfolio(ctx(), nil, o, listing(1), 3, decimal.RequireFromString("10")); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	p := h.portfolios.positions[[2]int64{7, 1}]
	if p.Quantity != 7 {
		t.Errorf("quantity = %d, want 7", p.Quantity)
	}
}

func TestUpdatePortfolio_SellWithoutOwned_Error(t *testing.T) {
	h := newHarness()
	o := &Order{ID: 9, UserID: 7, ListingID: 1, Direction: DirectionSell}
	if err := h.svc.updatePortfolio(ctx(), nil, o, listing(1), 5, decimal.RequireFromString("10")); err == nil {
		t.Error("expected error selling without owned quantity")
	}
}

func TestUpdatePortfolio_FindError(t *testing.T) {
	h := newHarness()
	h.portfolios.findUpdErr = errors.New("boom")
	o := &Order{UserID: 7, ListingID: 1, Direction: DirectionBuy}
	if err := h.svc.updatePortfolio(ctx(), nil, o, listing(1), 5, decimal.RequireFromString("10")); err == nil {
		t.Error("expected find error")
	}
}

// ---------------------------------------------------------------------------
// transferSellCommission
// ---------------------------------------------------------------------------

func TestTransferSellCommission_ZeroCommission_Noop(t *testing.T) {
	h := newHarness()
	if err := h.svc.transferSellCommission(ctx(), &Order{}, "USD", decimal.Zero); err != nil {
		t.Errorf("unexpected: %v", err)
	}
}

func TestTransferSellCommission_FundedByBank_Skipped(t *testing.T) {
	h := newHarness()
	bankID := int64(5)
	h.account.bank = &clients.BankAccount{ID: &bankID}
	o := &Order{AccountID: 5}
	if err := h.svc.transferSellCommission(ctx(), o, "USD", decimal.RequireFromString("5")); err != nil {
		t.Errorf("unexpected: %v", err)
	}
}

func TestTransferSellCommission_Transfers(t *testing.T) {
	h := newHarness()
	bankID := int64(50)
	h.account.bank = &clients.BankAccount{ID: &bankID}
	h.account.details[5] = acct(7, "", "1000")
	h.account.details[50] = acct(0, "", "0")
	o := &Order{AccountID: 5}
	if err := h.svc.transferSellCommission(ctx(), o, "", decimal.RequireFromString("5")); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(h.account.transactions) != 1 {
		t.Errorf("expected 1 transaction, got %d", len(h.account.transactions))
	}
}

func TestTransferSellCommission_BankError(t *testing.T) {
	h := newHarness()
	h.account.bankErr = errors.New("boom")
	if err := h.svc.transferSellCommission(ctx(), &Order{}, "USD", decimal.RequireFromString("5")); err == nil {
		t.Error("expected bank error")
	}
}

// ---------------------------------------------------------------------------
// finalizeActuaryExposure
// ---------------------------------------------------------------------------

func TestFinalizeActuaryExposure_NotBankFunded_Noop(t *testing.T) {
	h := newHarness()
	bankID := int64(50)
	h.account.bank = &clients.BankAccount{ID: &bankID}
	o := &Order{AccountID: 5} // not the bank account
	if err := h.svc.finalizeActuaryExposure(ctx(), nil, o, "RSD", decimal.RequireFromString("10")); err != nil {
		t.Errorf("unexpected: %v", err)
	}
}

func TestFinalizeActuaryExposure_BankFunded_MovesReservedToUsed(t *testing.T) {
	h := newHarness()
	bankID := int64(5)
	h.account.bank = &clients.BankAccount{ID: &bankID}
	h.actuaries.infos[1] = &actuary.ActuaryInfo{EmployeeID: 1, ReservedLimit: decimal.RequireFromString("100"), UsedLimit: decimal.Zero}
	o := &Order{AccountID: 5, UserID: 1, ReservedLimitExposure: decimal.RequireFromString("100")}
	if err := h.svc.finalizeActuaryExposure(ctx(), nil, o, "RSD", decimal.RequireFromString("30")); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	ru := h.actuaries.reservedUsed[1]
	if !ru[0].Equal(decimal.RequireFromString("70")) || !ru[1].Equal(decimal.RequireFromString("30")) {
		t.Errorf("reserved/used = %s/%s, want 70/30", ru[0], ru[1])
	}
}

func TestFinalizeActuaryExposure_NoActuary_Noop(t *testing.T) {
	h := newHarness()
	bankID := int64(5)
	h.account.bank = &clients.BankAccount{ID: &bankID}
	o := &Order{AccountID: 5, UserID: 1}
	if err := h.svc.finalizeActuaryExposure(ctx(), nil, o, "RSD", decimal.RequireFromString("30")); err != nil {
		t.Errorf("unexpected: %v", err)
	}
}

func TestFinalizeActuaryExposure_BankError(t *testing.T) {
	h := newHarness()
	h.account.bankErr = errors.New("boom")
	if err := h.svc.finalizeActuaryExposure(ctx(), nil, &Order{}, "RSD", decimal.RequireFromString("30")); err == nil {
		t.Error("expected bank error")
	}
}

// ---------------------------------------------------------------------------
// calculateExecutionDelay
// ---------------------------------------------------------------------------

func TestCalculateExecutionDelay_MissingQuote_Backoff(t *testing.T) {
	h := newHarness()
	l := listing(1)
	l.Price = nil
	h.market.listings[1] = l
	d := h.svc.calculateExecutionDelay(ctx(), &Order{ListingID: 1, Direction: DirectionBuy, RemainingPortions: 1})
	if d != missingQuoteRetryDelay {
		t.Errorf("delay = %v, want %v", d, missingQuoteRetryDelay)
	}
}

func TestCalculateExecutionDelay_ListingError_Backoff(t *testing.T) {
	h := newHarness()
	h.market.listingErr = errors.New("boom")
	d := h.svc.calculateExecutionDelay(ctx(), &Order{ListingID: 1, Direction: DirectionBuy, RemainingPortions: 1})
	if d != missingQuoteRetryDelay {
		t.Errorf("delay = %v", d)
	}
}

func TestCalculateExecutionDelay_AfterHours_AddsExtra(t *testing.T) {
	h := newHarness()
	l := listing(1)
	vol := int64(100)
	l.Volume = &vol
	h.market.listings[1] = l
	d := h.svc.calculateExecutionDelay(ctx(), &Order{ListingID: 1, Direction: DirectionBuy, RemainingPortions: 1, AfterHours: true})
	if d < afterHoursExtraDelay {
		t.Errorf("after-hours delay %v should be >= %v", d, afterHoursExtraDelay)
	}
}

func TestCalculateExecutionDelay_Normal(t *testing.T) {
	h := newHarness()
	l := listing(1)
	vol := int64(1440)
	l.Volume = &vol
	h.market.listings[1] = l
	d := h.svc.calculateExecutionDelay(ctx(), &Order{ListingID: 1, Direction: DirectionBuy, RemainingPortions: 1})
	if d < 0 {
		t.Error("delay should be non-negative")
	}
}

func TestCreateTransaction(t *testing.T) {
	h := newHarness()
	o := &Order{ID: 1}
	if err := h.svc.createTransaction(ctx(), nil, o, 2, decimal.RequireFromString("10"), decimal.RequireFromString("20"), decimal.RequireFromString("1")); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(h.repo.insertedTxns) != 1 {
		t.Errorf("expected 1 inserted txn, got %d", len(h.repo.insertedTxns))
	}
}

// ---------------------------------------------------------------------------
// hasRequiredQuoteData / currentExecutableCapacity edge
// ---------------------------------------------------------------------------

func TestHasRequiredQuoteData_NilListing(t *testing.T) {
	if hasRequiredQuoteData(&Order{}, nil) {
		t.Error("nil listing should be false")
	}
}

func TestHasRequiredQuoteData_SellNeedsBid(t *testing.T) {
	l := &clients.StockListing{Price: dp("10"), Bid: dp("9")}
	if !hasRequiredQuoteData(&Order{Direction: DirectionSell}, l) {
		t.Error("sell with bid should be true")
	}
	l.Bid = nil
	if hasRequiredQuoteData(&Order{Direction: DirectionSell}, l) {
		t.Error("sell without bid should be false")
	}
}

func TestExecutable(t *testing.T) {
	if executable(nil) {
		t.Error("nil not executable")
	}
	if executable(&Order{Status: StatusApproved, RemainingPortions: 0}) {
		t.Error("zero remaining not executable")
	}
	if !executable(&Order{Status: StatusApproved, RemainingPortions: 1}) {
		t.Error("approved with remaining is executable")
	}
}

func TestIsExecutableAtCurrentMarket_LimitNilValue(t *testing.T) {
	if isExecutableAtCurrentMarket(&Order{OrderType: TypeLimit}, &clients.StockListing{}) {
		t.Error("limit with nil value not executable")
	}
}

func TestIsExecutableAtCurrentMarket_LimitSellBidMissing(t *testing.T) {
	o := &Order{OrderType: TypeLimit, Direction: DirectionSell, LimitValue: dp("10")}
	if isExecutableAtCurrentMarket(o, &clients.StockListing{}) {
		t.Error("limit sell without bid not executable")
	}
}

func TestIsExecutableAtCurrentMarket_LimitSellExecutable(t *testing.T) {
	o := &Order{OrderType: TypeLimit, Direction: DirectionSell, LimitValue: dp("10")}
	if !isExecutableAtCurrentMarket(o, &clients.StockListing{Bid: dp("12")}) {
		t.Error("limit sell bid>=limit executable")
	}
}

func TestDetermineExecutionQuantity_NonAONInRange(t *testing.T) {
	for i := 0; i < 100; i++ {
		q := determineExecutionQuantity(&Order{RemainingPortions: 50}, 4)
		if q < 1 || q > 4 {
			t.Fatalf("out of range: %d", q)
		}
	}
}

func TestExecuteOrderPortion_ListingError(t *testing.T) {
	h := newHarness()
	o := approvedOrder(h, 7, DirectionBuy, 2)
	h.market.listingErr = errors.New("boom")
	_, err := h.svc.executeOrderPortion(ctx(), nil, o.ID)
	if err == nil {
		t.Error("expected listing error")
	}
}

var _ = time.Second
