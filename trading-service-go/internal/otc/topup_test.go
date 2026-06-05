package otc

import (
	"context"
	"errors"
	"testing"
	"time"

	"banka1/trading-service-go/internal/clients"
	"banka1/trading-service-go/internal/portfolio"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// ============================ notify.go ContractExpired ====================

func TestRabbitNotifier_ContractExpired(t *testing.T) {
	pub := &recordingPublisher{}
	email := "a@b.com"
	cust := &clients.Customer{ID: 1, Email: &email}
	n := NewRabbitNotifier(pub, customerClientReturning(cust, 200), discard())
	c := &OptionContract{ID: 1, OfferID: 2, StockTicker: "AAPL", BuyerID: 10, SellerID: 20, Amount: 3, PricePerStock: dec("100")}
	n.ContractExpired(context.Background(), c)
	// fires for both buyer and seller
	if len(pub.keys) != 2 {
		t.Errorf("expected 2 publishes (buyer+seller), got %d: %v", len(pub.keys), pub.keys)
	}
	for _, k := range pub.keys {
		if k != routingOtcExpired {
			t.Errorf("key = %q want %q", k, routingOtcExpired)
		}
	}
}

func TestNoopNotifier_AllMethodsConcrete(t *testing.T) {
	// Invoke directly on the concrete value (not the interface) so the no-op method
	// bodies register under -covermode=set.
	n := NoopNotifier{}
	ctx := context.Background()
	o := sampleOffer()
	c := &OptionContract{PricePerStock: dec("0")}
	n.CounterOffered(ctx, o, 10)
	n.Accepted(ctx, o, 10)
	n.Canceled(ctx, o, 10, "X")
	n.ExpiryReminder(ctx, c, 3)
	n.ContractExpired(ctx, c)
}

// ============================ service.go error branches ====================

func TestCreateOffer_HistoryError(t *testing.T) {
	repo := &stubRepo{historyErr: errors.New("boom")}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if _, err := svc.CreateOffer(context.Background(), 10, CreateOfferInput{PricePerStock: dec("0"), Premium: dec("0")}, nil); err == nil {
		t.Fatal("history insert error should propagate")
	}
}

func TestCounterOffer_UpdateError(t *testing.T) {
	repo := &stubRepo{offer: &OtcOffer{ID: 1, BuyerID: 10, SellerID: 20, Status: OfferPendingBuyer,
		PricePerStock: dec("0"), Premium: dec("0")}, updateOfferErr: errors.New("db")}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if _, err := svc.CounterOffer(context.Background(), 1, 10, CounterOfferInput{PricePerStock: dec("0"), Premium: dec("0")}, nil); err == nil {
		t.Fatal("UpdateOffer error should propagate")
	}
}

func TestCounterOffer_HistoryError(t *testing.T) {
	repo := &stubRepo{offer: &OtcOffer{ID: 1, BuyerID: 10, SellerID: 20, Status: OfferPendingBuyer,
		PricePerStock: dec("0"), Premium: dec("0")}, historyErr: errors.New("db")}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if _, err := svc.CounterOffer(context.Background(), 1, 10, CounterOfferInput{PricePerStock: dec("0"), Premium: dec("0")}, nil); err == nil {
		t.Fatal("history error should propagate")
	}
}

func TestAccept_SellerOwnedError(t *testing.T) {
	// FindByUserID error inside resolveSellerOwnedQuantity propagates
	repo := &stubRepo{offer: &OtcOffer{ID: 1, BuyerID: 10, SellerID: 20, Amount: 5, Status: OfferPendingSeller,
		StockTicker: "AAPL", PricePerStock: dec("0"), Premium: dec("0")}}
	pf := &stubPortfolio{byUserErr: errors.New("db")}
	svc := newSvc(repo, pf, nil, nil, nil, nil, nil)
	if _, err := svc.Accept(context.Background(), 1, 20, nil); err == nil {
		t.Fatal("resolveSellerOwnedQuantity error should propagate")
	}
}

func TestAccept_SumActiveError(t *testing.T) {
	repo := &stubRepo{
		offer:        &OtcOffer{ID: 1, BuyerID: 10, SellerID: 20, Amount: 5, Status: OfferPendingSeller, StockTicker: "AAPL", PricePerStock: dec("0"), Premium: dec("0")},
		sumActiveErr: errors.New("db"),
	}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if _, err := svc.Accept(context.Background(), 1, 20, nil); err == nil {
		t.Fatal("SumActive error should propagate")
	}
}

func TestAccept_UpdateOfferError(t *testing.T) {
	repo := &stubRepo{
		offer:          &OtcOffer{ID: 1, BuyerID: 10, SellerID: 20, Amount: 5, Status: OfferPendingSeller, StockTicker: "AAPL", PricePerStock: dec("0"), Premium: dec("0")},
		updateOfferErr: errors.New("db"),
	}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if _, err := svc.Accept(context.Background(), 1, 20, nil); err == nil {
		t.Fatal("UpdateOffer error should propagate")
	}
}

func TestAccept_InsertContractError(t *testing.T) {
	repo := &stubRepo{
		offer:             &OtcOffer{ID: 1, BuyerID: 10, SellerID: 20, Amount: 5, Status: OfferPendingSeller, StockTicker: "AAPL", PricePerStock: dec("0"), Premium: dec("0")},
		insertContractErr: errors.New("db"),
	}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if _, err := svc.Accept(context.Background(), 1, 20, nil); err == nil {
		t.Fatal("InsertOptionContract error should propagate")
	}
}

func TestAccept_ReserveError(t *testing.T) {
	ticker := "AAPL"
	repo := &stubRepo{
		offer: &OtcOffer{ID: 1, BuyerID: 10, SellerID: 20, Amount: 5, Status: OfferPendingSeller, StockTicker: ticker, PricePerStock: dec("0"), Premium: dec("0")},
	}
	pf := &stubPortfolio{
		byUser:            []portfolio.Portfolio{{ID: 9, UserID: 20, ListingID: 1, Quantity: 100, AveragePurchasePrice: dec("0")}},
		updateReservedErr: errors.New("db"),
	}
	mk := &stubMarket{listings: map[int64]*clients.StockListing{1: {ID: 1, Ticker: &ticker}}}
	svc := newSvc(repo, pf, mk, nil, nil, nil, nil)
	if _, err := svc.Accept(context.Background(), 1, 20, nil); err == nil {
		t.Fatal("reserveForContract error should propagate")
	}
}

func TestAccept_HistoryError(t *testing.T) {
	ticker := "AAPL"
	repo := &stubRepo{
		offer:      &OtcOffer{ID: 1, BuyerID: 10, SellerID: 20, Amount: 5, Status: OfferPendingSeller, StockTicker: ticker, PricePerStock: dec("0"), Premium: dec("0")},
		historyErr: errors.New("db"),
	}
	pf := &stubPortfolio{byUser: []portfolio.Portfolio{{ID: 9, UserID: 20, ListingID: 1, Quantity: 100, AveragePurchasePrice: dec("0")}}}
	mk := &stubMarket{listings: map[int64]*clients.StockListing{1: {ID: 1, Ticker: &ticker}}}
	svc := newSvc(repo, pf, mk, nil, nil, nil, nil)
	if _, err := svc.Accept(context.Background(), 1, 20, nil); err == nil {
		t.Fatal("history error should propagate")
	}
}

func TestAccept_BuyerAcceptsOnBuyerTurn(t *testing.T) {
	// PENDING_BUYER and seller (not buyer) tries to accept -> conflict (wrong turn)
	repo := &stubRepo{offer: &OtcOffer{ID: 1, BuyerID: 10, SellerID: 20, Status: OfferPendingBuyer, PricePerStock: dec("0"), Premium: dec("0")}}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if _, err := svc.Accept(context.Background(), 1, 20, nil); err == nil {
		t.Fatal("seller accepting on buyer turn should error")
	}
}

func TestAccept_PublishError(t *testing.T) {
	// publish failure after commit is logged, not returned
	ticker := "AAPL"
	repo := &stubRepo{
		offer: &OtcOffer{ID: 1, BuyerID: 10, SellerID: 20, Amount: 5, Status: OfferPendingSeller, StockTicker: ticker, PricePerStock: dec("0"), Premium: dec("0")},
	}
	pf := &stubPortfolio{byUser: []portfolio.Portfolio{{ID: 9, UserID: 20, ListingID: 1, Quantity: 100, AveragePurchasePrice: dec("0")}}}
	mk := &stubMarket{listings: map[int64]*clients.StockListing{1: {ID: 1, Ticker: &ticker}}}
	pub := &capturePublisher{err: errors.New("broker down")}
	if _, err := newSvc(repo, pf, mk, nil, nil, pub, nil).Accept(context.Background(), 1, 20, nil); err != nil {
		t.Fatalf("publish failure should not fail accept: %v", err)
	}
}

func TestReject_UpdateError(t *testing.T) {
	repo := &stubRepo{offer: &OtcOffer{ID: 1, BuyerID: 10, SellerID: 20, Status: OfferPendingSeller, PricePerStock: dec("0"), Premium: dec("0")}, updateOfferErr: errors.New("db")}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if _, err := svc.Reject(context.Background(), 1, 10, nil); err == nil {
		t.Fatal("Reject UpdateOffer error should propagate")
	}
}

func TestWithdraw_UpdateError(t *testing.T) {
	repo := &stubRepo{offer: &OtcOffer{ID: 1, BuyerID: 10, SellerID: 20, Status: OfferPendingSeller, PricePerStock: dec("0"), Premium: dec("0")}, updateOfferErr: errors.New("db")}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if _, err := svc.Withdraw(context.Background(), 1, 10, nil); err == nil {
		t.Fatal("Withdraw UpdateOffer error should propagate")
	}
}

func TestWithdraw_SellerWrongStatus(t *testing.T) {
	repo := &stubRepo{offer: &OtcOffer{ID: 1, BuyerID: 10, SellerID: 20, Status: OfferPendingSeller, PricePerStock: dec("0"), Premium: dec("0")}}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if _, err := svc.Withdraw(context.Background(), 1, 20, nil); err == nil {
		t.Fatal("seller can only withdraw while PENDING_BUYER")
	}
}

func TestExerciseContract_SetExercisedError(t *testing.T) {
	repo := &stubRepo{contract: &OptionContract{ID: 5, BuyerID: 10, Status: ContractActive,
		PricePerStock: dec("0"), SettlementDate: time.Now().AddDate(0, 0, 10)}, setExercisedErr: errors.New("db")}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if _, err := svc.ExerciseContract(context.Background(), 5, 10, nil); err == nil {
		t.Fatal("SetOptionContractExercisedAt error should propagate")
	}
}

func TestExerciseContract_FindError(t *testing.T) {
	repo := &stubRepo{contractErr: errors.New("db")}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if _, err := svc.ExerciseContract(context.Background(), 5, 10, nil); err == nil {
		t.Fatal("Find error should propagate")
	}
}

func TestExerciseContract_PublishError(t *testing.T) {
	repo := &stubRepo{contract: &OptionContract{ID: 5, BuyerID: 10, SellerID: 20, Status: ContractActive,
		StockTicker: "AAPL", Amount: 3, PricePerStock: dec("0"), SettlementDate: time.Now().AddDate(0, 0, 10)}}
	pub := &capturePublisher{err: errors.New("broker down")}
	if _, err := newSvc(repo, &stubPortfolio{}, nil, nil, nil, pub, nil).ExerciseContract(context.Background(), 5, 10, nil); err != nil {
		t.Fatalf("publish failure should not fail exercise: %v", err)
	}
}

func TestExerciseContract_WithFaultInjection(t *testing.T) {
	repo := &stubRepo{contract: &OptionContract{ID: 5, BuyerID: 10, SellerID: 20, Status: ContractActive,
		StockTicker: "AAPL", Amount: 3, PricePerStock: dec("0"), SettlementDate: time.Now().AddDate(0, 0, 10)}}
	pub := &capturePublisher{}
	fi := &FaultInjection{ForceFailStep: "RESERVE", InjectDelayMs: 100}
	if _, err := newSvc(repo, &stubPortfolio{}, nil, nil, nil, pub, nil).ExerciseContract(context.Background(), 5, 10, fi); err != nil {
		t.Fatal(err)
	}
	if len(pub.exer) != 1 || pub.exer[0].FaultInjection == nil || pub.exer[0].FaultInjection.ForceFailStep != "RESERVE" {
		t.Errorf("fault injection not carried into event: %+v", pub.exer)
	}
}

func TestCompletePremiumTransfer_FindError(t *testing.T) {
	repo := &stubRepo{contractErr: errors.New("db")}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if err := svc.CompletePremiumTransfer(context.Background(), 5); err == nil {
		t.Fatal("Find error should propagate")
	}
}

func TestCompletePremiumTransfer_UpdateError(t *testing.T) {
	repo := &stubRepo{contract: &OptionContract{ID: 5, Status: ContractPendingPremium, PricePerStock: dec("0")}, updateStatusErr: errors.New("db")}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if err := svc.CompletePremiumTransfer(context.Background(), 5); err == nil {
		t.Fatal("UpdateOptionContractStatus error should propagate")
	}
}

func TestFailPremiumTransfer_FindError(t *testing.T) {
	repo := &stubRepo{contractErr: errors.New("db")}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if err := svc.FailPremiumTransfer(context.Background(), 5, "x"); err == nil {
		t.Fatal("Find error should propagate")
	}
}

func TestFailPremiumTransfer_NotFound_NoOp(t *testing.T) {
	repo := &stubRepo{contract: nil}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if err := svc.FailPremiumTransfer(context.Background(), 5, "x"); err != nil {
		t.Fatal("not found should be a no-op")
	}
}

func TestFailPremiumTransfer_UpdateError(t *testing.T) {
	repo := &stubRepo{contract: &OptionContract{ID: 5, SellerID: 20, StockTicker: "AAPL", Amount: 3, Status: ContractPendingPremium, PricePerStock: dec("0")}, updateStatusErr: errors.New("db")}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if err := svc.FailPremiumTransfer(context.Background(), 5, "x"); err == nil {
		t.Fatal("UpdateOptionContractStatus error should propagate")
	}
}

func TestFailPremiumTransfer_ReleaseError(t *testing.T) {
	ticker := "AAPL"
	repo := &stubRepo{contract: &OptionContract{ID: 5, SellerID: 20, StockTicker: ticker, Amount: 3, Status: ContractPendingPremium, PricePerStock: dec("0")}}
	pf := &stubPortfolio{
		byUser:            []portfolio.Portfolio{{ID: 9, UserID: 20, ListingID: 1, Quantity: 10, ReservedQuantity: 3, AveragePurchasePrice: dec("0")}},
		updateReservedErr: errors.New("db"),
	}
	mk := &stubMarket{listings: map[int64]*clients.StockListing{1: {ID: 1, Ticker: &ticker}}}
	svc := newSvc(repo, pf, mk, nil, nil, nil, nil)
	if err := svc.FailPremiumTransfer(context.Background(), 5, "x"); err == nil {
		t.Fatal("releaseForContract error should propagate")
	}
}

func TestCompleteExercise_FindError(t *testing.T) {
	repo := &stubRepo{contractErr: errors.New("db")}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if err := svc.CompleteExercise(context.Background(), 5); err == nil {
		t.Fatal("Find error should propagate")
	}
}

func TestCompleteExercise_NotFound_NoOp(t *testing.T) {
	repo := &stubRepo{contract: nil}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if err := svc.CompleteExercise(context.Background(), 5); err != nil {
		t.Fatal("not found should be no-op")
	}
}

func TestCompleteExercise_UpdateError(t *testing.T) {
	repo := &stubRepo{contract: &OptionContract{ID: 5, Status: ContractActive, PricePerStock: dec("0")}, updateStatusErr: errors.New("db")}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if err := svc.CompleteExercise(context.Background(), 5); err == nil {
		t.Fatal("UpdateOptionContractStatus error should propagate")
	}
}

func TestRevertExercise_FindError(t *testing.T) {
	repo := &stubRepo{contractErr: errors.New("db")}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if err := svc.RevertExercise(context.Background(), 5); err == nil {
		t.Fatal("Find error should propagate")
	}
}

// RevertExercise success path needs tx.Exec; route it through a real querier-tx
// fake so the UPDATE exercised_at = NULL line is exercised.
func TestRevertExercise_Success(t *testing.T) {
	repo := &stubRepo{contract: &OptionContract{ID: 5, Status: ContractActive, PricePerStock: dec("0")}}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	q := &fakeQuerier{execTag: tag("UPDATE 1")}
	svc.runInTx = func(ctx context.Context, fn func(pgx.Tx) error) error {
		return fn(txFromQuerier{q: q})
	}
	if err := svc.RevertExercise(context.Background(), 5); err != nil {
		t.Fatal(err)
	}
	if q.lastSQL == "" {
		t.Error("expected an UPDATE exec for exercised_at = NULL")
	}
}

func TestRevertExercise_ExecError(t *testing.T) {
	repo := &stubRepo{contract: &OptionContract{ID: 5, Status: ContractActive, PricePerStock: dec("0")}}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	q := &fakeQuerier{execErr: errors.New("db")}
	svc.runInTx = func(ctx context.Context, fn func(pgx.Tx) error) error {
		return fn(txFromQuerier{q: q})
	}
	if err := svc.RevertExercise(context.Background(), 5); err == nil {
		t.Fatal("exec error should propagate")
	}
}

func TestGetMyPositions_Error(t *testing.T) {
	pf := &stubPortfolio{byUserErr: errors.New("db")}
	svc := newSvc(&stubRepo{}, pf, nil, nil, nil, nil, nil)
	if _, err := svc.GetMyPositions(context.Background(), 10); err == nil {
		t.Fatal("FindByUserID error should propagate")
	}
}

func TestAddPosition_FindError(t *testing.T) {
	pf := &stubPortfolio{byUserLstErr: errors.New("db")}
	svc := newSvc(&stubRepo{}, pf, nil, nil, nil, nil, nil)
	if _, err := svc.AddPosition(context.Background(), 10, 1, 5); err == nil {
		t.Fatal("FindByUserIDAndListingID error should propagate")
	}
}

func TestAddPosition_UpdateError(t *testing.T) {
	pf := &stubPortfolio{
		byUserList:      &portfolio.Portfolio{ID: 9, UserID: 10, ListingType: "STOCK", Quantity: 10, ReservedQuantity: 2, AveragePurchasePrice: dec("0")},
		updatePublicErr: errors.New("db"),
	}
	svc := newSvc(&stubRepo{}, pf, nil, nil, nil, nil, nil)
	if _, err := svc.AddPosition(context.Background(), 10, 1, 5); err == nil {
		t.Fatal("UpdatePublic error should propagate")
	}
}

func TestUpdatePosition_FindError(t *testing.T) {
	pf := &stubPortfolio{byIDErr: errors.New("db")}
	svc := newSvc(&stubRepo{}, pf, nil, nil, nil, nil, nil)
	if _, err := svc.UpdatePosition(context.Background(), 10, 9, 1); err == nil {
		t.Fatal("FindByID error should propagate")
	}
}

func TestUpdatePosition_NotFound(t *testing.T) {
	pf := &stubPortfolio{byID: nil}
	svc := newSvc(&stubRepo{}, pf, nil, nil, nil, nil, nil)
	if _, err := svc.UpdatePosition(context.Background(), 10, 9, 1); err == nil {
		t.Fatal("missing position should error")
	}
}

func TestUpdatePosition_TooMuch(t *testing.T) {
	pf := &stubPortfolio{byID: &portfolio.Portfolio{ID: 9, UserID: 10, Quantity: 10, ReservedQuantity: 3, AveragePurchasePrice: dec("0")}}
	svc := newSvc(&stubRepo{}, pf, nil, nil, nil, nil, nil)
	if _, err := svc.UpdatePosition(context.Background(), 10, 9, 50); err == nil {
		t.Fatal("exposing more than max allowed should error")
	}
}

func TestUpdatePosition_UpdateError(t *testing.T) {
	pf := &stubPortfolio{
		byID:            &portfolio.Portfolio{ID: 9, UserID: 10, Quantity: 20, ReservedQuantity: 3, IsPublic: true, AveragePurchasePrice: dec("0")},
		updatePublicErr: errors.New("db"),
	}
	svc := newSvc(&stubRepo{}, pf, nil, nil, nil, nil, nil)
	if _, err := svc.UpdatePosition(context.Background(), 10, 9, 10); err == nil {
		t.Fatal("UpdatePublic error should propagate")
	}
}

func TestRemovePosition_FindError(t *testing.T) {
	pf := &stubPortfolio{byIDErr: errors.New("db")}
	svc := newSvc(&stubRepo{}, pf, nil, nil, nil, nil, nil)
	if err := svc.RemovePosition(context.Background(), 10, 9); err == nil {
		t.Fatal("FindByID error should propagate")
	}
}

func TestGetPublicStocks_FindError(t *testing.T) {
	pf := &stubPortfolio{publicErr: errors.New("db")}
	svc := newSvc(&stubRepo{}, pf, &stubMarket{}, &stubCustomer{}, nil, nil, nil)
	if _, err := svc.GetPublicStocks(context.Background(), 10, false); err == nil {
		t.Fatal("FindAllPublicStocks error should propagate")
	}
}

func TestGetPublicStocks_SkipsZeroQtyAndNoTicker(t *testing.T) {
	ticker := "AAPL"
	pf := &stubPortfolio{publicStocks: []portfolio.Portfolio{
		{UserID: 20, ListingID: 1, PublicQuantity: 0, AveragePurchasePrice: dec("0")},  // qty 0 -> skipped
		{UserID: 21, ListingID: 99, PublicQuantity: 5, AveragePurchasePrice: dec("0")}, // no listing -> ticker "" -> skipped
		{UserID: 22, ListingID: 1, PublicQuantity: 7, AveragePurchasePrice: dec("0")},  // kept
	}}
	mk := &stubMarket{listings: map[int64]*clients.StockListing{1: {ID: 1, Ticker: &ticker}}}
	cu := &stubCustomer{customers: map[int64]*clients.Customer{22: {ID: 22, FirstName: ptr("A"), LastName: ptr("B")}}}
	svc := newSvc(&stubRepo{}, pf, mk, cu, nil, nil, nil)
	out, err := svc.GetPublicStocks(context.Background(), 0, false)
	if err != nil {
		t.Fatal(err)
	}
	if len(out) != 1 || out[0].Ticker != "AAPL" || len(out[0].Sellers) != 1 || out[0].Sellers[0].SellerID != 22 {
		t.Errorf("expected only the kept seller: %+v", out)
	}
}

func TestGetPublicStocks_MultipleSellersSameTicker(t *testing.T) {
	ticker := "AAPL"
	pf := &stubPortfolio{publicStocks: []portfolio.Portfolio{
		{UserID: 20, ListingID: 1, PublicQuantity: 5, AveragePurchasePrice: dec("0")},
		{UserID: 21, ListingID: 1, PublicQuantity: 7, AveragePurchasePrice: dec("0")},
	}}
	mk := &stubMarket{listings: map[int64]*clients.StockListing{1: {ID: 1, Ticker: &ticker}}}
	svc := newSvc(&stubRepo{}, pf, mk, &stubCustomer{}, nil, nil, nil)
	out, err := svc.GetPublicStocks(context.Background(), 0, false)
	if err != nil {
		t.Fatal(err)
	}
	if len(out) != 1 || len(out[0].Sellers) != 2 {
		t.Errorf("expected 1 ticker with 2 sellers: %+v", out)
	}
}

func TestHistoryForUser_DateBounds(t *testing.T) {
	repo := &stubRepo{historyRows: []NegotiationHistory{{ID: 1, OfferID: 2, EventType: EventCreated}}}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	// nil dates -> nil bounds branch; non-nil dates -> truncate branch
	if _, err := svc.HistoryForUser(context.Background(), 10, nil, nil, nil, nil); err != nil {
		t.Fatal(err)
	}
	from := time.Date(2026, 3, 15, 14, 0, 0, 0, time.UTC)
	to := time.Date(2026, 3, 20, 9, 0, 0, 0, time.UTC)
	if _, err := svc.HistoryForUser(context.Background(), 10, ptr(OfferAccepted), ptr(int64(20)), &from, &to); err != nil {
		t.Fatal(err)
	}
}

func TestHistoryForUser_Error(t *testing.T) {
	repo := &stubRepo{historyFErr: errors.New("db")}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if _, err := svc.HistoryForUser(context.Background(), 10, nil, nil, nil, nil); err == nil {
		t.Fatal("repo error should propagate")
	}
}

func TestResolveSellerOwnedQuantity_EmptyTicker(t *testing.T) {
	svc := newSvc(&stubRepo{}, &stubPortfolio{}, nil, nil, nil, nil, nil)
	n, err := svc.resolveSellerOwnedQuantity(context.Background(), nil, 20, "")
	if err != nil || n != 0 {
		t.Errorf("empty ticker should give 0, got %d %v", n, err)
	}
}

func TestResolveSellerOwnedQuantity_MarketSkipAndQuantityBranch(t *testing.T) {
	ticker := "AAPL"
	// position 1 -> market error skip; position 2 -> not public so use full quantity
	pf := &stubPortfolio{byUser: []portfolio.Portfolio{
		{ID: 1, UserID: 20, ListingID: 99, Quantity: 10, AveragePurchasePrice: dec("0")},
		{ID: 2, UserID: 20, ListingID: 1, Quantity: 8, IsPublic: false, AveragePurchasePrice: dec("0")},
	}}
	mk := &stubMarket{listings: map[int64]*clients.StockListing{1: {ID: 1, Ticker: &ticker}}}
	svc := newSvc(&stubRepo{}, pf, mk, nil, nil, nil, nil)
	n, err := svc.resolveSellerOwnedQuantity(context.Background(), nil, 20, "aapl") // case-insensitive
	if err != nil {
		t.Fatal(err)
	}
	if n != 8 {
		t.Errorf("non-public position should count full quantity 8, got %d", n)
	}
}

func TestResolveSellerOwnedQuantity_FindError(t *testing.T) {
	pf := &stubPortfolio{byUserErr: errors.New("db")}
	svc := newSvc(&stubRepo{}, pf, &stubMarket{}, nil, nil, nil, nil)
	if _, err := svc.resolveSellerOwnedQuantity(context.Background(), nil, 20, "AAPL"); err == nil {
		t.Fatal("FindByUserID error should propagate")
	}
}

func TestReserveForContract_NoPortfolio(t *testing.T) {
	// no matching position -> warn + nil (no error)
	svc := newSvc(&stubRepo{}, &stubPortfolio{byUser: nil}, &stubMarket{}, nil, nil, nil, nil)
	if err := svc.reserveForContract(context.Background(), nil, 20, "AAPL", 3); err != nil {
		t.Fatal(err)
	}
}

func TestReserveForContract_FindError(t *testing.T) {
	pf := &stubPortfolio{byUserErr: errors.New("db")}
	svc := newSvc(&stubRepo{}, pf, &stubMarket{}, nil, nil, nil, nil)
	if err := svc.reserveForContract(context.Background(), nil, 20, "AAPL", 3); err == nil {
		t.Fatal("findPortfolioByTicker error should propagate")
	}
}

func TestReleaseForContract_NoPortfolio(t *testing.T) {
	svc := newSvc(&stubRepo{}, &stubPortfolio{byUser: nil}, &stubMarket{}, nil, nil, nil, nil)
	if err := svc.releaseForContract(context.Background(), nil, 20, "AAPL", 3); err != nil {
		t.Fatal(err)
	}
}

func TestFindPortfolioByTicker_FindError(t *testing.T) {
	pf := &stubPortfolio{byUserErr: errors.New("db")}
	svc := newSvc(&stubRepo{}, pf, &stubMarket{}, nil, nil, nil, nil)
	if _, err := svc.findPortfolioByTicker(context.Background(), nil, 20, "AAPL"); err == nil {
		t.Fatal("FindByUserID error should propagate")
	}
}

func TestRequireOwnedPosition_FindError(t *testing.T) {
	pf := &stubPortfolio{byIDErr: errors.New("db")}
	svc := newSvc(&stubRepo{}, pf, nil, nil, nil, nil, nil)
	if _, err := svc.requireOwnedPosition(context.Background(), nil, 10, 9); err == nil {
		t.Fatal("FindByID error should propagate")
	}
}

func TestResolveClientName_CustomerError(t *testing.T) {
	cu := &stubCustomer{err: errors.New("down")}
	svc := newSvc(&stubRepo{}, &stubPortfolio{}, nil, cu, nil, nil, nil)
	if svc.resolveClientName(context.Background(), 10) != nil {
		t.Error("customer error should give nil name")
	}
}

// ============================ scheduler error continue paths ===============

func TestExpireOverdueContracts_TxErrorContinues(t *testing.T) {
	// UpdateOptionContractStatus error inside the per-contract tx is logged and skipped
	repo := &stubRepo{
		staleContracts:  []OptionContract{{ID: 1, SellerID: 20, StockTicker: "AAPL", Amount: 3, Status: ContractActive, PricePerStock: dec("0"), SettlementDate: time.Now().AddDate(0, 0, -2)}},
		updateStatusErr: errors.New("db"),
	}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	if err := svc.ExpireOverdueContracts(context.Background()); err != nil {
		t.Fatalf("per-contract failure should not fail the sweep: %v", err)
	}
	if len(repo.updatedStatuses) != 0 {
		t.Errorf("status update should have failed, got %v", repo.updatedStatuses)
	}
}

func TestSendExpiryReminders_MarkerInsertErrorContinues(t *testing.T) {
	spy := &spyNotifier{}
	repo := &stubRepo{
		reminderContracts: []OptionContract{{ID: 1, Status: ContractActive, PricePerStock: dec("0")}},
		reminderInsErr:    errors.New("db"),
	}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, spy)
	if err := svc.SendExpiryReminders(context.Background(), 3); err != nil {
		t.Fatalf("marker insert failure should be skipped, not fatal: %v", err)
	}
	if len(spy.reminders) != 0 {
		t.Errorf("reminder should not be sent on marker error, got %d", len(spy.reminders))
	}
}

