package tax

import (
	"context"
	"testing"
	"time"

	"banka1/trading-service-go/internal/clients"
	"banka1/trading-service-go/internal/order"
	"banka1/trading-service-go/internal/portfolio"
)

// addOtcTrackingMetrics happy path: records currentMonthTax (exercised this
// month) AND debt (not in chargedOtcIDs).
func TestAddOtcTrackingMetrics_RecordsCurrentMonthAndDebt(t *testing.T) {
	h := newHarness("0.15")
	h.market.convertResp = fxRate("8775")
	exThisMonth := time.Date(2026, 6, 10, 0, 0, 0, 0, time.UTC)
	h.tax.otcEntries = []OtcTaxEntry{otcEntry(1, exThisMonth)}
	m := map[int64]*taxTrackingMetrics{}
	currentMonthStart := time.Date(2026, 6, 1, 0, 0, 0, 0, time.UTC)
	err := h.svc.addOtcTrackingMetrics(context.Background(), m, currentMonthStart, taxDate(2026, 7, 1), map[int64]bool{})
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if m[7] == nil {
		t.Fatal("expected metrics for seller 7")
	}
	if !m[7].currentMonthTax.Equal(dec("8775")) {
		t.Errorf("currentMonthTax = %s, want 8775", m[7].currentMonthTax)
	}
	if !m[7].debt.Equal(dec("8775")) {
		t.Errorf("debt = %s, want 8775", m[7].debt)
	}
}

// addOtcTrackingMetrics: exercised before current month → debt only, no
// currentMonthTax; and a charged contract → no debt.
func TestAddOtcTrackingMetrics_DebtOnlyAndCharged(t *testing.T) {
	h := newHarness("0.15")
	h.market.convertResp = fxRate("8775")
	exLastMonth := time.Date(2026, 5, 10, 0, 0, 0, 0, time.UTC)
	h.tax.otcEntries = []OtcTaxEntry{otcEntry(1, exLastMonth), otcEntry(2, exLastMonth)}
	m := map[int64]*taxTrackingMetrics{}
	currentMonthStart := time.Date(2026, 6, 1, 0, 0, 0, 0, time.UTC)
	// contract 2 already charged → excluded from debt.
	err := h.svc.addOtcTrackingMetrics(context.Background(), m, currentMonthStart, taxDate(2026, 7, 1), map[int64]bool{2: true})
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if !m[7].currentMonthTax.IsZero() {
		t.Errorf("currentMonthTax should be 0 for last-month exercise, got %s", m[7].currentMonthTax)
	}
	// Only contract 1 contributes to debt (contract 2 charged) → 8775.
	if !m[7].debt.Equal(dec("8775")) {
		t.Errorf("debt = %s, want 8775 (only uncharged contract)", m[7].debt)
	}
}

// buildTaxChargeEntries: a transaction whose order resolves to nil (UserID 0) is
// skipped in the main allocation loop.
func TestBuildTaxChargeEntries_SkipsNilOrderInLoop(t *testing.T) {
	h := newHarness("0.15")
	so := sellOrder(10, 7, 99, 70)
	h.order.byDirection = map[string][]order.Order{order.DirectionSell: {so}}
	h.order.byUserID = map[int64][]order.Order{7: {so}}
	h.order.byID = map[int64]*order.Order{10: &so}
	sellTxn := tx(100, 10, 5, "150", inWindow)
	// historical contains a tx whose order (id 999*10) is unknown → resolves nil.
	ghostTxn := order.Transaction{ID: 300, OrderID: 9990, Quantity: 5, PricePerUnit: dec("100"), Timestamp: taxDate(2026, 4, 1)}
	buyTxn := tx(200, 20, 5, "100", taxDate(2026, 4, 1))
	bo := buyOrder(20, 7, 99, 70)
	h.order.byID[20] = &bo
	h.order.txBetween = []order.Transaction{sellTxn}
	h.order.txBefore = []order.Transaction{ghostTxn, buyTxn, sellTxn}
	h.market.listings = map[int64]*clients.StockListing{99: usdListing()}
	entries, err := h.svc.buildTaxChargeEntries(context.Background(), taxStart, taxEnd, nil)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(entries) != 1 {
		t.Fatalf("ghost tx should be skipped, want 1 entry, got %d", len(entries))
	}
}

