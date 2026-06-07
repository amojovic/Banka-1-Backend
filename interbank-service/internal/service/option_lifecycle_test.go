package service

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
)

// Wave-2 option post-accept lifecycle tests: S3 (no exercise on accept),
// S4 (buyer contract persisted on inbound accept), S6 (settlement/amount guards),
// S9 (inbound seller settlement flips EXERCISED).

// ---------------------------------------------------------------------------
// Shared test fakes for the option lifecycle (ContractWriter + JSON helper)
// ---------------------------------------------------------------------------

// fakeContractWriter implements ContractWriter (S4/S9 option lifecycle).
type fakeContractWriter struct {
	byNeg     map[string]*store.Contract
	inserted  []*store.Contract
	statuses  map[string]string // contractID → last status set
	insertErr error
}

func newFakeContractWriter() *fakeContractWriter {
	return &fakeContractWriter{byNeg: map[string]*store.Contract{}, statuses: map[string]string{}}
}

func (f *fakeContractWriter) Insert(_ context.Context, c *store.Contract) error {
	if f.insertErr != nil {
		return f.insertErr
	}
	cp := *c
	f.inserted = append(f.inserted, &cp)
	f.byNeg[c.NegotiationID] = &cp
	return nil
}

func (f *fakeContractWriter) FindByNegotiationID(_ context.Context, negotiationID string) (*store.Contract, error) {
	c, ok := f.byNeg[negotiationID]
	if !ok {
		return nil, nil
	}
	cp := *c
	return &cp, nil
}

func (f *fakeContractWriter) UpdateStatus(_ context.Context, id, status string) error {
	f.statuses[id] = status
	for _, c := range f.byNeg {
		if c.ID == id {
			c.Status = status
		}
	}
	return nil
}

func mustPostingsJSON(t *testing.T, postings []protocol.Posting) string {
	t.Helper()
	b, err := json.Marshal(postings)
	if err != nil {
		t.Fatalf("marshal postings: %v", err)
	}
	return string(b)
}

// ---------------------------------------------------------------------------
// S3 — accept-COMMIT must NOT exercise (keep HELD reservation)
// ---------------------------------------------------------------------------

// TestCommitLocal_OptionRef_DoesNotExerciseOnAccept is the canonical S3 guard at
// the CommitLocal level: an accept-COMMIT carrying an OPTION reservation ref must
// keep the seller's HELD reservation — no ExerciseOption, no ReleaseOption.
func TestCommitLocal_OptionRef_DoesNotExerciseOnAccept(t *testing.T) {
	td := &fakeTradingReserver{}
	negID := "neg-accept-s3"
	negRouting := 111
	s := &fakeExecStore{
		txns: map[string]*store.Transaction{
			txKey(222, "tx-s3"): {
				TransactionIdRouting: 222,
				TransactionIdLocal:   "tx-s3",
				Status:               store.TxStatusPrepared,
				ReservationRefs: []store.ReservationRef{
					{Kind: store.RefKindOption, NegotiationRouting: &negRouting, NegotiationID: &negID},
				},
			},
		},
	}
	e := newTestExecutor(111, s, &fakeBankingCoreReserver{}, td)

	if err := e.CommitLocal(context.Background(), protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-s3"}); err != nil {
		t.Fatalf("CommitLocal: %v", err)
	}
	if len(td.committed) != 0 {
		t.Errorf("accept-commit must NOT exercise the option, got td.committed=%v", td.committed)
	}
	if len(td.released) != 0 {
		t.Errorf("accept-commit must NOT release the option, got td.released=%v", td.released)
	}
	if s.txns[txKey(222, "tx-s3")].Status != store.TxStatusCommitted {
		t.Errorf("expected COMMITTED")
	}
}

// ---------------------------------------------------------------------------
// S6/S9 posting builders
// ---------------------------------------------------------------------------

