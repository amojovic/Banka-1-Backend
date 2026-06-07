package service

import (
	"context"
	"testing"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
)

// ---------------------------------------------------------------------------
// Executor CommitLocal — positive (recipient/DEBIT) MONAS-leg crediting.
//
// On an inbound REGULAR payment (partner sender → OUR account), the recipient
// leg is positive and is NOT reserved on prepare. CommitLocal must credit it via
// the idempotent FX-aware CreditMonas primitive (protocol §2.8.4); otherwise the
// money is debited from the sender but never booked on our recipient.
// ---------------------------------------------------------------------------

// inboundPaymentTx builds a balanced 2-posting payment: partner sender CREDIT
// (negative, partner-side) + OUR recipient DEBIT (positive, ours).
func inboundPaymentTx(txID, ourRecipient string, amount int64, currency string) protocol.InterbankTransactionPayload {
	return protocol.InterbankTransactionPayload{
		TransactionId: protocol.ForeignBankId{RoutingNumber: 222, Id: txID},
		Postings: []protocol.Posting{
			// a) partner sender CREDIT (negative) — partner-routed, not ours.
			{Account: &protocol.RealAccount{Num: "222000000000000099"}, Amount: decimal.NewFromInt(-amount), Asset: &protocol.MonasAsset{Currency: currency}},
			// b) OUR recipient DEBIT (positive) — credited on commit.
			{Account: &protocol.RealAccount{Num: ourRecipient}, Amount: decimal.NewFromInt(amount), Asset: &protocol.MonasAsset{Currency: currency}},
		},
	}
}

// TestCommitLocal_CreditsOurPositiveRealAccountLeg verifies the recipient RealAccount
// is credited via CreditMonas on commit, with the tx id passed as the idempotency key.
func TestCommitLocal_CreditsOurPositiveRealAccountLeg(t *testing.T) {
	const ourAcc = "1110001000000000322" // 19-digit B1 account (prefix 111 = ours)
	bc := newFakeBC(map[string]*AccountInfo{
		ourAcc: {Currency: "USD", AvailableBalance: decimal.NewFromInt(0)},
	})
	s := &fakeExecStore{}
	e := newTestExecutor(111, s, bc, nil)

	tx := inboundPaymentTx("tx-inbound-1", ourAcc, 250, "USD")
	vote, err := e.PrepareLocal(context.Background(), tx)
	if err != nil {
		t.Fatalf("PrepareLocal: %v", err)
	}
	if vote.Vote != protocol.VoteYes {
		t.Fatalf("expected YES, got %q reasons=%v", vote.Vote, vote.Reasons)
	}
	// Positive recipient leg is not reserved on prepare.
	if len(bc.reserved) != 0 {
		t.Errorf("positive recipient leg must NOT be reserved, got %v", bc.reserved)
	}

	if err := e.CommitLocal(context.Background(), tx.TransactionId); err != nil {
		t.Fatalf("CommitLocal: %v", err)
	}
	if len(bc.credited) != 1 {
		t.Fatalf("expected exactly 1 CreditMonas call, got %d (%v)", len(bc.credited), bc.credited)
	}
	c := bc.credited[0]
	if c.accountNum != ourAcc {
		t.Errorf("credited account=%s want %s", c.accountNum, ourAcc)
	}
	if c.currency != "USD" || c.amount.String() != "250" {
		t.Errorf("credited %s %s, want USD 250", c.amount.String(), c.currency)
	}
	// Idempotency key = the inbound tx id (routing, local).
	if c.txIDRouting != 222 || c.txIDLocal != "tx-inbound-1" {
		t.Errorf("credit idempotency key=(%d,%s), want (222,tx-inbound-1)", c.txIDRouting, c.txIDLocal)
	}
	if st := s.txns[txKey(222, "tx-inbound-1")].Status; st != store.TxStatusCommitted {
		t.Errorf("tx status=%s want COMMITTED", st)
	}
}

// TestCommitLocal_DoesNotCreditPartnerSidePositiveLeg verifies that a positive MONAS
// leg on a partner-routed account is NOT credited by us (partner books its own side).
func TestCommitLocal_DoesNotCreditPartnerSidePositiveLeg(t *testing.T) {
	const ourSender = "1110001000000000322"
	bc := newFakeBC(map[string]*AccountInfo{
		ourSender: {Currency: "USD", AvailableBalance: decimal.NewFromInt(1000)},
	})
	s := &fakeExecStore{}
	e := newTestExecutor(111, s, bc, nil)

	// Outbound payment: OUR sender CREDIT (negative, reserved) + partner recipient DEBIT (positive, theirs).
	tx := protocol.InterbankTransactionPayload{
		TransactionId: protocol.ForeignBankId{RoutingNumber: 111, Id: "tx-outbound-1"},
		Postings: []protocol.Posting{
			{Account: &protocol.RealAccount{Num: ourSender}, Amount: decimal.NewFromInt(-100), Asset: &protocol.MonasAsset{Currency: "USD"}},
			{Account: &protocol.RealAccount{Num: "222000000000000099"}, Amount: decimal.NewFromInt(100), Asset: &protocol.MonasAsset{Currency: "USD"}},
		},
	}
	if vote, err := e.PrepareLocal(context.Background(), tx); err != nil || vote.Vote != protocol.VoteYes {
		t.Fatalf("PrepareLocal vote=%v err=%v", vote, err)
	}
	if err := e.CommitLocal(context.Background(), tx.TransactionId); err != nil {
		t.Fatalf("CommitLocal: %v", err)
	}
	// Our sender reservation is committed; the partner recipient is NOT our credit.
	if len(bc.credited) != 0 {
		t.Errorf("must not credit partner-side recipient, got %v", bc.credited)
	}
	if len(bc.committed) != 1 {
		t.Errorf("expected our sender reservation committed once, got %v", bc.committed)
	}
}

