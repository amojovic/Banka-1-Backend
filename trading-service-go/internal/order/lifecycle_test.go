package order

import (
	"errors"
	"testing"

	"banka1/trading-service-go/internal/actuary"
	"banka1/trading-service-go/internal/clients"
	"banka1/trading-service-go/internal/portfolio"

	"github.com/shopspring/decimal"
)

// seedDraft inserts a PENDING_CONFIRMATION order owned by userID and returns it.
func seedDraft(h *harness, userID int64, direction string) *Order {
	o := &Order{
		UserID: userID, ListingID: 1, OrderType: TypeMarket, Direction: direction,
		Quantity: 2, ContractSize: 1, PricePerUnit: decimal.RequireFromString("10"),
		RemainingPortions: 2, Status: StatusPendingConfirmation, AccountID: 5,
	}
	_ = h.repo.Insert(ctx(), nil, o)
	return o
}

// ---------------------------------------------------------------------------
// ConfirmOrder
// ---------------------------------------------------------------------------

func TestConfirmOrder_Client_Approved(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.account.details[5] = acct(7, "", "1000000")
	h.account.details[0] = acct(0, "", "0") // bank account (default ResolvedID 0)
	o := seedDraft(h, 7, DirectionBuy)
	resp, err := h.svc.ConfirmOrder(ctx(), clientUser(7), o.ID)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if resp.Status != StatusApproved {
		t.Errorf("status = %q, want APPROVED", resp.Status)
	}
	if h.notifier.created != 1 {
		t.Errorf("created notifications = %d, want 1", h.notifier.created)
	}
}

func TestConfirmOrder_NotFound(t *testing.T) {
	h := newHarness()
	_, err := h.svc.ConfirmOrder(ctx(), clientUser(7), 999)
	if err == nil {
		t.Error("expected 404")
	}
}

func TestConfirmOrder_WrongOwner(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	o := seedDraft(h, 7, DirectionBuy)
	_, err := h.svc.ConfirmOrder(ctx(), clientUser(8), o.ID)
	if err == nil {
		t.Error("expected 403")
	}
}

func TestConfirmOrder_NotDraft(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	o := seedDraft(h, 7, DirectionBuy)
	o.Status = StatusApproved
	_ = h.repo.Update(ctx(), nil, o)
	_, err := h.svc.ConfirmOrder(ctx(), clientUser(7), o.ID)
	if err == nil {
		t.Error("expected 409 not-draft")
	}
}

func TestConfirmOrder_PastSettlement_Declined(t *testing.T) {
	h := newHarness()
	l := listing(1)
	l.SettlementDate = sp("2000-01-01")
	h.market.listings[1] = l
	o := seedDraft(h, 7, DirectionBuy)
	resp, err := h.svc.ConfirmOrder(ctx(), clientUser(7), o.ID)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if resp.Status != StatusDeclined {
		t.Errorf("status = %q, want DECLINED", resp.Status)
	}
}

func TestConfirmOrder_Agent_Pending_NeedApproval(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	bankID := int64(50)
	h.account.bank = &clients.BankAccount{ID: &bankID}
	h.account.details[50] = acct(0, "", "100000000")
	lim := decimal.RequireFromString("1000000")
	h.actuaries.infos[1] = &actuary.ActuaryInfo{EmployeeID: 1, Limit: &lim, NeedApproval: true}
	o := seedDraft(h, 1, DirectionBuy)
	resp, err := h.svc.ConfirmOrder(ctx(), agentUser(1), o.ID)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if resp.Status != StatusPending {
		t.Errorf("status = %q, want PENDING", resp.Status)
	}
}

func TestConfirmOrder_Agent_Approved_TransfersFee(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	bankID := int64(50)
	h.account.bank = &clients.BankAccount{ID: &bankID}
	h.account.details[50] = acct(0, "", "100000000")
	h.account.details[5] = acct(0, "", "100000000")
	h.actuaries.infos[1] = &actuary.ActuaryInfo{EmployeeID: 1} // nil limit -> approved
	o := seedDraft(h, 1, DirectionBuy)
	resp, err := h.svc.ConfirmOrder(ctx(), agentUser(1), o.ID)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if resp.Status != StatusApproved {
		t.Errorf("status = %q", resp.Status)
	}
}