// exerciseShapePostingsSellerSide builds a 4-posting EXERCISE tx where WE (routing
// 111) are the SELLER bank hosting the OPTION pseudo-account; the buyer is on the
// partner (routing 222). k=10, pi=150 → k*pi = 1500.
func exerciseShapePostingsSellerSide() []protocol.Posting {
	return []protocol.Posting{
		// p1: OPTION account money debit +k*pi (consumes buyer's reserved strike)
		{Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-sell-1"}}, Amount: decimal.NewFromInt(1500), Asset: &protocol.MonasAsset{Currency: "USD"}},
		// p2: seller real cash credit -k*pi (the strike → seller)
		{Account: &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "C-15"}}, Amount: decimal.NewFromInt(-1500), Asset: &protocol.MonasAsset{Currency: "USD"}},
		// p3: OPTION account stock credit -k (releases seller reservation)
		{Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-sell-1"}}, Amount: decimal.NewFromInt(-10), Asset: &protocol.StockAsset{Ticker: "AAPL"}},
		// p4: buyer (partner) portfolio stock debit +k (delivery)
		{Account: &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: 222, Id: "C-2"}}, Amount: decimal.NewFromInt(10), Asset: &protocol.StockAsset{Ticker: "AAPL"}},
	}
}

// newExerciseTestExecutor builds an executor whose negotiation lookup knows the
// hosted negotiation (so the validator can check the exercise amounts).
func newExerciseTestExecutor(s *fakeExecStore, bc *fakeBankingCoreReserver, td *fakeTradingReserver, neg *fakeNegForExec) *Executor {
	negs := &fakeNegotiationLookup{negs: map[string]*fakeNegForExec{"neg-sell-1": neg}}
	return newExecutorWithNegs(111, s, bc, td, negs)
}

// ---------------------------------------------------------------------------
// S6 — inbound EXERCISE validation (we are the SELLER bank)
// ---------------------------------------------------------------------------

func TestPrepareLocal_SellerExercise_Valid_YesVote(t *testing.T) {
	bc := newFakeBC(map[string]*AccountInfo{"111000000000000015": {Currency: "USD", AvailableBalance: decimal.NewFromInt(0)}})
	bc.byOwnerCurrency = map[string]string{formatOwnerKey(15, "USD"): "111000000000000015"}
	td := &fakeTradingReserver{}
	s := &fakeExecStore{}
	e := newExerciseTestExecutor(s, bc, td, &fakeNegForExec{
		SellerID: "C-15", IsOngoing: true, Amount: 10,
		PricePerUnit: decimal.NewFromInt(150), Settlement: time.Now().Add(24 * time.Hour).Format(time.RFC3339),
	})

	tx := protocol.InterbankTransactionPayload{
		TransactionId: protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-exec"},
		Postings:      exerciseShapePostingsSellerSide(),
	}
	vote, err := e.PrepareLocal(context.Background(), tx)
	if err != nil {
		t.Fatalf("PrepareLocal: %v", err)
	}
	if vote.Vote != protocol.VoteYes {
		t.Fatalf("expected YES, got %q reasons=%v", vote.Vote, vote.Reasons)
	}
	// Nothing should be reserved on the seller side at exercise prepare.
	if len(bc.reserved) != 0 {
		t.Errorf("expected no MONAS reservations on seller exercise prepare, got %v", bc.reserved)
	}
	if len(s.txns) != 1 {
		t.Errorf("expected exercise tx persisted PREPARED, got %d", len(s.txns))
	}
}

func TestPrepareLocal_SellerExercise_WrongMoney_NoVote(t *testing.T) {
	bc := newFakeBC(map[string]*AccountInfo{"111000000000000015": {Currency: "USD", AvailableBalance: decimal.NewFromInt(0)}})
	bc.byOwnerCurrency = map[string]string{formatOwnerKey(15, "USD"): "111000000000000015"}
	e := newExerciseTestExecutor(&fakeExecStore{}, bc, &fakeTradingReserver{}, &fakeNegForExec{
		SellerID: "C-15", IsOngoing: true, Amount: 10,
		PricePerUnit: decimal.NewFromInt(150), Settlement: time.Now().Add(24 * time.Hour).Format(time.RFC3339),
	})
	postings := exerciseShapePostingsSellerSide()
	// Corrupt the OPTION money debit (p1) to 1499 and rebalance the seller cash leg
	// so only the OPTION amount check (not the balance check) trips.
	postings[0].Amount = decimal.NewFromInt(1499)
	postings[1].Amount = decimal.NewFromInt(-1499)

	tx := protocol.InterbankTransactionPayload{
		TransactionId: protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-exec-bad"},
		Postings:      postings,
	}
	vote, err := e.PrepareLocal(context.Background(), tx)
	if err != nil {
		t.Fatalf("PrepareLocal: %v", err)
	}
	if vote.Vote != protocol.VoteNo {
		t.Fatalf("expected NO, got %q", vote.Vote)
	}
	if !hasReason(vote.Reasons, protocol.ReasonOptionAmountIncorrect) {
		t.Errorf("expected OPTION_AMOUNT_INCORRECT, got %v", vote.Reasons)
	}
}