// TestCommitLocal_CreditsOurPositivePersonLeg verifies a PERSON@ourRouting positive
// MONAS leg is resolved (owner+currency) and credited.
func TestCommitLocal_CreditsOurPositivePersonLeg(t *testing.T) {
	const resolved = "1110001000000000777"
	bc := newFakeBC(map[string]*AccountInfo{
		resolved: {Currency: "EUR", AvailableBalance: decimal.NewFromInt(0)},
	})
	bc.byOwnerCurrency = map[string]string{formatOwnerKey(15, "EUR"): resolved}
	s := &fakeExecStore{}
	e := newTestExecutor(111, s, bc, nil)

	tx := protocol.InterbankTransactionPayload{
		TransactionId: protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-person-credit"},
		Postings: []protocol.Posting{
			{Account: &protocol.RealAccount{Num: "222000000000000099"}, Amount: decimal.NewFromInt(-40), Asset: &protocol.MonasAsset{Currency: "EUR"}},
			{Account: &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "C-15"}}, Amount: decimal.NewFromInt(40), Asset: &protocol.MonasAsset{Currency: "EUR"}},
		},
	}
	if vote, err := e.PrepareLocal(context.Background(), tx); err != nil || vote.Vote != protocol.VoteYes {
		t.Fatalf("PrepareLocal vote=%v err=%v", vote, err)
	}
	if err := e.CommitLocal(context.Background(), tx.TransactionId); err != nil {
		t.Fatalf("CommitLocal: %v", err)
	}
	if len(bc.credited) != 1 || bc.credited[0].accountNum != resolved {
		t.Fatalf("expected 1 credit to resolved person account %s, got %v", resolved, bc.credited)
	}
	if bc.credited[0].amount.String() != "40" || bc.credited[0].currency != "EUR" {
		t.Errorf("credited %s %s want EUR 40", bc.credited[0].amount.String(), bc.credited[0].currency)
	}
}

// ---------------------------------------------------------------------------
// Validator cross-currency acceptance (DEBIT/recipient leg) — protocol §2.8.4.
//
// A positive (recipient) MONAS leg with a currency mismatch must be ACCEPTED
// (converted on commit via CreditMonas), NOT rejected with NO_SUCH_ASSET. Only the
// negative (sender) leg requires same-currency funds.
// ---------------------------------------------------------------------------

// TestValidatePosting_CrossCcy_PositiveLegAccepted verifies a positive recipient leg
// with an account/posting currency mismatch is accepted (no NO vote).
func TestValidatePosting_CrossCcy_PositiveLegAccepted(t *testing.T) {
	bc := &fakeBC{
		byNum: map[string]fakeAccountInfo{
			"1110001000000000322": {Currency: "RSD", AvailableBalance: decimal.NewFromInt(0)},
		},
	}
	v := NewValidator(111, nil, bc, nil)
	// Recipient leg: +100 EUR into an RSD account → accepted (converted on commit).
	p := mkRealAccountMonas("1110001000000000322", decimal.NewFromInt(100), "EUR")
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r != nil {
		t.Errorf("cross-ccy DEBIT (recipient) leg must be accepted, got NO reason %+v", r)
	}
}

// TestValidatePosting_CrossCcy_NegativeLegRejected verifies the sender (negative) leg
// still requires same-currency funds: a mismatch is NO_SUCH_ASSET.
func TestValidatePosting_CrossCcy_NegativeLegRejected(t *testing.T) {
	bc := &fakeBC{
		byNum: map[string]fakeAccountInfo{
			"1110001000000000322": {Currency: "RSD", AvailableBalance: decimal.NewFromInt(100000)},
		},
	}
	v := NewValidator(111, nil, bc, nil)
	// Sender leg: -100 EUR from an RSD account → NO_SUCH_ASSET.
	p := mkRealAccountMonas("1110001000000000322", decimal.NewFromInt(-100), "EUR")
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r == nil || r.Reason != protocol.ReasonNoSuchAsset {
		t.Errorf("cross-ccy CREDIT (sender) leg must be NO_SUCH_ASSET, got %+v", r)
	}
}

// TestAccountIsOurs_19DigitOwnAccount is the regression guard for the accountIsOurs
// prefix fix: B1's 19-digit own accounts (prefix 111) must be recognised as ours.
func TestAccountIsOurs_19DigitOwnAccount(t *testing.T) {
	v := NewValidator(111, nil, nil, nil)
	if !v.accountIsOurs("1110001000000000322") {
		t.Error("19-digit account with our 111 prefix must be recognised as ours")
	}
	if v.accountIsOurs("2220001000000000322") {
		t.Error("foreign 222-prefixed account must NOT be ours")
	}
	if v.accountIsOurs("11") {
		t.Error("too-short account must not be ours")
	}
}