// ============================ repository error/branch paths ================

func TestInsertOffer_Error(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{err: errors.New("db")}}
	r := NewRepository(nil)
	o := &OtcOffer{StockTicker: "AAPL", PricePerStock: dec("1"), Premium: dec("1")}
	if err := r.InsertOffer(context.Background(), q, o); err == nil {
		t.Fatal("insert error should propagate")
	}
}

func TestScanOffer_ScanError(t *testing.T) {
	if _, err := scanOffer(&fakeRow{err: errors.New("scan")}); err == nil {
		t.Fatal("scan error should propagate")
	}
}

func TestScanContract_ScanError(t *testing.T) {
	if _, err := scanContract(&fakeRow{err: errors.New("scan")}); err == nil {
		t.Fatal("scan error should propagate")
	}
}

func TestScanOffer_BadPremium(t *testing.T) {
	now := time.Now()
	// price ok, premium bad
	vals := []any{int64(1), "AAPL", int64(10), int64(20), 5, "100.00", "bad", now, OfferAccepted, (*string)(nil), now, now, int64(0)}
	if _, err := scanOffer(&fakeRow{vals: vals}); err == nil {
		t.Fatal("bad premium decimal should error")
	}
}

func TestScanOffers_ScanErrorMidIteration(t *testing.T) {
	// a row with bad decimal triggers scanOffer error inside scanOffers loop
	now := time.Now()
	bad := []any{int64(1), "AAPL", int64(10), int64(20), 5, "bad", "5.00", now, OfferAccepted, (*string)(nil), now, now, int64(0)}
	q := &fakeQuerier{rows: &fakeRows{data: [][]any{bad}}}
	r := &Repository{q: q}
	if _, err := r.FindActiveOffersForUser(context.Background(), 10); err == nil {
		t.Fatal("scanOffer error should propagate from scanOffers")
	}
}