func TestPrepareLocal_SellerExercise_PastSettlement_NoVote(t *testing.T) {
	bc := newFakeBC(map[string]*AccountInfo{"111000000000000015": {Currency: "USD", AvailableBalance: decimal.NewFromInt(0)}})
	bc.byOwnerCurrency = map[string]string{formatOwnerKey(15, "USD"): "111000000000000015"}
	e := newExerciseTestExecutor(&fakeExecStore{}, bc, &fakeTradingReserver{}, &fakeNegForExec{
		SellerID: "C-15", IsOngoing: true, Amount: 10,
		PricePerUnit: decimal.NewFromInt(150), Settlement: time.Now().Add(-1 * time.Hour).Format(time.RFC3339),
	})
	tx := protocol.InterbankTransactionPayload{
		TransactionId: protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-exec-late"},
		Postings:      exerciseShapePostingsSellerSide(),
	}
	vote, err := e.PrepareLocal(context.Background(), tx)
	if err != nil {
		t.Fatalf("PrepareLocal: %v", err)
	}
	if vote.Vote != protocol.VoteNo {
		t.Fatalf("expected NO, got %q", vote.Vote)
	}
	if !hasReason(vote.Reasons, protocol.ReasonOptionUsedOrExpired) {
		t.Errorf("expected OPTION_USED_OR_EXPIRED, got %v", vote.Reasons)
	}
}

func hasReason(reasons []protocol.NoVoteReason, want string) bool {
	for _, r := range reasons {
		if r.Reason == want {
			return true
		}
	}
	return false
}

// ---------------------------------------------------------------------------
// BUG 2 repro — exercise validation must NOT gate on negotiation IsOngoing.
//
// After an OTC accept concludes, the negotiation row is CORRECTLY is_ongoing=false
// (the deal is done), while the resulting CONTRACT is ACTIVE and exercisable until
// settlement. The old `!neg.IsOngoing` rejection in ValidateExerciseTx rejected
// EVERY legitimate inter-bank exercise (we as seller) as OPTION_USED_OR_EXPIRED.
// These tests pin the corrected behaviour: IsOngoing is irrelevant; the gate is
// settlement-date (validator) + contract status (executor).
// ---------------------------------------------------------------------------

// TestPrepareLocal_SellerExercise_ConcludedNegotiation_YesVote is the BUG-2 repro.
// The negotiation is is_ongoing=false (deal concluded on accept) but the contract is
// ACTIVE and before settlement → the exercise MUST be accepted (YES vote). Before the
// fix this returned NO + OPTION_USED_OR_EXPIRED, blocking every inbound exercise.
func TestPrepareLocal_SellerExercise_ConcludedNegotiation_YesVote(t *testing.T) {
	bc := newFakeBC(map[string]*AccountInfo{"111000000000000015": {Currency: "USD", AvailableBalance: decimal.NewFromInt(0)}})
	bc.byOwnerCurrency = map[string]string{formatOwnerKey(15, "USD"): "111000000000000015"}
	s := &fakeExecStore{}
	e := newExerciseTestExecutor(s, bc, &fakeTradingReserver{}, &fakeNegForExec{
		// is_ongoing=false: the negotiation concluded on accept — the realistic state.
		SellerID: "C-15", IsOngoing: false, Amount: 10,
		PricePerUnit: decimal.NewFromInt(150), Settlement: time.Now().Add(24 * time.Hour).Format(time.RFC3339),
	})
	// Wire an ACTIVE contract — the option has not yet been exercised.
	cw := newFakeContractWriter()
	cw.byNeg["neg-sell-1"] = &store.Contract{
		ID: "otc-active", NegotiationID: "neg-sell-1", Status: store.ContractStatusActive,
		StockTicker: "AAPL", Amount: 10, StrikeCurrency: "USD", StrikeAmount: decimal.NewFromInt(150),
		LocalPartyType: store.ContractPartySeller,
	}
	e.SetContractStore(cw)

	tx := protocol.InterbankTransactionPayload{
		TransactionId: protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-exec-concluded"},
		Postings:      exerciseShapePostingsSellerSide(),
	}
	vote, err := e.PrepareLocal(context.Background(), tx)
	if err != nil {
		t.Fatalf("PrepareLocal: %v", err)
	}
	if vote.Vote != protocol.VoteYes {
		t.Fatalf("BUG 2: a concluded (is_ongoing=false) but ACTIVE before-settlement option must PASS exercise validation, got %q reasons=%v", vote.Vote, vote.Reasons)
	}
	if len(s.txns) != 1 {
		t.Errorf("expected exercise tx persisted PREPARED, got %d", len(s.txns))
	}
}