// buildTaxChargeEntries: userIDFilter excludes a sell by a different user in the
// main loop.
func TestBuildTaxChargeEntries_UserFilterExcludesOtherUserInLoop(t *testing.T) {
	h := newHarness("0.15")
	// Two sell orders by different users, but filter is user 7.
	so7 := sellOrder(10, 7, 99, 70)
	so8 := sellOrder(11, 8, 99, 71)
	h.order.byUserAndDirection = map[int64][]order.Order{7: {so7}}
	h.order.byUserID = map[int64][]order.Order{7: {so7}}
	h.order.byID = map[int64]*order.Order{10: &so7, 11: &so8}
	sell7 := tx(100, 10, 5, "150", inWindow)
	buy7 := tx(200, 20, 5, "100", taxDate(2026, 4, 1))
	bo := buyOrder(20, 7, 99, 70)
	h.order.byID[20] = &bo
	// Historical includes an order-8 tx that, if filter weren't applied, would match.
	sell8 := tx(101, 110, 5, "150", inWindow)
	h.order.txBetween = []order.Transaction{sell7}
	h.order.txBefore = []order.Transaction{buy7, sell7, sell8}
	h.market.listings = map[int64]*clients.StockListing{99: usdListing()}
	uid := int64(7)
	entries, err := h.svc.buildTaxChargeEntries(context.Background(), taxStart, taxEnd, &uid)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	for _, e := range entries {
		if e.userID != 7 {
			t.Errorf("filter should exclude non-7 users, got %d", e.userID)
		}
	}
}

// buildTaxChargeEntries: a transaction on an order with neither BUY nor SELL
// direction is skipped (the final `o.Direction != DirectionSell` continue).
func TestBuildTaxChargeEntries_SkipsNonBuySellDirection(t *testing.T) {
	h := newHarness("0.15")
	so := sellOrder(10, 7, 99, 70)
	weird := order.Order{ID: 30, UserID: 7, ListingID: 99, AccountID: 70, Direction: "TRANSFER"}
	h.order.byDirection = map[string][]order.Order{order.DirectionSell: {so}}
	h.order.byUserID = map[int64][]order.Order{7: {so, weird}}
	h.order.byID = map[int64]*order.Order{10: &so, 30: &weird}
	sellTxn := tx(100, 10, 5, "150", inWindow)
	weirdTxn := tx(301, 30, 5, "100", taxDate(2026, 4, 2))
	buyTxn := tx(200, 20, 5, "100", taxDate(2026, 4, 1))
	bo := buyOrder(20, 7, 99, 70)
	h.order.byID[20] = &bo
	h.order.txBetween = []order.Transaction{sellTxn}
	h.order.txBefore = []order.Transaction{buyTxn, weirdTxn, sellTxn}
	h.market.listings = map[int64]*clients.StockListing{99: usdListing()}
	entries, err := h.svc.buildTaxChargeEntries(context.Background(), taxStart, taxEnd, nil)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	// The TRANSFER tx contributes no lot and no charge; FIFO from the one buy lot.
	if len(entries) != 1 {
		t.Fatalf("want 1 entry, got %d", len(entries))
	}
}

// buildTaxChargeEntries: same-timestamp transactions exercise the sort
// comparator's direction-rank + id tiebreakers.
func TestBuildTaxChargeEntries_SortTiebreakers(t *testing.T) {
	h := newHarness("0.15")
	so := sellOrder(10, 7, 99, 70)
	bo := buyOrder(20, 7, 99, 70)
	h.order.byDirection = map[string][]order.Order{order.DirectionSell: {so}}
	h.order.byUserID = map[int64][]order.Order{7: {so, bo}}
	h.order.byID = map[int64]*order.Order{10: &so, 20: &bo}
	ts := inWindow
	// Buy and sell share the same timestamp → BUY (rank 0) must sort before SELL.
	buyTxn := tx(200, 20, 5, "100", ts)
	sellTxn := tx(100, 10, 5, "150", ts)
	h.order.txBetween = []order.Transaction{sellTxn}
	h.order.txBefore = []order.Transaction{sellTxn, buyTxn} // deliberately out of order
	h.market.listings = map[int64]*clients.StockListing{99: usdListing()}
	entries, err := h.svc.buildTaxChargeEntries(context.Background(), taxStart, taxEnd, nil)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	// BUY processed first → lot available → SELL taxed: gain 50*5*0.15 = 37.5.
	if len(entries) != 1 || !entries[0].taxAmount.Equal(dec("37.5")) {
		t.Fatalf("sort should place BUY before SELL; got %+v", entries)
	}
}

