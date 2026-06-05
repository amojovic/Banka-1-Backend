package tax

import (
	"context"
	"errors"
	"testing"
	"time"

	"banka1/trading-service-go/internal/clients"
	"banka1/trading-service-go/internal/order"
	"banka1/trading-service-go/internal/portfolio"
)

// withClock overrides clockNow for the duration of fn.
func withClock(t time.Time, fn func()) {
	prev := clockNow
	clockNow = func() time.Time { return t }
	defer func() { clockNow = prev }()
	fn()
}

func acct(number string) *clients.AccountDetails {
	return &clients.AccountDetails{AccountNumber: &number}
}

// ------------------------------------------------------------------
// NewService — production constructor still wires concrete deps.
// ------------------------------------------------------------------

func TestNewService_NilNotifierDefaultsNoop(t *testing.T) {
	cl := &clients.Clients{
		Market:   clients.NewMarketClient("http://m", nil, fakeDoer{status: 200, body: "{}"}),
		Account:  clients.NewAccountClient("http://a", nil, fakeDoer{status: 200, body: "{}"}),
		Employee: clients.NewEmployeeClient("http://e", nil, fakeDoer{status: 200, body: "{}"}),
		Customer: clients.NewCustomerClient("http://c", nil, fakeDoer{status: 200, body: "{}"}),
	}
	s := NewService(NewRepository(nil), order.NewRepository(nil), portfolio.NewRepository(nil),
		nil, cl, nil, dec("0.15"), quietLogger())
	if s == nil {
		t.Fatal("NewService returned nil")
	}
	if _, ok := s.notifier.(NoopNotifier); !ok {
		t.Errorf("nil notifier should default to NoopNotifier, got %T", s.notifier)
	}
}

func TestNewServiceForTest_NilNotifierAndLogger(t *testing.T) {
	s := NewServiceForTest(&stubTaxRepo{}, &stubOrderRepo{}, &stubPortfolioRepo{}, &stubActuaryRepo{},
		&stubMarket{}, &stubAccount{}, &stubEmployee{}, &stubCustomer{}, nil, dec("0.15"), nil)
	if _, ok := s.notifier.(NoopNotifier); !ok {
		t.Errorf("expected NoopNotifier, got %T", s.notifier)
	}
	if s.logger == nil {
		t.Error("logger should default to a discard logger")
	}
}

// ------------------------------------------------------------------
// buildTaxChargeEntries — the FIFO engine end to end.
// ------------------------------------------------------------------

// seedSingleUserStockHistory wires a buy@100 (5sh) then sell@150 (5sh) for one
// user/listing, all USD STOCK, with the sell inside [taxStart,taxEnd).
func seedSingleUserStockHistory(h *harness) {
	so := sellOrder(10, 7, 99, 70)
	bo := buyOrder(20, 7, 99, 70)
	h.order.byDirection = map[string][]order.Order{order.DirectionSell: {so}}
	h.order.byUserID = map[int64][]order.Order{7: {so, bo}}
	h.order.byID = map[int64]*order.Order{10: &so, 20: &bo}
	// relevant sell tx (between) — only the SELL portion.
	sellTxn := tx(100, 10, 5, "150", inWindow)
	h.order.txBetween = []order.Transaction{sellTxn}
	// historical tx (before end) — the BUY then the SELL.
	buyTxn := tx(200, 20, 5, "100", taxDate(2026, 4, 1))
	h.order.txBefore = []order.Transaction{buyTxn, sellTxn}
	h.market.listings = map[int64]*clients.StockListing{99: usdListing()}
}

func TestBuildTaxChargeEntries_SingleUserFIFO(t *testing.T) {
	h := newHarness("0.15")
	seedSingleUserStockHistory(h)
	entries, err := h.svc.buildTaxChargeEntries(context.Background(), taxStart, taxEnd, nil)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(entries) != 1 {
		t.Fatalf("want 1 entry, got %d: %+v", len(entries), entries)
	}
	// gain 50/sh * 5 = 250 taxable * 0.15 = 37.50
	if !entries[0].taxAmount.Equal(dec("37.5")) {
		t.Errorf("tax = %s, want 37.5", entries[0].taxAmount)
	}
	if entries[0].buyTransactionID != 200 || entries[0].currency != "USD" {
		t.Errorf("entry metadata wrong: %+v", entries[0])
	}
}

func TestBuildTaxChargeEntries_NoSellOrders(t *testing.T) {
	h := newHarness("0.15")
	entries, err := h.svc.buildTaxChargeEntries(context.Background(), taxStart, taxEnd, nil)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if entries != nil {
		t.Errorf("want nil entries, got %+v", entries)
	}
}

func TestBuildTaxChargeEntries_SellOrdersError(t *testing.T) {
	h := newHarness("0.15")
	h.order.byDirectionErr = errors.New("db down")
	if _, err := h.svc.buildTaxChargeEntries(context.Background(), taxStart, taxEnd, nil); err == nil {
		t.Error("expected error from FindByDirection")
	}
}

func TestBuildTaxChargeEntries_UserFilterUsesByUserAndDirection(t *testing.T) {
	h := newHarness("0.15")
	seedSingleUserStockHistory(h)
	// Move the sell order into the user-and-direction map for the filtered path.
	so := sellOrder(10, 7, 99, 70)
	h.order.byUserAndDirection = map[int64][]order.Order{7: {so}}
	uid := int64(7)
	entries, err := h.svc.buildTaxChargeEntries(context.Background(), taxStart, taxEnd, &uid)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(entries) != 1 {
		t.Fatalf("want 1 entry, got %d", len(entries))
	}
}

func TestBuildTaxChargeEntries_UserFilterError(t *testing.T) {
	h := newHarness("0.15")
	h.order.byUserAndDirErr = errors.New("boom")
	uid := int64(7)
	if _, err := h.svc.buildTaxChargeEntries(context.Background(), taxStart, taxEnd, &uid); err == nil {
		t.Error("expected error")
	}
}

func TestBuildTaxChargeEntries_NoRelevantSellTxns(t *testing.T) {
	h := newHarness("0.15")
	so := sellOrder(10, 7, 99, 70)
	h.order.byDirection = map[string][]order.Order{order.DirectionSell: {so}}
	h.order.byID = map[int64]*order.Order{10: &so}
	h.market.listings = map[int64]*clients.StockListing{99: usdListing()}
	// No transactions between → relevantSell empty → nil entries.
	entries, err := h.svc.buildTaxChargeEntries(context.Background(), taxStart, taxEnd, nil)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if entries != nil {
		t.Errorf("want nil, got %+v", entries)
	}
}

func TestBuildTaxChargeEntries_TransactionsBetweenError(t *testing.T) {
	h := newHarness("0.15")
	so := sellOrder(10, 7, 99, 70)
	h.order.byDirection = map[string][]order.Order{order.DirectionSell: {so}}
	h.order.byID = map[int64]*order.Order{10: &so}
	h.market.listings = map[int64]*clients.StockListing{99: usdListing()}
	h.order.txBetweenErr = errors.New("boom")
	if _, err := h.svc.buildTaxChargeEntries(context.Background(), taxStart, taxEnd, nil); err == nil {
		t.Error("expected error from txBetween")
	}
}

func TestBuildTaxChargeEntries_HistoricalError(t *testing.T) {
	h := newHarness("0.15")
	seedSingleUserStockHistory(h)
	h.order.byUserIDErr = errors.New("boom")
	if _, err := h.svc.buildTaxChargeEntries(context.Background(), taxStart, taxEnd, nil); err == nil {
		t.Error("expected error from FindByUserID")
	}
}

