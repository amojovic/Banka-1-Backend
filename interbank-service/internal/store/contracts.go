package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/shopspring/decimal"
)

// Contract status constants — mirror Java NegotiationContractStatus enum.
const (
	ContractStatusPendingPremium = "PENDING_PREMIUM" // created; premium 2PC not yet committed
	ContractStatusActive         = "ACTIVE"          // premium received; option is live
	ContractStatusExercised      = "EXERCISED"       // option exercised successfully
	ContractStatusExpired        = "EXPIRED"         // settlement_date passed without exercise
	ContractStatusReleased       = "RELEASED"        // cancelled before expiry
	ContractStatusDeclined       = "DECLINED"        // buyer abandoned the option early ("Odbi")
)

// Contract local-party-type constants (migration 20260524000006). Distinguishes
// which side of the option WE hold locally:
//   - SELLER — we host the option pseudo-account (seller-side accept coordinator).
//   - BUYER  — our user holds the option right (buyer-side inbound accept).
const (
	ContractPartySeller = "SELLER"
	ContractPartyBuyer  = "BUYER"
)

// Contract mirrors the interbank_contracts row.
// Column notes (verified against migration 20260524000001):
//   - Price columns are strike_currency / strike_amount (NOT price_currency/price_amount)
//     because the SQL DDL uses the options-domain terminology "strike price".
//   - There are NO premium_* columns in this table — premiums are tracked in the
//     negotiation row and via the 2PC transaction.
//   - option_pseudo_owner_routing/id record which party conceptually "owns" the option
//     (the buyer holds the right; stored so the 2PC executor knows who to credit on exercise).
//   - No updated_at; exercised_at and expired_at are set on status transitions.
type Contract struct {
	ID                     string
	NegotiationID          string
	BuyerRouting           int
	BuyerID                string
	SellerRouting          int
	SellerID               string
	StockTicker            string
	Amount                 int
	StrikeCurrency         string
	StrikeAmount           decimal.Decimal
	SettlementDate         time.Time
	Status                 string
	OptionPseudoOwnerRouting int
	OptionPseudoOwnerID    string
	LocalPartyType         string // SELLER | BUYER (migration 20260524000006)
	Version                int64
	CreatedAt              time.Time
	ExercisedAt            *time.Time
	ExpiredAt              *time.Time
}

type ContractStore struct{ pool querier }

func NewContractStore(pool *pgxpool.Pool) *ContractStore { return &ContractStore{pool: pool} }

const contractSelectCols = `
	id, negotiation_id, buyer_routing_number, buyer_id, seller_routing_number, seller_id,
	stock_ticker, amount, strike_currency, strike_amount, settlement_date, status,
	option_pseudo_owner_routing, option_pseudo_owner_id, local_party_type,
	version, created_at, exercised_at, expired_at`

