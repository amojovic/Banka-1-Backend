package service

import (
	"context"
	"database/sql"
	"errors"
	"strings"

	"banka1/banking-core-service-go/internal/decimal"
	"banka1/banking-core-service-go/internal/uuid"
)

const (
	InterbankHeld      = "HELD"
	InterbankCommitted = "COMMITTED"
	InterbankReleased  = "RELEASED"
)

// MonasConverter is the FX seam used by CreditMonas for cross-currency credits.
// *MarketClient satisfies it via ConvertNoCommission. Kept as an interface so the
// FX path is unit-testable without a live market-service.
type MonasConverter interface {
	ConvertNoCommission(ctx context.Context, amount decimal.Decimal, from, to string) (ConversionResponse, error)
}

type InterbankService struct {
	db       *sql.DB
	accounts *AccountService
	market   MonasConverter
}

type ReserveMonasRequest struct {
	AccountNum           string          `json:"accountNum"`
	Currency             string          `json:"currency"`
	Amount               decimal.Decimal `json:"amount"`
	TransactionIDRouting int             `json:"transactionIdRouting"`
	TransactionIDLocal   string          `json:"transactionIdLocal"`
}

type ReserveMonasResponse struct {
	ReservationID string `json:"reservationId"`
}

// CreditMonasRequest is the body of POST /internal/interbank/credit-monas.
// The credit is keyed for idempotency by (TxIDRouting, TxIDLocal): a replay with
// the same key is a no-op that returns the originally-credited result.
type CreditMonasRequest struct {
	AccountNum  string          `json:"accountNum"`
	Currency    string          `json:"currency"`
	Amount      decimal.Decimal `json:"amount"`
	TxIDRouting int             `json:"txIdRouting"`
	TxIDLocal   string          `json:"txIdLocal"`
}

// CreditMonasResponse reports what was actually credited. CreditedAmount equals
// Amount when account.currency == posting.currency, otherwise it is the FX-converted
// amount in the account's currency. Idempotent is true when the call was a replay.
type CreditMonasResponse struct {
	AccountNumber    string          `json:"accountNumber"`
	PostingCurrency  string          `json:"postingCurrency"`
	PostingAmount    decimal.Decimal `json:"postingAmount"`
	CreditedCurrency string          `json:"creditedCurrency"`
	CreditedAmount   decimal.Decimal `json:"creditedAmount"`
	Idempotent       bool            `json:"idempotent"`
}

type AccountResolveResponse struct {
	OwnerType        string          `json:"ownerType"`
	OwnerID          int64           `json:"ownerId"`
	Currency         string          `json:"currency"`
	AvailableBalance decimal.Decimal `json:"availableBalance"`
}

type AccountByOwnerResponse struct {
	AccountNumber string `json:"accountNumber"`
}

func NewInterbankService(db *sql.DB, accounts *AccountService, market MonasConverter) *InterbankService {
	return &InterbankService{db: db, accounts: accounts, market: market}
}