func TestScanContracts_ScanErrorMidIteration(t *testing.T) {
	now := time.Now()
	bad := []any{int64(5), int64(1), "AAPL", int64(10), int64(20), 3, "bad", now, ContractActive, now, (*time.Time)(nil), int64(0)}
	q := &fakeQuerier{rows: &fakeRows{data: [][]any{bad}}}
	r := &Repository{q: q}
	if _, err := r.FindContractsByBuyerIDAndStatus(context.Background(), 10, ContractActive); err == nil {
		t.Fatal("scanContract error should propagate from scanContracts")
	}
}

func TestFindOfferByID_OtherError(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{err: errors.New("db")}}
	r := NewRepository(nil)
	if _, err := r.FindOfferByID(context.Background(), q, 1); err == nil || errors.Is(err, ErrNotFound) {
		t.Errorf("non-norows error should pass through, got %v", err)
	}
}

func TestFindOfferByIDForUpdate_Success(t *testing.T) {
	now := time.Now()
	vals := []any{int64(1), "AAPL", int64(10), int64(20), 5, "100.00", "5.00", now, OfferAccepted, (*string)(nil), now, now, int64(0)}
	q := &fakeQuerier{row: &fakeRow{vals: vals}}
	r := NewRepository(nil)
	o, err := r.FindOfferByIDForUpdate(context.Background(), q, 1)
	if err != nil || o.ID != 1 {
		t.Errorf("ForUpdate success: %v %+v", err, o)
	}
}