func TestBuildTaxChargeEntries_NoHistoricalTxns(t *testing.T) {
	h := newHarness("0.15")
	so := sellOrder(10, 7, 99, 70)
	h.order.byDirection = map[string][]order.Order{order.DirectionSell: {so}}
	h.order.byUserID = map[int64][]order.Order{7: {so}}
	h.order.byID = map[int64]*order.Order{10: &so}
	sellTxn := tx(100, 10, 5, "150", inWindow)
	h.order.txBetween = []order.Transaction{sellTxn}
	h.order.txBefore = nil // historical empty
	h.market.listings = map[int64]*clients.StockListing{99: usdListing()}
	entries, err := h.svc.buildTaxChargeEntries(context.Background(), taxStart, taxEnd, nil)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if entries != nil {
		t.Errorf("want nil entries, got %+v", entries)
	}
}

func TestBuildTaxChargeEntries_PortfolioFallback(t *testing.T) {
	h := newHarness("0.15")
	so := sellOrder(10, 7, 99, 70)
	h.order.byDirection = map[string][]order.Order{order.DirectionSell: {so}}
	h.order.byUserID = map[int64][]order.Order{7: {so}}
	h.order.byID = map[int64]*order.Order{10: &so}
	sellTxn := tx(100, 10, 4, "150", inWindow)
	h.order.txBetween = []order.Transaction{sellTxn}
	h.order.txBefore = []order.Transaction{sellTxn} // only the SELL — no buy lots
	h.market.listings = map[int64]*clients.StockListing{99: usdListing()}
	h.portfolio.byKey = map[[2]int64]*portfolio.Portfolio{
		{7, 99}: {AveragePurchasePrice: dec("100")},
	}
	entries, err := h.svc.buildTaxChargeEntries(context.Background(), taxStart, taxEnd, nil)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(entries) != 1 {
		t.Fatalf("want 1 fallback entry, got %d", len(entries))
	}
	// gain 50/sh * 4 = 200 * 0.15 = 30
	if !entries[0].taxAmount.Equal(dec("30")) || entries[0].buyTransactionID != -1 {
		t.Errorf("fallback entry wrong: %+v", entries[0])
	}
}

func TestBuildTaxChargeEntries_PortfolioLookupErrorSkips(t *testing.T) {
	h := newHarness("0.15")
	so := sellOrder(10, 7, 99, 70)
	h.order.byDirection = map[string][]order.Order{order.DirectionSell: {so}}
	h.order.byUserID = map[int64][]order.Order{7: {so}}
	h.order.byID = map[int64]*order.Order{10: &so}
	sellTxn := tx(100, 10, 4, "150", inWindow)
	h.order.txBetween = []order.Transaction{sellTxn}
	h.order.txBefore = []order.Transaction{sellTxn}
	h.market.listings = map[int64]*clients.StockListing{99: usdListing()}
	h.portfolio.err = errors.New("portfolio down")
	entries, err := h.svc.buildTaxChargeEntries(context.Background(), taxStart, taxEnd, nil)
	if err != nil {
		t.Fatalf("the per-tx error must be swallowed, got %v", err)
	}
	if len(entries) != 0 {
		t.Errorf("want 0 entries (sell skipped), got %d", len(entries))
	}
}

func TestBuildTaxChargeEntries_SkipsNonStockAndZeroQty(t *testing.T) {
	h := newHarness("0.15")
	so := sellOrder(10, 7, 99, 70)
	h.order.byDirection = map[string][]order.Order{order.DirectionSell: {so}}
	h.order.byUserID = map[int64][]order.Order{7: {so}}
	h.order.byID = map[int64]*order.Order{10: &so}
	h.order.txBetween = []order.Transaction{tx(100, 10, 5, "150", inWindow)}
	// non-stock listing → relevantSell filters it out
	h.market.listings = map[int64]*clients.StockListing{99: nonStockListing()}
	entries, err := h.svc.buildTaxChargeEntries(context.Background(), taxStart, taxEnd, nil)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if entries != nil {
		t.Errorf("non-stock should produce nil entries, got %+v", entries)
	}
}

// ------------------------------------------------------------------
// collectTaxForPeriod / chargeStockEntry — settlement.
// ------------------------------------------------------------------

func TestCollectMonthlyTax_FullStockSettlement(t *testing.T) {
	h := newHarness("0.15")
	seedSingleUserStockHistory(h)
	h.account.details = map[int64]*clients.AccountDetails{70: acct("111")}
	h.account.govAccount = acct("999")
	h.market.convertResp = fxRate("4387.5") // 37.5 USD → RSD
	h.customer.customers = map[int64]*clients.Customer{7: {ID: 7, FirstName: strptr("Ana"), Email: strptr("a@x")}}

	withClock(time.Date(2026, 6, 15, 0, 0, 0, 0, time.UTC), func() {
		if err := h.svc.CollectMonthlyTax(context.Background()); err != nil {
			t.Fatalf("unexpected: %v", err)
		}
	})
	if len(h.tax.inserted) != 1 {
		t.Fatalf("want 1 reservation, got %d", len(h.tax.inserted))
	}
	if len(h.account.payments) != 1 {
		t.Fatalf("want 1 payment, got %d", len(h.account.payments))
	}
	if h.account.payments[0].FromAccountNumber != "111" || h.account.payments[0].ToAccountNumber != "999" {
		t.Errorf("payment accounts wrong: %+v", h.account.payments[0])
	}
	if len(h.tax.updateChargedID) != 1 {
		t.Errorf("want 1 UpdateCharged, got %d", len(h.tax.updateChargedID))
	}
	if len(h.notifier.payloads) != 1 {
		t.Errorf("want 1 notification, got %d", len(h.notifier.payloads))
	}
	if h.notifier.payloads[0].Username == nil || *h.notifier.payloads[0].Username != "Ana" {
		t.Errorf("notification username wrong: %+v", h.notifier.payloads[0].Username)
	}
}

func TestCollectMonthlyTaxManually(t *testing.T) {
	h := newHarness("0.15")
	withClock(time.Date(2026, 6, 15, 0, 0, 0, 0, time.UTC), func() {
		if err := h.svc.CollectMonthlyTaxManually(context.Background()); err != nil {
			t.Fatalf("unexpected: %v", err)
		}
	})
}

func TestCollectCurrentMonthTax(t *testing.T) {
	h := newHarness("0.15")
	withClock(time.Date(2026, 6, 15, 0, 0, 0, 0, time.UTC), func() {
		if err := h.svc.CollectCurrentMonthTax(context.Background()); err != nil {
			t.Fatalf("unexpected: %v", err)
		}
	})
}

func TestCollectTaxForPeriod_BuildError(t *testing.T) {
	h := newHarness("0.15")
	h.order.byDirectionErr = errors.New("boom")
	if err := h.svc.collectTaxForPeriod(context.Background(), taxStart, taxEnd); err == nil {
		t.Error("expected error from buildTaxChargeEntries")
	}
}

func TestCollectTaxForPeriod_ReserveExistsSkips(t *testing.T) {
	h := newHarness("0.15")
	seedSingleUserStockHistory(h)
	h.tax.existsSellBuy = true // already reserved → skip
	h.account.govAccount = acct("999")
	if err := h.svc.collectTaxForPeriod(context.Background(), taxStart, taxEnd); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(h.account.payments) != 0 {
		t.Errorf("skipped entry must not pay, got %d payments", len(h.account.payments))
	}
}

func TestCollectTaxForPeriod_ReserveExistsError(t *testing.T) {
	h := newHarness("0.15")
	seedSingleUserStockHistory(h)
	h.tax.existsSellBuyErr = errors.New("boom")
	if err := h.svc.collectTaxForPeriod(context.Background(), taxStart, taxEnd); err == nil {
		t.Error("expected error from ExistsBySellAndBuy")
	}
}