// TestValidateExerciseTx_IgnoresIsOngoing is the tightest unit-level BUG-2 repro:
// directly against the validator, a concluded (is_ongoing=false) before-settlement
// option with the correct amounts must validate with NO reasons.
func TestValidateExerciseTx_IgnoresIsOngoing(t *testing.T) {
	negs := &fakeNegotiationLookup{negs: map[string]*fakeNegForExec{
		"neg-sell-1": {
			SellerID: "C-15", IsOngoing: false, Amount: 10,
			PricePerUnit: decimal.NewFromInt(150), Settlement: time.Now().Add(24 * time.Hour).Format(time.RFC3339),
		},
	}}
	v := NewValidator(111, negs, nil, nil)
	money := decimal.NewFromInt(1500) // k*pi = 10*150
	stock := decimal.NewFromInt(-10)  // -k
	reasons := v.ValidateExerciseTx(context.Background(), "neg-sell-1", &money, &stock)
	if len(reasons) != 0 {
		t.Fatalf("BUG 2: is_ongoing=false must not reject a valid before-settlement exercise, got %v", reasons)
	}
}

// TestPrepareLocal_SellerExercise_AlreadyExercised_NoVote proves the executor
// contract-status gate rejects a re-exercise of an already-EXERCISED contract.
func TestPrepareLocal_SellerExercise_AlreadyExercised_NoVote(t *testing.T) {
	bc := newFakeBC(map[string]*AccountInfo{"111000000000000015": {Currency: "USD", AvailableBalance: decimal.NewFromInt(0)}})
	bc.byOwnerCurrency = map[string]string{formatOwnerKey(15, "USD"): "111000000000000015"}
	s := &fakeExecStore{}
	e := newExerciseTestExecutor(s, bc, &fakeTradingReserver{}, &fakeNegForExec{
		SellerID: "C-15", IsOngoing: false, Amount: 10,
		PricePerUnit: decimal.NewFromInt(150), Settlement: time.Now().Add(24 * time.Hour).Format(time.RFC3339),
	})
	cw := newFakeContractWriter()
	cw.byNeg["neg-sell-1"] = &store.Contract{
		ID: "otc-used", NegotiationID: "neg-sell-1", Status: store.ContractStatusExercised,
		StockTicker: "AAPL", Amount: 10, StrikeCurrency: "USD", StrikeAmount: decimal.NewFromInt(150),
		LocalPartyType: store.ContractPartySeller,
	}
	e.SetContractStore(cw)

	tx := protocol.InterbankTransactionPayload{
		TransactionId: protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-exec-dup"},
		Postings:      exerciseShapePostingsSellerSide(),
	}
	vote, err := e.PrepareLocal(context.Background(), tx)
	if err != nil {
		t.Fatalf("PrepareLocal: %v", err)
	}
	if vote.Vote != protocol.VoteNo {
		t.Fatalf("expected NO for already-EXERCISED contract, got %q", vote.Vote)
	}
	if !hasReason(vote.Reasons, protocol.ReasonOptionUsedOrExpired) {
		t.Errorf("expected OPTION_USED_OR_EXPIRED, got %v", vote.Reasons)
	}
	if len(s.txns) != 0 {
		t.Errorf("nothing should be persisted on NO vote, got %d", len(s.txns))
	}
}