// CreditMonas credits a recipient/seller account during inter-bank 2PC commit
// (protocol §2.8.4). It is the settlement-side counterpart of ReserveMonas/CommitReservation
// (which handle the sender's debit). Two guarantees make it safe to call from the
// 2PC executor's commit step:
//
//   - Idempotent by (TxIDRouting, TxIDLocal): a replay (e.g. after a crash between
//     credit and status-flip) returns the originally-credited result without double-crediting,
//     enforced by the interbank_credits unique constraint.
//   - FX-aware: when the recipient account's currency differs from the posting currency,
//     the amount is converted via the same bank-pool-leg mechanism used by internal
//     transfers (InternalService.Transfer) — the bank's pool account of the credited
//     currency is debited and the recipient is credited the converted amount. No FX
//     commission is charged: this is settlement of an already-balanced inter-bank tx.
func (s *InterbankService) CreditMonas(ctx context.Context, req CreditMonasRequest) (CreditMonasResponse, error) {
	if req.AccountNum == "" || req.Currency == "" || req.TxIDLocal == "" {
		return CreditMonasResponse{}, BadRequest("accountNum, currency i txIdLocal su obavezni")
	}
	if req.Amount.Sign() <= 0 {
		return CreditMonasResponse{}, BadRequest("Amount must be positive")
	}

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return CreditMonasResponse{}, err
	}
	defer tx.Rollback()

	// Idempotency: if this (routing, local) was already credited, return that result.
	if existing, found, err := s.loadCreditForUpdate(ctx, tx, req.TxIDRouting, req.TxIDLocal); err != nil {
		return CreditMonasResponse{}, err
	} else if found {
		existing.Idempotent = true
		return existing, tx.Commit()
	}

	account, err := s.accounts.getByNumber(ctx, tx, req.AccountNum, true)
	if err != nil {
		return CreditMonasResponse{}, err
	}

	creditedAmount := req.Amount
	if !strings.EqualFold(account.Currency, req.Currency) {
		if s.market == nil {
			return CreditMonasResponse{}, BadRequest("FX konverzija nije dostupna za %s→%s", req.Currency, account.Currency)
		}
		conv, err := s.market.ConvertNoCommission(ctx, req.Amount, req.Currency, account.Currency)
		if err != nil {
			return CreditMonasResponse{}, err
		}
		creditedAmount = conv.ToAmount
		if creditedAmount.Sign() <= 0 {
			return CreditMonasResponse{}, BadRequest("Konvertovani iznos mora biti veci od 0")
		}
		// Bank-pool leg: the recipient's currency is sourced from the bank's pool
		// account of that currency (mirrors InternalService.Transfer cross-ccy path).
		bank, err := s.accounts.getByOwnerAndCurrency(ctx, tx, -1, account.Currency, true)
		if err != nil {
			return CreditMonasResponse{}, err
		}
		if err := s.accounts.DebitTx(ctx, tx, bank.AccountNumber, creditedAmount, bank.OwnerID); err != nil {
			return CreditMonasResponse{}, err
		}
	}

	if err := s.accounts.CreditTx(ctx, tx, account.AccountNumber, creditedAmount, account.OwnerID); err != nil {
		return CreditMonasResponse{}, err
	}

	if _, err := tx.ExecContext(ctx, `
INSERT INTO interbank_credits (
    transaction_id_routing, transaction_id_local,
    account_number, posting_currency, posting_amount,
    credited_currency, credited_amount
) VALUES ($1, $2, $3, $4, $5, $6, $7)
`, req.TxIDRouting, req.TxIDLocal, account.AccountNumber, req.Currency, req.Amount, account.Currency, creditedAmount); err != nil {
		return CreditMonasResponse{}, err
	}

	if err := tx.Commit(); err != nil {
		return CreditMonasResponse{}, err
	}
	return CreditMonasResponse{
		AccountNumber:    account.AccountNumber,
		PostingCurrency:  req.Currency,
		PostingAmount:    req.Amount,
		CreditedCurrency: account.Currency,
		CreditedAmount:   creditedAmount,
		Idempotent:       false,
	}, nil
}

// loadCreditForUpdate looks up a prior credit for (routing, local) and locks the row.
// Returns (result, found, err). When found, the returned result mirrors the stored credit.
func (s *InterbankService) loadCreditForUpdate(ctx context.Context, tx *sql.Tx, routing int, local string) (CreditMonasResponse, bool, error) {
	var out CreditMonasResponse
	err := tx.QueryRowContext(ctx, `
SELECT account_number, posting_currency, posting_amount, credited_currency, credited_amount
  FROM interbank_credits
 WHERE transaction_id_routing = $1 AND transaction_id_local = $2
 FOR UPDATE
`, routing, local).Scan(&out.AccountNumber, &out.PostingCurrency, &out.PostingAmount, &out.CreditedCurrency, &out.CreditedAmount)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return CreditMonasResponse{}, false, nil
		}
		return CreditMonasResponse{}, false, err
	}
	return out, true, nil
}

func (s *InterbankService) ReserveMonas(ctx context.Context, req ReserveMonasRequest) (string, error) {
	if req.AccountNum == "" || req.Currency == "" || req.TransactionIDLocal == "" {
		return "", BadRequest("accountNum, currency i transactionIdLocal su obavezni")
	}
	if req.Amount.Sign() <= 0 {
		return "", BadRequest("Amount must be positive")
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return "", err
	}
	defer tx.Rollback()

	account, err := s.accounts.getByNumber(ctx, tx, req.AccountNum, true)
	if err != nil {
		return "", err
	}
	if account.Currency != req.Currency {
		return "", BadRequest("Currency mismatch: account=%s requested=%s", account.Currency, req.Currency)
	}
	if account.AvailableBalance.Cmp(req.Amount) < 0 {
		return "", Conflict("ERR_INSUFFICIENT_ASSET", "Nedovoljno raspolozivo stanje", "Insufficient available balance: have=%s need=%s", account.AvailableBalance.String(), req.Amount.String())
	}
	if _, err := tx.ExecContext(ctx, `
UPDATE account_table
   SET raspolozivo_stanje = raspolozivo_stanje - $1,
       version = COALESCE(version, 0) + 1,
       updated_at = now()
 WHERE id = $2
`, req.Amount, account.ID); err != nil {
		return "", err
	}

	reservationID, err := uuid.New()
	if err != nil {
		return "", err
	}
	if _, err := tx.ExecContext(ctx, `
INSERT INTO interbank_reservations (
    reservation_id, transaction_id_routing, transaction_id_local,
    account_number, currency, amount, status
) VALUES ($1::uuid, $2, $3, $4, $5, $6, 'HELD')
`, reservationID, req.TransactionIDRouting, req.TransactionIDLocal, req.AccountNum, req.Currency, req.Amount); err != nil {
		return "", err
	}
	if err := tx.Commit(); err != nil {
		return "", err
	}
	return reservationID, nil
}