func TestCollectTaxForPeriod_DuplicateInsertSkips(t *testing.T) {
	h := newHarness("0.15")
	seedSingleUserStockHistory(h)
	h.tax.insertErr = ErrDuplicate
	h.account.govAccount = acct("999")
	if err := h.svc.collectTaxForPeriod(context.Background(), taxStart, taxEnd); err != nil {
		t.Fatalf("duplicate should be skipped, got %v", err)
	}
	if len(h.account.payments) != 0 {
		t.Errorf("duplicate must not pay, got %d", len(h.account.payments))
	}
}

func TestCollectTaxForPeriod_InsertError(t *testing.T) {
	h := newHarness("0.15")
	seedSingleUserStockHistory(h)
	h.tax.insertErr = errors.New("db boom")
	if err := h.svc.collectTaxForPeriod(context.Background(), taxStart, taxEnd); err == nil {
		t.Error("expected insert error to propagate")
	}
}

// chargeStockEntry failure paths (debit fails → reservation deleted).
func TestChargeStockEntry_DebitFailsDeletesReservation(t *testing.T) {
	h := newHarness("0.15")
	h.account.details = map[int64]*clients.AccountDetails{70: acct("111")}
	h.account.govAccount = acct("999")
	h.account.transactionErr = errors.New("payment rejected")
	res := &TaxCharge{ID: 5}
	entry := taxChargeEntry{userID: 7, sourceAccountID: 70, taxAmount: dec("10"), currency: "RSD"}
	h.svc.chargeStockEntry(context.Background(), entry, res)
	if len(h.tax.deletedID) != 1 || h.tax.deletedID[0] != 5 {
		t.Errorf("debit failure should delete reservation, got %+v", h.tax.deletedID)
	}
	if len(h.tax.markChargedID) != 0 {
		t.Errorf("should not MarkCharged on debit failure")
	}
}

func TestChargeStockEntry_SourceAccountErrorDeletes(t *testing.T) {
	h := newHarness("0.15")
	h.account.detailsErr = errors.New("not found")
	res := &TaxCharge{ID: 6}
	entry := taxChargeEntry{userID: 7, sourceAccountID: 70, taxAmount: dec("10"), currency: "RSD"}
	h.svc.chargeStockEntry(context.Background(), entry, res)
	if len(h.tax.deletedID) != 1 {
		t.Errorf("source-account error before debit should delete reservation")
	}
}

func TestChargeStockEntry_GovAccountErrorDeletes(t *testing.T) {
	h := newHarness("0.15")
	h.account.details = map[int64]*clients.AccountDetails{70: acct("111")}
	h.account.govErr = errors.New("gov down")
	res := &TaxCharge{ID: 7}
	entry := taxChargeEntry{userID: 7, sourceAccountID: 70, taxAmount: dec("10"), currency: "RSD"}
	h.svc.chargeStockEntry(context.Background(), entry, res)
	if len(h.tax.deletedID) != 1 {
		t.Errorf("gov-account error before debit should delete reservation")
	}
}

func TestChargeStockEntry_UpdateChargedFailsAfterDebitMarksCharged(t *testing.T) {
	h := newHarness("0.15")
	h.account.details = map[int64]*clients.AccountDetails{70: acct("111")}
	h.account.govAccount = acct("999")
	h.tax.updateChargedErr = errors.New("update fail") // debit succeeded, post-step fails
	res := &TaxCharge{ID: 8}
	entry := taxChargeEntry{userID: 7, sourceAccountID: 70, taxAmount: dec("10"), currency: "RSD"}
	h.svc.chargeStockEntry(context.Background(), entry, res)
	// debit succeeded → forced to CHARGED (MarkCharged), not deleted.
	if len(h.tax.markChargedID) != 1 || h.tax.markChargedID[0] != 8 {
		t.Errorf("post-debit failure should MarkCharged, got %+v", h.tax.markChargedID)
	}
	if len(h.tax.deletedID) != 0 {
		t.Errorf("should not delete after a successful debit")
	}
}

func TestHandleFailedChargeAttempt_NilReservation(t *testing.T) {
	h := newHarness("0.15")
	h.svc.handleFailedChargeAttempt(context.Background(), nil, false)
	// no panic, no calls
	if len(h.tax.deletedID) != 0 || len(h.tax.markChargedID) != 0 {
		t.Error("nil reservation should be a no-op")
	}
}

func TestHandleFailedChargeAttempt_DeleteErrorLogged(t *testing.T) {
	h := newHarness("0.15")
	h.tax.deleteErr = errors.New("delete fail")
	h.svc.handleFailedChargeAttempt(context.Background(), &TaxCharge{ID: 1}, false)
	// just covers the error-log branch
	if len(h.tax.deletedID) != 1 {
		t.Error("expected a delete attempt")
	}
}

func TestHandleFailedChargeAttempt_MarkChargedErrorLogged(t *testing.T) {
	h := newHarness("0.15")
	h.tax.markChargedErr = errors.New("mark fail")
	h.svc.handleFailedChargeAttempt(context.Background(), &TaxCharge{ID: 1}, true)
	if len(h.tax.markChargedID) != 1 {
		t.Error("expected a mark-charged attempt")
	}
}

// ------------------------------------------------------------------
// OTC collection.
// ------------------------------------------------------------------

func otcEntry(contractID int64, ex time.Time) OtcTaxEntry {
	return OtcTaxEntry{
		ContractID: contractID, SellerID: 7, ListingID: 99, Amount: 10,
		SellPricePerStock: dec("150"), AveragePurchasePrice: dec("100"), ExercisedAt: ex,
	}
}

func TestCollectOtcTaxForPeriod_FullSettlement(t *testing.T) {
	h := newHarness("0.15")
	h.account.govAccount = acct("999")
	h.account.defaultRsd = map[int64]string{7: "555"}
	h.market.convertResp = fxRate("8775") // 75 USD → 8775 RSD
	h.tax.otcEntries = []OtcTaxEntry{otcEntry(1, inWindow)}
	if err := h.svc.collectOtcTaxForPeriod(context.Background(), taxStart, taxEnd); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(h.tax.inserted) != 1 || h.tax.inserted[0].OtcContractID == nil {
		t.Fatalf("want 1 OTC charge inserted, got %+v", h.tax.inserted)
	}
	if len(h.account.payments) != 1 || h.account.payments[0].FromAccountNumber != "555" {
		t.Errorf("OTC payment wrong: %+v", h.account.payments)
	}
	if len(h.tax.markChargedID) != 1 {
		t.Errorf("want OTC MarkCharged, got %d", len(h.tax.markChargedID))
	}
}

func TestCollectOtcTaxForPeriod_GovAccountError(t *testing.T) {
	h := newHarness("0.15")
	h.account.govErr = errors.New("gov down")
	if err := h.svc.collectOtcTaxForPeriod(context.Background(), taxStart, taxEnd); err == nil {
		t.Error("expected gov-account error")
	}
}

func TestCollectOtcTaxForPeriod_SkipsBeforeStartAndZeroExercise(t *testing.T) {
	h := newHarness("0.15")
	h.account.govAccount = acct("999")
	h.tax.otcEntries = []OtcTaxEntry{
		otcEntry(1, taxDate(2026, 4, 1)), // before start
		otcEntry(2, time.Time{}),         // zero exercise
	}
	if err := h.svc.collectOtcTaxForPeriod(context.Background(), taxStart, taxEnd); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(h.tax.inserted) != 0 {
		t.Errorf("none should settle, got %d", len(h.tax.inserted))
	}
}

