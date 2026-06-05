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

func seedRecurring(h *harness, userID int64, mode, direction string, value string) *RecurringOrder {
	ro := &RecurringOrder{
		UserID: userID, ListingID: 1, Direction: direction, Mode: mode,
		Value: decimal.RequireFromString(value), AccountID: 5, Cadence: CadenceDaily,
		NextRun: time.Now().Add(-time.Hour), Active: true,
	}
	_ = h.repo.InsertRecurring(ctx(), nil, ro)
	return ro
}

// ---------------------------------------------------------------------------
// CRUD
// ---------------------------------------------------------------------------

func TestGetRecurringOrders(t *testing.T) {
	h := newHarness()
	h.repo.recurByUser[7] = []RecurringOrder{{ID: 1, UserID: 7, Direction: DirectionBuy}}
	out, err := h.svc.GetRecurringOrders(ctx(), 7)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(out) != 1 {
		t.Errorf("len = %d, want 1", len(out))
	}
}

func TestGetRecurringOrders_Error(t *testing.T) {
	h := newHarness()
	h.repo.recurByUserErr = errors.New("boom")
	if _, err := h.svc.GetRecurringOrders(ctx(), 7); err == nil {
		t.Error("expected error")
	}
}

func TestCreateRecurringOrder(t *testing.T) {
	h := newHarness()
	dto, err := h.svc.CreateRecurringOrder(ctx(), 7, 1, DirectionBuy, RecurringModeByQuantity, decimal.RequireFromString("10"), 5, CadenceWeekly, time.Now())
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if dto.ID == 0 {
		t.Error("expected persisted id")
	}
}

func TestCreateRecurringOrder_Error(t *testing.T) {
	h := newHarness()
	h.repo.insertRecurErr = errors.New("boom")
	if _, err := h.svc.CreateRecurringOrder(ctx(), 7, 1, DirectionBuy, RecurringModeByQuantity, decimal.RequireFromString("10"), 5, CadenceWeekly, time.Now()); err == nil {
		t.Error("expected error")
	}
}

func TestPauseResumeRecurring(t *testing.T) {
	h := newHarness()
	ro := seedRecurring(h, 7, RecurringModeByQuantity, DirectionBuy, "5")
	dto, err := h.svc.PauseRecurringOrder(ctx(), 7, ro.ID)
	if err != nil {
		t.Fatalf("pause: %v", err)
	}
	if dto.Active {
		t.Error("paused should be inactive")
	}
	dto, err = h.svc.ResumeRecurringOrder(ctx(), 7, ro.ID)
	if err != nil {
		t.Fatalf("resume: %v", err)
	}
	if !dto.Active {
		t.Error("resumed should be active")
	}
}

func TestSetRecurringActive_NotOwned(t *testing.T) {
	h := newHarness()
	ro := seedRecurring(h, 7, RecurringModeByQuantity, DirectionBuy, "5")
	if _, err := h.svc.PauseRecurringOrder(ctx(), 8, ro.ID); err == nil {
		t.Error("expected 404 for foreign owner")
	}
}

func TestSetRecurringActive_SetError(t *testing.T) {
	h := newHarness()
	ro := seedRecurring(h, 7, RecurringModeByQuantity, DirectionBuy, "5")
	h.repo.setActiveErr = errors.New("boom")
	if _, err := h.svc.PauseRecurringOrder(ctx(), 7, ro.ID); err == nil {
		t.Error("expected set error")
	}
}

func TestCancelRecurringOrder(t *testing.T) {
	h := newHarness()
	ro := seedRecurring(h, 7, RecurringModeByQuantity, DirectionBuy, "5")
	if err := h.svc.CancelRecurringOrder(ctx(), 7, ro.ID); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if _, ok := h.repo.recurring[ro.ID]; ok {
		t.Error("expected deletion")
	}
}

func TestCancelRecurringOrder_NotOwned(t *testing.T) {
	h := newHarness()
	ro := seedRecurring(h, 7, RecurringModeByQuantity, DirectionBuy, "5")
	if err := h.svc.CancelRecurringOrder(ctx(), 8, ro.ID); err == nil {
		t.Error("expected 404")
	}
}

