package service

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log/slog"
	"math/big"
	"time"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
)

// ---------------------------------------------------------------------------
// OutboundClient interface (Task 18 will provide the concrete implementation)
// ---------------------------------------------------------------------------

// OutboundClient is consumed by Coordinator to perform 2PC messaging toward
// the partner bank. The concrete implementation (InterbankClient, Task 18)
// signs requests with the partner's outbound token and POSTs to their
// /interbank endpoint. Tests use a fake.
type OutboundClient interface {
	// SendNewTx sends a NEW_TX message to the partner bank and returns their vote.
	SendNewTx(ctx context.Context, partnerRouting int, tx protocol.InterbankTransactionPayload) (protocol.TransactionVote, error)
	// SendCommitTx sends a COMMIT_TX message. Returns nil on 204.
	SendCommitTx(ctx context.Context, partnerRouting int, txID protocol.ForeignBankId) error
	// SendRollbackTx sends a ROLLBACK_TX message. Returns nil on 204.
	SendRollbackTx(ctx context.Context, partnerRouting int, txID protocol.ForeignBankId) error
}

// ---------------------------------------------------------------------------
// ContractStoreIface — persistence seam for Coordinator
// ---------------------------------------------------------------------------

// ContractStoreIface is the subset of store.ContractStore used by Coordinator.
type ContractStoreIface interface {
	Insert(ctx context.Context, c *store.Contract) error
}

// ---------------------------------------------------------------------------
// BankingCoreAccountResolver — resolve local user → 18-digit account number
// ---------------------------------------------------------------------------

// BankingCoreAccountResolver is a subset of BankingCoreReserver used by Coordinator
// to look up real account numbers for local parties.
type BankingCoreAccountResolver interface {
	FindAccountByOwnerAndCurrency(ctx context.Context, ownerID int64, currency string) (string, error)
}

// ---------------------------------------------------------------------------
// Coordinator
// ---------------------------------------------------------------------------

// Coordinator orchestrates the synchronous 2PC for §3.6 GET /negotiations/{rn}/{id}/accept.
// Corresponds to Java InterbankCoordinatorService.
//
// Flow per spec §3.6 + §7:
//  1. Build 4-posting transaction (buyer premium debit, seller premium credit,
//     seller Option pseudo-account credit, buyer Person Option debit).
//  2. PrepareLocal (local side reservation).
//  3. OutboundClient.SendNewTx (partner side prepare).
//  4. On partner NO or error: rollback local + rollback partner; return error.
//  5. CommitLocal (finalize local reservations).
//  6. MarkClosed negotiation + insert contract ACTIVE.
//  7. OutboundClient.SendCommitTx (best-effort; retry scheduler covers failures).
type Coordinator struct {
	myRouting  int
	executor   *Executor
	outbound   OutboundClient
	negStore   NegotiationStoreIface
	contractSt ContractStoreIface
	bcResolver BankingCoreAccountResolver
	bc         BankingCoreReserver // strike reserve/commit/release for outbound exercise (S7/S8)
	td         TradingReserver     // buyer stock delivery (CreditPortfolio) for outbound exercise
	contracts  ContractWriter      // buyer contract find + EXERCISED flip
	log        *slog.Logger
}

// NewCoordinator constructs a Coordinator. bcResolver may be nil — in that case
// MONAS account resolution falls back to TxAccount.Person (partner resolves).
// bc/td/contracts power the outbound exercise coordinator (S7/S8); they may be nil
// in pure-accept tests (ExerciseOutbound then returns an error if invoked).
func NewCoordinator(
	myRouting int,
	executor *Executor,
	outbound OutboundClient,
	negStore NegotiationStoreIface,
	contractSt ContractStoreIface,
	bcResolver BankingCoreAccountResolver,
	bc BankingCoreReserver,
	td TradingReserver,
	contracts ContractWriter,
	log *slog.Logger,
) *Coordinator {
	if log == nil {
		log = slog.Default()
	}
	return &Coordinator{
		myRouting:  myRouting,
		executor:   executor,
		outbound:   outbound,
		negStore:   negStore,
		contractSt: contractSt,
		bcResolver: bcResolver,
		bc:         bc,
		td:         td,
		contracts:  contracts,
		log:        log,
	}
}

