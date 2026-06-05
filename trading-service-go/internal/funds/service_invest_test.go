package funds

import (
	"strings"
	"testing"

	"github.com/jackc/pgx/v5"
)

// investQuerier routes the QueryRow paths of invest/redeem: fund FOR UPDATE,
// position FOR UPDATE (present or not), the transaction RETURNING id, and the
// EXISTS probe.
func investQuerier(positionExists bool) *fakeQuerier {
	return &fakeQuerier{
		rowFn: func(sql string, _ []any) *fakeRow {
			switch {
			case strings.Contains(sql, "EXISTS"):
				return &fakeRow{vals: []any{true}}
			case strings.Contains(sql, "client_fund_transactions"):
				return &fakeRow{vals: []any{int64(1)}}
			case strings.Contains(sql, "client_fund_positions"):
				if positionExists {
					return &fakeRow{vals: positionRow(1, 3, 1, "0")}
				}
				return &fakeRow{err: pgx.ErrNoRows}
			case strings.Contains(sql, "investment_funds"):
				return &fakeRow{vals: fundRow(1, "Alpha", "100.00")}
			default:
				return &fakeRow{err: pgx.ErrNoRows}
			}
		},
	}
}

func TestInvest_NotFound(t *testing.T) {
	if _, err := fundsSvc(&fakeQuerier{}).Invest(ctx(), 1, 3, dec("200"), "acc"); err == nil {
		t.Error("expected 404")
	}
}

func TestInvest_BelowMin(t *testing.T) {
	// fundRow minimumContribution is 100.00; 50 is below it.
	if _, err := fundsSvc(investQuerier(false)).Invest(ctx(), 1, 3, dec("50"), "acc"); err == nil {
		t.Error("expected below-minimum 404")
	}
}

func TestInvest_Success(t *testing.T) {
	saved, err := fundsSvc(investQuerier(false)).Invest(ctx(), 1, 3, dec("200"), "acc")
	if err != nil || saved == nil || saved.ID != 1 {
		t.Fatalf("got %+v %v", saved, err)
	}
}

func TestBankInvest_Success(t *testing.T) {
	saved, err := fundsSvc(investQuerier(false)).BankInvest(ctx(), 1, dec("200"), "acc")
	if err != nil || saved == nil {
		t.Fatalf("got %+v %v", saved, err)
	}
}

func TestRedeem_NotFound(t *testing.T) {
	if _, err := fundsSvc(&fakeQuerier{}).Redeem(ctx(), 1, 3, dec("10"), "acc"); err == nil {
		t.Error("expected 404")
	}
}

func TestRedeem_NoPosition(t *testing.T) {
	if _, err := fundsSvc(investQuerier(false)).Redeem(ctx(), 1, 3, dec("10"), "acc"); err == nil {
		t.Error("expected no-position 404")
	}
}

func TestRedeem_AmountTooHigh(t *testing.T) {
	// position present but value computes to 0 → any positive redeem exceeds it.
	if _, err := fundsSvc(investQuerier(true)).Redeem(ctx(), 1, 3, dec("10"), "acc"); err == nil {
		t.Error("expected amount-too-high 404")
	}
}

func TestBankRedeem_NoPosition(t *testing.T) {
	if _, err := fundsSvc(investQuerier(false)).BankRedeem(ctx(), 1, dec("10"), "acc"); err == nil {
		t.Error("expected bank no-position 404")
	}
}