func TestConfirmOrder_Sell_ReservesQuantity(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.portfolios.positions[[2]int64{7, 1}] = &portfolio.Portfolio{ID: 1, UserID: 7, ListingID: 1, Quantity: 100}
	o := seedDraft(h, 7, DirectionSell)
	resp, err := h.svc.ConfirmOrder(ctx(), clientUser(7), o.ID)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if resp.Status != StatusApproved {
		t.Errorf("status = %q", resp.Status)
	}
	if len(h.portfolios.reservedUpdates) == 0 {
		t.Error("expected a reserved-quantity update")
	}
}

func TestConfirmOrder_FundOrder_NoFee(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.account.details[5] = acct(0, "", "100000000")
	o := seedDraft(h, 1, DirectionBuy)
	pf := PurchaseForInvestmentFund
	o.PurchaseFor = &pf
	o.FundID = int64Ptr(10)
	_ = h.repo.Update(ctx(), nil, o)
	resp, err := h.svc.ConfirmOrder(ctx(), agentUser(1), o.ID)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if resp.Fee.Sign() != 0 {
		t.Errorf("fund order fee must be zero, got %s", resp.Fee)
	}
}

func TestConfirmOrder_ListingError(t *testing.T) {
	h := newHarness()
	o := seedDraft(h, 7, DirectionBuy)
	h.market.listingErr = errors.New("boom")
	_, err := h.svc.ConfirmOrder(ctx(), clientUser(7), o.ID)
	if err == nil {
		t.Error("expected listing error")
	}
}

func TestConfirmOrder_Margin_ChecksRequirements(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.account.details[5] = acct(7, "", "100000000")
	h.account.details[0] = acct(0, "", "0") // bank account
	u := AuthUser{UserID: 7, Roles: []string{"CLIENT"}, Permissions: []string{"SECURITIES_TRADE", "MARGIN_TRADE"}}
	o := seedDraft(h, 7, DirectionBuy)
	o.Margin = true
	_ = h.repo.Update(ctx(), nil, o)
	resp, err := h.svc.ConfirmOrder(ctx(), u, o.ID)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if resp.Status != StatusApproved {
		t.Errorf("status = %q", resp.Status)
	}
}

// ---------------------------------------------------------------------------
// ApproveOrder / DeclineOrder
// ---------------------------------------------------------------------------

func seedPending(h *harness, userID int64, direction string) *Order {
	o := &Order{
		UserID: userID, ListingID: 1, OrderType: TypeMarket, Direction: direction,
		Quantity: 2, ContractSize: 1, PricePerUnit: decimal.RequireFromString("10"),
		RemainingPortions: 2, Status: StatusPending, AccountID: 5,
	}
	_ = h.repo.Insert(ctx(), nil, o)
	return o
}

func TestApproveOrder_HappyPath(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	bankID := int64(50)
	h.account.bank = &clients.BankAccount{ID: &bankID}
	h.account.details[50] = acct(0, "", "100000000")
	h.account.details[5] = acct(0, "", "100000000") // funding account
	o := seedPending(h, 1, DirectionBuy)
	resp, err := h.svc.ApproveOrder(ctx(), 99, o.ID)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if resp.Status != StatusApproved {
		t.Errorf("status = %q", resp.Status)
	}
	if h.notifier.approved != 1 {
		t.Errorf("approved notifications = %d", h.notifier.approved)
	}
	if len(h.auditor.events) != 1 {
		t.Errorf("audit events = %d", len(h.auditor.events))
	}
}

func TestApproveOrder_NotFound(t *testing.T) {
	h := newHarness()
	_, err := h.svc.ApproveOrder(ctx(), 99, 999)
	if err == nil {
		t.Error("expected 404")
	}
}