func TestFindOfferByIDForUpdate_OtherError(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{err: errors.New("db")}}
	r := NewRepository(nil)
	if _, err := r.FindOfferByIDForUpdate(context.Background(), q, 1); err == nil || errors.Is(err, ErrNotFound) {
		t.Errorf("non-norows error should pass through, got %v", err)
	}
}

func TestUpdateOffer_Error(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{err: errors.New("db")}}
	r := NewRepository(nil)
	o := &OtcOffer{ID: 1, PricePerStock: dec("1"), Premium: dec("1")}
	if err := r.UpdateOffer(context.Background(), q, o); err == nil {
		t.Fatal("update error should propagate")
	}
}

func TestInsertOptionContract_Error(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{err: errors.New("db")}}
	r := NewRepository(nil)
	c := &OptionContract{StockTicker: "AAPL", PricePerStock: dec("1")}
	if err := r.InsertOptionContract(context.Background(), q, c); err == nil {
		t.Fatal("insert error should propagate")
	}
}

func TestFindOptionContractByID_OtherError(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{err: errors.New("db")}}
	r := NewRepository(nil)
	if _, err := r.FindOptionContractByID(context.Background(), q, 5); err == nil || errors.Is(err, ErrNotFound) {
		t.Errorf("non-norows error should pass through, got %v", err)
	}
}