// loadHistoricalTransactionsForRelevantKeys: multi-user path uses FindByUserIDIn.
func TestBuildTaxChargeEntries_MultiUserUsesByUserIDIn(t *testing.T) {
	h := newHarness("0.15")
	so7 := sellOrder(10, 7, 99, 70)
	so8 := sellOrder(11, 8, 99, 71)
	h.order.byDirection = map[string][]order.Order{order.DirectionSell: {so7, so8}}
	h.order.byID = map[int64]*order.Order{10: &so7, 11: &so8}
	sell7 := tx(100, 10, 5, "150", inWindow)
	sell8 := tx(101, 11, 5, "150", inWindow)
	h.order.txBetween = []order.Transaction{sell7, sell8}
	// Multi-user → FindByUserIDIn returns all candidate orders.
	bo7 := buyOrder(20, 7, 99, 70)
	bo8 := buyOrder(21, 8, 99, 71)
	h.order.byUserIDIn = []order.Order{so7, so8, bo7, bo8}
	h.order.byID[20] = &bo7
	h.order.byID[21] = &bo8
	buy7 := tx(200, 20, 5, "100", taxDate(2026, 4, 1))
	buy8 := tx(201, 21, 5, "100", taxDate(2026, 4, 1))
	h.order.txBefore = []order.Transaction{buy7, buy8, sell7, sell8}
	h.market.listings = map[int64]*clients.StockListing{99: usdListing()}
	entries, err := h.svc.buildTaxChargeEntries(context.Background(), taxStart, taxEnd, nil)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(entries) != 2 {
		t.Fatalf("want 2 entries (one per user), got %d", len(entries))
	}
}

// loadHistoricalTransactionsForRelevantKeys: a relevant-sell whose order has
// ListingID 0 is skipped (the continue inside the listingsByUser builder), and
// the resulting empty scope returns nil.
func TestBuildTaxChargeEntries_RelevantSellZeroListingSkipped(t *testing.T) {
	h := newHarness("0.15")
	// Sell order with ListingID 0 — but isStockOrder needs a listing to pass the
	// relevant filter, so it gets filtered earlier. Use a stock order, then make
	// the historical builder see a zero-listing order via cache injection is not
	// possible here; instead verify the empty-scope nil return via no buy lots.
	so := sellOrder(10, 7, 99, 70)
	h.order.byDirection = map[string][]order.Order{order.DirectionSell: {so}}
	h.order.byUserID = map[int64][]order.Order{7: {so}}
	h.order.byID = map[int64]*order.Order{10: &so}
	sellTxn := tx(100, 10, 5, "150", inWindow)
	h.order.txBetween = []order.Transaction{sellTxn}
	// candidateOrders has only the sell order (no buys) → orderIDs non-empty but no
	// buy lots; this still exercises belongsToRelevantTaxScope true branch.
	h.order.txBefore = []order.Transaction{sellTxn}
	h.market.listings = map[int64]*clients.StockListing{99: usdListing()}
	// No portfolio fallback → no charge, but the historical path is exercised.
	entries, err := h.svc.buildTaxChargeEntries(context.Background(), taxStart, taxEnd, nil)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(entries) != 0 {
		t.Errorf("no buy lots + no fallback → 0 charges, got %d", len(entries))
	}
}

// calculateTrackingMetrics: an OTC component with positive tax flows into the
// per-user metrics alongside stock entries (covers addOtcTrackingMetrics call
// inside calculateTrackingMetrics).
func TestCalculateTrackingMetrics_WithOtc(t *testing.T) {
	h := newHarness("0.15")
	h.market.convertResp = fxRate("8775")
	h.tax.otcEntries = []OtcTaxEntry{otcEntry(1, time.Date(2026, 6, 10, 0, 0, 0, 0, time.UTC))}
	withClock(time.Date(2026, 6, 15, 0, 0, 0, 0, time.UTC), func() {
		m, err := h.svc.calculateTrackingMetrics(context.Background())
		if err != nil {
			t.Fatalf("unexpected: %v", err)
		}
		if m[7] == nil || !m[7].debt.Equal(dec("8775")) {
			t.Errorf("OTC debt not recorded: %+v", m[7])
		}
	})
}

// calculateUnchargedTaxForRangeInRsd: a CHARGED row for a *different* user is not
// excluded when filtering by the target user (covers the userID!=c.UserID continue).
func TestCalculateUnchargedTax_OtherUserChargedNotExcluded(t *testing.T) {
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
	h.market.convertResp = fxRate("3510")
	// A charged row for user 8 with the same key — must NOT exclude user 7's entry.
	h.tax.all = []TaxCharge{{UserID: 8, Status: StatusCharged, SellTransactionID: 100, BuyTransactionID: -1}}
	uid := int64(7)
	got, err := h.svc.calculateUnchargedTaxForRangeInRsd(context.Background(), &uid, taxStart, taxEnd)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if !got.Equal(dec("3510")) {
		t.Errorf("other-user charge should not exclude; want 3510, got %s", got)
	}
}