func scanContract(row pgx.Row) (*Contract, error) {
	var c Contract
	err := row.Scan(
		&c.ID, &c.NegotiationID, &c.BuyerRouting, &c.BuyerID, &c.SellerRouting, &c.SellerID,
		&c.StockTicker, &c.Amount, &c.StrikeCurrency, &c.StrikeAmount, &c.SettlementDate, &c.Status,
		&c.OptionPseudoOwnerRouting, &c.OptionPseudoOwnerID, &c.LocalPartyType,
		&c.Version, &c.CreatedAt, &c.ExercisedAt, &c.ExpiredAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &c, nil
}

// Insert writes a new contract row, populating CreatedAt. When LocalPartyType is
// empty it defaults to SELLER (the historical seller-side accept-coordinator path).
func (s *ContractStore) Insert(ctx context.Context, c *Contract) error {
	partyType := c.LocalPartyType
	if partyType == "" {
		partyType = ContractPartySeller
	}
	c.LocalPartyType = partyType
	return s.pool.QueryRow(ctx, `
		INSERT INTO interbank_contracts
			(id, negotiation_id, buyer_routing_number, buyer_id, seller_routing_number, seller_id,
			 stock_ticker, amount, strike_currency, strike_amount, settlement_date, status,
			 option_pseudo_owner_routing, option_pseudo_owner_id, local_party_type, version)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,0)
		RETURNING created_at`,
		c.ID, c.NegotiationID, c.BuyerRouting, c.BuyerID, c.SellerRouting, c.SellerID,
		c.StockTicker, c.Amount, c.StrikeCurrency, c.StrikeAmount, c.SettlementDate, c.Status,
		c.OptionPseudoOwnerRouting, c.OptionPseudoOwnerID, partyType,
	).Scan(&c.CreatedAt)
}

// ListBuyerContracts returns the buyer-side held option contracts (local_party_type
// = BUYER) for a given buyer foreign id, newest first. When buyerID is empty
// (admin/supervisor scope) all buyer-side contracts are returned. Used by
// GET /api/interbank/otc/contracts so the FE can list & exercise held options.
func (s *ContractStore) ListBuyerContracts(ctx context.Context, buyerID string) ([]*Contract, error) {
	var rows pgx.Rows
	var err error
	if buyerID == "" {
		rows, err = s.pool.Query(ctx,
			`SELECT `+contractSelectCols+` FROM interbank_contracts
			 WHERE local_party_type = 'BUYER' ORDER BY created_at DESC`)
	} else {
		rows, err = s.pool.Query(ctx,
			`SELECT `+contractSelectCols+` FROM interbank_contracts
			 WHERE local_party_type = 'BUYER' AND buyer_id = $1 ORDER BY created_at DESC`,
			buyerID)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Contract
	for rows.Next() {
		c, scanErr := scanContract(rows)
		if scanErr != nil {
			return nil, scanErr
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// FindByNegotiationID returns (nil, nil) if not found.
func (s *ContractStore) FindByNegotiationID(ctx context.Context, negotiationID string) (*Contract, error) {
	row := s.pool.QueryRow(ctx,
		`SELECT `+contractSelectCols+` FROM interbank_contracts WHERE negotiation_id = $1`, negotiationID)
	return scanContract(row)
}

// FindByID returns (nil, nil) if not found.
func (s *ContractStore) FindByID(ctx context.Context, id string) (*Contract, error) {
	row := s.pool.QueryRow(ctx,
		`SELECT `+contractSelectCols+` FROM interbank_contracts WHERE id = $1`, id)
	return scanContract(row)
}

// SumActiveBySellerAndTicker returns the total stock quantity reserved across
// ACTIVE inter-bank contracts where the seller matches (sellerRouting, sellerID)
// AND ticker matches. Used by GET /public-stock to subtract the
// committed-but-not-exercised inventory from the advertised quantity.
func (s *ContractStore) SumActiveBySellerAndTicker(ctx context.Context, sellerRouting int, sellerID, ticker string) (int, error) {
	var sum int
	err := s.pool.QueryRow(ctx, `
		SELECT COALESCE(SUM(amount), 0)
		FROM interbank_contracts
		WHERE seller_routing_number = $1
		  AND seller_id             = $2
		  AND stock_ticker          = $3
		  AND status                = 'ACTIVE'`,
		sellerRouting, sellerID, ticker).Scan(&sum)
	return sum, err
}

// ListExpirable returns every ACTIVE contract whose settlement_date is already in
// the past — the candidates the expiry sweeper (scheduler/expiry.go) must settle.
// Ordered oldest-first so a backlog is drained in settlement order. The index
// idx_interbank_contracts_status_settle (status, settlement_date) backs the
// status='ACTIVE' predicate.
func (s *ContractStore) ListExpirable(ctx context.Context) ([]*Contract, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT `+contractSelectCols+` FROM interbank_contracts
		 WHERE status = 'ACTIVE' AND settlement_date < now()
		 ORDER BY settlement_date ASC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Contract
	for rows.Next() {
		c, scanErr := scanContract(rows)
		if scanErr != nil {
			return nil, scanErr
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// UpdateStatus flips a contract's status (e.g. ACTIVE→EXERCISED or →EXPIRED).
// For EXERCISED transitions, exercised_at is set to now(); for EXPIRED, expired_at.
// Other transitions leave those columns NULL.
func (s *ContractStore) UpdateStatus(ctx context.Context, id, status string) error {
	var err error
	switch status {
	case ContractStatusExercised:
		_, err = s.pool.Exec(ctx, `
			UPDATE interbank_contracts
			SET status       = $1,
			    exercised_at = now(),
			    version      = version + 1
			WHERE id = $2`, status, id)
	case ContractStatusExpired:
		_, err = s.pool.Exec(ctx, `
			UPDATE interbank_contracts
			SET status    = $1,
			    expired_at = now(),
			    version   = version + 1
			WHERE id = $2`, status, id)
	default:
		_, err = s.pool.Exec(ctx, `
			UPDATE interbank_contracts
			SET status  = $1,
			    version = version + 1
			WHERE id = $2`, status, id)
	}
	return err
}