func TestCollectOtcTaxForPeriod_AlreadyProcessedSkips(t *testing.T) {
	h := newHarness("0.15")
	h.account.govAccount = acct("999")
	h.tax.existsOtc = true
	h.tax.otcEntries = []OtcTaxEntry{otcEntry(1, inWindow)}
	if err := h.svc.collectOtcTaxForPeriod(context.Background(), taxStart, taxEnd); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(h.tax.inserted) != 0 {
		t.Errorf("already-processed should skip, got %d inserts", len(h.tax.inserted))
	}
}

func TestCollectOtcTaxForPeriod_ExistsByOtcError(t *testing.T) {
	h := newHarness("0.15")
	h.account.govAccount = acct("999")
	h.tax.existsOtcErr = errors.New("boom")
	h.tax.otcEntries = []OtcTaxEntry{otcEntry(1, inWindow)}
	if err := h.svc.collectOtcTaxForPeriod(context.Background(), taxStart, taxEnd); err == nil {
		t.Error("expected ExistsByOtcContractID error")
	}
}

func TestCollectOtcTaxForPeriod_ZeroTaxSkips(t *testing.T) {
	h := newHarness("0.15")
	h.account.govAccount = acct("999")
	// loss: sell < avg → tax 0
	e := otcEntry(1, inWindow)
	e.SellPricePerStock = dec("50")
	h.tax.otcEntries = []OtcTaxEntry{e}
	if err := h.svc.collectOtcTaxForPeriod(context.Background(), taxStart, taxEnd); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(h.tax.inserted) != 0 {
		t.Errorf("zero tax should skip insert")
	}
}

func TestCollectOtcTaxForPeriod_FxFailureErrors(t *testing.T) {
	h := newHarness("0.15")
	h.account.govAccount = acct("999")
	h.market.convertErr = errors.New("fx down")
	h.tax.otcEntries = []OtcTaxEntry{otcEntry(1, inWindow)}
	if err := h.svc.collectOtcTaxForPeriod(context.Background(), taxStart, taxEnd); err == nil {
		t.Error("expected OTC FX conversion error")
	}
}

func TestCollectOtcTaxForPeriod_NoSellerAccountSkips(t *testing.T) {
	h := newHarness("0.15")
	h.account.govAccount = acct("999")
	h.market.convertResp = fxRate("8775")
	h.account.defaultRsd = map[int64]string{} // no RSD account for seller
	h.tax.otcEntries = []OtcTaxEntry{otcEntry(1, inWindow)}
	if err := h.svc.collectOtcTaxForPeriod(context.Background(), taxStart, taxEnd); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(h.tax.inserted) != 0 || len(h.account.payments) != 0 {
		t.Errorf("missing seller account should skip")
	}
}

func TestCollectOtcTaxForPeriod_DuplicateInsertSkips(t *testing.T) {
	h := newHarness("0.15")
	h.account.govAccount = acct("999")
	h.market.convertResp = fxRate("8775")
	h.account.defaultRsd = map[int64]string{7: "555"}
	h.tax.insertErr = ErrDuplicate
	h.tax.otcEntries = []OtcTaxEntry{otcEntry(1, inWindow)}
	if err := h.svc.collectOtcTaxForPeriod(context.Background(), taxStart, taxEnd); err != nil {
		t.Fatalf("duplicate should be skipped, got %v", err)
	}
	if len(h.account.payments) != 0 {
		t.Errorf("duplicate OTC must not pay")
	}
}

func TestCollectOtcTaxForPeriod_InsertError(t *testing.T) {
	h := newHarness("0.15")
	h.account.govAccount = acct("999")
	h.market.convertResp = fxRate("8775")
	h.account.defaultRsd = map[int64]string{7: "555"}
	h.tax.insertErr = errors.New("db boom")
	h.tax.otcEntries = []OtcTaxEntry{otcEntry(1, inWindow)}
	if err := h.svc.collectOtcTaxForPeriod(context.Background(), taxStart, taxEnd); err == nil {
		t.Error("expected OTC insert error")
	}
}

func TestChargeOtcEntry_DebitFailsDeletes(t *testing.T) {
	h := newHarness("0.15")
	h.account.transactionErr = errors.New("reject")
	charge := &TaxCharge{ID: 9}
	h.svc.chargeOtcEntry(context.Background(), charge, "999", "555", 7, 1, dec("100"))
	if len(h.tax.deletedID) != 1 {
		t.Errorf("OTC debit failure should delete reservation")
	}
}

func TestChargeOtcEntry_MarkChargedFailsAfterDebit(t *testing.T) {
	h := newHarness("0.15")
	h.tax.markChargedErr = errors.New("mark fail")
	charge := &TaxCharge{ID: 10}
	h.svc.chargeOtcEntry(context.Background(), charge, "999", "555", 7, 1, dec("100"))
	// debit succeeded, then markCharged fails → handleFailed forces MarkCharged again.
	if len(h.tax.markChargedID) < 1 {
		t.Errorf("expected a MarkCharged attempt")
	}
	if len(h.tax.deletedID) != 0 {
		t.Errorf("should not delete after successful debit")
	}
}

// ------------------------------------------------------------------
// Debts.
// ------------------------------------------------------------------

func TestGetAllDebts_AggregatesByUser(t *testing.T) {
	h := newHarness("0.15")
	seedSingleUserStockHistory(h)
	page, err := h.svc.GetAllDebts(context.Background(), 0, 10)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if page.TotalElements != 1 {
		t.Fatalf("want 1 debtor, got %d", page.TotalElements)
	}
	if !page.Content[0].DebtRsd.Equal(dec("37.5")) || page.Content[0].UserID != 7 {
		t.Errorf("debt row wrong: %+v", page.Content[0])
	}
}

func TestGetAllDebts_BuildError(t *testing.T) {
	h := newHarness("0.15")
	h.order.byDirectionErr = errors.New("boom")
	if _, err := h.svc.GetAllDebts(context.Background(), 0, 10); err == nil {
		t.Error("expected error")
	}
}

func TestGetUserDebt_Sum(t *testing.T) {
	h := newHarness("0.15")
	seedSingleUserStockHistory(h)
	h.order.byUserAndDirection = map[int64][]order.Order{7: {sellOrder(10, 7, 99, 70)}}
	got, err := h.svc.GetUserDebt(context.Background(), 7)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if !got.DebtRsd.Equal(dec("37.5")) || got.UserID != 7 {
		t.Errorf("user debt wrong: %+v", got)
	}
}

func TestGetUserDebt_BuildError(t *testing.T) {
	h := newHarness("0.15")
	h.order.byUserAndDirErr = errors.New("boom")
	if _, err := h.svc.GetUserDebt(context.Background(), 7); err == nil {
		t.Error("expected error")
	}
}

// ------------------------------------------------------------------
// CurrentYearPaidTax / CurrentMonthUnpaidTax.
// ------------------------------------------------------------------

func TestCurrentYearPaidTax_SumsChargedInYear(t *testing.T) {
	h := newHarness("0.15")
	rsd := dec("1000")
	inYear := time.Date(2026, 3, 1, 0, 0, 0, 0, time.UTC)
	lastYear := time.Date(2025, 12, 1, 0, 0, 0, 0, time.UTC)
	h.tax.byUserStatus = []TaxCharge{
		{UserID: 7, Status: StatusCharged, TaxAmount: dec("10"), TaxAmountRsd: &rsd, ChargedAt: &inYear},
		{UserID: 7, Status: StatusCharged, TaxAmount: dec("20"), ChargedAt: &lastYear}, // out of year
		{UserID: 7, Status: StatusCharged, TaxAmount: dec("5"), ChargedAt: nil},        // never charged
	}
	withClock(time.Date(2026, 6, 15, 0, 0, 0, 0, time.UTC), func() {
		got, err := h.svc.CurrentYearPaidTax(context.Background(), 7)
		if err != nil {
			t.Fatalf("unexpected: %v", err)
		}
		if !got.Equal(dec("1000")) {
			t.Errorf("paid = %s, want 1000 (RSD column wins)", got)
		}
	})
}

