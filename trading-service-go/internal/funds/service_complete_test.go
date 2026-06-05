package funds

import (
	"strings"
	"testing"

	"github.com/jackc/pgx/v5"
)

// sagaQuerier serves a transaction (in the given status), an existing position
// (so UpsertPosition takes the UPDATE/Exec branch), and a fund.
func sagaQuerier(status string) *fakeQuerier {
	return &fakeQuerier{
		execTag: tag("UPDATE 1"),
		rowFn: func(sql string, _ []any) *fakeRow {
			switch {
			case strings.Contains(sql, "client_fund_transactions"):
				return &fakeRow{vals: txRow(1, 3, 1, "50", true, status)}
			case strings.Contains(sql, "client_fund_positions"):
				return &fakeRow{vals: positionRow(1, 3, 1, "100")}
			case strings.Contains(sql, "investment_funds"):
				return &fakeRow{vals: fundRow(1, "Alpha", "100.00")}
			default:
				return &fakeRow{err: pgx.ErrNoRows}
			}
		},
	}
}

func TestCompleteInvest_TxNotFound(t *testing.T) {
	if err := fundsSvc(&fakeQuerier{}).CompleteInvest(ctx(), 1, 3, 1, dec("50")); err != nil {
		t.Fatal(err) // not-found is a no-op skip, not an error
	}
}

func TestCompleteInvest_AlreadyTerminal(t *testing.T) {
	if err := fundsSvc(sagaQuerier(TxStatusCompleted)).CompleteInvest(ctx(), 1, 3, 1, dec("50")); err != nil {
		t.Fatal(err)
	}
}

func TestCompleteInvest_Success(t *testing.T) {
	if err := fundsSvc(sagaQuerier(TxStatusPending)).CompleteInvest(ctx(), 1, 3, 1, dec("50")); err != nil {
		t.Fatal(err)
	}
}

func TestCompleteRedeem_TxNotFound(t *testing.T) {
	if err := fundsSvc(&fakeQuerier{}).CompleteRedeem(ctx(), 1, 3, 1, dec("50")); err != nil {
		t.Fatal(err)
	}
}

func TestCompleteRedeem_Success(t *testing.T) {
	if err := fundsSvc(sagaQuerier(TxStatusPending)).CompleteRedeem(ctx(), 1, 3, 1, dec("50")); err != nil {
		t.Fatal(err)
	}
}

func TestFailTransaction_NotFound(t *testing.T) {
	if err := fundsSvc(&fakeQuerier{}).FailTransaction(ctx(), 1, "boom"); err != nil {
		t.Fatal(err)
	}
}

func TestFailTransaction_AlreadyTerminal(t *testing.T) {
	if err := fundsSvc(sagaQuerier(TxStatusFailed)).FailTransaction(ctx(), 1, "boom"); err != nil {
		t.Fatal(err)
	}
}

func TestFailTransaction_Success(t *testing.T) {
	if err := fundsSvc(sagaQuerier(TxStatusPending)).FailTransaction(ctx(), 1, "boom"); err != nil {
		t.Fatal(err)
	}
}