// AcceptNegotiation implements CoordinatorIface.AcceptNegotiation.
// The negotiation entity must have is_ongoing=true and settlement in the future;
// all turn checks have already been performed in OtcNegotiationService.
func (c *Coordinator) AcceptNegotiation(ctx context.Context, neg *store.Negotiation) error {
	partnerRouting := neg.BuyerRouting
	if partnerRouting == c.myRouting {
		partnerRouting = neg.SellerRouting
	}

	// Premija je UKUPNA premija ugovora (O.premium, jedan MonetaryValue po §2.8.2 /
	// §3.6.1) — NE per-unit. Protokol accept posting je "Buyer Credit O.premium" bez
	// ×amount. neg.PremiumAmount je vec ukupna premija (kako je razmenjena na zici i
	// kako je vraca negotiation view) — mnozenje sa amount je duplo naplacivanje.
	totalPremium := neg.PremiumAmount
	premCurrency := neg.PremiumCurrency
	strikeCurrency := neg.PriceCurrency

	optionDesc := protocol.OptionDescription{
		NegotiationId: protocol.ForeignBankId{RoutingNumber: c.myRouting, Id: neg.ID},
		Stock:         protocol.StockDescription{Ticker: neg.StockTicker},
		PricePerUnit:  protocol.MonetaryValue{Currency: strikeCurrency, Amount: neg.PriceAmount},
		SettlementDate: neg.SettlementDate.UTC().Format(time.RFC3339),
		Amount:        neg.Amount,
	}

	// Resolve MONAS accounts: our local parties get real 18-digit numbers;
	// partner-side parties become TxAccount.Person (partner resolves internally).
	buyerCashAcc := c.resolveMonasAccount(ctx, neg.BuyerRouting, neg.BuyerID, premCurrency)
	sellerCashAcc := c.resolveMonasAccount(ctx, neg.SellerRouting, neg.SellerID, premCurrency)

	postings := []protocol.Posting{
		// a) Buyer credit premium (-totalPremium): premium leaves buyer account.
		{
			Account: buyerCashAcc,
			Amount:  totalPremium.Neg(),
			Asset:   &protocol.MonasAsset{Currency: premCurrency},
		},
		// b) Seller debit premium (+totalPremium): seller receives premium.
		{
			Account: sellerCashAcc,
			Amount:  totalPremium,
			Asset:   &protocol.MonasAsset{Currency: premCurrency},
		},
		// c) Seller Option pseudo-account credit (-1 option unit).
		{
			Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: c.myRouting, Id: neg.ID}},
			Amount:  decimal.NewFromFloat(-1),
			Asset:   &protocol.OptionAsset{OptionDescription: optionDesc},
		},
		// d) Buyer Person account debit (+1 option unit): buyer receives option.
		{
			Account: &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: neg.BuyerRouting, Id: neg.BuyerID}},
			Amount:  decimal.NewFromFloat(1),
			Asset:   &protocol.OptionAsset{OptionDescription: optionDesc},
		},
	}

	txID := protocol.ForeignBankId{RoutingNumber: c.myRouting, Id: generateTxID()}
	tx := protocol.InterbankTransactionPayload{
		TransactionId:  txID,
		Postings:       postings,
		Message:        "OTC accept for negotiation " + neg.ID,
		PaymentCode:    "289",
		PaymentPurpose: "OTC premium + option transfer",
	}

	// 1. PrepareLocal.
	localVote, err := c.executor.PrepareLocal(ctx, tx)
	if err != nil {
		return fmt.Errorf("%w: local prepare infra failure: %v", ErrInterbankProtocol, err)
	}
	if localVote.Vote != protocol.VoteYes {
		return fmt.Errorf("%w: local prepare rejected: %v", ErrInterbankProtocol, localVote.Reasons)
	}

	// 2. Partner prepare.
	partnerVote, err := c.outbound.SendNewTx(ctx, partnerRouting, tx)
	if err != nil {
		c.safeRollbackLocal(ctx, txID)
		return fmt.Errorf("%w: partner prepare exception: %v", ErrInterbankProtocol, err)
	}
	if partnerVote.Vote != protocol.VoteYes {
		c.safeRollbackLocal(ctx, txID)
		c.safeRollbackPartner(ctx, partnerRouting, txID)
		return fmt.Errorf("%w: partner rejected: %v", ErrInterbankProtocol, partnerVote.Reasons)
	}

	// 3. CommitLocal.
	if err := c.executor.CommitLocal(ctx, txID); err != nil {
		// Catastrophic: we committed locally but might fail to tell partner.
		c.safeRollbackPartner(ctx, partnerRouting, txID)
		return fmt.Errorf("%w: catastrophic commitLocal failure: %v", ErrInterbankProtocol, err)
	}

	// 4. Flip negotiation + create contract.
	if err := c.negStore.MarkClosed(ctx, neg.ID); err != nil {
		c.log.WarnContext(ctx, "coordinator: failed to mark negotiation closed",
			"neg", neg.ID, "err", err)
		// Non-fatal; 2PC is committed. Continue.
	}
	contract := &store.Contract{
		ID:                       generateContractID(),
		NegotiationID:            neg.ID,
		BuyerRouting:             neg.BuyerRouting,
		BuyerID:                  neg.BuyerID,
		SellerRouting:            neg.SellerRouting,
		SellerID:                 neg.SellerID,
		StockTicker:              neg.StockTicker,
		Amount:                   neg.Amount,
		StrikeCurrency:           neg.PriceCurrency,
		StrikeAmount:             neg.PriceAmount,
		SettlementDate:           neg.SettlementDate,
		Status:                   store.ContractStatusActive,
		OptionPseudoOwnerRouting: neg.BuyerRouting,
		OptionPseudoOwnerID:      neg.BuyerID,
	}
	if err := c.contractSt.Insert(ctx, contract); err != nil {
		c.log.ErrorContext(ctx, "coordinator: failed to insert contract",
			"neg", neg.ID, "err", err)
		// Non-fatal for the 2PC protocol; contract will be missing but 2PC committed.
	}

	// 5. Partner commit — CRITICAL for 204 response; best-effort (retry scheduler covers failures).
	if err := c.outbound.SendCommitTx(ctx, partnerRouting, txID); err != nil {
		c.log.WarnContext(ctx, "coordinator: sendCommitTx to partner failed — retry scheduler will pick it up",
			"partnerRouting", partnerRouting, "txID", txID, "err", err)
	}

	c.log.InfoContext(ctx, "accepted negotiation — contract ACTIVE",
		"neg", neg.ID, "contract", contract.ID, "tx", txID)
	return nil
}