func TestFindOptionContractByIDForUpdate_Success(t *testing.T) {
	now := time.Now()
	vals := []any{int64(5), int64(1), "AAPL", int64(10), int64(20), 3, "100.00", now, ContractActive, now, (*time.Time)(nil), int64(0)}
	q := &fakeQuerier{row: &fakeRow{vals: vals}}
	r := NewRepository(nil)
	c, err := r.FindOptionContractByIDForUpdate(context.Background(), q, 5)
	if err != nil || c.ID != 5 {
		t.Errorf("ForUpdate success: %v %+v", err, c)
	}
}

func TestFindOptionContractByIDForUpdate_OtherError(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{err: errors.New("db")}}
	r := NewRepository(nil)
	if _, err := r.FindOptionContractByIDForUpdate(context.Background(), q, 5); err == nil || errors.Is(err, ErrNotFound) {
		t.Errorf("non-norows error should pass through, got %v", err)
	}
}

func TestSetOptionContractExercisedAt_ExecError(t *testing.T) {
	q := &fakeQuerier{execErr: errors.New("db")}
	r := NewRepository(nil)
	if err := r.SetOptionContractExercisedAt(context.Background(), q, 5, time.Now()); err == nil {
		t.Fatal("exec error should propagate")
	}
}

func TestSumActiveBySellerAndTicker_Error(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{err: errors.New("db")}}
	r := NewRepository(nil)
	if _, err := r.SumActiveBySellerAndTicker(context.Background(), q, 20, "AAPL"); err == nil {
		t.Fatal("scan error should propagate")
	}
}

