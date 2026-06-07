package service

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
)

// S4 list + S6 guard tests for OtcOutboundService.ListContracts / ExerciseContract.

// fakeContractReader implements ContractReader.
type fakeContractReader struct {
	byID      map[string]*store.Contract
	buyerList []*store.Contract
	listErr   error
	updateErr error
	updated   map[string]string
}

func newFakeContractReader() *fakeContractReader {
	return &fakeContractReader{byID: map[string]*store.Contract{}, updated: map[string]string{}}
}

func (f *fakeContractReader) FindByID(_ context.Context, id string) (*store.Contract, error) {
	c, ok := f.byID[id]
	if !ok {
		return nil, nil
	}
	cp := *c
	return &cp, nil
}

func (f *fakeContractReader) ListBuyerContracts(_ context.Context, buyerID string) ([]*store.Contract, error) {
	if f.listErr != nil {
		return nil, f.listErr
	}
	if buyerID == "" {
		return f.buyerList, nil
	}
	var out []*store.Contract
	for _, c := range f.buyerList {
		if c.BuyerID == buyerID {
			out = append(out, c)
		}
	}
	return out, nil
}

func (f *fakeContractReader) UpdateStatus(_ context.Context, id, status string) error {
	if f.updateErr != nil {
		return f.updateErr
	}
	f.updated[id] = status
	return nil
}

// fakeExerciseCoordinator implements ExerciseCoordinator.
type fakeExerciseCoordinator struct {
	called      bool
	gotContract *store.Contract
	gotBuyer    int64
	gotAccount  string
	err         error
}

func (f *fakeExerciseCoordinator) ExerciseOutbound(_ context.Context, c *store.Contract, buyer int64, account string) error {
	f.called = true
	f.gotContract = c
	f.gotBuyer = buyer
	f.gotAccount = account
	return f.err
}

func activeBuyerContract(id string) *store.Contract {
	return &store.Contract{
		ID:             id,
		NegotiationID:  "neg-" + id,
		BuyerRouting:   testOutboundMyRouting,
		BuyerID:        "C-7",
		SellerRouting:  testOutboundPartnerRN,
		SellerID:       "C-2",
		StockTicker:    "AAPL",
		Amount:         10,
		StrikeCurrency: "USD",
		StrikeAmount:   decimal.NewFromInt(150),
		SettlementDate: time.Now().Add(24 * time.Hour),
		Status:         store.ContractStatusActive,
		LocalPartyType: store.ContractPartyBuyer,
	}
}

func newContractsSvc(cr ContractReader, ex ExerciseCoordinator) *OtcOutboundService {
	svc := newOutboundSvc(newFakeOutboundNegStore(), &fakeOtcOutboundClient{})
	svc.SetContractDeps(cr, ex)
	return svc
}

func TestListContracts_BuyerScoped(t *testing.T) {
	cr := newFakeContractReader()
	cr.buyerList = []*store.Contract{activeBuyerContract("otc-1"), activeBuyerContract("otc-2")}
	svc := newContractsSvc(cr, &fakeExerciseCoordinator{})

	views, err := svc.ListContracts(context.Background(), 7, false)
	if err != nil {
		t.Fatalf("ListContracts: %v", err)
	}
	if len(views) != 2 {
		t.Fatalf("expected 2 buyer contracts, got %d", len(views))
	}
	if views[0].LocalPartyType != store.ContractPartyBuyer || views[0].StrikeAmount.String() != "150" {
		t.Errorf("unexpected view: %+v", views[0])
	}
}

func TestListContracts_NoDepsWired_EmptySlice(t *testing.T) {
	svc := newOutboundSvc(newFakeOutboundNegStore(), &fakeOtcOutboundClient{})
	views, err := svc.ListContracts(context.Background(), 7, false)
	if err != nil || len(views) != 0 {
		t.Errorf("expected empty, got views=%v err=%v", views, err)
	}
}

func TestExerciseContract_HappyPath_DelegatesToCoordinator(t *testing.T) {
	cr := newFakeContractReader()
	c := activeBuyerContract("otc-1")
	cr.byID["otc-1"] = c
	ex := &fakeExerciseCoordinator{}
	svc := newContractsSvc(cr, ex)

	if err := svc.ExerciseContract(context.Background(), 7, "otc-1", "111000000000000007"); err != nil {
		t.Fatalf("ExerciseContract: %v", err)
	}
	if !ex.called {
		t.Fatal("expected coordinator.ExerciseOutbound to be called")
	}
	if ex.gotBuyer != 7 || ex.gotAccount != "111000000000000007" {
		t.Errorf("buyer/account wrong: buyer=%d account=%q", ex.gotBuyer, ex.gotAccount)
	}
}

func TestExerciseContract_NotFound(t *testing.T) {
	svc := newContractsSvc(newFakeContractReader(), &fakeExerciseCoordinator{})
	err := svc.ExerciseContract(context.Background(), 7, "ghost", "")
	if !errors.Is(err, ErrNegotiationNotFound) {
		t.Errorf("expected ErrNegotiationNotFound, got %v", err)
	}
}

func TestExerciseContract_NotBuyerSide_Rejected(t *testing.T) {
	cr := newFakeContractReader()
	c := activeBuyerContract("otc-1")
	c.LocalPartyType = store.ContractPartySeller // we host the option → not a buyer-side holding
	cr.byID["otc-1"] = c
	ex := &fakeExerciseCoordinator{}
	svc := newContractsSvc(cr, ex)

	err := svc.ExerciseContract(context.Background(), 7, "otc-1", "")
	if !errors.Is(err, ErrNegotiationInvalid) {
		t.Errorf("expected ErrNegotiationInvalid for non-buyer contract, got %v", err)
	}
	if ex.called {
		t.Error("coordinator must NOT be called for a non-buyer contract")
	}
}

func TestExerciseContract_NotActive_Rejected(t *testing.T) {
	cr := newFakeContractReader()
	c := activeBuyerContract("otc-1")
	c.Status = store.ContractStatusExercised
	cr.byID["otc-1"] = c
	ex := &fakeExerciseCoordinator{}
	svc := newContractsSvc(cr, ex)

	err := svc.ExerciseContract(context.Background(), 7, "otc-1", "")
	if !errors.Is(err, ErrNegotiationClosed) {
		t.Errorf("expected ErrNegotiationClosed for non-ACTIVE contract, got %v", err)
	}
	if ex.called {
		t.Error("coordinator must NOT be called for a non-ACTIVE contract")
	}
}

func TestExerciseContract_PastSettlement_Rejected(t *testing.T) {
	cr := newFakeContractReader()
	c := activeBuyerContract("otc-1")
	c.SettlementDate = time.Now().Add(-1 * time.Hour) // expired
	cr.byID["otc-1"] = c
	ex := &fakeExerciseCoordinator{}
	svc := newContractsSvc(cr, ex)

	err := svc.ExerciseContract(context.Background(), 7, "otc-1", "")
	if !errors.Is(err, ErrNegotiationClosed) {
		t.Errorf("expected ErrNegotiationClosed for past settlement, got %v", err)
	}
	if ex.called {
		t.Error("coordinator must NOT be called past settlement")
	}
}