func (s *InterbankService) CommitReservation(ctx context.Context, reservationID string) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	res, err := s.loadReservationForUpdate(ctx, tx, reservationID)
	if err != nil {
		return err
	}
	if res.Status == InterbankCommitted {
		return nil
	}
	if res.Status != InterbankHeld {
		return Conflict("ERR_INVALID_RESERVATION_STATE", "Neispravno stanje rezervacije", "Cannot commit reservation %s in state %s", reservationID, res.Status)
	}
	account, err := s.accounts.getByNumber(ctx, tx, res.AccountNumber, true)
	if err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
UPDATE account_table
   SET stanje = stanje - $1,
       version = COALESCE(version, 0) + 1,
       updated_at = now()
 WHERE id = $2
`, res.Amount, account.ID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
UPDATE interbank_reservations
   SET status = 'COMMITTED', finalized_at = NOW()
 WHERE reservation_id = $1::uuid
`, reservationID); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *InterbankService) ReleaseReservation(ctx context.Context, reservationID string) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	res, err := s.loadReservationForUpdate(ctx, tx, reservationID)
	if err != nil {
		return err
	}
	if res.Status == InterbankReleased {
		return nil
	}
	if res.Status == InterbankCommitted {
		return Conflict("ERR_INVALID_RESERVATION_STATE", "Neispravno stanje rezervacije", "Cannot release reservation %s - already COMMITTED", reservationID)
	}
	account, err := s.accounts.getByNumber(ctx, tx, res.AccountNumber, true)
	if err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
UPDATE account_table
   SET raspolozivo_stanje = raspolozivo_stanje + $1,
       version = COALESCE(version, 0) + 1,
       updated_at = now()
 WHERE id = $2
`, res.Amount, account.ID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
UPDATE interbank_reservations
   SET status = 'RELEASED', finalized_at = NOW()
 WHERE reservation_id = $1::uuid
`, reservationID); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *InterbankService) AccountByOwner(ctx context.Context, ownerID int64, currency string) (AccountByOwnerResponse, error) {
	acc, err := s.accounts.getByOwnerAndCurrency(ctx, s.db, ownerID, currency, false)
	if err != nil {
		return AccountByOwnerResponse{}, err
	}
	return AccountByOwnerResponse{AccountNumber: acc.AccountNumber}, nil
}

func (s *InterbankService) ResolveAccount(ctx context.Context, accountNumber string) (AccountResolveResponse, error) {
	acc, err := s.accounts.getByNumber(ctx, s.db, accountNumber, false)
	if err != nil {
		return AccountResolveResponse{}, err
	}
	return AccountResolveResponse{
		OwnerType:        resolveOwnerType(acc.OwnerID),
		OwnerID:          acc.OwnerID,
		Currency:         acc.Currency,
		AvailableBalance: acc.AvailableBalance,
	}, nil
}

type interbankReservationRow struct {
	AccountNumber string
	Amount        decimal.Decimal
	Status        string
}

func (s *InterbankService) loadReservationForUpdate(ctx context.Context, tx *sql.Tx, reservationID string) (interbankReservationRow, error) {
	var out interbankReservationRow
	err := tx.QueryRowContext(ctx, `
SELECT account_number, amount, status
  FROM interbank_reservations
 WHERE reservation_id = $1::uuid
 FOR UPDATE
`, reservationID).Scan(&out.AccountNumber, &out.Amount, &out.Status)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return interbankReservationRow{}, NotFound("Reservation not found: %s", reservationID)
		}
		return interbankReservationRow{}, err
	}
	return out, nil
}

func resolveOwnerType(ownerID int64) string {
	switch ownerID {
	case -1:
		return "BANK"
	case -2:
		return "STATE"
	case -3:
		return "EXCHANGE"
	default:
		return "CLIENT"
	}
}