func TestScanOffers_RowError(t *testing.T) {
	// rows.Err() returns an error after iteration
	q := &fakeQuerier{rows: &fakeRows{data: nil, err: errors.New("rows")}}
	r := &Repository{q: q}
	if _, err := r.FindActiveOffersForUser(context.Background(), 10); err == nil {
		t.Fatal("rows.Err should propagate")
	}
}

func TestScanContracts_RowError(t *testing.T) {
	q := &fakeQuerier{rows: &fakeRows{data: nil, err: errors.New("rows")}}
	r := &Repository{q: q}
	if _, err := r.FindContractsByBuyerIDAndStatus(context.Background(), 10, ContractActive); err == nil {
		t.Fatal("rows.Err should propagate")
	}
}

func TestFindContractsBySellerIDAndStatus_QueryError(t *testing.T) {
	q := &fakeQuerier{queryErr: errors.New("db")}
	r := &Repository{q: q}
	if _, err := r.FindContractsBySellerIDAndStatus(context.Background(), 20, ContractActive); err == nil {
		t.Fatal("query error should propagate")
	}
}

func TestFindContractsByStatusAndSettlementDateBefore_QueryError(t *testing.T) {
	q := &fakeQuerier{queryErr: errors.New("db")}
	r := &Repository{q: q}
	if _, err := r.FindContractsByStatusAndSettlementDateBefore(context.Background(), ContractActive, time.Now()); err == nil {
		t.Fatal("query error should propagate")
	}
}

