package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
)

// ErrAlreadyTerminal indicates a commit/rollback was attempted on a tx whose
// status is already terminal (COMMITTED, ROLLED_BACK, or FAILED).
var ErrAlreadyTerminal = errors.New("service: transaction already terminal")

// ---------------------------------------------------------------------------
// Interfaces
// ---------------------------------------------------------------------------

// ExecutorStore is the persistence seam used by Executor. Production wiring
// adapts *store.TransactionStore; tests use an in-memory fake.
type ExecutorStore interface {
	// PersistPrepared inserts a new PREPARED transaction row.
	// Populates t.ID and t.CreatedAt on success.
	PersistPrepared(ctx context.Context, t *store.Transaction) error

	// FindTx returns (nil, nil) when the transaction is not found.
	FindTx(ctx context.Context, routing int, id string) (*store.Transaction, error)

	// UpdateTxStatus flips the status column for the given (routing, id) pair.
	UpdateTxStatus(ctx context.Context, routing int, id, status string) error
}

// BankingCoreReserver extends BankingCoreReader with reservation actions toward
// banking-core's internal interbank endpoints.
type BankingCoreReserver interface {
	BankingCoreReader // ResolveAccount, FindAccountByOwnerAndCurrency
	// ReserveMonas places an amount reservation on the given account.
	// Returns the reservation UUID on success.
	ReserveMonas(ctx context.Context, accountNum, currency string, amount decimal.Decimal, txIDRouting int, txIDLocal string) (string, error)
	// CommitMonas permanently debits a previously reserved amount.
	CommitMonas(ctx context.Context, reservationID string) error
	// ReleaseMonas frees a previously reserved amount back to available balance.
	ReleaseMonas(ctx context.Context, reservationID string) error
	// CreditMonas credits a recipient/seller account on commit (protocol §2.8.4).
	// Idempotent per (txIDRouting, txIDLocal) and FX-aware on the banking-core side.
	CreditMonas(ctx context.Context, accountNum, currency string, amount decimal.Decimal, txIDRouting int, txIDLocal string) error
}

// TradingReserver provides stock and option reservation actions toward
// trading-service's internal interbank endpoints.
type TradingReserver interface {
	// ReserveStock creates a stock reservation for a pending inter-bank transaction.
	// Returns the reservation UUID on success.
	ReserveStock(ctx context.Context, sellerUserID int64, ticker string, quantity int, txIDRouting int, txIDLocal string) (string, error)
	// CommitStock permanently transfers the reserved stock.
	CommitStock(ctx context.Context, reservationID string) error
	// ReleaseStock frees the stock reservation.
	ReleaseStock(ctx context.Context, reservationID string) error
	// ReserveOption marks an option contract as reserved (idempotent per spec §3.6).
	ReserveOption(ctx context.Context, negotiationID protocol.ForeignBankId, sellerForeignID, ticker string, quantity int) error
	// ExerciseOption marks an option contract as exercised (spec §2.7.2): commits
	// the seller's HELD stock reservation (removes the k shares) and flips the
	// option to EXERCISED. Run ONLY from the real exercise round, never on accept.
	ExerciseOption(ctx context.Context, negotiationID protocol.ForeignBankId) error
	// ReleaseOption frees an option reservation.
	ReleaseOption(ctx context.Context, negotiationID protocol.ForeignBankId) error
	// CreditPortfolio delivers `quantity` shares of `ticker` into the buyer's
	// portfolio — the buyer-side leg of an inter-bank option EXERCISE.
	CreditPortfolio(ctx context.Context, buyerUserID int64, ticker string, quantity int) error
}

// ContractWriter is the persistence seam the Executor uses to persist buyer-side
// option contracts (S4) and to flip a contract to EXERCISED after settlement (S9).
// Production wiring adapts *store.ContractStore; tests use an in-memory fake.
type ContractWriter interface {
	// Insert persists a new contract row.
	Insert(ctx context.Context, c *store.Contract) error
	// FindByNegotiationID returns (nil, nil) when no contract matches.
	FindByNegotiationID(ctx context.Context, negotiationID string) (*store.Contract, error)
	// UpdateStatus flips a contract's status (e.g. ACTIVE→EXERCISED).
	UpdateStatus(ctx context.Context, id, status string) error
}

// OptionNegotiationLookup resolves an option negotiation to its seller foreign-id.
// Used during reservePosting for the OPTION branch. In production this is
// implemented by a thin adapter over *store.NegotiationStore.
type OptionNegotiationLookup interface {
	// FindSellerID returns the seller's foreign-id string for the given negotiation id.
	// Returns a non-nil error (and empty string) when the negotiation is not found.
	FindSellerID(ctx context.Context, negID string) (string, error)

	// ResolveLocalNegotiationID maps a negotiation id carried on the wire to the id
	// of the row that ACTUALLY EXISTS in our interbank_negotiations table — the one
	// the interbank_contracts.negotiation_id FK references.
	//
	// When WE are the inter-bank BUYER, the partner (authoritative seller) sends us
	// the option keyed on ITS negotiation id (our remote_negotiation_id). We stored
	// that negotiation under a DIFFERENT local id (is_authoritative=false,
	// remote_negotiation_id=<wire id>). Persisting a buyer-side contract on the raw
	// wire id violates the FK; we must first resolve it to our local id.
	//
	// Resolution rules (mirrors store.NegotiationStore.FindByAuthoritativeRef):
	//   - authoritative row whose id == wireID            → returns wireID (already local)
	//   - mirror row whose remote_negotiation_id == wireID → returns its local id
	//
	// Returns (localID, true, nil) on a hit; ("", false, nil) when no negotiation row
	// maps to wireID; ("", false, err) on an infrastructure failure.
	ResolveLocalNegotiationID(ctx context.Context, wireID string) (string, bool, error)
}