func TestCurrentYearPaidTax_FallsBackToTaxAmount(t *testing.T) {
	h := newHarness("0.15")
	inYear := time.Date(2026, 3, 1, 0, 0, 0, 0, time.UTC)
	h.tax.byUserStatus = []TaxCharge{
		{UserID: 7, Status: StatusCharged, TaxAmount: dec("42"), ChargedAt: &inYear},
	}
	withClock(time.Date(2026, 6, 15, 0, 0, 0, 0, time.UTC), func() {
		got, _ := h.svc.CurrentYearPaidTax(context.Background(), 7)
		if !got.Equal(dec("42")) {
			t.Errorf("want 42 (no RSD column), got %s", got)
		}
	})
}

func TestCurrentYearPaidTax_RepoError(t *testing.T) {
	h := newHarness("0.15")
	h.tax.byUserStatusErr = errors.New("boom")
	if _, err := h.svc.CurrentYearPaidTax(context.Background(), 7); err == nil {
		t.Error("expected error")
	}
}

func TestCurrentMonthUnpaidTax_StrictConversion(t *testing.T) {
	h := newHarness("0.15")
	// one uncharged USD stock entry in current month → strict FX applies.
	so := sellOrder(10, 7, 99, 70)
	h.order.byUserAndDirection = map[int64][]order.Order{7: {so}}
	h.order.byUserID = map[int64][]order.Order{7: {so}}
	h.order.byID = map[int64]*order.Order{10: &so}
	sellTime := time.Date(2026, 6, 10, 0, 0, 0, 0, time.UTC)
	sellTxn := tx(100, 10, 4, "150", sellTime)
	h.order.txBetween = []order.Transaction{sellTxn}
	h.order.txBefore = []order.Transaction{sellTxn}
	h.market.listings = map[int64]*clients.StockListing{99: usdListing()}
	h.portfolio.byKey = map[[2]int64]*portfolio.Portfolio{{7, 99}: {AveragePurchasePrice: dec("100")}}
	h.market.convertResp = fxRate("3510") // 30 USD → 3510 RSD
	withClock(time.Date(2026, 6, 15, 0, 0, 0, 0, time.UTC), func() {
		got, err := h.svc.CurrentMonthUnpaidTax(context.Background(), 7)
		if err != nil {
			t.Fatalf("unexpected: %v", err)
		}
		if !got.Equal(dec("3510")) {
			t.Errorf("unpaid = %s, want 3510", got)
		}
	})
}

// ------------------------------------------------------------------
// calculateUnchargedTaxForRangeInRsd branches.
// ------------------------------------------------------------------

func TestCalculateUnchargedTax_FindAllError(t *testing.T) {
	h := newHarness("0.15")
	h.tax.allErr = errors.New("boom")
	uid := int64(7)
	if _, err := h.svc.calculateUnchargedTaxForRangeInRsd(context.Background(), &uid, taxStart, taxEnd); err == nil {
		t.Error("expected FindAll error")
	}
}

func TestCalculateUnchargedTax_ChargedKeySkipped(t *testing.T) {
	h := newHarness("0.15")
	so := sellOrder(10, 7, 99, 70)
	h.order.byUserAndDirection = map[int64][]order.Order{7: {so}}
	h.order.byUserID = map[int64][]order.Order{7: {so}}
	h.order.byID = map[int64]*order.Order{10: &so}
	sellTxn := tx(100, 10, 4, "150", inWindow)
	h.order.txBetween = []order.Transaction{sellTxn}
	h.order.txBefore = []order.Transaction{sellTxn}
	h.market.listings = map[int64]*clients.StockListing{99: usdListing()}
	h.portfolio.byKey = map[[2]int64]*portfolio.Portfolio{{7, 99}: {AveragePurchasePrice: dec("100")}}
	// Mark the (sell=100, buy=-1) key CHARGED so it is excluded.
	h.tax.all = []TaxCharge{{UserID: 7, Status: StatusCharged, SellTransactionID: 100, BuyTransactionID: -1}}
	uid := int64(7)
	got, err := h.svc.calculateUnchargedTaxForRangeInRsd(context.Background(), &uid, taxStart, taxEnd)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if !got.IsZero() {
		t.Errorf("charged key should be excluded, got %s", got)
	}
}

func TestCalculateUnchargedTax_StrictFxFailure(t *testing.T) {
	h := newHarness("0.15")
	so := sellOrder(10, 7, 99, 70)
	h.order.byUserAndDirection = map[int64][]order.Order{7: {so}}
	h.order.byUserID = map[int64][]order.Order{7: {so}}
	h.order.byID = map[int64]*order.Order{10: &so}
	sellTxn := tx(100, 10, 4, "150", inWindow)
	h.order.txBetween = []order.Transaction{sellTxn}
	h.order.txBefore = []order.Transaction{sellTxn}
	h.market.listings = map[int64]*clients.StockListing{99: usdListing()}
	h.portfolio.byKey = map[[2]int64]*portfolio.Portfolio{{7, 99}: {AveragePurchasePrice: dec("100")}}
	h.market.convertErr = errors.New("fx down") // strict → 409
	uid := int64(7)
	if _, err := h.svc.calculateUnchargedTaxForRangeInRsd(context.Background(), &uid, taxStart, taxEnd); err == nil {
		t.Error("expected strict FX failure error")
	}
}

func TestCalculateUnchargedTax_OtcComponent(t *testing.T) {
	h := newHarness("0.15")
	h.market.convertResp = fxRate("8775")
	h.tax.otcEntries = []OtcTaxEntry{otcEntry(1, inWindow)}
	uid := int64(7)
	got, err := h.svc.calculateUnchargedTaxForRangeInRsd(context.Background(), &uid, taxStart, taxEnd)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if !got.Equal(dec("8775")) {
		t.Errorf("want 8775 OTC, got %s", got)
	}
}

func TestCalculateUnchargedTax_OtcSkippedByCharged(t *testing.T) {
	h := newHarness("0.15")
	h.market.convertResp = fxRate("8775")
	h.tax.otcEntries = []OtcTaxEntry{otcEntry(1, inWindow)}
	otc := int64(1)
	h.tax.all = []TaxCharge{{UserID: 7, Status: StatusCharged, OtcContractID: &otc}}
	uid := int64(7)
	got, err := h.svc.calculateUnchargedTaxForRangeInRsd(context.Background(), &uid, taxStart, taxEnd)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if !got.IsZero() {
		t.Errorf("charged OTC should be excluded, got %s", got)
	}
}

func TestCalculateUnchargedTax_OtcOtherUserAndBeforeStartSkipped(t *testing.T) {
	h := newHarness("0.15")
	h.market.convertResp = fxRate("8775")
	other := otcEntry(1, inWindow)
	other.SellerID = 99 // not the filtered user
	before := otcEntry(2, taxDate(2026, 4, 1))
	h.tax.otcEntries = []OtcTaxEntry{other, before}
	uid := int64(7)
	got, err := h.svc.calculateUnchargedTaxForRangeInRsd(context.Background(), &uid, taxStart, taxEnd)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if !got.IsZero() {
		t.Errorf("want 0, got %s", got)
	}
}

func TestCalculateUnchargedTax_BuildEntriesError(t *testing.T) {
	h := newHarness("0.15")
	h.order.byUserAndDirErr = errors.New("boom")
	uid := int64(7)
	if _, err := h.svc.calculateUnchargedTaxForRangeInRsd(context.Background(), &uid, taxStart, taxEnd); err == nil {
		t.Error("expected buildTaxChargeEntries error")
	}
}