func TestFindContractsByStatusAndSettlementDate_QueryError(t *testing.T) {
	q := &fakeQuerier{queryErr: errors.New("db")}
	r := &Repository{q: q}
	if _, err := r.FindContractsByStatusAndSettlementDate(context.Background(), ContractActive, time.Now()); err == nil {
		t.Fatal("query error should propagate")
	}
}

func TestQuerier_PrefersInjectedQ(t *testing.T) {
	// querier() returns the injected q when non-nil (the tests' path); the nil-q
	// fallback to r.db (the pool) is the production path.
	q := &fakeQuerier{}
	r := &Repository{db: nil, q: q}
	if got := r.querier(); got != Querier(q) {
		t.Error("querier should return the injected q")
	}
	// nil q -> returns the db field (a typed-nil *pgxpool.Pool wrapped in Querier).
	r2 := &Repository{db: nil, q: nil}
	_ = r2.querier()
}

// ============================ repository_history error paths ===============

func TestScanHistory_BadOldDecimal(t *testing.T) {
	now := time.Now()
	bad := "not-a-number"
	row := []any{int64(1), int64(2), int64(10), int64(20), (*int64)(nil), (*string)(nil),
		EventCreated, "AAPL", (*int)(nil), (*int)(nil), &bad, (*string)(nil),
		(*string)(nil), (*string)(nil), (*time.Time)(nil), (*time.Time)(nil), (*string)(nil), (*string)(nil), now}
	if _, err := scanHistory(&fakeRow{vals: row}); err == nil {
		t.Fatal("bad old_price decimal should error")
	}
}

func TestScanHistory_ScanError(t *testing.T) {
	if _, err := scanHistory(&fakeRow{err: errors.New("scan")}); err == nil {
		t.Fatal("scan error should propagate")
	}
}

func TestScanHistory_BadNewPPSPremiumBranches(t *testing.T) {
	now := time.Now()
	bad := "x"
	good := "1.00"
	mk := func(oldPPS, newPPS, oldPrem, newPrem *string) []any {
		return []any{int64(1), int64(2), int64(10), int64(20), (*int64)(nil), (*string)(nil),
			EventCreated, "AAPL", (*int)(nil), (*int)(nil), oldPPS, newPPS,
			oldPrem, newPrem, (*time.Time)(nil), (*time.Time)(nil), (*string)(nil), (*string)(nil), now}
	}
	// each of the three later decPtr conversions failing
	for i, vals := range [][]any{
		mk(&good, &bad, nil, nil),     // new_pps bad
		mk(&good, &good, &bad, nil),   // old_prem bad
		mk(&good, &good, &good, &bad), // new_prem bad
	} {
		if _, err := scanHistory(&fakeRow{vals: vals}); err == nil {
			t.Errorf("case %d: bad decimal should error", i)
		}
	}
}

func TestRequireOfferForUpdate_OtherError(t *testing.T) {
	repo := &stubRepo{offerErr: errors.New("db")}
	svc := newSvc(repo, &stubPortfolio{}, nil, nil, nil, nil, nil)
	// CounterOffer routes through requireOfferForUpdate; a non-NotFound error passes through
	if _, err := svc.CounterOffer(context.Background(), 1, 10, CounterOfferInput{PricePerStock: dec("0"), Premium: dec("0")}, nil); err == nil {
		t.Fatal("non-notfound find error should propagate")
	}
}

func TestInsertHistory_Error(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{err: errors.New("db")}}
	r := NewRepository(nil)
	h := &NegotiationHistory{OfferID: 1, EventType: EventCreated}
	if err := r.InsertHistory(context.Background(), q, h); err == nil {
		t.Fatal("insert error should propagate")
	}
}

func TestHistoryForUser_Repo_ScanError(t *testing.T) {
	now := time.Now()
	bad := "bad"
	row := []any{int64(1), int64(2), int64(10), int64(20), (*int64)(nil), (*string)(nil),
		EventCreated, "AAPL", (*int)(nil), (*int)(nil), &bad, (*string)(nil),
		(*string)(nil), (*string)(nil), (*time.Time)(nil), (*time.Time)(nil), (*string)(nil), (*string)(nil), now}
	q := &fakeQuerier{rows: &fakeRows{data: [][]any{row}}}
	r := &Repository{q: q}
	if _, err := r.HistoryForUser(context.Background(), 10, nil, nil, nil, nil); err == nil {
		t.Fatal("scan error should propagate")
	}
}

