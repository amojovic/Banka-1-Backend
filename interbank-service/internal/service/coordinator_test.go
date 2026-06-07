package service

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
)

// ---------------------------------------------------------------------------
// fakeOutboundClient
// ---------------------------------------------------------------------------

type fakeOutboundClient struct {
	vote     protocol.TransactionVote
	sendErr  error
	commitErr error
	committed bool
	rolledBack bool
}

func (f *fakeOutboundClient) SendNewTx(_ context.Context, _ int, _ protocol.InterbankTransactionPayload) (protocol.TransactionVote, error) {
	return f.vote, f.sendErr
}

func (f *fakeOutboundClient) SendCommitTx(_ context.Context, _ int, _ protocol.ForeignBankId) error {
	f.committed = true
	return f.commitErr
}

func (f *fakeOutboundClient) SendRollbackTx(_ context.Context, _ int, _ protocol.ForeignBankId) error {
	f.rolledBack = true
	return nil
}

// ---------------------------------------------------------------------------
// fakeContractStore
// ---------------------------------------------------------------------------

type fakeContractStore struct {
	inserted []*store.Contract
}

func (f *fakeContractStore) Insert(_ context.Context, c *store.Contract) error {
	cp := *c
	f.inserted = append(f.inserted, &cp)
	return nil
}

// ---------------------------------------------------------------------------
// fakeExecutorForCoordinator
// Acts as a minimal stand-in for *Executor without a real Postgres backend.
// The Coordinator requires *Executor (not an interface), so we use a real
// Executor wired with stub store/clients that return predictable results.
// ---------------------------------------------------------------------------

// coordExecStore is a minimal ExecutorStore for Coordinator tests (named to
// avoid conflict with executor_test.go's fakeExecStore).
type coordExecStore struct {
	prepared map[string]*store.Transaction
	fail     bool
}

func newCoordExecStore() *coordExecStore {
	return &coordExecStore{prepared: make(map[string]*store.Transaction)}
}

func (f *coordExecStore) PersistPrepared(_ context.Context, t *store.Transaction) error {
	if f.fail {
		return errors.New("fake: persist failed")
	}
	key := t.TransactionIdLocal
	cp := *t
	f.prepared[key] = &cp
	return nil
}

func (f *coordExecStore) FindTx(_ context.Context, _ int, id string) (*store.Transaction, error) {
	if t, ok := f.prepared[id]; ok {
		cp := *t
		return &cp, nil
	}
	return nil, nil
}

func (f *coordExecStore) UpdateTxStatus(_ context.Context, _ int, id, status string) error {
	if t, ok := f.prepared[id]; ok {
		t.Status = status
	}
	return nil
}

// fakeBCReserver satisfies BankingCoreReserver with no real reservations.
type fakeBCReserver struct{}

func (fakeBCReserver) ResolveAccount(_ context.Context, _ string) (*AccountInfo, error) {
	return &AccountInfo{Currency: "USD", AvailableBalance: decimal.NewFromFloat(99999)}, nil
}

func (fakeBCReserver) FindAccountByOwnerAndCurrency(_ context.Context, _ int64, _ string) (string, error) {
	return "111000001234567890", nil
}

func (fakeBCReserver) ReserveMonas(_ context.Context, _, _ string, _ decimal.Decimal, _, _ interface{}) (string, error) {
	return "res-monas-01", nil
}

func (fakeBCReserver) CommitMonas(_ context.Context, _ string) error { return nil }
func (fakeBCReserver) ReleaseMonas(_ context.Context, _ string) error { return nil }

// These ReserveMonas signatures need to match the interface.
// BankingCoreReserver has ReserveMonas(ctx, accountNum, currency string, amount decimal.Decimal, txIDRouting int, txIDLocal string)
// We need to match that exactly.

// fakeTDReserver satisfies TradingReserver with no-ops.
type fakeTDReserver struct{}

func (fakeTDReserver) ReserveStock(_ context.Context, _ int64, _ string, _ int, _ int, _ string) (string, error) {
	return "res-stock-01", nil
}
func (fakeTDReserver) CommitStock(_ context.Context, _ string) error  { return nil }
func (fakeTDReserver) ReleaseStock(_ context.Context, _ string) error { return nil }
func (fakeTDReserver) ReserveOption(_ context.Context, _ protocol.ForeignBankId, _, _ string, _ int) error {
	return nil
}
func (fakeTDReserver) ExerciseOption(_ context.Context, _ protocol.ForeignBankId) error { return nil }
func (fakeTDReserver) ReleaseOption(_ context.Context, _ protocol.ForeignBankId) error  { return nil }
func (fakeTDReserver) CreditPortfolio(_ context.Context, _ int64, _ string, _ int) error {
	return nil
}