// ---------------------------------------------------------------------------
// Executor struct
// ---------------------------------------------------------------------------

// Executor orchestrates the 2PC prepare/commit/rollback lifecycle for
// incoming inter-bank transactions per spec §7.
//
// Critical invariant: Spring @Transactional (Java) maps to our per-method
// approach: all outbound REST reservation calls are NOT inside the same DB
// transaction as PersistPrepared. We use saga-style LIFO compensation instead.
type Executor struct {
	myRouting int
	store     ExecutorStore
	bc        BankingCoreReserver
	td        TradingReserver
	negLookup OptionNegotiationLookup
	contracts ContractWriter // optional — wired via SetContractStore for option lifecycle (S4/S9)
	validator *Validator
	log       *slog.Logger
}

// SetContractStore wires the optional contract persistence seam used by the option
// post-accept lifecycle: buyer-side contract persist on inbound accept (S4) and the
// EXERCISED flip after inbound seller settlement (S9). Left nil in pure 2PC tests.
func (e *Executor) SetContractStore(c ContractWriter) { e.contracts = c }

// NewExecutor constructs an Executor. negLookup is used only during
// reservePosting for the OPTION branch (looking up the seller's foreign-id).
// A nil negLookup means no OPTION reservations will succeed; pass a real
// adapter in production.
func NewExecutor(
	myRouting int,
	s ExecutorStore,
	bc BankingCoreReserver,
	td TradingReserver,
	negLookup OptionNegotiationLookup,
	log *slog.Logger,
) *Executor {
	if log == nil {
		log = slog.Default()
	}
	// The Validator uses negLookup (which implements NegotiationReader) for
	// option validation checks. bc provides BankingCoreReader.
	var negReader NegotiationReader
	if negLookup != nil {
		if nr, ok := negLookup.(NegotiationReader); ok {
			negReader = nr
		}
	}
	return &Executor{
		myRouting: myRouting,
		store:     s,
		bc:        bc,
		td:        td,
		negLookup: negLookup,
		validator: NewValidator(myRouting, negReader, bc, td),
		log:       log,
	}
}

// ---------------------------------------------------------------------------
// PrepareLocal
// ---------------------------------------------------------------------------