// TestSettleSellerExercise_RefusesNonActiveContract proves the commit-side
// double-exercise guard: settleSellerExercise must refuse (error, no side effects)
// when the contract is already EXERCISED, so a replayed/duplicate EXERCISE commit
// cannot remove shares or credit cash twice.
func TestSettleSellerExercise_RefusesNonActiveContract(t *testing.T) {
	bc := newFakeBC(map[string]*AccountInfo{"111000000000000015": {Currency: "USD", AvailableBalance: decimal.NewFromInt(0)}})
	bc.byOwnerCurrency = map[string]string{formatOwnerKey(15, "USD"): "111000000000000015"}
	td := &fakeTradingReserver{}
	postings := exerciseShapePostingsSellerSide()
	s := &fakeExecStore{
		txns: map[string]*store.Transaction{
			txKey(222, "tx-exec-replay"): {
				TransactionIdRouting: 222,
				TransactionIdLocal:   "tx-exec-replay",
				Status:               store.TxStatusPrepared,
				PostingsJSON:         mustPostingsJSON(t, postings),
			},
		},
	}
	cw := newFakeContractWriter()
	cw.byNeg["neg-sell-1"] = &store.Contract{
		ID: "otc-1", NegotiationID: "neg-sell-1", Status: store.ContractStatusExercised,
		StockTicker: "AAPL", Amount: 10, StrikeCurrency: "USD", StrikeAmount: decimal.NewFromInt(150),
		LocalPartyType: store.ContractPartySeller,
	}
	e := newTestExecutor(111, s, bc, td)
	e.SetContractStore(cw)

	err := e.CommitLocal(context.Background(), protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-exec-replay"})
	if err == nil {
		t.Fatal("expected CommitLocal to fail when settling an already-EXERCISED contract")
	}
	// No irreversible side effects: no shares removed, no cash credited.
	if len(td.committed) != 0 {
		t.Errorf("ExerciseOption must NOT run for a non-ACTIVE contract, got td.committed=%v", td.committed)
	}
	if len(bc.credited) != 0 {
		t.Errorf("CreditMonas must NOT run for a non-ACTIVE contract, got bc.credited=%v", bc.credited)
	}
}

// ---------------------------------------------------------------------------
// S4 — buyer-side Contract persistence on inbound accept-COMMIT
// ---------------------------------------------------------------------------

// acceptShapePostingsBuyerSide builds a 4-posting ACCEPT tx where WE (routing 111)
// are the BUYER and the partner (routing 222) hosts the OPTION pseudo-account.
func acceptShapePostingsBuyerSide() []protocol.Posting {
	optDesc := protocol.OptionDescription{
		NegotiationId:  protocol.ForeignBankId{RoutingNumber: 222, Id: "neg-buy-1"},
		Stock:          protocol.StockDescription{Ticker: "AAPL"},
		PricePerUnit:   protocol.MonetaryValue{Currency: "USD", Amount: decimal.NewFromInt(150)},
		SettlementDate: "2999-01-01T00:00:00Z",
		Amount:         10,
	}
	return []protocol.Posting{
		{Account: &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "C-7"}}, Amount: decimal.NewFromInt(-50), Asset: &protocol.MonasAsset{Currency: "USD"}},
		{Account: &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: 222, Id: "C-2"}}, Amount: decimal.NewFromInt(50), Asset: &protocol.MonasAsset{Currency: "USD"}},
		{Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 222, Id: "neg-buy-1"}}, Amount: decimal.NewFromInt(-1), Asset: &protocol.OptionAsset{OptionDescription: optDesc}},
		{Account: &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "C-7"}}, Amount: decimal.NewFromInt(1), Asset: &protocol.OptionAsset{OptionDescription: optDesc}},
	}
}