func TestCalculateUnchargedTax_OtcTaxError(t *testing.T) {
	h := newHarness("0.15")
	h.market.convertErr = errors.New("fx down")
	h.tax.otcEntries = []OtcTaxEntry{otcEntry(1, inWindow)}
	uid := int64(7)
	if _, err := h.svc.calculateUnchargedTaxForRangeInRsd(context.Background(), &uid, taxStart, taxEnd); err == nil {
		t.Error("expected OTC tax conversion error")
	}
}

// ------------------------------------------------------------------
// Tracking.
// ------------------------------------------------------------------

func TestGetTaxTracking_ClientAndActuaryRows(t *testing.T) {
	h := newHarness("0.15")
	h.customer.pages = []*clients.CustomerPage{
		{Content: []clients.Customer{{ID: 7, FirstName: strptr("Ana"), LastName: strptr("A")}}, TotalPages: 1},
	}
	h.actuary.ids = []int64{500}
	h.employee.employees = map[int64]*clients.Employee{500: {ID: 500, Ime: strptr("Bob"), Prezime: strptr("B")}}
	withClock(time.Date(2026, 6, 15, 0, 0, 0, 0, time.UTC), func() {
		page, err := h.svc.GetTaxTracking(context.Background(), nil, nil, nil, 0, 10)
		if err != nil {
			t.Fatalf("unexpected: %v", err)
		}
		if page.TotalElements != 2 {
			t.Fatalf("want 2 rows (1 client + 1 actuary), got %d", page.TotalElements)
		}
	})
}

func TestGetTaxTracking_FilterClientOnly(t *testing.T) {
	h := newHarness("0.15")
	h.customer.pages = []*clients.CustomerPage{
		{Content: []clients.Customer{{ID: 7, FirstName: strptr("Ana")}}, TotalPages: 1},
	}
	ut := "CLIENT"
	withClock(time.Date(2026, 6, 15, 0, 0, 0, 0, time.UTC), func() {
		page, err := h.svc.GetTaxTracking(context.Background(), &ut, nil, nil, 0, 10)
		if err != nil {
			t.Fatalf("unexpected: %v", err)
		}
		if page.TotalElements != 1 || page.Content[0].UserType != "CLIENT" {
			t.Errorf("want 1 CLIENT row, got %+v", page.Content)
		}
	})
}

func TestGetTaxTracking_FilterActuaryOnly(t *testing.T) {
	h := newHarness("0.15")
	h.actuary.ids = []int64{500}
	h.employee.employees = map[int64]*clients.Employee{500: {ID: 500, Ime: strptr("Bob"), Prezime: strptr("B")}}
	ut := "actuary" // case-insensitive
	withClock(time.Date(2026, 6, 15, 0, 0, 0, 0, time.UTC), func() {
		page, err := h.svc.GetTaxTracking(context.Background(), &ut, nil, nil, 0, 10)
		if err != nil {
			t.Fatalf("unexpected: %v", err)
		}
		if page.TotalElements != 1 || page.Content[0].UserType != "ACTUARY" {
			t.Errorf("want 1 ACTUARY row, got %+v", page.Content)
		}
	})
}

func TestGetTaxTracking_MetricsError(t *testing.T) {
	h := newHarness("0.15")
	h.tax.allErr = errors.New("boom")
	withClock(time.Date(2026, 6, 15, 0, 0, 0, 0, time.UTC), func() {
		if _, err := h.svc.GetTaxTracking(context.Background(), nil, nil, nil, 0, 10); err == nil {
			t.Error("expected metrics error")
		}
	})
}

func TestGetTaxTracking_ClientError(t *testing.T) {
	h := newHarness("0.15")
	h.customer.searchErr = errors.New("boom")
	withClock(time.Date(2026, 6, 15, 0, 0, 0, 0, time.UTC), func() {
		if _, err := h.svc.GetTaxTracking(context.Background(), nil, nil, nil, 0, 10); err == nil {
			t.Error("expected client error")
		}
	})
}

func TestGetTaxTracking_ActuaryError(t *testing.T) {
	h := newHarness("0.15")
	h.actuary.err = errors.New("boom")
	ut := "ACTUARY"
	withClock(time.Date(2026, 6, 15, 0, 0, 0, 0, time.UTC), func() {
		if _, err := h.svc.GetTaxTracking(context.Background(), &ut, nil, nil, 0, 10); err == nil {
			t.Error("expected actuary error")
		}
	})
}

func TestCalculateTrackingMetrics_PaidFailedAndDebt(t *testing.T) {
	h := newHarness("0.15")
	rsd := dec("500")
	created := time.Date(2026, 6, 1, 0, 0, 0, 0, time.UTC)
	otc := int64(1)
	h.tax.all = []TaxCharge{
		{UserID: 7, Status: StatusCharged, TaxAmount: dec("10"), TaxAmountRsd: &rsd, CreatedAt: created, SellTransactionID: 100, BuyTransactionID: 200},
		{UserID: 8, Status: StatusFailed, CreatedAt: created},
		{UserID: 9, Status: StatusCharged, TaxAmount: dec("7"), CreatedAt: created, OtcContractID: &otc},
	}
	withClock(time.Date(2026, 6, 15, 0, 0, 0, 0, time.UTC), func() {
		m, err := h.svc.calculateTrackingMetrics(context.Background())
		if err != nil {
			t.Fatalf("unexpected: %v", err)
		}
		if !m[7].paidTax.Equal(dec("500")) {
			t.Errorf("user 7 paid = %s, want 500", m[7].paidTax)
		}
		if m[7].status() != "PAID" {
			t.Errorf("user 7 status = %s, want PAID", m[7].status())
		}
		if !m[8].failed || m[8].status() != "FAILED" {
			t.Errorf("user 8 should be FAILED")
		}
	})
}

func TestCalculateTrackingMetrics_FindAllError(t *testing.T) {
	h := newHarness("0.15")
	h.tax.allErr = errors.New("boom")
	withClock(time.Date(2026, 6, 15, 0, 0, 0, 0, time.UTC), func() {
		if _, err := h.svc.calculateTrackingMetrics(context.Background()); err == nil {
			t.Error("expected FindAll error")
		}
	})
}

func TestCalculateTrackingMetrics_StrictFxError(t *testing.T) {
	h := newHarness("0.15")
	seedSingleUserStockHistory(h)
	h.market.listingErr = nil
	// Force strict FX failure on the USD entry by failing the conversion.
	h.market.convertErr = errors.New("fx down")
	withClock(time.Date(2026, 6, 15, 0, 0, 0, 0, time.UTC), func() {
		if _, err := h.svc.calculateTrackingMetrics(context.Background()); err == nil {
			t.Error("expected strict FX error in tracking metrics")
		}
	})
}