// PrepareLocal validates and reserves resources for an incoming NEW_TX message.
// Returns a YES vote and persists a PREPARED row on success.
// Returns a NO vote (never an error) when any validation rule is violated.
// Returns an error (not a vote) on unexpected infrastructure failures; in that
// case compensation has already been attempted for any partial reservations.
func (e *Executor) PrepareLocal(ctx context.Context, tx protocol.InterbankTransactionPayload) (protocol.TransactionVote, error) {
	// 1. Balance check — pure local computation.
	if reasons := e.validator.BalanceCheck(tx.Postings); len(reasons) > 0 {
		return protocol.TransactionVote{Vote: protocol.VoteNo, Reasons: reasons}, nil
	}

	// 1b. Exercise-shape detection. When WE host the OPTION pseudo-account, an
	// inbound EXERCISE tx is validated at the transaction level (the per-posting
	// validator cannot tell the seller's negative cash-credit leg from a debit, and
	// the OPTION account carries MONAS/STOCK rather than an OptionAsset). On a valid
	// exercise the seller side has nothing to reserve (the shares are already HELD
	// from accept; the seller receives cash on commit), so we vote YES and persist.
	if legs, _, isExercise := e.classifyOptionTx(tx.Postings); isExercise && legs.negRouting == e.myRouting {
		if reasons := e.validator.ValidateExerciseTx(ctx, legs.negID, legs.optionMoneyDebit, legs.optionStockCredit); len(reasons) > 0 {
			return protocol.TransactionVote{Vote: protocol.VoteNo, Reasons: reasons}, nil
		}
		// Contract-status gate: the validator can only reach the negotiation row, but
		// "option already used/expired" is the CONTRACT status. A freshly-accepted,
		// not-yet-exercised, before-settlement contract is ACTIVE and MUST pass; an
		// already EXERCISED / EXPIRED / DECLINED / RELEASED one MUST be rejected
		// (prevents double-exercise at prepare time). When no contract store is wired
		// (pure 2PC tests) or no contract row exists yet, fall through — the validator's
		// settlement-date check and settleSellerExercise's status gate still apply.
		if e.contracts != nil {
			c, err := e.contracts.FindByNegotiationID(ctx, legs.negID)
			if err != nil {
				return protocol.TransactionVote{}, fmt.Errorf("executor: find contract for exercise: %w", err)
			}
			if c != nil && c.Status != store.ContractStatusActive {
				return protocol.TransactionVote{Vote: protocol.VoteNo,
					Reasons: []protocol.NoVoteReason{{Reason: protocol.ReasonOptionUsedOrExpired}}}, nil
			}
		}
		postingsJSON, _ := json.Marshal(tx.Postings)
		metaJSON, _ := json.Marshal(map[string]any{
			"message":        tx.Message,
			"callNumber":     tx.CallNumber,
			"paymentCode":    tx.PaymentCode,
			"paymentPurpose": tx.PaymentPurpose,
		})
		row := &store.Transaction{
			TransactionIdRouting: tx.TransactionId.RoutingNumber,
			TransactionIdLocal:   tx.TransactionId.Id,
			Status:               store.TxStatusPrepared,
			PostingsJSON:         string(postingsJSON),
			MessageMeta:          string(metaJSON),
		}
		if err := e.store.PersistPrepared(ctx, row); err != nil {
			return protocol.TransactionVote{}, fmt.Errorf("executor: persist prepared (exercise): %w", err)
		}
		return protocol.TransactionVote{Vote: protocol.VoteYes}, nil
	}

	// 2. Filter postings that belong to our side.
	var ours []protocol.Posting
	for _, p := range tx.Postings {
		if e.isOurs(p) {
			ours = append(ours, p)
		}
	}

	// 3. Validate each of our postings (no side effects; NO vote on any failure).
	var reasons []protocol.NoVoteReason
	for _, p := range ours {
		r, err := e.validator.ValidatePosting(ctx, p)
		if err != nil {
			return protocol.TransactionVote{}, fmt.Errorf("executor: validate posting: %w", err)
		}
		if r != nil {
			reasons = append(reasons, *r)
		}
	}
	if len(reasons) > 0 {
		return protocol.TransactionVote{Vote: protocol.VoteNo, Reasons: reasons}, nil
	}

	// 4. Reserve — saga-style, LIFO compensation on failure.
	var refs []store.ReservationRef
	for _, p := range ours {
		if !p.Amount.IsNegative() {
			// Only outgoing (debit) postings need reservation.
			continue
		}
		ref, err := e.reservePosting(ctx, p, tx.TransactionId)
		if err != nil {
			e.compensate(ctx, refs)
			return protocol.TransactionVote{}, fmt.Errorf("executor: reserve posting: %w", err)
		}
		refs = append(refs, ref)
	}

	// 5. Build message meta JSON.
	metaJSON, _ := json.Marshal(map[string]any{
		"message":        tx.Message,
		"callNumber":     tx.CallNumber,
		"paymentCode":    tx.PaymentCode,
		"paymentPurpose": tx.PaymentPurpose,
	})

	// 6. Marshal postings for storage.
	postingsJSON, _ := json.Marshal(tx.Postings)

	// 7. Persist PREPARED row.
	row := &store.Transaction{
		TransactionIdRouting: tx.TransactionId.RoutingNumber,
		TransactionIdLocal:   tx.TransactionId.Id,
		Status:               store.TxStatusPrepared,
		PostingsJSON:         string(postingsJSON),
		ReservationRefs:      refs,
		MessageMeta:          string(metaJSON),
	}
	if err := e.store.PersistPrepared(ctx, row); err != nil {
		e.compensate(ctx, refs)
		return protocol.TransactionVote{}, fmt.Errorf("executor: persist prepared: %w", err)
	}

	return protocol.TransactionVote{Vote: protocol.VoteYes}, nil
}

// ---------------------------------------------------------------------------
// CommitLocal
// ---------------------------------------------------------------------------

// CommitLocal finalizes a PREPARED transaction. Idempotent.
// Returns nil when:
//   - tx is already COMMITTED (no-op)
//   - tx is not found (no-op; partner is protocol master)
//
// Returns ErrAlreadyTerminal when tx is in ROLLED_BACK or FAILED state.
func (e *Executor) CommitLocal(ctx context.Context, txID protocol.ForeignBankId) error {
	tx, err := e.store.FindTx(ctx, txID.RoutingNumber, txID.Id)
	if err != nil {
		return fmt.Errorf("executor: find tx for commit: %w", err)
	}
	if tx == nil {
		e.log.WarnContext(ctx, "commitLocal: tx not found — partner is master, no-op",
			"txID", txID)
		return nil
	}
	if tx.Status == store.TxStatusCommitted {
		e.log.DebugContext(ctx, "commitLocal: already committed — idempotent no-op",
			"txID", txID)
		return nil
	}
	if tx.Status != store.TxStatusPrepared {
		return fmt.Errorf("%w: status=%s txID=%v", ErrAlreadyTerminal, tx.Status, txID)
	}

	for _, ref := range tx.ReservationRefs {
		if err := e.commitRef(ctx, ref); err != nil {
			// Best-effort status flip to FAILED; log and propagate.
			if updateErr := e.store.UpdateTxStatus(ctx, txID.RoutingNumber, txID.Id, store.TxStatusFailed); updateErr != nil {
				e.log.ErrorContext(ctx, "commitLocal: failed to flip status to FAILED",
					"txID", txID, "err", updateErr)
			}
			return fmt.Errorf("executor: commit ref %+v: %w", ref, err)
		}
	}

	// Credit OUR positive (recipient/seller) MONAS legs (protocol §2.8.4). These are
	// NOT reserved on prepare (positive legs are skipped in the reserve loop), so they
	// have no ReservationRef — they must be credited here. CreditMonas is idempotent per
	// (routing, local), so a crash-and-retry after partial crediting is safe.
	if err := e.creditPositiveMonasLegs(ctx, tx, txID); err != nil {
		if updateErr := e.store.UpdateTxStatus(ctx, txID.RoutingNumber, txID.Id, store.TxStatusFailed); updateErr != nil {
			e.log.ErrorContext(ctx, "commitLocal: failed to flip status to FAILED",
				"txID", txID, "err", updateErr)
		}
		return fmt.Errorf("executor: credit positive legs: %w", err)
	}

	// Option post-accept lifecycle (S4 buyer-persist / S9 seller-settlement). Runs
	// off the stored postings: an accept-shape tx persists a buyer-side contract
	// when WE are the buyer; an exercise-shape tx settles the option when WE are
	// the seller bank (release reservation, credit seller strike, flip EXERCISED).
	if err := e.settleOptionLifecycle(ctx, tx, txID); err != nil {
		if updateErr := e.store.UpdateTxStatus(ctx, txID.RoutingNumber, txID.Id, store.TxStatusFailed); updateErr != nil {
			e.log.ErrorContext(ctx, "commitLocal: failed to flip status to FAILED",
				"txID", txID, "err", updateErr)
		}
		return fmt.Errorf("executor: option lifecycle: %w", err)
	}

	return e.store.UpdateTxStatus(ctx, txID.RoutingNumber, txID.Id, store.TxStatusCommitted)
}