func TestCommitLocal_BuyerSideAccept_PersistsContract(t *testing.T) {
	bc := newFakeBC(map[string]*AccountInfo{
		"111000000000000007": {Currency: "USD", AvailableBalance: decimal.NewFromInt(0)},
	})
	bc.byOwnerCurrency = map[string]string{formatOwnerKey(7, "USD"): "111000000000000007"}
	postings := acceptShapePostingsBuyerSide()
	s := &fakeExecStore{
		txns: map[string]*store.Transaction{
			txKey(222, "tx-accept-buy"): {
				TransactionIdRouting: 222,
				TransactionIdLocal:   "tx-accept-buy",
				Status:               store.TxStatusPrepared,
				PostingsJSON:         mustPostingsJSON(t, postings),
			},
		},
	}
	cw := newFakeContractWriter()
	// We are the BUYER (routing 111); the wire/option id "neg-buy-1" is the SELLER
	// bank's authoritative id. We mirror it locally under "neg-local-buy-1" with
	// remote_negotiation_id="neg-buy-1". The persisted contract must reference the
	// LOCAL id (FK target), not the wire id.
	negs := &fakeNegotiationLookup{negs: map[string]*fakeNegForExec{
		"neg-local-buy-1": {SellerID: "C-2", IsOngoing: true, RemoteID: "neg-buy-1"},
	}}
	e := newExecutorWithNegs(111, s, bc, &fakeTradingReserver{}, negs)
	e.SetContractStore(cw)

	if err := e.CommitLocal(context.Background(), protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-accept-buy"}); err != nil {
		t.Fatalf("CommitLocal: %v", err)
	}
	if len(cw.inserted) != 1 {
		t.Fatalf("expected 1 buyer-side contract persisted, got %d", len(cw.inserted))
	}
	c := cw.inserted[0]
	if c.LocalPartyType != store.ContractPartyBuyer {
		t.Errorf("expected localPartyType=BUYER, got %q", c.LocalPartyType)
	}
	// Contract keyed on the LOCAL negotiation id (FK satisfied), NOT the wire id.
	if c.NegotiationID != "neg-local-buy-1" || c.StockTicker != "AAPL" || c.Amount != 10 {
		t.Errorf("contract copy wrong: %+v", c)
	}
	if c.StrikeAmount.String() != "150" || c.StrikeCurrency != "USD" {
		t.Errorf("expected strike 150 USD, got %s %s", c.StrikeAmount, c.StrikeCurrency)
	}
	if c.Status != store.ContractStatusActive {
		t.Errorf("expected ACTIVE, got %q", c.Status)
	}
	if c.SellerRouting != 222 {
		t.Errorf("expected sellerRouting 222 (option lives at seller bank), got %d", c.SellerRouting)
	}
}

// fkEnforcingContractWriter wraps a fakeContractWriter and rejects an Insert whose
// NegotiationID is not present in localNegIDs — modelling the real Postgres
// interbank_contracts_negotiation_id_fkey constraint to interbank_negotiations(id).
// This lets the unit test reproduce the live 500 deterministically without a DB.
type fkEnforcingContractWriter struct {
	*fakeContractWriter
	localNegIDs map[string]bool
}

func (w *fkEnforcingContractWriter) Insert(ctx context.Context, c *store.Contract) error {
	if !w.localNegIDs[c.NegotiationID] {
		// Mirrors pgx error: insert or update on table "interbank_contracts" violates
		// foreign key constraint "interbank_contracts_negotiation_id_fkey".
		return errors.New(`ERROR: insert or update on table "interbank_contracts" violates foreign key constraint "interbank_contracts_negotiation_id_fkey" (SQLSTATE 23503)`)
	}
	return w.fakeContractWriter.Insert(ctx, c)
}