// ---------------------------------------------------------------------------
// ExerciseOutbound — BUYER-bank exercise coordinator (S7/S8)
// ---------------------------------------------------------------------------

// ExerciseOutbound runs the BUYER-bank side of an inter-bank option EXERCISE for a
// held buyer-side contract. It is the mirror of AcceptNegotiation but for the
// exercise round:
//
//  1. Reserve the buyer's strike (k×pi) on the chosen/auto account (the strike is
//     reserved at EXERCISE time, not at accept — protocol default).
//  2. Build the 4-posting EXERCISE tx against the SELLER-bank OPTION account.
//  3. Send NEW_TX to the seller bank.
//  4. On partner YES: commit the buyer's strike reservation, deliver the buyer's
//     shares (CreditPortfolio), send COMMIT_TX, flip the contract → EXERCISED.
//  5. On partner NO / error: release the strike reservation and roll back the
//     partner (safe — both sides end clean).
//
// chosenAccountNum optionally pins the buyer's source cash account; when empty the
// account is resolved from (buyerLocalUserID, strikeCurrency).
func (c *Coordinator) ExerciseOutbound(ctx context.Context, contract *store.Contract, buyerLocalUserID int64, chosenAccountNum string) error {
	if c.bc == nil || c.td == nil || c.contracts == nil {
		return fmt.Errorf("%w: exercise coordinator not wired (bc/td/contracts nil)", ErrInterbankProtocol)
	}
	sellerRouting := contract.SellerRouting
	if sellerRouting == c.myRouting {
		return fmt.Errorf("%w: exercise of a same-bank option is not an inter-bank flow", ErrNegotiationInvalid)
	}

	k := int64(contract.Amount)
	kDec := decimal.NewFromInt(k)
	kPi := contract.StrikeAmount.Mul(kDec)
	strikeCurrency := contract.StrikeCurrency

	// 1. Resolve and reserve the buyer's strike (k×pi).
	accountNum := chosenAccountNum
	if accountNum == "" {
		num, err := c.bc.FindAccountByOwnerAndCurrency(ctx, buyerLocalUserID, strikeCurrency)
		if err != nil || num == "" {
			return fmt.Errorf("%w: resolve buyer %d %s account: %v", ErrNegotiationInvalid, buyerLocalUserID, strikeCurrency, err)
		}
		accountNum = num
	}

	// The OPTION pseudo-account sent to the seller bank must carry the id the SELLER
	// hosts the option under — its AUTHORITATIVE negotiation id. contract.NegotiationID
	// is OUR LOCAL interbank_negotiations.id (the FK target); for a buyer-side held
	// option that is a mirror row whose remote_negotiation_id is the seller's id. Resolve
	// it so the seller can locate the option (sending our local id would 404 there).
	remoteNegID, err := c.resolveRemoteNegotiationID(ctx, contract.NegotiationID)
	if err != nil {
		return fmt.Errorf("%w: resolve remote negotiation id for %q: %v", ErrInterbankProtocol, contract.NegotiationID, err)
	}

	txID := protocol.ForeignBankId{RoutingNumber: c.myRouting, Id: generateTxID()}
	reservationID, err := c.bc.ReserveMonas(ctx, accountNum, strikeCurrency, kPi, txID.RoutingNumber, txID.Id)
	if err != nil {
		return fmt.Errorf("%w: reserve buyer strike: %v", ErrInterbankProtocol, err)
	}

	// 2. Build the 4-posting EXERCISE tx against the seller-bank OPTION account.
	optionAcc := &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: sellerRouting, Id: remoteNegID}}
	sellerCashAcc := protocol.TxAccount(&protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: contract.SellerRouting, Id: contract.SellerID}})
	buyerPerson := &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: contract.BuyerRouting, Id: contract.BuyerID}}

	postings := []protocol.Posting{
		// p1: Debit OPTION money +k×pi (consumes the buyer's reserved strike).
		{Account: optionAcc, Amount: kPi, Asset: &protocol.MonasAsset{Currency: strikeCurrency}},
		// p2: Credit the seller's real cash -k×pi (the strike → seller).
		{Account: sellerCashAcc, Amount: kPi.Neg(), Asset: &protocol.MonasAsset{Currency: strikeCurrency}},
		// p3: Credit OPTION stocks -k (releases the seller's reservation).
		{Account: optionAcc, Amount: kDec.Neg(), Asset: &protocol.StockAsset{Ticker: contract.StockTicker}},
		// p4: Debit BUYER portfolio +k stocks (delivery).
		{Account: buyerPerson, Amount: kDec, Asset: &protocol.StockAsset{Ticker: contract.StockTicker}},
	}
	tx := protocol.InterbankTransactionPayload{
		TransactionId:  txID,
		Postings:       postings,
		Message:        "OTC exercise for negotiation " + remoteNegID,
		PaymentCode:    "289",
		PaymentPurpose: "OTC option exercise — strike + stock delivery",
	}

	// 3. Send NEW_TX to the seller bank (the seller hosts the OPTION account).
	partnerVote, err := c.outbound.SendNewTx(ctx, sellerRouting, tx)
	if err != nil {
		c.releaseStrike(ctx, reservationID)
		return fmt.Errorf("%w: partner prepare exception: %v", ErrInterbankProtocol, err)
	}
	if partnerVote.Vote != protocol.VoteYes {
		c.releaseStrike(ctx, reservationID)
		c.safeRollbackPartner(ctx, sellerRouting, txID)
		return fmt.Errorf("%w: partner rejected exercise: %v", ErrInterbankProtocol, partnerVote.Reasons)
	}

	// 4a. Commit the buyer's strike reservation (money leaves the buyer).
	if err := c.bc.CommitMonas(ctx, reservationID); err != nil {
		c.safeRollbackPartner(ctx, sellerRouting, txID)
		return fmt.Errorf("%w: commit buyer strike: %v", ErrInterbankProtocol, err)
	}

	// 4b. Deliver the buyer's shares.
	if err := c.td.CreditPortfolio(ctx, buyerLocalUserID, contract.StockTicker, int(k)); err != nil {
		// The strike is committed and the partner voted YES; we cannot cleanly undo.
		// Log loudly — the contract stays ACTIVE so a retry/manual fix is possible.
		c.log.ErrorContext(ctx, "exercise: buyer stock delivery failed AFTER strike commit — manual reconciliation needed",
			"neg", contract.NegotiationID, "buyer", buyerLocalUserID, "err", err)
		c.safeRollbackPartner(ctx, sellerRouting, txID)
		return fmt.Errorf("%w: buyer stock delivery: %v", ErrInterbankProtocol, err)
	}

	// 4c. Partner commit — best-effort (retry scheduler covers a transient failure).
	if err := c.outbound.SendCommitTx(ctx, sellerRouting, txID); err != nil {
		c.log.WarnContext(ctx, "exercise: SendCommitTx to seller bank failed — retry scheduler will pick it up",
			"sellerRouting", sellerRouting, "txID", txID, "err", err)
	}

	// 4d. Flip the buyer contract → EXERCISED.
	if err := c.contracts.UpdateStatus(ctx, contract.ID, store.ContractStatusExercised); err != nil {
		c.log.ErrorContext(ctx, "exercise: failed to flip buyer contract EXERCISED (settlement already done)",
			"contract", contract.ID, "err", err)
	}

	c.log.InfoContext(ctx, "exercised buyer option — contract EXERCISED",
		"neg", contract.NegotiationID, "contract", contract.ID, "tx", txID)
	return nil
}