// optionLegs is the parsed view of an option-bearing transaction's postings used
// to classify accept-shape vs exercise-shape and drive the post-accept lifecycle.
type optionLegs struct {
	// shared
	negID      string
	negRouting int
	optionDesc *protocol.OptionDescription

	// accept-shape: a Person@ourRouting leg that receives the option (+option).
	buyerOptionPerson *protocol.PersonAccount

	// exercise-shape: OPTION account hosted by us with a money-debit + stock-credit.
	optionMoneyDebit  *decimal.Decimal  // +k*pi on OUR OPTION account (MONAS)
	optionStockCredit *decimal.Decimal  // -k on OUR OPTION account (STOCK)
	sellerCashLeg     *protocol.Posting // the -k*pi seller real-cash credit leg (ours)
}

// classifyOptionTx scans the stored postings and returns (legs, isAccept, isExercise).
// Accept-shape: contains an OptionAsset posting. Exercise-shape: contains an OPTION
// pseudo-account WE host carrying a MONAS debit + a STOCK credit (no OptionAsset).
func (e *Executor) classifyOptionTx(postings []protocol.Posting) (optionLegs, bool, bool) {
	var legs optionLegs
	hasOptionAsset := false
	hostsOptionMoneyStock := false

	for i := range postings {
		p := postings[i]
		switch asset := p.Asset.(type) {
		case *protocol.OptionAsset:
			hasOptionAsset = true
			d := asset.OptionDescription
			legs.optionDesc = &d
			legs.negID = asset.NegotiationId.Id
			legs.negRouting = asset.NegotiationId.RoutingNumber
			// Buyer side: a Person@ourRouting leg that receives the option (+).
			if person, ok := p.Account.(*protocol.PersonAccount); ok &&
				person.Id.RoutingNumber == e.myRouting && p.Amount.IsPositive() {
				legs.buyerOptionPerson = person
			}
		case *protocol.MonasAsset:
			if opt, ok := p.Account.(*protocol.OptionPseudoAccount); ok && opt.Id.RoutingNumber == e.myRouting {
				amt := p.Amount
				legs.optionMoneyDebit = &amt
				legs.negID = opt.Id.Id
				legs.negRouting = opt.Id.RoutingNumber
				hostsOptionMoneyStock = true
			} else if e.isOurs(p) && p.Amount.IsNegative() {
				// The seller's real-cash credit leg (protocol "credit" = negative).
				cp := p
				legs.sellerCashLeg = &cp
			}
		case *protocol.StockAsset:
			if opt, ok := p.Account.(*protocol.OptionPseudoAccount); ok && opt.Id.RoutingNumber == e.myRouting {
				amt := p.Amount
				legs.optionStockCredit = &amt
				legs.negID = opt.Id.Id
				legs.negRouting = opt.Id.RoutingNumber
			}
		}
	}

	isExercise := hostsOptionMoneyStock && legs.optionStockCredit != nil && !hasOptionAsset
	isAccept := hasOptionAsset
	return legs, isAccept, isExercise
}

// settleOptionLifecycle drives the post-accept option lifecycle off the stored
// postings. No-op for non-option transactions.
func (e *Executor) settleOptionLifecycle(ctx context.Context, tx *store.Transaction, txID protocol.ForeignBankId) error {
	if tx.PostingsJSON == "" {
		return nil
	}
	var postings []protocol.Posting
	if err := json.Unmarshal([]byte(tx.PostingsJSON), &postings); err != nil {
		return fmt.Errorf("unmarshal stored postings: %w", err)
	}
	legs, isAccept, isExercise := e.classifyOptionTx(postings)

	switch {
	case isExercise:
		return e.settleSellerExercise(ctx, legs, txID)
	case isAccept:
		return e.persistBuyerContractIfOurs(ctx, legs)
	default:
		return nil
	}
}