// fakeBCReserverFull implements BankingCoreReserver with correct signatures.
type fakeBCReserverFull struct{}

func (f fakeBCReserverFull) ResolveAccount(ctx context.Context, num string) (*AccountInfo, error) {
	return &AccountInfo{Currency: "USD", AvailableBalance: decimal.NewFromFloat(99999)}, nil
}

func (f fakeBCReserverFull) FindAccountByOwnerAndCurrency(ctx context.Context, ownerID int64, currency string) (string, error) {
	return "111000001234567890", nil
}

func (f fakeBCReserverFull) ReserveMonas(ctx context.Context, accountNum, currency string, amount decimal.Decimal, txIDRouting int, txIDLocal string) (string, error) {
	return "res-monas-01", nil
}

func (f fakeBCReserverFull) CommitMonas(ctx context.Context, reservationID string) error { return nil }
func (f fakeBCReserverFull) CreditMonas(ctx context.Context, accountNum, currency string, amount decimal.Decimal, txIDRouting int, txIDLocal string) error {
	return nil
}
func (f fakeBCReserverFull) ReleaseMonas(ctx context.Context, reservationID string) error { return nil }

// ---------------------------------------------------------------------------
// build helpers
// ---------------------------------------------------------------------------

func buildNeg(ongoing bool, settlementFuture bool) *store.Negotiation {
	sd := time.Now().Add(24 * time.Hour)
	if !settlementFuture {
		sd = time.Now().Add(-24 * time.Hour)
	}
	return &store.Negotiation{
		ID:                    "neg-test-001",
		BuyerRouting:          theirRouting,
		BuyerID:               "C-2",
		SellerRouting:         myRouting,
		SellerID:              "C-15",
		StockTicker:           "AAPL",
		Amount:                10,
		PriceCurrency:         "USD",
		PriceAmount:           decimal.NewFromFloat(150),
		PremiumCurrency:       "USD",
		PremiumAmount:         decimal.NewFromFloat(5),
		SettlementDate:        sd,
		LastModifiedByRouting: myRouting, // last mod by us → their turn to accept
		LastModifiedByID:      "sys",
		IsOngoing:             ongoing,
		IsAuthoritative:       true,
	}
}

func buildCoordinator(outbound OutboundClient, negStore NegotiationStoreIface, contractSt ContractStoreIface) *Coordinator {
	es := newCoordExecStore()
	// Wire NegotiationSellerLookup so the Validator can resolve option negotiations.
	negLookup := NewNegotiationSellerLookup(negStore)
	exec := NewExecutor(myRouting, es, fakeBCReserverFull{}, fakeTDReserver{}, negLookup, nil)
	return NewCoordinator(myRouting, exec, outbound, negStore, contractSt, fakeBCReserverFull{}, nil, nil, nil, nil)
}

// ---------------------------------------------------------------------------
// Coordinator tests
// ---------------------------------------------------------------------------

func TestCoordinator_AcceptNegotiation_HappyPath(t *testing.T) {
	ns := newFakeNegotiationStore()
	neg := buildNeg(true, true)
	ns.mu.Lock()
	ns.rows[neg.ID] = neg
	ns.mu.Unlock()
	cs := &fakeContractStore{}
	outbound := &fakeOutboundClient{
		vote: protocol.TransactionVote{Vote: protocol.VoteYes},
	}
	coord := buildCoordinator(outbound, ns, cs)
	err := coord.AcceptNegotiation(context.Background(), neg)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !outbound.committed {
		t.Error("expected SendCommitTx to be called")
	}
	if len(cs.inserted) == 0 {
		t.Error("expected contract to be inserted")
	}
}

func TestCoordinator_AcceptNegotiation_PartnerRejects(t *testing.T) {
	ns := newFakeNegotiationStore()
	neg := buildNeg(true, true)
	ns.mu.Lock()
	ns.rows[neg.ID] = neg
	ns.mu.Unlock()
	cs := &fakeContractStore{}
	outbound := &fakeOutboundClient{
		vote: protocol.TransactionVote{
			Vote:    protocol.VoteNo,
			Reasons: []protocol.NoVoteReason{{Reason: protocol.ReasonInsufficientAsset}},
		},
	}
	coord := buildCoordinator(outbound, ns, cs)
	err := coord.AcceptNegotiation(context.Background(), neg)
	if err == nil {
		t.Fatal("expected error when partner rejects")
	}
	if !errors.Is(err, ErrInterbankProtocol) {
		t.Errorf("expected ErrInterbankProtocol, got %v", err)
	}
	// Partner rollback should have been sent.
	if !outbound.rolledBack {
		t.Error("expected SendRollbackTx to be called on partner reject")
	}
}