func TestCalculateTrackingMetrics_CurrentMonthAndDebt(t *testing.T) {
	h := newHarness("0.15")
	so := sellOrder(10, 7, 99, 70)
	h.order.byDirection = map[string][]order.Order{order.DirectionSell: {so}}
	h.order.byUserID = map[int64][]order.Order{7: {so}}
	h.order.byID = map[int64]*order.Order{10: &so}
	sellTime := time.Date(2026, 6, 10, 0, 0, 0, 0, time.UTC)
	sellTxn := tx(100, 10, 4, "150", sellTime)
	h.order.txBetween = []order.Transaction{sellTxn}
	h.order.txBefore = []order.Transaction{sellTxn}
	h.market.listings = map[int64]*clients.StockListing{99: usdListing()}
	h.portfolio.byKey = map[[2]int64]*portfolio.Portfolio{{7, 99}: {AveragePurchasePrice: dec("100")}}
	h.market.convertResp = fxRate("3510")
	withClock(time.Date(2026, 6, 15, 0, 0, 0, 0, time.UTC), func() {
		m, err := h.svc.calculateTrackingMetrics(context.Background())
		if err != nil {
			t.Fatalf("unexpected: %v", err)
		}
		if m[7] == nil {
			t.Fatal("expected metrics for user 7")
		}
		if !m[7].debt.Equal(dec("3510")) {
			t.Errorf("debt = %s, want 3510", m[7].debt)
		}
		if !m[7].currentMonthTax.Equal(dec("3510")) {
			t.Errorf("currentMonth = %s, want 3510", m[7].currentMonthTax)
		}
		if m[7].status() != "PENDING" {
			t.Errorf("status = %s, want PENDING", m[7].status())
		}
	})
}

func TestAddOtcTrackingMetrics_Error(t *testing.T) {
	h := newHarness("0.15")
	h.market.convertErr = errors.New("fx down")
	h.tax.otcEntries = []OtcTaxEntry{otcEntry(1, inWindow)}
	m := map[int64]*taxTrackingMetrics{}
	err := h.svc.addOtcTrackingMetrics(context.Background(), m, taxStart, taxEnd, map[int64]bool{})
	if err == nil {
		t.Error("expected OTC conversion error")
	}
}

func TestAddOtcTrackingMetrics_ZeroTaxSkipped(t *testing.T) {
	h := newHarness("0.15")
	e := otcEntry(1, inWindow)
	e.SellPricePerStock = dec("50") // loss
	h.tax.otcEntries = []OtcTaxEntry{e}
	m := map[int64]*taxTrackingMetrics{}
	if err := h.svc.addOtcTrackingMetrics(context.Background(), m, taxStart, taxEnd, map[int64]bool{}); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(m) != 0 {
		t.Errorf("zero-tax OTC should not create metrics, got %v", m)
	}
}

func TestLoadClientTrackingRows_Pagination(t *testing.T) {
	h := newHarness("0.15")
	h.customer.pages = []*clients.CustomerPage{
		{Content: []clients.Customer{{ID: 1, FirstName: strptr("A")}}, TotalPages: 2},
		{Content: []clients.Customer{{ID: 2, FirstName: strptr("B")}}, TotalPages: 2},
	}
	rows, err := h.svc.loadClientTrackingRows(context.Background(), nil, nil, map[int64]*taxTrackingMetrics{})
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(rows) != 2 {
		t.Errorf("want 2 rows across pages, got %d", len(rows))
	}
}

func TestLoadClientTrackingRows_EmptyPageBreaks(t *testing.T) {
	h := newHarness("0.15")
	h.customer.pages = []*clients.CustomerPage{{Content: nil, TotalPages: 5}}
	rows, err := h.svc.loadClientTrackingRows(context.Background(), nil, nil, map[int64]*taxTrackingMetrics{})
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(rows) != 0 {
		t.Errorf("empty content should break, got %d", len(rows))
	}
}

func TestLoadActuaryTrackingRows_NameFilters(t *testing.T) {
	h := newHarness("0.15")
	h.actuary.ids = []int64{500, 501, 502}
	h.employee.employees = map[int64]*clients.Employee{
		500: {ID: 500, Ime: strptr("Bob"), Prezime: strptr("Smith")},
		501: {ID: 501, Ime: strptr("Alice"), Prezime: strptr("Jones")},
		// 502 missing → GetEmployee returns nil
	}
	first := "bob"
	rows, err := h.svc.loadActuaryTrackingRows(context.Background(), &first, nil, map[int64]*taxTrackingMetrics{})
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(rows) != 1 || *rows[0].FirstName != "Bob" {
		t.Errorf("first-name filter wrong: %+v", rows)
	}
}

func TestLoadActuaryTrackingRows_LastNameFilterAndEmployeeError(t *testing.T) {
	h := newHarness("0.15")
	h.actuary.ids = []int64{500}
	h.employee.err = errors.New("user-service down") // logged + skipped
	last := "smith"
	rows, err := h.svc.loadActuaryTrackingRows(context.Background(), nil, &last, map[int64]*taxTrackingMetrics{})
	if err != nil {
		t.Fatalf("employee fetch error should be swallowed, got %v", err)
	}
	if len(rows) != 0 {
		t.Errorf("want 0 rows when employee fetch fails, got %d", len(rows))
	}
}

func TestLoadActuaryTrackingRows_LastNameNoMatch(t *testing.T) {
	h := newHarness("0.15")
	h.actuary.ids = []int64{500}
	h.employee.employees = map[int64]*clients.Employee{500: {ID: 500, Ime: strptr("Bob"), Prezime: strptr("Smith")}}
	last := "jones"
	rows, err := h.svc.loadActuaryTrackingRows(context.Background(), nil, &last, map[int64]*taxTrackingMetrics{})
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(rows) != 0 {
		t.Errorf("last-name mismatch should filter out, got %d", len(rows))
	}
}

func TestLoadActuaryTrackingRows_IDsError(t *testing.T) {
	h := newHarness("0.15")
	h.actuary.err = errors.New("boom")
	if _, err := h.svc.loadActuaryTrackingRows(context.Background(), nil, nil, map[int64]*taxTrackingMetrics{}); err == nil {
		t.Error("expected FindAllEmployeeIDs error")
	}
}

// ------------------------------------------------------------------
// Conversions.
// ------------------------------------------------------------------

func TestConvertTaxToRsd_RsdAndEmptyPassthrough(t *testing.T) {
	h := newHarness("0.15")
	if got := h.svc.convertTaxToRsd(context.Background(), "RSD", dec("10")); !got.Equal(dec("10")) {
		t.Errorf("RSD passthrough wrong: %s", got)
	}
	if got := h.svc.convertTaxToRsd(context.Background(), "  ", dec("10")); !got.Equal(dec("10")) {
		t.Errorf("blank currency passthrough wrong: %s", got)
	}
}

func TestConvertTaxToRsd_FxFailureFallsBack(t *testing.T) {
	h := newHarness("0.15")
	h.market.convertErr = errors.New("fx down")
	if got := h.svc.convertTaxToRsd(context.Background(), "USD", dec("10")); !got.Equal(dec("10")) {
		t.Errorf("FX failure should fall back to original, got %s", got)
	}
}

func TestConvertTaxToRsd_NilConversionFallsBack(t *testing.T) {
	h := newHarness("0.15")
	h.market.convertResp = &clients.ExchangeRate{} // Converted() == nil
	if got := h.svc.convertTaxToRsd(context.Background(), "USD", dec("10")); !got.Equal(dec("10")) {
		t.Errorf("nil conversion should fall back, got %s", got)
	}
}

func TestConvertTaxToRsd_Success(t *testing.T) {
	h := newHarness("0.15")
	h.market.convertResp = fxRate("1170")
	if got := h.svc.convertTaxToRsd(context.Background(), "USD", dec("10")); !got.Equal(dec("1170")) {
		t.Errorf("want 1170, got %s", got)
	}
}

func TestConvertTaxToRsdStrict_MissingCurrency(t *testing.T) {
	h := newHarness("0.15")
	if _, err := h.svc.convertTaxToRsdStrict(context.Background(), "  ", dec("10"), 5); err == nil {
		t.Error("blank currency should be a strict error")
	}
}

func TestConvertTaxToRsdStrict_Rsd(t *testing.T) {
	h := newHarness("0.15")
	got, err := h.svc.convertTaxToRsdStrict(context.Background(), "RSD", dec("10"), 5)
	if err != nil || !got.Equal(dec("10")) {
		t.Errorf("RSD strict passthrough wrong: %s / %v", got, err)
	}
}