// persistBuyerContractIfOurs persists a buyer-side Contract (S4) when an inbound
// accept-COMMIT delivers the option to a Person on OUR routing. When WE host the
// OPTION pseudo-account (we are the seller side) this is a no-op — the seller-side
// contract is created by the accept Coordinator, not here.
func (e *Executor) persistBuyerContractIfOurs(ctx context.Context, legs optionLegs) error {
	if legs.buyerOptionPerson == nil || legs.optionDesc == nil {
		return nil
	}
	// If WE host the option (seller side), do not double-persist a buyer contract.
	if legs.negRouting == e.myRouting {
		return nil
	}
	if e.contracts == nil {
		e.log.WarnContext(ctx, "buyer-side accept: no contract store wired — skipping persist", "neg", legs.negID)
		return nil
	}

	// FK resolution: legs.negID is the negotiation id as it travels on the wire — the
	// AUTHORITATIVE (seller-bank) id. When WE are the buyer that is the partner's id,
	// which we mirror locally under a DIFFERENT interbank_negotiations.id (with
	// remote_negotiation_id=legs.negID). interbank_contracts.negotiation_id has an FK
	// to interbank_negotiations(id), so the contract MUST reference our LOCAL id, not
	// the wire/remote id — otherwise the INSERT violates
	// interbank_contracts_negotiation_id_fkey and the whole accept-COMMIT 500s.
	localNegID := legs.negID
	if e.negLookup != nil {
		resolved, found, rerr := e.negLookup.ResolveLocalNegotiationID(ctx, legs.negID)
		if rerr != nil {
			return fmt.Errorf("resolve local negotiation for wire id %q: %w", legs.negID, rerr)
		}
		if !found {
			// No local negotiation row maps to this wire id. Persisting anyway would
			// FK-violate; refuse loudly rather than 500 deep in the INSERT or, worse,
			// silently drop referential integrity. A missing local mirror is itself a
			// bug (the buyer flow always creates one on CreateOutbound/counter), so the
			// caller marks the tx FAILED and it can be retried/diagnosed.
			return fmt.Errorf("buyer-side accept: no local negotiation mirrors wire id %q — cannot persist contract without violating FK", legs.negID)
		}
		localNegID = resolved
	}

	// Idempotent: skip if a contract already exists for this negotiation (keyed on the
	// local id, matching what we insert below and what the seller-side path uses).
	existing, err := e.contracts.FindByNegotiationID(ctx, localNegID)
	if err != nil {
		return fmt.Errorf("find buyer contract %q: %w", localNegID, err)
	}
	if existing != nil {
		e.log.DebugContext(ctx, "buyer-side accept: contract already exists — idempotent", "neg", localNegID)
		return nil
	}

	desc := legs.optionDesc
	settlement, perr := time.Parse(time.RFC3339, desc.SettlementDate)
	if perr != nil {
		return fmt.Errorf("parse settlementDate %q: %w", desc.SettlementDate, perr)
	}
	// Razresi prodavcev foreign id iz (mirror) pregovora — NIJE opaque: negotiation
	// mirror cuva seller_id. Bez ovoga buyer contract ima SellerID="" pa exercise salje
	// prazan seller Person id na seller-cash nozi -> partner N3 authz odbija (UNACCEPTABLE_ASSET).
	sellerForeignID := ""
	if e.negLookup != nil {
		sid, serr := e.negLookup.FindSellerID(ctx, legs.negID)
		if serr != nil {
			return fmt.Errorf("resolve seller foreign id for buyer contract %q: %w", legs.negID, serr)
		}
		sellerForeignID = sid
	}
	c := &store.Contract{
		ID:                       generateContractID(),
		NegotiationID:            localNegID,
		BuyerRouting:             legs.buyerOptionPerson.Id.RoutingNumber,
		BuyerID:                  legs.buyerOptionPerson.Id.Id,
		SellerRouting:            legs.negRouting, // option lives at the seller bank (§3.6.1)
		SellerID:                 sellerForeignID,
		StockTicker:              desc.Stock.Ticker,
		Amount:                   desc.Amount,
		StrikeCurrency:           desc.PricePerUnit.Currency,
		StrikeAmount:             desc.PricePerUnit.Amount,
		SettlementDate:           settlement,
		Status:                   store.ContractStatusActive,
		OptionPseudoOwnerRouting: legs.buyerOptionPerson.Id.RoutingNumber,
		OptionPseudoOwnerID:      legs.buyerOptionPerson.Id.Id,
		LocalPartyType:           store.ContractPartyBuyer,
	}
	if err := e.contracts.Insert(ctx, c); err != nil {
		return fmt.Errorf("insert buyer contract %q: %w", localNegID, err)
	}
	e.log.InfoContext(ctx, "persisted buyer-side option contract",
		"localNeg", localNegID, "wireNeg", legs.negID, "contract", c.ID,
		"buyer", c.BuyerID, "ticker", c.StockTicker, "amount", c.Amount)
	return nil
}