func TestApproveOrder_NotPending(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	o := seedDraft(h, 1, DirectionBuy)
	_, err := h.svc.ApproveOrder(ctx(), 99, o.ID)
	if err == nil {
		t.Error("expected 409 not-pending")
	}
}

func TestApproveOrder_PastSettlement_409(t *testing.T) {
	h := newHarness()
	l := listing(1)
	l.SettlementDate = sp("2000-01-01")
	h.market.listings[1] = l
	o := seedPending(h, 1, DirectionBuy)
	_, err := h.svc.ApproveOrder(ctx(), 99, o.ID)
	if err == nil {
		t.Error("expected 409 past-settlement")
	}
}

func TestDeclineOrder_HappyPath(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	o := seedPending(h, 1, DirectionBuy)
	resp, err := h.svc.DeclineOrder(ctx(), 99, o.ID)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if resp.Status != StatusDeclined {
		t.Errorf("status = %q", resp.Status)
	}
	if h.notifier.declined != 1 {
		t.Errorf("declined notifications = %d", h.notifier.declined)
	}
}

func TestDeclineOrder_NotFound(t *testing.T) {
	h := newHarness()
	_, err := h.svc.DeclineOrder(ctx(), 99, 999)
	if err == nil {
		t.Error("expected 404")
	}
}

func TestDeclineOrder_NotPending(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	o := seedDraft(h, 1, DirectionBuy)
	_, err := h.svc.DeclineOrder(ctx(), 99, o.ID)
	if err == nil {
		t.Error("expected 409 not-pending")
	}
}

func TestDeclineOrder_Sell_ReleasesReservation(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.portfolios.positions[[2]int64{1, 1}] = &portfolio.Portfolio{ID: 1, UserID: 1, ListingID: 1, Quantity: 100, ReservedQuantity: 2}
	o := seedPending(h, 1, DirectionSell)
	_, err := h.svc.DeclineOrder(ctx(), 99, o.ID)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
}

// ---------------------------------------------------------------------------
// CancelOrder / CancelOrderSupervisor
// ---------------------------------------------------------------------------

func TestCancelOrder_Owner_FullCancel(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	o := seedPending(h, 7, DirectionBuy)
	resp, err := h.svc.CancelOrder(ctx(), clientUser(7), o.ID)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if resp.Status != StatusCancelled {
		t.Errorf("status = %q", resp.Status)
	}
}

func TestCancelOrder_NotFound(t *testing.T) {
	h := newHarness()
	_, err := h.svc.CancelOrder(ctx(), clientUser(7), 999)
	if err == nil {
		t.Error("expected 404")
	}
}

func TestCancelOrder_WrongOwner(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	o := seedPending(h, 7, DirectionBuy)
	_, err := h.svc.CancelOrder(ctx(), clientUser(8), o.ID)
	if err == nil {
		t.Error("expected 403")
	}
}

func TestCancelOrder_AlreadyDone_409(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	o := seedPending(h, 7, DirectionBuy)
	o.Status = StatusDone
	_ = h.repo.Update(ctx(), nil, o)
	_, err := h.svc.CancelOrder(ctx(), clientUser(7), o.ID)
	if err == nil {
		t.Error("expected 409 already-done")
	}
}

func TestCancelOrder_PendingPastSettlement_409(t *testing.T) {
	h := newHarness()
	l := listing(1)
	l.SettlementDate = sp("2000-01-01")
	h.market.listings[1] = l
	o := seedPending(h, 7, DirectionBuy)
	_, err := h.svc.CancelOrder(ctx(), clientUser(7), o.ID)
	if err == nil {
		t.Error("expected 409 expired-pending")
	}
}