// TestCommitLocal_BuyerSideAccept_RemoteNegID_FKViolation_Reproducer is the
// regression test for the reverse-direction (B1=buyer) accept-COMMIT 500. The inbound
// accept's OptionDescription.negotiationId is the SELLER bank's authoritative id
// ("neg-buy-1"); our only matching interbank_negotiations row is the local mirror
// ("neg-local-buy-1", remote_negotiation_id="neg-buy-1"). Persisting the buyer contract
// on the raw wire id violates the FK. The fix resolves wire→local first, so the contract
// is keyed on the local id and the FK is satisfied.
func TestCommitLocal_BuyerSideAccept_RemoteNegID_FKViolation_Reproducer(t *testing.T) {
	bc := newFakeBC(map[string]*AccountInfo{
		"111000000000000007": {Currency: "USD", AvailableBalance: decimal.NewFromInt(0)},
	})
	bc.byOwnerCurrency = map[string]string{formatOwnerKey(7, "USD"): "111000000000000007"}
	postings := acceptShapePostingsBuyerSide() // wire/option id = "neg-buy-1"
	s := &fakeExecStore{
		txns: map[string]*store.Transaction{
			txKey(222, "tx-accept-buy"): {
				TransactionIdRouting: 222,
				TransactionIdLocal:   "tx-accept-buy",
				Status:               store.TxStatusPrepared,
				PostingsJSON:         mustPostingsJSON(t, postings),
			},
		},
	}
	// FK enforcer: only the LOCAL mirror id exists in interbank_negotiations.
	cw := &fkEnforcingContractWriter{
		fakeContractWriter: newFakeContractWriter(),
		localNegIDs:        map[string]bool{"neg-local-buy-1": true},
	}
	negs := &fakeNegotiationLookup{negs: map[string]*fakeNegForExec{
		"neg-local-buy-1": {SellerID: "C-2", IsOngoing: true, RemoteID: "neg-buy-1"},
	}}
	e := newExecutorWithNegs(111, s, bc, &fakeTradingReserver{}, negs)
	e.SetContractStore(cw)

	// With the fix the COMMIT_TX handler must NOT 500: the contract is keyed on the
	// local id, which the FK enforcer accepts.
	if err := e.CommitLocal(context.Background(), protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-accept-buy"}); err != nil {
		t.Fatalf("CommitLocal must not fail (FK must be satisfied via local id): %v", err)
	}
	if len(cw.inserted) != 1 {
		t.Fatalf("expected 1 buyer contract persisted, got %d", len(cw.inserted))
	}
	if got := cw.inserted[0].NegotiationID; got != "neg-local-buy-1" {
		t.Fatalf("contract must be keyed on the LOCAL negotiation id; got %q (wire id leaked → FK violation)", got)
	}
	// And the tx is COMMITTED, not FAILED, so is_ongoing flips and the buyer can list/exercise.
	tx, _ := s.FindTx(context.Background(), 222, "tx-accept-buy")
	if tx == nil || tx.Status != store.TxStatusCommitted {
		t.Fatalf("tx must be COMMITTED after a clean buyer-accept, got %+v", tx)
	}
}

// TestCommitLocal_BuyerSideAccept_NoLocalMirror_FailsCleanly proves that when NO local
// negotiation maps to the wire id we refuse to insert (rather than FK-500 or silently
// drop referential integrity) — the option lifecycle returns an error and the tx is
// flipped FAILED.
func TestCommitLocal_BuyerSideAccept_NoLocalMirror_FailsCleanly(t *testing.T) {
	bc := newFakeBC(map[string]*AccountInfo{
		"111000000000000007": {Currency: "USD", AvailableBalance: decimal.NewFromInt(0)},
	})
	bc.byOwnerCurrency = map[string]string{formatOwnerKey(7, "USD"): "111000000000000007"}
	postings := acceptShapePostingsBuyerSide()
	s := &fakeExecStore{
		txns: map[string]*store.Transaction{
			txKey(222, "tx-accept-buy"): {
				TransactionIdRouting: 222,
				TransactionIdLocal:   "tx-accept-buy",
				Status:               store.TxStatusPrepared,
				PostingsJSON:         mustPostingsJSON(t, postings),
			},
		},
	}
	cw := newFakeContractWriter()
	// Empty lookup → no local negotiation maps to "neg-buy-1".
	negs := &fakeNegotiationLookup{negs: map[string]*fakeNegForExec{}}
	e := newExecutorWithNegs(111, s, bc, &fakeTradingReserver{}, negs)
	e.SetContractStore(cw)

	err := e.CommitLocal(context.Background(), protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-accept-buy"})
	if err == nil {
		t.Fatal("expected CommitLocal to fail when no local negotiation maps to the wire id")
	}
	if len(cw.inserted) != 0 {
		t.Fatalf("no contract must be inserted without a resolvable local negotiation, got %+v", cw.inserted)
	}
	tx, _ := s.FindTx(context.Background(), 222, "tx-accept-buy")
	if tx == nil || tx.Status != store.TxStatusFailed {
		t.Fatalf("tx must be flipped FAILED on lifecycle error, got %+v", tx)
	}
}

func TestCommitLocal_SellerSideAccept_DoesNotPersistBuyerContract(t *testing.T) {
	optDesc := protocol.OptionDescription{
		NegotiationId:  protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-sell-1"},
		Stock:          protocol.StockDescription{Ticker: "AAPL"},
		PricePerUnit:   protocol.MonetaryValue{Currency: "USD", Amount: decimal.NewFromInt(150)},
		SettlementDate: "2999-01-01T00:00:00Z",
		Amount:         10,
	}
	postings := []protocol.Posting{
		{Account: &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: 222, Id: "C-2"}}, Amount: decimal.NewFromInt(-50), Asset: &protocol.MonasAsset{Currency: "USD"}},
		{Account: &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "C-15"}}, Amount: decimal.NewFromInt(50), Asset: &protocol.MonasAsset{Currency: "USD"}},
		{Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-sell-1"}}, Amount: decimal.NewFromInt(-1), Asset: &protocol.OptionAsset{OptionDescription: optDesc}},
		{Account: &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: 222, Id: "C-2"}}, Amount: decimal.NewFromInt(1), Asset: &protocol.OptionAsset{OptionDescription: optDesc}},
	}
	bc := newFakeBC(map[string]*AccountInfo{"111000000000000015": {Currency: "USD", AvailableBalance: decimal.NewFromInt(0)}})
	bc.byOwnerCurrency = map[string]string{formatOwnerKey(15, "USD"): "111000000000000015"}
	s := &fakeExecStore{
		txns: map[string]*store.Transaction{
			txKey(222, "tx-accept-sell"): {
				TransactionIdRouting: 222,
				TransactionIdLocal:   "tx-accept-sell",
				Status:               store.TxStatusPrepared,
				PostingsJSON:         mustPostingsJSON(t, postings),
			},
		},
	}
	cw := newFakeContractWriter()
	e := newTestExecutor(111, s, bc, &fakeTradingReserver{})
	e.SetContractStore(cw)

	if err := e.CommitLocal(context.Background(), protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-accept-sell"}); err != nil {
		t.Fatalf("CommitLocal: %v", err)
	}
	if len(cw.inserted) != 0 {
		t.Errorf("seller-side accept must NOT persist a buyer contract here, got %+v", cw.inserted)
	}
}

