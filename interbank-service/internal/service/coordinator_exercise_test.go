package service

import (
	"context"
	"testing"
	"time"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
)

// ExerciseOutbound (S7/S8) — buyer-bank exercise coordinator.

func buildExerciseCoordinator(outbound OutboundClient, bc *fakeBankingCoreReserver, td *fakeTradingReserver, cw *fakeContractWriter) *Coordinator {
	return buildExerciseCoordinatorWithNegStore(outbound, bc, td, cw, newFakeNegotiationStore())
}

func buildExerciseCoordinatorWithNegStore(outbound OutboundClient, bc *fakeBankingCoreReserver, td *fakeTradingReserver, cw *fakeContractWriter, negStore *fakeNegotiationStore) *Coordinator {
	es := newCoordExecStore()
	negLookup := NewNegotiationSellerLookup(newFakeNegotiationStore())
	exec := NewExecutor(myRouting, es, bc, td, negLookup, nil)
	return NewCoordinator(myRouting, exec, outbound, negStore, &fakeContractStore{}, bc, bc, td, cw, nil)
}

func buyerExerciseContract() *store.Contract {
	return &store.Contract{
		ID:             "otc-buy-1",
		NegotiationID:  "neg-buy-1",
		BuyerRouting:   myRouting,
		BuyerID:        "C-7",
		SellerRouting:  theirRouting,
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

func TestExerciseOutbound_HappyPath(t *testing.T) {
	bc := newFakeBC(map[string]*AccountInfo{"111000000000000007": {Currency: "USD", AvailableBalance: decimal.NewFromInt(100000)}})
	bc.byOwnerCurrency = map[string]string{formatOwnerKey(7, "USD"): "111000000000000007"}
	td := &fakeTradingReserver{}
	cw := newFakeContractWriter()
	c := buyerExerciseContract()
	cw.byNeg["neg-buy-1"] = c
	outbound := &fakeOutboundClient{vote: protocol.TransactionVote{Vote: protocol.VoteYes}}
	coord := buildExerciseCoordinator(outbound, bc, td, cw)

	if err := coord.ExerciseOutbound(context.Background(), c, 7, ""); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	// Strike reserved (k*pi = 1500) then committed.
	if len(bc.reserved) != 1 {
		t.Errorf("expected 1 strike reservation, got %v", bc.reserved)
	}
	if len(bc.committed) != 1 {
		t.Errorf("expected the strike reservation committed, got %v", bc.committed)
	}
	if len(bc.released) != 0 {
		t.Errorf("strike must not be released on the happy path, got %v", bc.released)
	}
	// Buyer shares delivered (k = 10).
	if len(td.creditedPortfolio) != 1 || td.creditedPortfolio[0].quantity != 10 ||
		td.creditedPortfolio[0].ticker != "AAPL" || td.creditedPortfolio[0].buyerUserID != 7 {
		t.Errorf("expected CreditPortfolio(7, AAPL, 10), got %v", td.creditedPortfolio)
	}
	// Partner committed + contract EXERCISED.
	if !outbound.committed {
		t.Error("expected SendCommitTx to the seller bank")
	}
	if cw.statuses["otc-buy-1"] != store.ContractStatusExercised {
		t.Errorf("expected contract EXERCISED, got %v", cw.statuses)
	}
}

func TestExerciseOutbound_BalancedExerciseTx(t *testing.T) {
	// Capture the tx the coordinator builds and verify it is a balanced 4-posting
	// EXERCISE tx: OPTION money +1500, seller cash -1500, OPTION stock -10, buyer +10.
	bc := newFakeBC(map[string]*AccountInfo{"111000000000000007": {Currency: "USD", AvailableBalance: decimal.NewFromInt(100000)}})
	bc.byOwnerCurrency = map[string]string{formatOwnerKey(7, "USD"): "111000000000000007"}
	td := &fakeTradingReserver{}
	cw := newFakeContractWriter()
	c := buyerExerciseContract()
	cw.byNeg["neg-buy-1"] = c
	cap := &capturingOutbound{vote: protocol.TransactionVote{Vote: protocol.VoteYes}}
	coord := buildExerciseCoordinator(cap, bc, td, cw)

	if err := coord.ExerciseOutbound(context.Background(), c, 7, "111000000000000007"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	v := NewValidator(myRouting, nil, nil, nil)
	if reasons := v.BalanceCheck(cap.tx.Postings); len(reasons) != 0 {
		t.Fatalf("exercise tx must be balanced, got %v", reasons)
	}
	if len(cap.tx.Postings) != 4 {
		t.Fatalf("expected 4 postings, got %d", len(cap.tx.Postings))
	}
	var optMoney, optStock, sellerCash, buyerStock *protocol.Posting
	for i := range cap.tx.Postings {
		p := &cap.tx.Postings[i]
		switch p.Account.(type) {
		case *protocol.OptionPseudoAccount:
			if _, ok := p.Asset.(*protocol.MonasAsset); ok {
				optMoney = p
			} else {
				optStock = p
			}
		case *protocol.PersonAccount:
			if _, ok := p.Asset.(*protocol.MonasAsset); ok {
				sellerCash = p
			} else {
				buyerStock = p
			}
		}
	}
	if optMoney == nil || optMoney.Amount.String() != "1500" {
		t.Errorf("OPTION money debit must be +1500, got %v", optMoney)
	}
	if optStock == nil || optStock.Amount.String() != "-10" {
		t.Errorf("OPTION stock credit must be -10, got %v", optStock)
	}
	if sellerCash == nil || sellerCash.Amount.String() != "-1500" {
		t.Errorf("seller cash credit must be -1500, got %v", sellerCash)
	}
	if buyerStock == nil || buyerStock.Amount.String() != "10" {
		t.Errorf("buyer stock delivery must be +10, got %v", buyerStock)
	}
}

// TestExerciseOutbound_UsesRemoteNegotiationID verifies the buyer-bank exercise sends
// the SELLER's authoritative negotiation id (our remote_negotiation_id) on the OPTION
// pseudo-account, even though the local contract is keyed on our LOCAL negotiation id.
// This is the exercise-side counterpart of the FK fix: the contract now stores the local
// id (FK target), so the coordinator must resolve it to the remote id for the wire.
func TestExerciseOutbound_UsesRemoteNegotiationID(t *testing.T) {
	bc := newFakeBC(map[string]*AccountInfo{"111000000000000007": {Currency: "USD", AvailableBalance: decimal.NewFromInt(100000)}})
	bc.byOwnerCurrency = map[string]string{formatOwnerKey(7, "USD"): "111000000000000007"}
	td := &fakeTradingReserver{}
	cw := newFakeContractWriter()
	c := buyerExerciseContract()
	c.NegotiationID = "neg-local-buy-1" // OUR local id (FK target)
	cw.byNeg["neg-local-buy-1"] = c

	// Seed the local mirror row: local id "neg-local-buy-1" → remote "318222f9-remote".
	negStore := newFakeNegotiationStore()
	remote := "318222f9-remote"
	_ = negStore.Insert(context.Background(), &store.Negotiation{
		ID:                  "neg-local-buy-1",
		IsAuthoritative:     false,
		RemoteNegotiationID: &remote,
		SellerRouting:       theirRouting,
	})

	cap := &capturingOutbound{vote: protocol.TransactionVote{Vote: protocol.VoteYes}}
	coord := buildExerciseCoordinatorWithNegStore(cap, bc, td, cw, negStore)

	if err := coord.ExerciseOutbound(context.Background(), c, 7, "111000000000000007"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	// The OPTION pseudo-account id sent to the seller MUST be the remote id, not our local id.
	var optID string
	for i := range cap.tx.Postings {
		if opt, ok := cap.tx.Postings[i].Account.(*protocol.OptionPseudoAccount); ok {
			optID = opt.Id.Id
			break
		}
	}
	if optID != remote {
		t.Fatalf("OPTION account must carry the remote negotiation id %q (seller hosts it); got %q", remote, optID)
	}
}

func TestExerciseOutbound_PartnerRejects_ReleasesStrike(t *testing.T) {
	bc := newFakeBC(map[string]*AccountInfo{"111000000000000007": {Currency: "USD", AvailableBalance: decimal.NewFromInt(100000)}})
	bc.byOwnerCurrency = map[string]string{formatOwnerKey(7, "USD"): "111000000000000007"}
	td := &fakeTradingReserver{}
	cw := newFakeContractWriter()
	c := buyerExerciseContract()
	cw.byNeg["neg-buy-1"] = c
	outbound := &fakeOutboundClient{vote: protocol.TransactionVote{Vote: protocol.VoteNo, Reasons: []protocol.NoVoteReason{{Reason: protocol.ReasonOptionUsedOrExpired}}}}
	coord := buildExerciseCoordinator(outbound, bc, td, cw)

	err := coord.ExerciseOutbound(context.Background(), c, 7, "")
	if err == nil {
		t.Fatal("expected error when partner rejects exercise")
	}
	// Strike must be released; nothing committed; no shares delivered.
	if len(bc.released) != 1 {
		t.Errorf("expected strike released on partner reject, got %v", bc.released)
	}
	if len(bc.committed) != 0 {
		t.Errorf("strike must NOT be committed on reject, got %v", bc.committed)
	}
	if len(td.creditedPortfolio) != 0 {
		t.Errorf("no shares should be delivered on reject, got %v", td.creditedPortfolio)
	}
	if !outbound.rolledBack {
		t.Error("expected partner rollback on reject")
	}
	if cw.statuses["otc-buy-1"] == store.ContractStatusExercised {
		t.Error("contract must NOT be EXERCISED on reject")
	}
}

// capturingOutbound records the tx sent via SendNewTx for posting assertions.
type capturingOutbound struct {
	vote       protocol.TransactionVote
	tx         protocol.InterbankTransactionPayload
	committed  bool
	rolledBack bool
}

func (f *capturingOutbound) SendNewTx(_ context.Context, _ int, tx protocol.InterbankTransactionPayload) (protocol.TransactionVote, error) {
	f.tx = tx
	return f.vote, nil
}
func (f *capturingOutbound) SendCommitTx(_ context.Context, _ int, _ protocol.ForeignBankId) error {
	f.committed = true
	return nil
}
func (f *capturingOutbound) SendRollbackTx(_ context.Context, _ int, _ protocol.ForeignBankId) error {
	f.rolledBack = true
	return nil
}