func TestCoordinator_AcceptNegotiation_PartnerSendError(t *testing.T) {
	ns := newFakeNegotiationStore()
	neg := buildNeg(true, true)
	ns.mu.Lock()
	ns.rows[neg.ID] = neg
	ns.mu.Unlock()
	cs := &fakeContractStore{}
	outbound := &fakeOutboundClient{
		sendErr: errors.New("network timeout"),
	}
	coord := buildCoordinator(outbound, ns, cs)
	err := coord.AcceptNegotiation(context.Background(), neg)
	if err == nil {
		t.Fatal("expected error on send failure")
	}
	if !errors.Is(err, ErrInterbankProtocol) {
		t.Errorf("expected ErrInterbankProtocol, got %v", err)
	}
}

func TestCoordinator_CommitTxSendFailure_NonFatal(t *testing.T) {
	// If SendCommitTx fails, AcceptNegotiation should still return nil
	// (retry scheduler handles it). Contract must be inserted.
	ns := newFakeNegotiationStore()
	neg := buildNeg(true, true)
	ns.mu.Lock()
	ns.rows[neg.ID] = neg
	ns.mu.Unlock()
	cs := &fakeContractStore{}
	outbound := &fakeOutboundClient{
		vote:      protocol.TransactionVote{Vote: protocol.VoteYes},
		commitErr: errors.New("partner unreachable"),
	}
	coord := buildCoordinator(outbound, ns, cs)
	err := coord.AcceptNegotiation(context.Background(), neg)
	if err != nil {
		t.Fatalf("expected nil (best-effort commit); got %v", err)
	}
	if len(cs.inserted) == 0 {
		t.Error("contract must be inserted even if SendCommitTx fails")
	}
}

// ---------------------------------------------------------------------------
// ExecutePayment — outbound REGULAR inter-bank payment coordinator (Banka1→Banka2)
// ---------------------------------------------------------------------------

// buildPaymentCoordinator wires a coordinator whose executor + bc share the same
// map-backed fakeBankingCoreReserver so the funds guard (ResolveAccount), the
// sender reservation (ReserveMonas), commit (CommitMonas) and release (ReleaseMonas)
// all land on one fake we can assert against.
func buildPaymentCoordinator(outbound OutboundClient, bc *fakeBankingCoreReserver) *Coordinator {
	es := newCoordExecStore()
	negLookup := NewNegotiationSellerLookup(newFakeNegotiationStore())
	exec := NewExecutor(myRouting, es, bc, &fakeTradingReserver{}, negLookup, nil)
	return NewCoordinator(myRouting, exec, outbound, newFakeNegotiationStore(), &fakeContractStore{}, bc, bc, &fakeTradingReserver{}, newFakeContractWriter(), nil)
}

func paymentReq() PaymentRequest {
	return PaymentRequest{
		FromAccount: "111000000000000015",
		ToRouting:   theirRouting,
		ToAccount:   "222000000000000099",
		Amount:      decimal.NewFromInt(500),
		Currency:    "USD",
		Purpose:     "rent",
	}
}

func TestExecutePayment_HappyPath_BalancedTxCommits(t *testing.T) {
	bc := newFakeBC(map[string]*AccountInfo{
		"111000000000000015": {Currency: "USD", AvailableBalance: decimal.NewFromInt(100000)},
	})
	cap := &capturingOutbound{vote: protocol.TransactionVote{Vote: protocol.VoteYes}}
	coord := buildPaymentCoordinator(cap, bc)

	if err := coord.ExecutePayment(context.Background(), paymentReq()); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	// Built tx must be a balanced 2-posting payment.
	v := NewValidator(myRouting, nil, nil, nil)
	if reasons := v.BalanceCheck(cap.tx.Postings); len(reasons) != 0 {
		t.Fatalf("payment tx must be balanced, got %v", reasons)
	}
	if len(cap.tx.Postings) != 2 {
		t.Fatalf("expected 2 postings, got %d", len(cap.tx.Postings))
	}
	// Sender debited via reservation-commit; nothing released.
	if len(bc.reserved) != 1 || len(bc.committed) != 1 || len(bc.released) != 0 {
		t.Errorf("expected reserve+commit (no release): reserved=%v committed=%v released=%v", bc.reserved, bc.committed, bc.released)
	}
	if !cap.committed {
		t.Error("expected SendCommitTx to partner")
	}
	// Recipient is partner-side; we must NOT credit it locally.
	if len(bc.credited) != 0 {
		t.Errorf("recipient is partner-side — no local credit expected, got %v", bc.credited)
	}
}