// ---------------------------------------------------------------------------
// S9 — inbound seller exercise settlement
// ---------------------------------------------------------------------------

func TestCommitLocal_SellerExercise_SettlesAndFlips(t *testing.T) {
	bc := newFakeBC(map[string]*AccountInfo{"111000000000000015": {Currency: "USD", AvailableBalance: decimal.NewFromInt(0)}})
	bc.byOwnerCurrency = map[string]string{formatOwnerKey(15, "USD"): "111000000000000015"}
	td := &fakeTradingReserver{}
	postings := exerciseShapePostingsSellerSide()
	s := &fakeExecStore{
		txns: map[string]*store.Transaction{
			txKey(222, "tx-exec-sell"): {
				TransactionIdRouting: 222,
				TransactionIdLocal:   "tx-exec-sell",
				Status:               store.TxStatusPrepared,
				PostingsJSON:         mustPostingsJSON(t, postings),
			},
		},
	}
	cw := newFakeContractWriter()
	cw.byNeg["neg-sell-1"] = &store.Contract{
		ID: "otc-1", NegotiationID: "neg-sell-1", Status: store.ContractStatusActive,
		StockTicker: "AAPL", Amount: 10, StrikeCurrency: "USD", StrikeAmount: decimal.NewFromInt(150),
		LocalPartyType: store.ContractPartySeller,
	}
	e := newTestExecutor(111, s, bc, td)
	e.SetContractStore(cw)

	if err := e.CommitLocal(context.Background(), protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-exec-sell"}); err != nil {
		t.Fatalf("CommitLocal: %v", err)
	}
	// ExerciseOption must have been called for the negotiation.
	if len(td.committed) != 1 || td.committed[0] != "option-exercise-neg-sell-1" {
		t.Errorf("expected ExerciseOption(neg-sell-1), got td.committed=%v", td.committed)
	}
	// Seller cash credited k*pi = 1500 via CreditMonas.
	if len(bc.credited) != 1 {
		t.Fatalf("expected exactly 1 CreditMonas (seller strike), got %d: %+v", len(bc.credited), bc.credited)
	}
	got := bc.credited[0]
	if got.accountNum != "111000000000000015" || got.currency != "USD" || got.amount.String() != "1500" {
		t.Errorf("seller credit = %+v, want account=111000000000000015 USD 1500", got)
	}
	// Contract flipped EXERCISED.
	if cw.statuses["otc-1"] != store.ContractStatusExercised {
		t.Errorf("expected contract EXERCISED, got statuses=%v", cw.statuses)
	}
	if s.txns[txKey(222, "tx-exec-sell")].Status != store.TxStatusCommitted {
		t.Errorf("expected tx COMMITTED")
	}
}