// settleSellerExercise performs the real inbound exercise settlement (S9) when WE
// are the seller bank hosting the OPTION pseudo-account: release the seller's HELD
// reservation and remove the k shares (ExerciseOption), credit the seller's real
// cash account k*pi via the idempotent FX-aware CreditMonas, then flip the contract
// to EXERCISED — transactionally, only after settlement. A bare status flip is
// non-conformant.
func (e *Executor) settleSellerExercise(ctx context.Context, legs optionLegs, txID protocol.ForeignBankId) error {
	if legs.negID == "" {
		return errors.New("exercise tx missing OPTION negotiation id")
	}
	if e.td == nil {
		return errors.New("executor: TradingReserver is nil for exercise settlement")
	}

	// (a0) Double-exercise guard: refuse to settle a non-ACTIVE contract BEFORE any
	// irreversible side effect (ExerciseOption removes shares; CreditMonas books cash).
	// A contract already EXERCISED/EXPIRED/DECLINED/RELEASED must not be settled again.
	// Looked up once here and reused for the status flip in (c).
	var contract *store.Contract
	if e.contracts != nil {
		c, err := e.contracts.FindByNegotiationID(ctx, legs.negID)
		if err != nil {
			return fmt.Errorf("find contract %q for exercise: %w", legs.negID, err)
		}
		if c != nil && c.Status != store.ContractStatusActive {
			e.log.WarnContext(ctx, "refusing to settle non-ACTIVE contract on exercise",
				"neg", legs.negID, "contract", c.ID, "status", c.Status)
			return fmt.Errorf("executor: contract %q not ACTIVE (status=%s) — refusing double exercise", c.ID, c.Status)
		}
		contract = c
	}

	// (a) Release the seller's reservation and remove the k shares.
	if err := e.td.ExerciseOption(ctx, protocol.ForeignBankId{RoutingNumber: legs.negRouting, Id: legs.negID}); err != nil {
		return fmt.Errorf("ExerciseOption neg=%s: %w", legs.negID, err)
	}

	// (b) Credit the seller's real cash account k*pi via CreditMonas (FX-aware).
	if legs.sellerCashLeg != nil {
		if e.bc == nil {
			return errors.New("executor: BankingCoreReserver is nil for seller strike credit")
		}
		monas, ok := legs.sellerCashLeg.Asset.(*protocol.MonasAsset)
		if !ok {
			return fmt.Errorf("seller cash leg is not MONAS: %T", legs.sellerCashLeg.Asset)
		}
		accountNum, err := e.resolveCreditAccount(ctx, *legs.sellerCashLeg, monas)
		if err != nil {
			return fmt.Errorf("resolve seller cash account: %w", err)
		}
		if accountNum != "" {
			if err := e.bc.CreditMonas(ctx, accountNum, monas.Currency, legs.sellerCashLeg.Amount.Abs(),
				txID.RoutingNumber, txID.Id); err != nil {
				return fmt.Errorf("CreditMonas seller strike account=%s: %w", accountNum, err)
			}
		}
	}

	// (c) Flip the contract → EXERCISED, only after settlement. Reuses the row read
	// in (a0); it was verified ACTIVE there, so this is the single ACTIVE→EXERCISED
	// transition that locks out any subsequent re-settlement.
	if e.contracts != nil && contract != nil {
		if err := e.contracts.UpdateStatus(ctx, contract.ID, store.ContractStatusExercised); err != nil {
			return fmt.Errorf("flip contract %q EXERCISED: %w", contract.ID, err)
		}
	}
	e.log.InfoContext(ctx, "settled inbound seller exercise", "neg", legs.negID, "txID", txID)
	return nil
}

// creditPositiveMonasLegs credits each of OUR positive (DEBIT / recipient) MONAS
// postings via banking-core's idempotent FX-aware credit primitive. Resolves the
// account from a RealAccount number or a PERSON@<ourRouting> reference.
func (e *Executor) creditPositiveMonasLegs(ctx context.Context, tx *store.Transaction, txID protocol.ForeignBankId) error {
	if tx.PostingsJSON == "" {
		return nil
	}
	var postings []protocol.Posting
	if err := json.Unmarshal([]byte(tx.PostingsJSON), &postings); err != nil {
		return fmt.Errorf("unmarshal stored postings: %w", err)
	}
	for _, p := range postings {
		// Only OUR positive MONAS legs get credited.
		if p.Amount.IsNegative() || p.Amount.IsZero() {
			continue
		}
		monas, ok := p.Asset.(*protocol.MonasAsset)
		if !ok {
			continue
		}
		if !e.isOurs(p) {
			continue
		}
		if e.bc == nil {
			return errors.New("executor: BankingCoreReserver is nil for MONAS credit")
		}

		accountNum, err := e.resolveCreditAccount(ctx, p, monas)
		if err != nil {
			return err
		}
		if accountNum == "" {
			// Not resolvable on our side (e.g. partner-side person) — skip.
			continue
		}
		if err := e.bc.CreditMonas(ctx, accountNum, monas.Currency, p.Amount.Abs(), txID.RoutingNumber, txID.Id); err != nil {
			return fmt.Errorf("CreditMonas account=%s: %w", accountNum, err)
		}
	}
	return nil
}

// resolveCreditAccount resolves the destination account number for a positive MONAS leg.
// RealAccount → its account number. PersonAccount (ours) → resolve owner+currency to
// an account number. Returns ("", nil) when the leg is not ours to credit.
func (e *Executor) resolveCreditAccount(ctx context.Context, p protocol.Posting, monas *protocol.MonasAsset) (string, error) {
	switch acc := p.Account.(type) {
	case *protocol.RealAccount:
		return acc.Num, nil
	case *protocol.PersonAccount:
		if acc.Id.RoutingNumber != e.myRouting {
			return "", nil
		}
		ownerID, err := parseOwnerID(acc.Id.Id)
		if err != nil {
			return "", fmt.Errorf("parse recipient owner id %q: %w", acc.Id.Id, err)
		}
		num, err := e.bc.FindAccountByOwnerAndCurrency(ctx, ownerID, monas.Currency)
		if err != nil || num == "" {
			return "", fmt.Errorf("resolve recipient person %d to %s account: %w", ownerID, monas.Currency, err)
		}
		return num, nil
	default:
		return "", nil
	}
}

// ---------------------------------------------------------------------------
// RollbackLocal
// ---------------------------------------------------------------------------