func TestCancelOrderSupervisor_PartialCancel(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	o := seedPending(h, 7, DirectionBuy) // remaining 2
	resp, err := h.svc.CancelOrderSupervisor(ctx(), o.ID, intPtr(1))
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if resp.RemainingPortions != 1 {
		t.Errorf("remaining = %d, want 1", resp.RemainingPortions)
	}
	if resp.Status == StatusCancelled {
		t.Error("partial cancel should not fully cancel")
	}
}

func TestCancelOrder_InvalidQuantity_400(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	o := seedPending(h, 7, DirectionBuy)
	_, err := h.svc.CancelOrderSupervisor(ctx(), o.ID, intPtr(99)) // > remaining
	if err == nil {
		t.Error("expected 400 invalid quantity")
	}
}

func TestCancelOrder_Sell_ReleasesReservationAndExposure(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.portfolios.positions[[2]int64{1, 1}] = &portfolio.Portfolio{ID: 1, UserID: 1, ListingID: 1, Quantity: 100, ReservedQuantity: 2}
	lim := decimal.RequireFromString("1000")
	h.actuaries.infos[1] = &actuary.ActuaryInfo{EmployeeID: 1, Limit: &lim, ReservedLimit: decimal.RequireFromString("20")}
	o := seedPending(h, 1, DirectionSell)
	o.ReservedLimitExposure = decimal.RequireFromString("20")
	_ = h.repo.Update(ctx(), nil, o)
	resp, err := h.svc.CancelOrderSupervisor(ctx(), o.ID, nil)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if resp.Status != StatusCancelled {
		t.Errorf("status = %q", resp.Status)
	}
}

// ---------------------------------------------------------------------------
// AutoDeclineExpiredPendingOrders
// ---------------------------------------------------------------------------

func TestAutoDecline_ExpiredOrder(t *testing.T) {
	h := newHarness()
	l := listing(1)
	l.SettlementDate = sp("2000-01-01")
	h.market.listings[1] = l
	o := seedPending(h, 1, DirectionBuy)
	h.repo.byStatus[StatusPending] = []Order{*o}
	if err := h.svc.AutoDeclineExpiredPendingOrders(ctx()); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if h.notifier.autoCancelled != 1 {
		t.Errorf("auto-cancelled notifications = %d, want 1", h.notifier.autoCancelled)
	}
	updated, _ := h.repo.FindByID(ctx(), nil, o.ID)
	if updated.Status != StatusDeclined {
		t.Errorf("status = %q, want DECLINED", updated.Status)
	}
}

func TestAutoDecline_NotExpired_Skipped(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1) // no settlement date -> not past
	o := seedPending(h, 1, DirectionBuy)
	h.repo.byStatus[StatusPending] = []Order{*o}
	if err := h.svc.AutoDeclineExpiredPendingOrders(ctx()); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if h.notifier.autoCancelled != 0 {
		t.Errorf("auto-cancelled = %d, want 0", h.notifier.autoCancelled)
	}
}

func TestAutoDecline_FindStatusError(t *testing.T) {
	h := newHarness()
	h.repo.statusErr = errors.New("boom")
	if err := h.svc.AutoDeclineExpiredPendingOrders(ctx()); err == nil {
		t.Error("expected error")
	}
}

func TestAutoDecline_PerOrderError_Continues(t *testing.T) {
	h := newHarness()
	o := seedPending(h, 1, DirectionBuy)
	h.repo.byStatus[StatusPending] = []Order{*o}
	h.market.listingErr = errors.New("boom") // listing lookup inside tx fails
	if err := h.svc.AutoDeclineExpiredPendingOrders(ctx()); err != nil {
		t.Fatalf("batch should not fail: %v", err)
	}
	if h.notifier.autoCancelled != 0 {
		t.Errorf("auto-cancelled = %d, want 0", h.notifier.autoCancelled)
	}
}