func TestGetOwnedRecurring_FindError(t *testing.T) {
	h := newHarness()
	h.repo.findRecurErr = errors.New("boom")
	if _, err := h.svc.getOwnedRecurring(ctx(), 7, 1); err == nil {
		t.Error("expected find error")
	}
}

// ---------------------------------------------------------------------------
// Scheduler / firing
// ---------------------------------------------------------------------------

func TestRunDueRecurringOrders_None(t *testing.T) {
	h := newHarness()
	if err := h.svc.RunDueRecurringOrders(ctx()); err != nil {
		t.Errorf("unexpected: %v", err)
	}
}

func TestRunDueRecurringOrders_DueError(t *testing.T) {
	h := newHarness()
	h.repo.dueErr = errors.New("boom")
	if err := h.svc.RunDueRecurringOrders(ctx()); err == nil {
		t.Error("expected due error")
	}
}

func TestRunDueRecurringOrders_FiresAndAdvances(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.account.details[5] = acct(7, "", "1000000")
	ro := seedRecurring(h, 7, RecurringModeByQuantity, DirectionBuy, "2")
	h.repo.due = []RecurringOrder{*ro}
	if err := h.svc.RunDueRecurringOrders(ctx()); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if _, ok := h.repo.nextRunSet[ro.ID]; !ok {
		t.Error("expected next_run advanced")
	}
}

func TestRunDueRecurringOrder_InactiveSkipped(t *testing.T) {
	h := newHarness()
	ro := seedRecurring(h, 7, RecurringModeByQuantity, DirectionBuy, "2")
	ro.Active = false
	h.repo.recurring[ro.ID] = ro
	h.svc.runDueRecurringOrder(ctx(), ro.ID)
	if _, ok := h.repo.nextRunSet[ro.ID]; ok {
		t.Error("inactive should not advance")
	}
}

func TestRunDueRecurringOrder_LoadError(t *testing.T) {
	h := newHarness()
	h.repo.findRecurErr = errors.New("boom")
	h.svc.runDueRecurringOrder(ctx(), 1) // must not panic
}

func TestRunDueRecurringOrder_SkippedOn409(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.account.details[5] = acct(7, "", "1") // insufficient -> 409 at create
	ro := seedRecurring(h, 7, RecurringModeByQuantity, DirectionBuy, "2")
	h.svc.runDueRecurringOrder(ctx(), ro.ID)
	if h.notifier.recurringSkipped != 1 {
		t.Errorf("recurring skipped notifications = %d, want 1", h.notifier.recurringSkipped)
	}
	if _, ok := h.repo.nextRunSet[ro.ID]; !ok {
		t.Error("schedule must advance even on skip")
	}
}

func TestRunDueRecurringOrder_FailureAdvances(t *testing.T) {
	h := newHarness()
	h.market.listingErr = errors.New("boom") // fire fails non-409
	ro := seedRecurring(h, 7, RecurringModeByQuantity, DirectionBuy, "2")
	h.svc.runDueRecurringOrder(ctx(), ro.ID)
	if _, ok := h.repo.nextRunSet[ro.ID]; !ok {
		t.Error("schedule must advance even on failure")
	}
}

func TestFireRecurringOrder_SellPath(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.portfolios.positions[[2]int64{7, 1}] = &portfolio.Portfolio{ID: 1, UserID: 7, ListingID: 1, Quantity: 100}
	h.account.details[5] = acct(7, "", "1000000")
	ro := seedRecurring(h, 7, RecurringModeByQuantity, DirectionSell, "2")
	if err := h.svc.fireRecurringOrder(ctx(), ro); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
}

func TestFireRecurringOrder_ListingError(t *testing.T) {
	h := newHarness()
	h.market.listingErr = errors.New("boom")
	ro := seedRecurring(h, 7, RecurringModeByQuantity, DirectionBuy, "2")
	if err := h.svc.fireRecurringOrder(ctx(), ro); err == nil {
		t.Error("expected listing error")
	}
}

func TestFireRecurringOrder_ZeroQuantity_409(t *testing.T) {
	h := newHarness()
	l := listing(1)
	l.Ask = dp("0")
	h.market.listings[1] = l
	ro := seedRecurring(h, 7, RecurringModeByAmount, DirectionBuy, "1")
	if err := h.svc.fireRecurringOrder(ctx(), ro); err == nil {
		t.Error("expected 409 zero quantity")
	}
}