// TestExecutePayment_RecipientIsRealAccount is the RealAccount red-proof: the
// recipient posting MUST be a *protocol.RealAccount (type=ACCOUNT) carrying the
// raw ToAccount number, NOT a *protocol.PersonAccount (which would make the partner
// try to resolve the account number as a client-id → NO_SUCH_ACCOUNT — a live-test
// failure). Both legs are RealAccount, so sender vs recipient is told apart by the
// account number rather than the type. Flipping recipient to PersonAccount in
// coordinator.go makes THIS test fail.
func TestExecutePayment_RecipientIsRealAccount(t *testing.T) {
	bc := newFakeBC(map[string]*AccountInfo{
		"111000000000000015": {Currency: "USD", AvailableBalance: decimal.NewFromInt(100000)},
	})
	cap := &capturingOutbound{vote: protocol.TransactionVote{Vote: protocol.VoteYes}}
	coord := buildPaymentCoordinator(cap, bc)
	if err := coord.ExecutePayment(context.Background(), paymentReq()); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var sender, recipient *protocol.Posting
	for i := range cap.tx.Postings {
		p := &cap.tx.Postings[i]
		ra, ok := p.Account.(*protocol.RealAccount)
		if !ok {
			t.Fatalf("every payment posting must be a *protocol.RealAccount, got %T", p.Account)
		}
		switch ra.Num {
		case "111000000000000015":
			sender = p
		case "222000000000000099":
			recipient = p
		default:
			t.Fatalf("unexpected account number %q in posting", ra.Num)
		}
	}
	if recipient == nil {
		t.Fatal("recipient posting (RealAccount{Num: ToAccount}) not found — recipient must be a RealAccount, not a PersonAccount")
	}
	// Directions: sender CREDIT (−amount), recipient DEBIT (+amount).
	if sender == nil || sender.Amount.String() != "-500" {
		t.Errorf("sender CREDIT must be -500 on RealAccount sender, got %v", sender)
	}
	if recipient.Amount.String() != "500" {
		t.Errorf("recipient DEBIT must be +500 on RealAccount recipient, got %v", recipient)
	}
}

func TestExecutePayment_PartnerRejects_SafeRollback(t *testing.T) {
	bc := newFakeBC(map[string]*AccountInfo{
		"111000000000000015": {Currency: "USD", AvailableBalance: decimal.NewFromInt(100000)},
	})
	outbound := &fakeOutboundClient{vote: protocol.TransactionVote{Vote: protocol.VoteNo, Reasons: []protocol.NoVoteReason{{Reason: protocol.ReasonNoSuchAccount}}}}
	coord := buildPaymentCoordinator(outbound, bc)

	err := coord.ExecutePayment(context.Background(), paymentReq())
	if err == nil {
		t.Fatal("expected error when partner rejects payment")
	}
	// Sender reservation must be released; never committed.
	if len(bc.reserved) != 1 || len(bc.released) != 1 || len(bc.committed) != 0 {
		t.Errorf("expected reserve+release (no commit): reserved=%v released=%v committed=%v", bc.reserved, bc.released, bc.committed)
	}
	if !outbound.rolledBack {
		t.Error("expected partner ROLLBACK_TX on reject")
	}
}

func TestExecutePayment_InsufficientFunds_NoReservation(t *testing.T) {
	bc := newFakeBC(map[string]*AccountInfo{
		"111000000000000015": {Currency: "USD", AvailableBalance: decimal.NewFromInt(100)}, // < 500
	})
	cap := &capturingOutbound{vote: protocol.TransactionVote{Vote: protocol.VoteYes}}
	coord := buildPaymentCoordinator(cap, bc)

	err := coord.ExecutePayment(context.Background(), paymentReq())
	if !errors.Is(err, ErrInsufficientFunds) {
		t.Fatalf("expected ErrInsufficientFunds, got %v", err)
	}
	// Guard runs BEFORE any reservation and before contacting the partner.
	if len(bc.reserved) != 0 {
		t.Errorf("no reservation must be made on insufficient funds, got %v", bc.reserved)
	}
	if cap.tx.TransactionId.Id != "" {
		t.Errorf("partner must not be contacted on insufficient funds, got tx %v", cap.tx.TransactionId)
	}
}

func TestExecutePayment_CurrencyMismatch_Rejected(t *testing.T) {
	bc := newFakeBC(map[string]*AccountInfo{
		"111000000000000015": {Currency: "EUR", AvailableBalance: decimal.NewFromInt(100000)},
	})
	outbound := &fakeOutboundClient{vote: protocol.TransactionVote{Vote: protocol.VoteYes}}
	coord := buildPaymentCoordinator(outbound, bc)

	err := coord.ExecutePayment(context.Background(), paymentReq()) // USD vs EUR account
	if !errors.Is(err, ErrNegotiationInvalid) {
		t.Fatalf("expected ErrNegotiationInvalid for currency mismatch, got %v", err)
	}
	if len(bc.reserved) != 0 {
		t.Errorf("no reservation on currency mismatch, got %v", bc.reserved)
	}
}