func TestAutoDecline_StatusChangedBeforeLock_Skipped(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	// Listed as pending in byStatus, but the actual stored row is APPROVED.
	o := seedPending(h, 1, DirectionBuy)
	listed := *o
	o.Status = StatusApproved
	_ = h.repo.Update(ctx(), nil, o)
	h.repo.byStatus[StatusPending] = []Order{listed}
	if err := h.svc.AutoDeclineExpiredPendingOrders(ctx()); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if h.notifier.autoCancelled != 0 {
		t.Errorf("auto-cancelled = %d, want 0", h.notifier.autoCancelled)
	}
}

// ---------------------------------------------------------------------------
// Reservation helpers (direct)
// ---------------------------------------------------------------------------

func TestReserveSellQuantityIfNeeded_BuyNoop(t *testing.T) {
	h := newHarness()
	if err := h.svc.reserveSellQuantityIfNeeded(ctx(), nil, &Order{Direction: DirectionBuy}); err != nil {
		t.Errorf("buy should be a no-op: %v", err)
	}
}

func TestReserveSellQuantityIfNeeded_NoPortfolio_404(t *testing.T) {
	h := newHarness()
	err := h.svc.reserveSellQuantityIfNeeded(ctx(), nil, &Order{Direction: DirectionSell, UserID: 7, ListingID: 1, RemainingPortions: 5})
	if err == nil {
		t.Error("expected 404")
	}
}

func TestReserveSellQuantityIfNeeded_Insufficient_409(t *testing.T) {
	h := newHarness()
	h.portfolios.positions[[2]int64{7, 1}] = &portfolio.Portfolio{ID: 1, UserID: 7, ListingID: 1, Quantity: 2, ReservedQuantity: 0}
	err := h.svc.reserveSellQuantityIfNeeded(ctx(), nil, &Order{Direction: DirectionSell, UserID: 7, ListingID: 1, RemainingPortions: 5})
	if err == nil {
		t.Error("expected 409")
	}
}

func TestReleaseSellReservation_ClampsToZero(t *testing.T) {
	h := newHarness()
	h.portfolios.positions[[2]int64{7, 1}] = &portfolio.Portfolio{ID: 1, UserID: 7, ListingID: 1, Quantity: 100, ReservedQuantity: 1}
	o := &Order{Direction: DirectionSell, UserID: 7, ListingID: 1}
	if err := h.svc.releaseSellReservation(ctx(), nil, o, 5); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if h.portfolios.reservedUpdates[len(h.portfolios.reservedUpdates)-1] != 0 {
		t.Error("reserved should clamp to 0")
	}
}

func TestReleaseSellReservation_NoPosition_Noop(t *testing.T) {
	h := newHarness()
	o := &Order{Direction: DirectionSell, UserID: 7, ListingID: 1}
	if err := h.svc.releaseSellReservation(ctx(), nil, o, 5); err != nil {
		t.Errorf("unexpected: %v", err)
	}
}

func TestReleaseAgentExposure_FullRelease(t *testing.T) {
	h := newHarness()
	h.actuaries.infos[1] = &actuary.ActuaryInfo{EmployeeID: 1, ReservedLimit: decimal.RequireFromString("100")}
	o := &Order{UserID: 1, Quantity: 4, RemainingPortions: 0, ReservedLimitExposure: decimal.RequireFromString("100")}
	if err := h.svc.releaseAgentExposure(ctx(), nil, o, 4); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if o.ReservedLimitExposure.Sign() != 0 {
		t.Errorf("exposure = %s, want 0", o.ReservedLimitExposure)
	}
}

func TestReleaseAgentExposure_ProportionalRelease(t *testing.T) {
	h := newHarness()
	h.actuaries.infos[1] = &actuary.ActuaryInfo{EmployeeID: 1, ReservedLimit: decimal.RequireFromString("100")}
	o := &Order{UserID: 1, Quantity: 4, RemainingPortions: 4, ReservedLimitExposure: decimal.RequireFromString("100")}
	if err := h.svc.releaseAgentExposure(ctx(), nil, o, 2); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	// release 100 * 2/4 = 50; exposure now 50.
	if !o.ReservedLimitExposure.Equal(decimal.RequireFromString("50")) {
		t.Errorf("exposure = %s, want 50", o.ReservedLimitExposure)
	}
}