// RollbackLocal releases all reservations for a PREPARED transaction. Idempotent.
// Best-effort: logs warnings but does not return errors for individual release
// failures (partner is protocol master).
func (e *Executor) RollbackLocal(ctx context.Context, txID protocol.ForeignBankId) error {
	tx, err := e.store.FindTx(ctx, txID.RoutingNumber, txID.Id)
	if err != nil {
		return fmt.Errorf("executor: find tx for rollback: %w", err)
	}
	if tx == nil {
		e.log.WarnContext(ctx, "rollbackLocal: tx not found — best-effort no-op",
			"txID", txID)
		return nil
	}
	if tx.Status != store.TxStatusPrepared {
		e.log.WarnContext(ctx, "rollbackLocal: tx not in PREPARED state — no-op",
			"txID", txID, "status", tx.Status)
		return nil
	}

	// Release LIFO (reverse order).
	e.compensate(ctx, tx.ReservationRefs)

	return e.store.UpdateTxStatus(ctx, txID.RoutingNumber, txID.Id, store.TxStatusRolledBack)
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

// isOurs returns true when a posting's account side belongs to our routing number.
// "Ours" means:
//   - RealAccount: prefix of 18-digit number matches our routing (e.g. "111...")
//   - PersonAccount: id.RoutingNumber == myRouting
//   - OptionPseudoAccount: id.RoutingNumber == myRouting
func (e *Executor) isOurs(p protocol.Posting) bool {
	switch acc := p.Account.(type) {
	case *protocol.RealAccount:
		return e.validator.accountIsOurs(acc.Num)
	case *protocol.PersonAccount:
		return acc.Id.RoutingNumber == e.myRouting
	case *protocol.OptionPseudoAccount:
		return acc.Id.RoutingNumber == e.myRouting
	}
	return false
}

// reservePosting dispatches to the appropriate downstream reservation call
// based on the asset type. Returns the ReservationRef for bookkeeping.
func (e *Executor) reservePosting(ctx context.Context, p protocol.Posting, txID protocol.ForeignBankId) (store.ReservationRef, error) {
	switch asset := p.Asset.(type) {

	case *protocol.MonasAsset:
		return e.reserveMonasPosting(ctx, p, asset, txID)

	case *protocol.StockAsset:
		return e.reserveStockPosting(ctx, p, asset, txID)

	case *protocol.OptionAsset:
		return e.reserveOptionPosting(ctx, p, asset, txID)

	default:
		return store.ReservationRef{}, fmt.Errorf("executor: unknown asset type %T", p.Asset)
	}
}

// reserveMonasPosting handles MONAS (monetary asset) reservation.
// Both RealAccount and PersonAccount are supported (Person → resolve to account number first).
func (e *Executor) reserveMonasPosting(ctx context.Context, p protocol.Posting, asset *protocol.MonasAsset, txID protocol.ForeignBankId) (store.ReservationRef, error) {
	if e.bc == nil {
		return store.ReservationRef{}, errors.New("executor: BankingCoreReserver is nil")
	}

	var accountNum string
	switch acc := p.Account.(type) {
	case *protocol.RealAccount:
		accountNum = acc.Num

	case *protocol.PersonAccount:
		// Person+MONAS: spec §2.6 allows opaque person id; resolve to real account.
		if acc.Id.RoutingNumber != e.myRouting {
			// Partner-side person — skip reservation (partner reserves their own side).
			return store.ReservationRef{}, nil
		}
		ownerID, err := parseOwnerID(acc.Id.Id)
		if err != nil {
			return store.ReservationRef{}, fmt.Errorf("executor: parse owner id %q: %w", acc.Id.Id, err)
		}
		num, err := e.bc.FindAccountByOwnerAndCurrency(ctx, ownerID, asset.Currency)
		if err != nil || num == "" {
			return store.ReservationRef{}, fmt.Errorf("executor: resolve person %d to %s account: %w", ownerID, asset.Currency, err)
		}
		accountNum = num

	default:
		return store.ReservationRef{}, fmt.Errorf("executor: MONAS posting with unexpected account type %T", p.Account)
	}

	resID, err := e.bc.ReserveMonas(ctx, accountNum, asset.Currency, p.Amount.Abs(), txID.RoutingNumber, txID.Id)
	if err != nil {
		return store.ReservationRef{}, fmt.Errorf("executor: ReserveMonas account=%s: %w", accountNum, err)
	}
	return store.ReservationRef{Kind: store.RefKindMonas, ReservationID: resID}, nil
}

// reserveStockPosting handles STOCK reservation (only PersonAccount sellers are valid here).
func (e *Executor) reserveStockPosting(ctx context.Context, p protocol.Posting, asset *protocol.StockAsset, txID protocol.ForeignBankId) (store.ReservationRef, error) {
	if e.td == nil {
		return store.ReservationRef{}, errors.New("executor: TradingReserver is nil")
	}

	person, ok := p.Account.(*protocol.PersonAccount)
	if !ok {
		return store.ReservationRef{}, fmt.Errorf("executor: STOCK posting requires PersonAccount, got %T", p.Account)
	}
	if person.Id.RoutingNumber != e.myRouting {
		// Partner-side stock seller — partner reserves their side, skip.
		return store.ReservationRef{}, nil
	}

	sellerUserID, err := parseOwnerID(person.Id.Id)
	if err != nil {
		return store.ReservationRef{}, fmt.Errorf("executor: parse stock seller id %q: %w", person.Id.Id, err)
	}

	resID, err := e.td.ReserveStock(ctx, sellerUserID, asset.Ticker, int(p.Amount.Abs().IntPart()), txID.RoutingNumber, txID.Id)
	if err != nil {
		return store.ReservationRef{}, fmt.Errorf("executor: ReserveStock seller=%d ticker=%s: %w", sellerUserID, asset.Ticker, err)
	}
	return store.ReservationRef{Kind: store.RefKindStock, ReservationID: resID}, nil
}

// reserveOptionPosting handles OPTION reservation (OptionPseudoAccount only).
// The negotiation id IS the reservation key — there is no separate reservation UUID.
// Per spec §3.6: the option pseudo-account.id is the negotiationId; the seller is
// looked up from our negotiation store (pseudo.id() is NOT the seller).
func (e *Executor) reserveOptionPosting(ctx context.Context, p protocol.Posting, asset *protocol.OptionAsset, txID protocol.ForeignBankId) (store.ReservationRef, error) {
	if e.td == nil {
		return store.ReservationRef{}, errors.New("executor: TradingReserver is nil")
	}

	pseudo, ok := p.Account.(*protocol.OptionPseudoAccount)
	if !ok {
		return store.ReservationRef{}, fmt.Errorf("executor: OPTION posting requires OptionPseudoAccount, got %T", p.Account)
	}
	if pseudo.Id.RoutingNumber != e.myRouting {
		// Partner-side option pseudo-account — skip.
		return store.ReservationRef{}, nil
	}

	negID := asset.NegotiationId.Id

	// Resolve seller from our negotiation mirror.
	// pseudo.Id.Id holds the negotiationId (NOT the seller); per Java comment in reservePosting.
	sellerForeignID := ""
	if e.negLookup != nil {
		sid, err := e.negLookup.FindSellerID(ctx, negID)
		if err != nil {
			return store.ReservationRef{}, fmt.Errorf("executor: resolve option seller for neg %q: %w", negID, err)
		}
		sellerForeignID = sid
	}

	err := e.td.ReserveOption(ctx, asset.NegotiationId, sellerForeignID, asset.Stock.Ticker, asset.Amount)
	if err != nil {
		return store.ReservationRef{}, fmt.Errorf("executor: ReserveOption neg=%s: %w", negID, err)
	}

	negRouting := pseudo.Id.RoutingNumber
	return store.ReservationRef{
		Kind:               store.RefKindOption,
		NegotiationRouting: &negRouting,
		NegotiationID:      &negID,
	}, nil
}

// commitRef finalizes a single reservation ref.
func (e *Executor) commitRef(ctx context.Context, ref store.ReservationRef) error {
	switch ref.Kind {
	case store.RefKindMonas:
		if e.bc == nil {
			return errors.New("executor: BankingCoreReserver is nil for MONAS commit")
		}
		return e.bc.CommitMonas(ctx, ref.ReservationID)

	case store.RefKindStock:
		if e.td == nil {
			return errors.New("executor: TradingReserver is nil for STOCK commit")
		}
		return e.td.CommitStock(ctx, ref.ReservationID)

	case store.RefKindOption:
		// [S3 BUG FIX] On the accept-COMMIT we must KEEP the option's HELD
		// reservation — committing the option leg here does NOT mean "exercise".
		// Exercising at accept-time would strip the seller's shares immediately,
		// which is wrong: the strike has not been paid and the buyer has not
		// chosen to exercise. ReserveOption (run on accept-PREPARE) already holds
		// the seller's reservedQuantity; the real ExerciseOption only runs from
		// the dedicated exercise round (inbound EXERCISE tx, executor S9 path).
		// So this is intentionally a no-op.
		e.log.DebugContext(ctx, "commitRef OPTION accept-COMMIT — keeping HELD reservation (no exercise)",
			"negotiationRouting", ref.NegotiationRouting, "negotiationID", ref.NegotiationID)
		return nil

	default:
		e.log.Warn("executor: commitRef: unknown ref kind — skipping", "kind", ref.Kind)
		return nil
	}
}

// releaseRef frees a single reservation ref. Best-effort — logs failures but
// does not return them (caller continues with remaining refs).
func (e *Executor) releaseRef(ctx context.Context, ref store.ReservationRef) {
	var err error
	switch ref.Kind {
	case store.RefKindMonas:
		if e.bc != nil {
			err = e.bc.ReleaseMonas(ctx, ref.ReservationID)
		}
	case store.RefKindStock:
		if e.td != nil {
			err = e.td.ReleaseStock(ctx, ref.ReservationID)
		}
	case store.RefKindOption:
		if e.td != nil && ref.NegotiationRouting != nil && ref.NegotiationID != nil {
			err = e.td.ReleaseOption(ctx, protocol.ForeignBankId{
				RoutingNumber: *ref.NegotiationRouting,
				Id:            *ref.NegotiationID,
			})
		}
	default:
		e.log.Warn("executor: releaseRef: unknown ref kind — skipping", "kind", ref.Kind)
		return
	}
	if err != nil {
		e.log.Warn("executor: releaseRef: stuck reservation",
			"kind", ref.Kind,
			"reservationID", ref.ReservationID,
			"err", err)
	}
}

// compensate releases all refs in LIFO order. Best-effort: individual release
// errors are logged but do not interrupt the sweep.
func (e *Executor) compensate(ctx context.Context, refs []store.ReservationRef) {
	for i := len(refs) - 1; i >= 0; i-- {
		e.releaseRef(ctx, refs[i])
	}
}