func TestResolveRecurringQuantity_ByQuantity(t *testing.T) {
	ro := &RecurringOrder{Mode: RecurringModeByQuantity, Value: decimal.RequireFromString("7.9")}
	if q := resolveRecurringQuantity(ro, listing(1)); q != 7 {
		t.Errorf("got %d, want 7 (floored)", q)
	}
}

func TestResolveRecurringQuantity_ByAmount(t *testing.T) {
	l := listing(1)
	l.Ask = dp("10")
	l.ContractSize = ip(1)
	ro := &RecurringOrder{Mode: RecurringModeByAmount, Value: decimal.RequireFromString("35")}
	if q := resolveRecurringQuantity(ro, l); q != 3 {
		t.Errorf("got %d, want 3 (35/10 truncated)", q)
	}
}

func TestResolveRecurringQuantity_ByAmount_ZeroUnitCost(t *testing.T) {
	l := listing(1)
	l.Ask = nil
	ro := &RecurringOrder{Mode: RecurringModeByAmount, Value: decimal.RequireFromString("100")}
	if q := resolveRecurringQuantity(ro, l); q != 0 {
		t.Errorf("got %d, want 0", q)
	}
}

func TestRecurringUnitCost_NilListing(t *testing.T) {
	if !recurringUnitCost(nil).IsZero() {
		t.Error("nil listing -> zero unit cost")
	}
}

func TestBuildRecurringOwner_Agent(t *testing.T) {
	h := newHarness()
	h.actuaries.infos[1] = &actuary.ActuaryInfo{EmployeeID: 1}
	u := h.svc.buildRecurringOwner(ctx(), 1)
	if !u.IsAgent() {
		t.Error("actuary should build as agent")
	}
}

func TestBuildRecurringOwner_Client(t *testing.T) {
	h := newHarness()
	u := h.svc.buildRecurringOwner(ctx(), 1)
	if !u.IsClient() {
		t.Error("non-actuary should build as client")
	}
}

func TestAdvanceRecurringNextRun_ReloadNil_Noop(t *testing.T) {
	h := newHarness()
	h.svc.advanceRecurringNextRun(ctx(), 999) // no such row -> no panic
}

func TestAdvanceRecurringNextRun_ReloadError(t *testing.T) {
	h := newHarness()
	h.repo.findRecurErr = errors.New("boom")
	h.svc.advanceRecurringNextRun(ctx(), 1) // logs, no panic
}

func TestAdvanceRecurringNextRun_UpdateError(t *testing.T) {
	h := newHarness()
	ro := seedRecurring(h, 7, RecurringModeByQuantity, DirectionBuy, "2")
	h.repo.updateNextErr = errors.New("boom")
	h.svc.advanceRecurringNextRun(ctx(), ro.ID) // logs, no panic
}

func TestPublishRecurringSkipped_ClientCustomer(t *testing.T) {
	h := newHarness()
	h.customers.customers[7] = &clients.Customer{FirstName: sp("Jo"), Email: sp("jo@x.y")}
	ro := &RecurringOrder{ID: 1, UserID: 7, ListingID: 1}
	h.svc.publishRecurringSkipped(ctx(), ro, "")
	if h.notifier.recurringSkipped != 1 {
		t.Errorf("skipped = %d, want 1", h.notifier.recurringSkipped)
	}
}

func TestPublishRecurringSkipped_Employee(t *testing.T) {
	h := newHarness()
	// Non-nil actuary info means the else-branch (employee) fires.
	h.actuaries.infos[7] = &actuary.ActuaryInfo{EmployeeID: 7}
	h.employees.employees[7] = &clients.Employee{Ime: sp("E"), Email: sp("e@x.y")}
	ro := &RecurringOrder{ID: 1, UserID: 7, ListingID: 1}
	h.svc.publishRecurringSkipped(ctx(), ro, "reason")
	if h.notifier.recurringSkipped != 1 {
		t.Errorf("skipped = %d, want 1", h.notifier.recurringSkipped)
	}
}