func TestReleaseAgentExposure_NoExposure_Noop(t *testing.T) {
	h := newHarness()
	o := &Order{ReservedLimitExposure: decimal.Zero}
	if err := h.svc.releaseAgentExposure(ctx(), nil, o, 1); err != nil {
		t.Errorf("unexpected: %v", err)
	}
}

func TestReleaseAgentExposure_NoActuary_ResetsExposure(t *testing.T) {
	h := newHarness()
	o := &Order{UserID: 1, Quantity: 0, ReservedLimitExposure: decimal.RequireFromString("50")}
	if err := h.svc.releaseAgentExposure(ctx(), nil, o, 1); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if o.ReservedLimitExposure.Sign() != 0 {
		t.Errorf("exposure = %s, want 0", o.ReservedLimitExposure)
	}
}

func TestDetermineOrderStatusAndReserveExposure_NoActuary_Approved(t *testing.T) {
	h := newHarness()
	status, exp, err := h.svc.determineOrderStatusAndReserveExposure(ctx(), nil, 1, decimal.RequireFromString("10"), "RSD")
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if status != StatusApproved || exp.Sign() != 0 {
		t.Errorf("got %s,%s", status, exp)
	}
}

func TestDetermineOrderStatusAndReserveExposure_LimitExceeded_Pending(t *testing.T) {
	h := newHarness()
	lim := decimal.RequireFromString("100")
	h.actuaries.infos[1] = &actuary.ActuaryInfo{EmployeeID: 1, Limit: &lim, UsedLimit: decimal.RequireFromString("95")}
	status, exp, err := h.svc.determineOrderStatusAndReserveExposure(ctx(), nil, 1, decimal.RequireFromString("50"), "RSD")
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if status != StatusPending {
		t.Errorf("status = %q, want PENDING", status)
	}
	if exp.Sign() == 0 {
		t.Error("expected reserved exposure")
	}
}

func TestDetermineOrderStatusAndReserveExposure_WithinLimit_Approved(t *testing.T) {
	h := newHarness()
	lim := decimal.RequireFromString("1000")
	h.actuaries.infos[1] = &actuary.ActuaryInfo{EmployeeID: 1, Limit: &lim}
	status, _, err := h.svc.determineOrderStatusAndReserveExposure(ctx(), nil, 1, decimal.RequireFromString("50"), "RSD")
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if status != StatusApproved {
		t.Errorf("status = %q, want APPROVED", status)
	}
}

func TestInitialBuyAccountID_ClientNoSelection_400(t *testing.T) {
	h := newHarness()
	_, err := h.svc.initialBuyAccountID(ctx(), clientUser(7), nil, "USD")
	if err == nil {
		t.Error("expected 400")
	}
}

func TestInitialBuyAccountID_ClientSelected(t *testing.T) {
	h := newHarness()
	id, err := h.svc.initialBuyAccountID(ctx(), clientUser(7), int64Ptr(5), "USD")
	if err != nil || id != 5 {
		t.Errorf("got %d,%v", id, err)
	}
}

func TestExecutePaymentByOwnership_SameOwner_Transfer(t *testing.T) {
	h := newHarness()
	from := acct(7, "USD", "100")
	to := acct(7, "USD", "0")
	if err := h.svc.executePaymentByOwnership(ctx(), from, to, clients.Payment{}); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(h.account.transfers) != 1 {
		t.Errorf("expected transfer, got %d transfers", len(h.account.transfers))
	}
}

func TestExecutePaymentByOwnership_CrossOwner_Transaction(t *testing.T) {
	h := newHarness()
	from := acct(7, "USD", "100")
	to := acct(8, "USD", "0")
	if err := h.svc.executePaymentByOwnership(ctx(), from, to, clients.Payment{}); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(h.account.transactions) != 1 {
		t.Errorf("expected transaction, got %d", len(h.account.transactions))
	}
}
