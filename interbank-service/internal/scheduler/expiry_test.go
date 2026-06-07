package scheduler_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/scheduler"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
)

// ---------------------------------------------------------------------------
// Fakes
// ---------------------------------------------------------------------------

type fakeExpiryStore struct {
	expirable []*store.Contract
	statuses  map[string]string // contractID → status set
	failFlip  map[string]bool   // contractID → UpdateStatus returns error
}

func newFakeExpiryStore(cs ...*store.Contract) *fakeExpiryStore {
	return &fakeExpiryStore{expirable: cs, statuses: map[string]string{}, failFlip: map[string]bool{}}
}

func (f *fakeExpiryStore) ListExpirable(_ context.Context) ([]*store.Contract, error) {
	return f.expirable, nil
}

func (f *fakeExpiryStore) UpdateStatus(_ context.Context, id, status string) error {
	if f.failFlip[id] {
		return errors.New("fakeExpiryStore: UpdateStatus forced failure for " + id)
	}
	f.statuses[id] = status
	return nil
}

type fakeOptionReleaser struct {
	released []string // negotiation ids released
	failNeg  map[string]bool
}

func newFakeOptionReleaser() *fakeOptionReleaser {
	return &fakeOptionReleaser{failNeg: map[string]bool{}}
}

func (f *fakeOptionReleaser) ReleaseOption(_ context.Context, negID protocol.ForeignBankId) error {
	if f.failNeg[negID.Id] {
		return errors.New("fakeOptionReleaser: ReleaseOption forced failure for " + negID.Id)
	}
	f.released = append(f.released, negID.Id)
	return nil
}

const expiryMyRouting = 111

func pastContract(id, neg, party string) *store.Contract {
	return &store.Contract{
		ID:             id,
		NegotiationID:  neg,
		BuyerRouting:   222,
		BuyerID:        "C-2",
		SellerRouting:  111,
		SellerID:       "C-5",
		StockTicker:    "AAPL",
		Amount:         10,
		StrikeCurrency: "USD",
		SettlementDate: time.Now().Add(-1 * time.Hour),
		Status:         store.ContractStatusActive,
		LocalPartyType: party,
	}
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

func TestExpiry_SellerContract_ReleasesReservationAndFlipsExpired(t *testing.T) {
	st := newFakeExpiryStore(pastContract("otc-sell-1", "neg-sell-1", store.ContractPartySeller))
	rel := newFakeOptionReleaser()
	sched := scheduler.NewExpiryScheduler(st, rel, expiryMyRouting, time.Minute, nil)

	sched.TickOnce(context.Background())

	if len(rel.released) != 1 || rel.released[0] != "neg-sell-1" {
		t.Errorf("expected ReleaseOption(neg-sell-1) for seller contract, got %v", rel.released)
	}
	if st.statuses["otc-sell-1"] != store.ContractStatusExpired {
		t.Errorf("expected seller contract EXPIRED, got %q", st.statuses["otc-sell-1"])
	}
}

func TestExpiry_BuyerContract_FlipsExpired_NoMoneyMove(t *testing.T) {
	st := newFakeExpiryStore(pastContract("otc-buy-1", "neg-buy-1", store.ContractPartyBuyer))
	rel := newFakeOptionReleaser()
	sched := scheduler.NewExpiryScheduler(st, rel, expiryMyRouting, time.Minute, nil)

	sched.TickOnce(context.Background())

	// Buyer side must NOT release any option (no standing reservation in Banka 1).
	if len(rel.released) != 0 {
		t.Errorf("buyer contract must not release any reservation, got %v", rel.released)
	}
	if st.statuses["otc-buy-1"] != store.ContractStatusExpired {
		t.Errorf("expected buyer contract EXPIRED, got %q", st.statuses["otc-buy-1"])
	}
}

func TestExpiry_FailureOnOneContract_DoesNotAbortOthers(t *testing.T) {
	c1 := pastContract("otc-sell-bad", "neg-bad", store.ContractPartySeller)
	c2 := pastContract("otc-sell-good", "neg-good", store.ContractPartySeller)
	st := newFakeExpiryStore(c1, c2)
	rel := newFakeOptionReleaser()
	// First contract's release fails — its flip must be skipped, second must still settle.
	rel.failNeg["neg-bad"] = true
	sched := scheduler.NewExpiryScheduler(st, rel, expiryMyRouting, time.Minute, nil)

	sched.TickOnce(context.Background())

	if _, flipped := st.statuses["otc-sell-bad"]; flipped {
		t.Errorf("contract whose release failed must NOT be flipped EXPIRED, got %q", st.statuses["otc-sell-bad"])
	}
	if st.statuses["otc-sell-good"] != store.ContractStatusExpired {
		t.Errorf("second contract must still be EXPIRED despite first failure, got %q", st.statuses["otc-sell-good"])
	}
}

func TestExpiry_NoExpirable_NoOp(t *testing.T) {
	st := newFakeExpiryStore()
	rel := newFakeOptionReleaser()
	sched := scheduler.NewExpiryScheduler(st, rel, expiryMyRouting, time.Minute, nil)
	sched.TickOnce(context.Background()) // must not panic / do nothing
	if len(rel.released) != 0 || len(st.statuses) != 0 {
		t.Errorf("expected no work, got released=%v statuses=%v", rel.released, st.statuses)
	}
}