func (c *Coordinator) releaseStrike(ctx context.Context, reservationID string) {
	if reservationID == "" {
		return
	}
	if err := c.bc.ReleaseMonas(ctx, reservationID); err != nil {
		c.log.WarnContext(ctx, "exercise: releaseStrike failed", "reservationID", reservationID, "err", err)
	}
}

// resolveRemoteNegotiationID maps a contract's LOCAL negotiation id to the
// authoritative id the partner (seller) bank hosts the option under, so the
// outbound EXERCISE tx addresses an OPTION account the seller can locate.
//
//   - mirror row (we are the buyer; partner is authoritative): the negotiation's
//     remote_negotiation_id IS the seller's id → return it.
//   - authoritative row (we host the negotiation): the partner mirrors us by our
//     own id → return localNegID unchanged.
//
// When negStore is nil or the row is not found, fall back to localNegID — the
// pre-existing behaviour — so same-bank/test wiring keeps working.
func (c *Coordinator) resolveRemoteNegotiationID(ctx context.Context, localNegID string) (string, error) {
	if c.negStore == nil {
		return localNegID, nil
	}
	// MORA biti FindByID (po LOKALNOM id-u): contract.NegotiationID je nas lokalni id.
	// FindByAuthoritativeRef mapira mirror SAMO po remote_negotiation_id, pa za mirror
	// pozvan sa lokalnim id-om vraca nil -> fallback na lokalni id -> pogresan id na zici
	// (partner glasa OPTION_NEGOTIATION_NOT_FOUND). FindByID nalazi mirror po njegovom id-u.
	neg, err := c.negStore.FindByID(ctx, localNegID)
	if err != nil {
		return "", err
	}
	if neg == nil {
		// No local row maps to this id — keep the id as-is rather than fail; the
		// seller-side prepare validation will reject if it is genuinely unknown.
		return localNegID, nil
	}
	if !neg.IsAuthoritative && neg.RemoteNegotiationID != nil && *neg.RemoteNegotiationID != "" {
		return *neg.RemoteNegotiationID, nil
	}
	return neg.ID, nil
}