func TestHistoryForUser_Repo_RowsErr(t *testing.T) {
	q := &fakeQuerier{rows: &fakeRows{data: nil, err: errors.New("rows")}}
	r := &Repository{q: q}
	if _, err := r.HistoryForUser(context.Background(), 10, nil, nil, nil, nil); err == nil {
		t.Fatal("rows.Err should propagate")
	}
}

// ============================ reservations branch paths ====================

func TestReservation_Release_PortfolioNil(t *testing.T) {
	// reservation HELD but seller portfolio missing -> skip the reserved decrement
	pf := &stubResPortfolio{forUp: nil}
	q := &seqQuerier{
		rows:    []*fakeRow{{vals: []any{int64(20), int64(1), 5, ReservationHeld}}},
		execTag: tag("UPDATE 1"),
	}
	svc := newResSvc(pf, nil, q)
	resp, err := svc.Release(context.Background(), "res-id", "corr")
	if err != nil {
		t.Fatal(err)
	}
	if resp.Status != ReservationReleased {
		t.Errorf("status = %s want RELEASED", resp.Status)
	}
}

func TestReservation_Release_QueryError(t *testing.T) {
	q := &seqQuerier{rows: []*fakeRow{{err: errors.New("db")}}}
	svc := newResSvc(&stubResPortfolio{}, nil, q)
	if _, err := svc.Release(context.Background(), "res-id", "corr"); err == nil {
		t.Fatal("scan error should propagate")
	}
}

func TestReservation_TransferOwnership_SellerNil(t *testing.T) {
	pf := &stubResPortfolio{forUp: nil} // seller portfolio missing
	q := &seqQuerier{rows: []*fakeRow{{vals: []any{int64(20), int64(1), "AAPL", 5, ReservationHeld}}}}
	svc := newResSvc(pf, nil, q)
	if _, err := svc.TransferOwnership(context.Background(), "res-id", 10, "corr"); err == nil {
		t.Fatal("missing seller portfolio should error")
	}
}

func TestReservation_TransferOwnership_QueryError(t *testing.T) {
	q := &seqQuerier{rows: []*fakeRow{{err: errors.New("db")}}}
	svc := newResSvc(&stubResPortfolio{}, nil, q)
	if _, err := svc.TransferOwnership(context.Background(), "res-id", 10, "corr"); err == nil {
		t.Fatal("scan error should propagate")
	}
}

func TestReservation_ReverseOwnership_BuyerAndSellerNil(t *testing.T) {
	// both portfolios missing -> only the transfer row is marked REVERSED
	pf := &stubResPortfolio{forUp: nil, forUpB: nil}
	q := &seqQuerier{
		rows:    []*fakeRow{{vals: []any{int64(20), int64(10), int64(1), 5, TransferCompleted}}},
		execTag: tag("UPDATE 1"),
	}
	svc := newResSvc(pf, nil, q)
	if err := svc.ReverseOwnership(context.Background(), "transfer-id", "corr"); err != nil {
		t.Fatal(err)
	}
}

func TestReservation_ReverseOwnership_QueryError(t *testing.T) {
	q := &seqQuerier{rows: []*fakeRow{{err: errors.New("db")}}}
	svc := newResSvc(&stubResPortfolio{}, nil, q)
	if err := svc.ReverseOwnership(context.Background(), "transfer-id", "corr"); err == nil {
		t.Fatal("scan error should propagate")
	}
}

func TestReservation_Reserve_ForUpdateNil(t *testing.T) {
	// resolveListingByTicker finds a listing, but FindByUserIDAndListingIDForUpdate
	// returns nil -> conflict.
	ticker := "AAPL"
	pf := &stubResPortfolio{
		byUser: []portfolio.Portfolio{{ID: 9, UserID: 20, ListingID: 1, Quantity: 10, AveragePurchasePrice: dec("0")}},
		forUp:  nil,
	}
	mk := &stubMarket{listings: map[int64]*clients.StockListing{1: {ID: 1, Ticker: &ticker}}}
	svc := newResSvc(pf, mk, &seqQuerier{})
	if _, err := svc.Reserve(context.Background(), 20, "AAPL", 5, "c"); err == nil {
		t.Fatal("nil portfolio for update should be a conflict")
	}
}

func TestReservation_Reserve_ExecError(t *testing.T) {
	ticker := "AAPL"
	pf := &stubResPortfolio{
		byUser: []portfolio.Portfolio{{ID: 9, UserID: 20, ListingID: 1, Quantity: 10, AveragePurchasePrice: dec("0")}},
		forUp:  &portfolio.Portfolio{ID: 9, UserID: 20, ListingID: 1, Quantity: 10, AveragePurchasePrice: dec("0")},
	}
	mk := &stubMarket{listings: map[int64]*clients.StockListing{1: {ID: 1, Ticker: &ticker}}}
	q := &seqQuerier{execErr: errors.New("db")}
	svc := newResSvc(pf, mk, q)
	if _, err := svc.Reserve(context.Background(), 20, "AAPL", 5, "c"); err == nil {
		t.Fatal("INSERT exec error should propagate")
	}
}

func TestReservation_ResolveListingByTicker_FindError(t *testing.T) {
	pf := &stubResPortfolio{errAny: errors.New("db")}
	svc := newResSvc(pf, &stubMarket{}, &seqQuerier{})
	if _, _, err := svc.resolveListingByTicker(context.Background(), &fakeQuerier{}, 20, "AAPL"); err == nil {
		t.Fatal("FindByUserID error should propagate")
	}
}

// txFromQuerier adapts a Querier into a pgx.Tx for the few service methods that
// call tx.Exec directly (RevertExercise). The embedded pgx.Tx is nil — only
// Exec/Query/QueryRow are overridden and used by those paths.
type txFromQuerier struct {
	pgx.Tx
	q Querier
}

func (t txFromQuerier) Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error) {
	return t.q.Exec(ctx, sql, args...)
}
func (t txFromQuerier) Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error) {
	return t.q.Query(ctx, sql, args...)
}
func (t txFromQuerier) QueryRow(ctx context.Context, sql string, args ...any) pgx.Row {
	return t.q.QueryRow(ctx, sql, args...)
}