func TestConvertTaxToRsdStrict_FxFailure(t *testing.T) {
	h := newHarness("0.15")
	h.market.convertErr = errors.New("fx down")
	if _, err := h.svc.convertTaxToRsdStrict(context.Background(), "USD", dec("10"), 5); err == nil {
		t.Error("strict FX failure should error")
	}
}

func TestConvertTaxToRsdStrict_Success(t *testing.T) {
	h := newHarness("0.15")
	h.market.convertResp = fxRate("1170")
	got, err := h.svc.convertTaxToRsdStrict(context.Background(), "USD", dec("10"), 5)
	if err != nil || !got.Equal(dec("1170")) {
		t.Errorf("want 1170, got %s / %v", got, err)
	}
}

func TestConvertOtcTaxToRsd_NilConversionError(t *testing.T) {
	h := newHarness("0.15")
	h.market.convertResp = &clients.ExchangeRate{}
	if _, err := h.svc.convertOtcTaxToRsd(context.Background(), dec("10"), 1); err == nil {
		t.Error("nil OTC conversion should error")
	}
}

// ------------------------------------------------------------------
// loadExercisedOtcTaxEntries — error swallowed to nil.
// ------------------------------------------------------------------

func TestLoadExercisedOtcTaxEntries_ErrorSwallowed(t *testing.T) {
	h := newHarness("0.15")
	h.tax.otcEntriesErr = errors.New("table missing")
	got := h.svc.loadExercisedOtcTaxEntries(context.Background(), taxEnd)
	if got != nil {
		t.Errorf("error should be swallowed to nil, got %+v", got)
	}
}

// ------------------------------------------------------------------
// resolveOrder / isStockOrder edge cases.
// ------------------------------------------------------------------

func TestResolveOrder_ZeroIDAndCacheAndDBFallback(t *testing.T) {
	h := newHarness("0.15")
	cache := map[int64]*order.Order{}
	if h.svc.resolveOrder(context.Background(), cache, 0) != nil {
		t.Error("zero id should resolve to nil")
	}
	cached := buyOrder(5, 7, 99, 70)
	cache[5] = &cached
	if h.svc.resolveOrder(context.Background(), cache, 5) != &cached {
		t.Error("cache hit should return cached order")
	}
	// DB fallback (not in cache) then cached.
	dbOrder := buyOrder(6, 7, 99, 70)
	h.order.byID = map[int64]*order.Order{6: &dbOrder}
	got := h.svc.resolveOrder(context.Background(), cache, 6)
	if got == nil || got.ID != 6 {
		t.Errorf("DB fallback failed: %+v", got)
	}
	if cache[6] == nil {
		t.Error("DB-resolved order should be cached")
	}
}

func TestResolveOrder_DBError(t *testing.T) {
	h := newHarness("0.15")
	h.order.byIDErr = errors.New("db down")
	if h.svc.resolveOrder(context.Background(), map[int64]*order.Order{}, 5) != nil {
		t.Error("DB error should resolve to nil")
	}
}

func TestIsStockOrder_NilAndZeroListing(t *testing.T) {
	h := newHarness("0.15")
	sc := map[int64]bool{}
	cc := map[int64]string{}
	if h.svc.isStockOrder(context.Background(), nil, sc, cc) {
		t.Error("nil order is not a stock")
	}
	if h.svc.isStockOrder(context.Background(), &order.Order{ListingID: 0}, sc, cc) {
		t.Error("zero listing is not a stock")
	}
}

func TestIsStockOrder_MarketErrorCachesUsdNotStock(t *testing.T) {
	h := newHarness("0.15")
	h.market.listingErr = errors.New("market down")
	sc := map[int64]bool{}
	cc := map[int64]string{}
	o := &order.Order{ListingID: 99}
	if h.svc.isStockOrder(context.Background(), o, sc, cc) {
		t.Error("market error should yield not-a-stock")
	}
	if cc[99] != "USD" || sc[99] {
		t.Errorf("market error should cache USD + not-stock, got cc=%v sc=%v", cc, sc)
	}
}

func TestIsStockOrder_CacheHit(t *testing.T) {
	h := newHarness("0.15")
	sc := map[int64]bool{99: true}
	cc := map[int64]string{}
	if !h.svc.isStockOrder(context.Background(), &order.Order{ListingID: 99}, sc, cc) {
		t.Error("cache hit true should return true without calling market")
	}
}

func TestIsStockOrder_EmptyCurrencyDefaultsUsd(t *testing.T) {
	h := newHarness("0.15")
	st := "STOCK"
	h.market.listings = map[int64]*clients.StockListing{99: {ListingType: &st}} // CurrencyRaw nil → Currency() == ""
	sc := map[int64]bool{}
	cc := map[int64]string{}
	if !h.svc.isStockOrder(context.Background(), &order.Order{ListingID: 99}, sc, cc) {
		t.Error("STOCK listing should be a stock")
	}
	if cc[99] != "USD" {
		t.Errorf("empty currency should default to USD, got %q", cc[99])
	}
}

func TestIsStockOrder_RsdCurrency(t *testing.T) {
	h := newHarness("0.15")
	h.market.listings = map[int64]*clients.StockListing{99: rsdListing()}
	sc := map[int64]bool{}
	cc := map[int64]string{}
	if !h.svc.isStockOrder(context.Background(), &order.Order{ListingID: 99}, sc, cc) {
		t.Error("RSD STOCK should be a stock")
	}
	if cc[99] != "RSD" {
		t.Errorf("currency should be RSD, got %q", cc[99])
	}
}

// ------------------------------------------------------------------
// Notification enrichment.
// ------------------------------------------------------------------

func TestEnrichPayload_CustomerWins(t *testing.T) {
	h := newHarness("0.15")
	h.customer.customers = map[int64]*clients.Customer{7: {ID: 7, FirstName: strptr("Ana"), LastName: strptr("A"), Email: strptr("a@x")}}
	payload := h.svc.createTaxNotificationPayload(context.Background(), taxChargeEntry{userID: 7, taxAmount: dec("10"), listingID: 99, transactionID: 100}, dec("1170"))
	if payload.Username == nil || *payload.Username != "Ana A" {
		t.Errorf("expected customer name, got %+v", payload.Username)
	}
	if payload.UserEmail == nil || *payload.UserEmail != "a@x" {
		t.Errorf("expected customer email")
	}
	if payload.TemplateVariables["taxRsd"] != "1170" {
		t.Errorf("taxRsd var wrong: %v", payload.TemplateVariables)
	}
}

func TestEnrichPayload_FallsBackToEmployee(t *testing.T) {
	h := newHarness("0.15")
	h.customer.customerErr = errNotFound // customer lookup fails
	h.employee.employees = map[int64]*clients.Employee{7: {ID: 7, Ime: strptr("Bob"), Prezime: strptr("B"), Email: strptr("b@x")}}
	payload := h.svc.createTaxNotificationPayload(context.Background(), taxChargeEntry{userID: 7}, dec("0"))
	if payload.Username == nil || *payload.Username != "Bob B" {
		t.Errorf("expected employee fallback name, got %+v", payload.Username)
	}
}

func TestEnrichPayload_NeitherResolves(t *testing.T) {
	h := newHarness("0.15")
	h.customer.customerErr = errNotFound
	h.employee.err = errNotFound
	payload := h.svc.createTaxNotificationPayload(context.Background(), taxChargeEntry{userID: 7}, dec("0"))
	if payload.Username != nil || payload.UserEmail != nil {
		t.Errorf("unresolved user should leave name/email nil, got %+v", payload)
	}
}