// ---------------------------------------------------------------------------
// ExecutePayment — outbound REGULAR inter-bank payment coordinator (Banka1→Banka2)
// ---------------------------------------------------------------------------

// PaymentRequest is the input to ExecutePayment: a plain cross-bank money transfer
// from one of our 18-digit accounts to a person/account at the target bank.
type PaymentRequest struct {
	FromAccount string          // our 18-digit sender account
	ToRouting   int             // target bank routing number
	ToAccount   string          // recipient id at the target bank (their account/person id)
	Amount      decimal.Decimal // positive amount in Currency
	Currency    string          // ISO currency of the transfer
	Purpose     string          // free-text payment purpose
}

// ExecutePayment runs the 2PC coordinator for a REGULAR (non-OTC) inter-bank
// payment. It mirrors the OTC accept coordinator's prepare/commit/rollback
// structure but without any negotiation/contract steps — just a balanced two-posting
// money transfer:
//
//	a) sender CREDIT (−amount) on OUR real account  → reserved on PrepareLocal,
//	   committed on CommitLocal.
//	b) recipient DEBIT (+amount) on the target bank's account → the partner
//	   credits it on their own commit.
//
// Flow:
//  1. Synchronous FX-aware sufficient-funds guard BEFORE any reservation (clean 4xx).
//  2. PrepareLocal (reserve sender) → SendNewTx(targetRouting) (partner prepare).
//  3. On partner YES: CommitLocal + SendCommitTx (best-effort; retry scheduler covers).
//  4. On partner NO / error: release the sender reservation (RollbackLocal) and send
//     ROLLBACK_TX — both sides end clean.
func (c *Coordinator) ExecutePayment(ctx context.Context, req PaymentRequest) error {
	if c.bc == nil {
		return fmt.Errorf("%w: payment coordinator not wired (bc nil)", ErrInterbankProtocol)
	}
	if req.ToRouting == c.myRouting {
		return fmt.Errorf("%w: toRouting must not be our own routing (%d) — this is an inter-bank payment",
			ErrNegotiationInvalid, c.myRouting)
	}
	if req.FromAccount == "" || req.ToAccount == "" {
		return fmt.Errorf("%w: fromAccount and toAccount are required", ErrNegotiationInvalid)
	}
	if req.Amount.IsZero() || req.Amount.IsNegative() {
		return fmt.Errorf("%w: amount must be positive", ErrNegotiationInvalid)
	}
	if req.Currency == "" {
		return fmt.Errorf("%w: currency is required", ErrNegotiationInvalid)
	}

	// 1. Funds guard — resolve the sender account and verify same-currency cover.
	//    A foreign-currency sender leg cannot be debited here without conversion
	//    (out of this wave's scope, mirroring validateRealAccount), so we require the
	//    sender account currency to match the transfer currency before reserving.
	info, err := c.bc.ResolveAccount(ctx, req.FromAccount)
	if err != nil {
		return fmt.Errorf("%w: resolve sender account %s: %v", ErrNegotiationInvalid, req.FromAccount, err)
	}
	if info == nil {
		return fmt.Errorf("%w: sender account %s not found", ErrNegotiationInvalid, req.FromAccount)
	}
	if info.Currency != req.Currency {
		return fmt.Errorf("%w: sender account %s holds %s, cannot pay %s without conversion",
			ErrNegotiationInvalid, req.FromAccount, info.Currency, req.Currency)
	}
	if info.AvailableBalance.LessThan(req.Amount) {
		return fmt.Errorf("%w: sender account %s available %s < amount %s",
			ErrInsufficientFunds, req.FromAccount, info.AvailableBalance, req.Amount)
	}

	// 2. Build the balanced 2-posting payment tx.
	// Primalac je POZNAT broj racuna na partnerskoj banci → RealAccount{Num} (type=ACCOUNT),
	// NE PersonAccount (to bi gurnulo broj racuna u Person id pa bi partner pokusao da
	// ga razresi kao client-id → NO_SUCH_ACCOUNT). Partner kredituje RealAccount po prefiksu
	// (prve 3 cifre = njegov routing); isto kao nas outbound (TxAccount.Account) i kao
	// Banka-1 sopstveni inbound credit (resolveCreditAccount podrzava RealAccount).
	senderAcc := &protocol.RealAccount{Num: req.FromAccount}
	recipient := &protocol.RealAccount{Num: req.ToAccount}
	postings := []protocol.Posting{
		// a) Sender CREDIT (−amount): money leaves our account.
		{Account: senderAcc, Amount: req.Amount.Neg(), Asset: &protocol.MonasAsset{Currency: req.Currency}},
		// b) Recipient DEBIT (+amount): partner credits the recipient on commit.
		{Account: recipient, Amount: req.Amount, Asset: &protocol.MonasAsset{Currency: req.Currency}},
	}

	txID := protocol.ForeignBankId{RoutingNumber: c.myRouting, Id: generateTxID()}
	tx := protocol.InterbankTransactionPayload{
		TransactionId:  txID,
		Postings:       postings,
		Message:        "Inter-bank payment from " + req.FromAccount,
		PaymentCode:    "289",
		PaymentPurpose: req.Purpose,
	}

	// 3. PrepareLocal — reserve the sender.
	localVote, err := c.executor.PrepareLocal(ctx, tx)
	if err != nil {
		return fmt.Errorf("%w: local prepare infra failure: %v", ErrInterbankProtocol, err)
	}
	if localVote.Vote != protocol.VoteYes {
		return fmt.Errorf("%w: local prepare rejected: %v", ErrInterbankProtocol, localVote.Reasons)
	}

	// 4. Partner prepare.
	partnerVote, err := c.outbound.SendNewTx(ctx, req.ToRouting, tx)
	if err != nil {
		c.safeRollbackLocal(ctx, txID)
		return fmt.Errorf("%w: partner prepare exception: %v", ErrInterbankProtocol, err)
	}
	if partnerVote.Vote != protocol.VoteYes {
		c.safeRollbackLocal(ctx, txID)
		c.safeRollbackPartner(ctx, req.ToRouting, txID)
		return fmt.Errorf("%w: partner rejected payment: %v", ErrInterbankProtocol, partnerVote.Reasons)
	}

	// 5. CommitLocal — finalize the sender debit.
	if err := c.executor.CommitLocal(ctx, txID); err != nil {
		c.safeRollbackPartner(ctx, req.ToRouting, txID)
		return fmt.Errorf("%w: catastrophic commitLocal failure: %v", ErrInterbankProtocol, err)
	}

	// 6. Partner commit — best-effort (retry scheduler covers a transient failure).
	if err := c.outbound.SendCommitTx(ctx, req.ToRouting, txID); err != nil {
		c.log.WarnContext(ctx, "payment: SendCommitTx to partner failed — retry scheduler will pick it up",
			"toRouting", req.ToRouting, "txID", txID, "err", err)
	}

	c.log.InfoContext(ctx, "executed outbound inter-bank payment",
		"from", req.FromAccount, "toRouting", req.ToRouting, "toAccount", req.ToAccount,
		"amount", req.Amount, "currency", req.Currency, "tx", txID)
	return nil
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

func (c *Coordinator) resolveMonasAccount(ctx context.Context, userRouting int, userID string, currency string) protocol.TxAccount {
	if userRouting != c.myRouting || c.bcResolver == nil {
		return &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: userRouting, Id: userID}}
	}
	// Strip C-/E- prefix to get numeric owner id.
	numericPart := userID
	if len(userID) > 2 && (userID[:2] == "C-" || userID[:2] == "E-") {
		numericPart = userID[2:]
	}
	ownerID, err := parseBigInt(numericPart)
	if err != nil {
		c.log.WarnContext(ctx, "coordinator: parse owner id failed; fallback to Person",
			"userID", userID, "err", err)
		return &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: userRouting, Id: userID}}
	}
	accountNum, err := c.bcResolver.FindAccountByOwnerAndCurrency(ctx, ownerID, currency)
	if err != nil || accountNum == "" {
		c.log.WarnContext(ctx, "coordinator: resolve MONAS account failed; fallback to Person",
			"userID", userID, "currency", currency, "err", err)
		return &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: userRouting, Id: userID}}
	}
	return &protocol.RealAccount{Num: accountNum}
}

func (c *Coordinator) safeRollbackLocal(ctx context.Context, txID protocol.ForeignBankId) {
	if err := c.executor.RollbackLocal(ctx, txID); err != nil {
		c.log.WarnContext(ctx, "coordinator: safeRollbackLocal failed", "txID", txID, "err", err)
	}
}

func (c *Coordinator) safeRollbackPartner(ctx context.Context, partnerRouting int, txID protocol.ForeignBankId) {
	if err := c.outbound.SendRollbackTx(ctx, partnerRouting, txID); err != nil {
		c.log.WarnContext(ctx, "coordinator: safeRollbackPartner failed",
			"partnerRouting", partnerRouting, "txID", txID, "err", err)
	}
}

func generateTxID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return "tx-" + hex.EncodeToString(b)
}

func generateContractID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return "otc-" + hex.EncodeToString(b)
}

// parseBigInt parses a numeric string into int64. Used for owner ID extraction.
func parseBigInt(s string) (int64, error) {
	n := new(big.Int)
	if _, ok := n.SetString(s, 10); !ok {
		return 0, fmt.Errorf("not a valid integer: %q", s)
	}
	if !n.IsInt64() {
		return 0, fmt.Errorf("integer too large for int64: %q", s)
	}
	return n.Int64(), nil
}

