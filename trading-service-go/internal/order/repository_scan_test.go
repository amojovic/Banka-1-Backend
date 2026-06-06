package order

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/shopspring/decimal"
)

// ---- fake pgx plumbing (order package copy; extended assign helper) ----

type fakeRow struct {
	vals    []any
	scanErr error
}

func (r *fakeRow) Scan(dest ...any) error {
	if r.scanErr != nil {
		return r.scanErr
	}
	for i, d := range dest {
		if i >= len(r.vals) {
			break
		}
		if err := assignFake(d, r.vals[i]); err != nil {
			return err
		}
	}
	return nil
}

func assignFake(dst, src any) error {
	switch d := dst.(type) {
	case *int64:
		if src == nil {
			*d = 0
		} else {
			*d = src.(int64)
		}
	case *int:
		if src == nil {
			*d = 0
		} else {
			*d = src.(int)
		}
	case *bool:
		if src == nil {
			*d = false
		} else {
			*d = src.(bool)
		}
	case **int64:
		if src == nil {
			*d = nil
		} else {
			switch v := src.(type) {
			case int64:
				*d = &v
			case *int64:
				*d = v
			}
		}
	case **string:
		if src == nil {
			*d = nil
		} else {
			switch v := src.(type) {
			case string:
				*d = &v
			case *string:
				*d = v
			}
		}
	case *string:
		if src == nil {
			*d = ""
		} else {
			switch v := src.(type) {
			case string:
				*d = v
			case *string:
				if v != nil {
					*d = *v
				}
			}
		}
	case *time.Time:
		if src != nil {
			*d = src.(time.Time)
		}
	case **time.Time:
		if src == nil {
			*d = nil
		} else {
			switch v := src.(type) {
			case time.Time:
				*d = &v
			case *time.Time:
				*d = v
			}
		}
	default:
		// ignore unknown types in tests
	}
	return nil
}

type fakeRows struct {
	rows    [][]any
	idx     int
	scanErr error
	errVal  error
}

func (r *fakeRows) Next() bool                                   { return r.idx < len(r.rows) }
func (r *fakeRows) Close()                                       {}
func (r *fakeRows) Err() error                                   { return r.errVal }
func (r *fakeRows) CommandTag() pgconn.CommandTag                { return pgconn.CommandTag{} }
func (r *fakeRows) FieldDescriptions() []pgconn.FieldDescription { return nil }
func (r *fakeRows) Values() ([]any, error)                       { return nil, nil }
func (r *fakeRows) RawValues() [][]byte                          { return nil }
func (r *fakeRows) Conn() *pgx.Conn                              { return nil }
func (r *fakeRows) Scan(dest ...any) error {
	if r.scanErr != nil {
		return r.scanErr
	}
	row := r.rows[r.idx]
	r.idx++
	for i, d := range dest {
		if i >= len(row) {
			break
		}
		if err := assignFake(d, row[i]); err != nil {
			return err
		}
	}
	return nil
}

type fakeDB struct {
	row      *fakeRow
	rows     *fakeRows
	queryErr error
	execErr  error
	execTag  pgconn.CommandTag
}

func (f *fakeDB) QueryRow(_ context.Context, _ string, _ ...any) pgx.Row {
	if f.row != nil {
		return f.row
	}
	return &fakeRow{scanErr: pgx.ErrNoRows}
}
func (f *fakeDB) Query(_ context.Context, _ string, _ ...any) (pgx.Rows, error) {
	if f.queryErr != nil {
		return nil, f.queryErr
	}
	if f.rows != nil {
		return f.rows, nil
	}
	return &fakeRows{}, nil
}
func (f *fakeDB) Exec(_ context.Context, _ string, _ ...any) (pgconn.CommandTag, error) {
	return f.execTag, f.execErr
}

// orderRow builds the value slice in the orderColumns order.
func orderRow(id int64) []any {
	return []any{
		id, int64(7), int64(1), "MARKET", 2, 1, // id..contract_size
		"10", nil, nil, "BUY", "APPROVED", // price, limit, stop, direction, status
		nil, false, time.Now(), 2, false, // approved_by, is_done, last_mod, remaining, after_hours
		false, false, false, int64(5), "0", // exchange_closed, all_or_none, margin, account_id, reserved
		nil, nil, time.Now(), nil, // purchase_for, fund_id, created_at, executed_at
	}
}

func txnRow(id int64) []any {
	return []any{id, int64(1), 2, "10", "20", "1", time.Now()}
}

func recurRow(id int64) []any {
	return []any{id, int64(7), int64(1), "BUY", "BY_QUANTITY", "5", int64(5), "DAILY", time.Now(), true, time.Now()}
}

// ---- scanOrder / FindByID ----

func TestRepo_FindByID_NoRows(t *testing.T) {
	r := &Repository{}
	o, err := r.FindByID(ctx(), &fakeDB{row: &fakeRow{scanErr: pgx.ErrNoRows}}, 1)
	if err != nil || o != nil {
		t.Errorf("got %v,%v want nil,nil", o, err)
	}
}

func TestRepo_FindByID_Success(t *testing.T) {
	r := &Repository{}
	o, err := r.FindByID(ctx(), &fakeDB{row: &fakeRow{vals: orderRow(1)}}, 1)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if o == nil || o.ID != 1 {
		t.Errorf("got %v", o)
	}
}

func TestRepo_FindByID_ScanError(t *testing.T) {
	r := &Repository{}
	_, err := r.FindByID(ctx(), &fakeDB{row: &fakeRow{scanErr: errors.New("boom")}}, 1)
	if err == nil {
		t.Error("expected scan error")
	}
}

func TestRepo_FindByID_BadPrice(t *testing.T) {
	r := &Repository{}
	row := orderRow(1)
	row[6] = "not-a-decimal" // price_per_unit
	_, err := r.FindByID(ctx(), &fakeDB{row: &fakeRow{vals: row}}, 1)
	if err == nil {
		t.Error("expected decimal parse error")
	}
}

func TestRepo_FindByID_BadReserved(t *testing.T) {
	r := &Repository{}
	row := orderRow(1)
	row[20] = "bad" // reserved
	_, err := r.FindByID(ctx(), &fakeDB{row: &fakeRow{vals: row}}, 1)
	if err == nil {
		t.Error("expected reserved parse error")
	}
}

func TestRepo_FindByID_WithLimitAndStop(t *testing.T) {
	r := &Repository{}
	row := orderRow(1)
	row[7] = "5.5" // limit_value
	row[8] = "4.4" // stop_value
	o, err := r.FindByID(ctx(), &fakeDB{row: &fakeRow{vals: row}}, 1)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if o.LimitValue == nil || o.StopValue == nil {
		t.Error("expected limit and stop parsed")
	}
}

func TestRepo_FindByID_BadLimit(t *testing.T) {
	r := &Repository{}
	row := orderRow(1)
	row[7] = "bad"
	_, err := r.FindByID(ctx(), &fakeDB{row: &fakeRow{vals: row}}, 1)
	if err == nil {
		t.Error("expected bad limit error")
	}
}

func TestRepo_FindByID_BadStop(t *testing.T) {
	r := &Repository{}
	row := orderRow(1)
	row[8] = "bad"
	_, err := r.FindByID(ctx(), &fakeDB{row: &fakeRow{vals: row}}, 1)
	if err == nil {
		t.Error("expected bad stop error")
	}
}

func TestRepo_FindByIDForUpdate_NoRows(t *testing.T) {
	r := &Repository{}
	o, err := r.FindByIDForUpdate(ctx(), &fakeDB{row: &fakeRow{scanErr: pgx.ErrNoRows}}, 1)
	if err != nil || o != nil {
		t.Errorf("got %v,%v", o, err)
	}
}

// ---- list reads ----

func TestRepo_FindAll(t *testing.T) {
	r := &Repository{}
	out, err := r.FindAll(ctx(), &fakeDB{rows: &fakeRows{rows: [][]any{orderRow(1), orderRow(2)}}})
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(out) != 2 {
		t.Errorf("len = %d, want 2", len(out))
	}
}

func TestRepo_FindAll_QueryError(t *testing.T) {
	r := &Repository{}
	_, err := r.FindAll(ctx(), &fakeDB{queryErr: errors.New("boom")})
	if err == nil {
		t.Error("expected query error")
	}
}

func TestRepo_FindAll_ScanError(t *testing.T) {
	r := &Repository{}
	_, err := r.FindAll(ctx(), &fakeDB{rows: &fakeRows{rows: [][]any{orderRow(1)}, scanErr: errors.New("boom")}})
	if err == nil {
		t.Error("expected scan error")
	}
}

func TestRepo_FindByStatus(t *testing.T) {
	r := &Repository{}
	out, err := r.FindByStatus(ctx(), &fakeDB{rows: &fakeRows{rows: [][]any{orderRow(1)}}}, "PENDING")
	if err != nil || len(out) != 1 {
		t.Errorf("got %v,%v", out, err)
	}
}

func TestRepo_FindByStatus_QueryError(t *testing.T) {
	r := &Repository{}
	if _, err := r.FindByStatus(ctx(), &fakeDB{queryErr: errors.New("boom")}, "X"); err == nil {
		t.Error("expected error")
	}
}

func TestRepo_FindByUserID(t *testing.T) {
	r := &Repository{}
	out, err := r.FindByUserID(ctx(), &fakeDB{rows: &fakeRows{rows: [][]any{orderRow(1)}}}, 7)
	if err != nil || len(out) != 1 {
		t.Errorf("got %v,%v", out, err)
	}
}

func TestRepo_FindByUserID_QueryError(t *testing.T) {
	r := &Repository{}
	if _, err := r.FindByUserID(ctx(), &fakeDB{queryErr: errors.New("boom")}, 7); err == nil {
		t.Error("expected error")
	}
}

func TestRepo_FindByDirection(t *testing.T) {
	r := &Repository{}
	out, err := r.FindByDirection(ctx(), &fakeDB{rows: &fakeRows{rows: [][]any{orderRow(1)}}}, "BUY")
	if err != nil || len(out) != 1 {
		t.Errorf("got %v,%v", out, err)
	}
}

func TestRepo_FindByDirection_QueryError(t *testing.T) {
	r := &Repository{}
	if _, err := r.FindByDirection(ctx(), &fakeDB{queryErr: errors.New("boom")}, "BUY"); err == nil {
		t.Error("expected error")
	}
}

func TestRepo_FindByUserIDAndDirection(t *testing.T) {
	r := &Repository{}
	out, err := r.FindByUserIDAndDirection(ctx(), &fakeDB{rows: &fakeRows{rows: [][]any{orderRow(1)}}}, 7, "BUY")
	if err != nil || len(out) != 1 {
		t.Errorf("got %v,%v", out, err)
	}
}

func TestRepo_FindByUserIDAndDirection_QueryError(t *testing.T) {
	r := &Repository{}
	if _, err := r.FindByUserIDAndDirection(ctx(), &fakeDB{queryErr: errors.New("boom")}, 7, "BUY"); err == nil {
		t.Error("expected error")
	}
}

func TestRepo_FindByUserIDIn_Empty(t *testing.T) {
	r := &Repository{}
	out, err := r.FindByUserIDIn(ctx(), &fakeDB{}, nil)
	if err != nil || len(out) != 0 {
		t.Errorf("got %v,%v", out, err)
	}
}

func TestRepo_FindByUserIDIn_WithRows(t *testing.T) {
	r := &Repository{}
	out, err := r.FindByUserIDIn(ctx(), &fakeDB{rows: &fakeRows{rows: [][]any{orderRow(1)}}}, []int64{7})
	if err != nil || len(out) != 1 {
		t.Errorf("got %v,%v", out, err)
	}
}

func TestRepo_FindByUserIDIn_QueryError(t *testing.T) {
	r := &Repository{}
	if _, err := r.FindByUserIDIn(ctx(), &fakeDB{queryErr: errors.New("boom")}, []int64{7}); err == nil {
		t.Error("expected error")
	}
}

// ---- writes ----

func TestRepo_Insert(t *testing.T) {
	r := &Repository{}
	o := &Order{PricePerUnit: decimal.RequireFromString("10"), ReservedLimitExposure: decimal.Zero}
	db := &fakeDB{row: &fakeRow{vals: []any{int64(5), time.Now(), time.Now()}}}
	if err := r.Insert(ctx(), db, o); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if o.ID != 5 {
		t.Errorf("id = %d, want 5", o.ID)
	}
}

func TestRepo_Insert_WithLimitStop(t *testing.T) {
	r := &Repository{}
	o := &Order{PricePerUnit: decimal.RequireFromString("10"), LimitValue: dp("5"), StopValue: dp("4"), ReservedLimitExposure: decimal.Zero}
	db := &fakeDB{row: &fakeRow{vals: []any{int64(6), time.Now(), time.Now()}}}
	if err := r.Insert(ctx(), db, o); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
}

func TestRepo_Insert_Error(t *testing.T) {
	r := &Repository{}
	o := &Order{PricePerUnit: decimal.RequireFromString("10"), ReservedLimitExposure: decimal.Zero}
	db := &fakeDB{row: &fakeRow{scanErr: errors.New("boom")}}
	if err := r.Insert(ctx(), db, o); err == nil {
		t.Error("expected insert error")
	}
}

func TestRepo_Update(t *testing.T) {
	r := &Repository{}
	o := &Order{ID: 1, PricePerUnit: decimal.RequireFromString("10"), ReservedLimitExposure: decimal.Zero}
	db := &fakeDB{row: &fakeRow{vals: []any{time.Now()}}}
	if err := r.Update(ctx(), db, o); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
}

func TestRepo_Update_WithLimitStop(t *testing.T) {
	r := &Repository{}
	o := &Order{ID: 1, PricePerUnit: decimal.RequireFromString("10"), LimitValue: dp("5"), StopValue: dp("4"), ReservedLimitExposure: decimal.Zero}
	db := &fakeDB{row: &fakeRow{vals: []any{time.Now()}}}
	if err := r.Update(ctx(), db, o); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
}

func TestRepo_Update_Error(t *testing.T) {
	r := &Repository{}
	o := &Order{ID: 1, PricePerUnit: decimal.RequireFromString("10"), ReservedLimitExposure: decimal.Zero}
	db := &fakeDB{row: &fakeRow{scanErr: errors.New("boom")}}
	if err := r.Update(ctx(), db, o); err == nil {
		t.Error("expected update error")
	}
}

func TestRepo_InsertTransaction(t *testing.T) {
	r := &Repository{}
	tx := &Transaction{PricePerUnit: decimal.RequireFromString("10"), TotalPrice: decimal.RequireFromString("20"), Commission: decimal.RequireFromString("1")}
	db := &fakeDB{row: &fakeRow{vals: []any{int64(3), time.Now()}}}
	if err := r.InsertTransaction(ctx(), db, tx); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if tx.ID != 3 {
		t.Errorf("id = %d", tx.ID)
	}
}

func TestRepo_InsertTransaction_Error(t *testing.T) {
	r := &Repository{}
	tx := &Transaction{PricePerUnit: decimal.RequireFromString("10"), TotalPrice: decimal.RequireFromString("20"), Commission: decimal.RequireFromString("1")}
	db := &fakeDB{row: &fakeRow{scanErr: errors.New("boom")}}
	if err := r.InsertTransaction(ctx(), db, tx); err == nil {
		t.Error("expected error")
	}
}

// ---- transaction reads ----

func TestRepo_FindTransactionsByOrderID(t *testing.T) {
	r := &Repository{}
	out, err := r.FindTransactionsByOrderID(ctx(), &fakeDB{rows: &fakeRows{rows: [][]any{txnRow(1)}}}, 1)
	if err != nil || len(out) != 1 {
		t.Errorf("got %v,%v", out, err)
	}
}

func TestRepo_FindTransactionsByOrderID_QueryError(t *testing.T) {
	r := &Repository{}
	if _, err := r.FindTransactionsByOrderID(ctx(), &fakeDB{queryErr: errors.New("boom")}, 1); err == nil {
		t.Error("expected error")
	}
}

func TestRepo_FindTransactionsByOrderID_BadDecimal(t *testing.T) {
	r := &Repository{}
	row := txnRow(1)
	row[3] = "bad" // price_per_unit
	if _, err := r.FindTransactionsByOrderID(ctx(), &fakeDB{rows: &fakeRows{rows: [][]any{row}}}, 1); err == nil {
		t.Error("expected decimal error")
	}
}

func TestRepo_FindTransactionsByOrderID_BadTotal(t *testing.T) {
	r := &Repository{}
	row := txnRow(1)
	row[4] = "bad"
	if _, err := r.FindTransactionsByOrderID(ctx(), &fakeDB{rows: &fakeRows{rows: [][]any{row}}}, 1); err == nil {
		t.Error("expected total error")
	}
}

func TestRepo_FindTransactionsByOrderID_BadCommission(t *testing.T) {
	r := &Repository{}
	row := txnRow(1)
	row[5] = "bad"
	if _, err := r.FindTransactionsByOrderID(ctx(), &fakeDB{rows: &fakeRows{rows: [][]any{row}}}, 1); err == nil {
		t.Error("expected commission error")
	}
}

func TestRepo_FindTransactionsBetween_Empty(t *testing.T) {
	r := &Repository{}
	out, err := r.FindTransactionsByOrderIDsAndTimestampBetween(ctx(), &fakeDB{}, nil, time.Now(), time.Now())
	if err != nil || len(out) != 0 {
		t.Errorf("got %v,%v", out, err)
	}
}

func TestRepo_FindTransactionsBetween_WithRows(t *testing.T) {
	r := &Repository{}
	out, err := r.FindTransactionsByOrderIDsAndTimestampBetween(ctx(), &fakeDB{rows: &fakeRows{rows: [][]any{txnRow(1)}}}, []int64{1}, time.Now(), time.Now())
	if err != nil || len(out) != 1 {
		t.Errorf("got %v,%v", out, err)
	}
}

func TestRepo_FindTransactionsBetween_QueryError(t *testing.T) {
	r := &Repository{}
	if _, err := r.FindTransactionsByOrderIDsAndTimestampBetween(ctx(), &fakeDB{queryErr: errors.New("boom")}, []int64{1}, time.Now(), time.Now()); err == nil {
		t.Error("expected error")
	}
}

func TestRepo_FindTransactionsBefore_Empty(t *testing.T) {
	r := &Repository{}
	out, err := r.FindTransactionsByOrderIDsAndTimestampBefore(ctx(), &fakeDB{}, nil, time.Now())
	if err != nil || len(out) != 0 {
		t.Errorf("got %v,%v", out, err)
	}
}

func TestRepo_FindTransactionsBefore_WithRows(t *testing.T) {
	r := &Repository{}
	out, err := r.FindTransactionsByOrderIDsAndTimestampBefore(ctx(), &fakeDB{rows: &fakeRows{rows: [][]any{txnRow(1)}}}, []int64{1}, time.Now())
	if err != nil || len(out) != 1 {
		t.Errorf("got %v,%v", out, err)
	}
}

func TestRepo_FindTransactionsBefore_QueryError(t *testing.T) {
	r := &Repository{}
	if _, err := r.FindTransactionsByOrderIDsAndTimestampBefore(ctx(), &fakeDB{queryErr: errors.New("boom")}, []int64{1}, time.Now()); err == nil {
		t.Error("expected error")
	}
}

func TestRepo_BankHeldBuyQuantity(t *testing.T) {
	r := &Repository{}
	db := &fakeDB{row: &fakeRow{vals: []any{int64(42)}}}
	sum, err := r.BankHeldBuyQuantity(ctx(), db, 7, 1)
	if err != nil || sum != 42 {
		t.Errorf("got %d,%v want 42,nil", sum, err)
	}
}

func TestRepo_BankHeldBuyQuantity_Error(t *testing.T) {
	r := &Repository{}
	db := &fakeDB{row: &fakeRow{scanErr: errors.New("boom")}}
	if _, err := r.BankHeldBuyQuantity(ctx(), db, 7, 1); err == nil {
		t.Error("expected error")
	}
}

// ---- recurring repository ----

func TestRepo_FindRecurringByUserID(t *testing.T) {
	r := &Repository{}
	out, err := r.FindRecurringByUserID(ctx(), &fakeDB{rows: &fakeRows{rows: [][]any{recurRow(1)}}}, 7)
	if err != nil || len(out) != 1 {
		t.Errorf("got %v,%v", out, err)
	}
}

func TestRepo_FindRecurringByUserID_QueryError(t *testing.T) {
	r := &Repository{}
	if _, err := r.FindRecurringByUserID(ctx(), &fakeDB{queryErr: errors.New("boom")}, 7); err == nil {
		t.Error("expected error")
	}
}

func TestRepo_FindRecurringByUserID_BadDecimal(t *testing.T) {
	r := &Repository{}
	row := recurRow(1)
	row[5] = "bad" // value
	if _, err := r.FindRecurringByUserID(ctx(), &fakeDB{rows: &fakeRows{rows: [][]any{row}}}, 7); err == nil {
		t.Error("expected decimal error")
	}
}

func TestRepo_FindRecurringByID_NoRows(t *testing.T) {
	r := &Repository{}
	ro, err := r.FindRecurringByID(ctx(), &fakeDB{row: &fakeRow{scanErr: pgx.ErrNoRows}}, 1)
	if err != nil || ro != nil {
		t.Errorf("got %v,%v", ro, err)
	}
}

func TestRepo_FindRecurringByID_Success(t *testing.T) {
	r := &Repository{}
	ro, err := r.FindRecurringByID(ctx(), &fakeDB{row: &fakeRow{vals: recurRow(1)}}, 1)
	if err != nil || ro == nil {
		t.Errorf("got %v,%v", ro, err)
	}
}

func TestRepo_FindDueRecurring(t *testing.T) {
	r := &Repository{}
	out, err := r.FindDueRecurring(ctx(), &fakeDB{rows: &fakeRows{rows: [][]any{recurRow(1)}}}, time.Now())
	if err != nil || len(out) != 1 {
		t.Errorf("got %v,%v", out, err)
	}
}

func TestRepo_FindDueRecurring_QueryError(t *testing.T) {
	r := &Repository{}
	if _, err := r.FindDueRecurring(ctx(), &fakeDB{queryErr: errors.New("boom")}, time.Now()); err == nil {
		t.Error("expected error")
	}
}

func TestRepo_InsertRecurring(t *testing.T) {
	r := &Repository{}
	ro := &RecurringOrder{Value: decimal.RequireFromString("5")}
	db := &fakeDB{row: &fakeRow{vals: []any{int64(8), time.Now()}}}
	if err := r.InsertRecurring(ctx(), db, ro); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if ro.ID != 8 {
		t.Errorf("id = %d", ro.ID)
	}
}

func TestRepo_InsertRecurring_Error(t *testing.T) {
	r := &Repository{}
	ro := &RecurringOrder{Value: decimal.RequireFromString("5")}
	db := &fakeDB{row: &fakeRow{scanErr: errors.New("boom")}}
	if err := r.InsertRecurring(ctx(), db, ro); err == nil {
		t.Error("expected error")
	}
}

func TestRepo_SetRecurringActive(t *testing.T) {
	r := &Repository{}
	ok, err := r.SetRecurringActive(ctx(), &fakeDB{execTag: pgconn.NewCommandTag("UPDATE 1")}, 1, true)
	if err != nil || !ok {
		t.Errorf("got %v,%v", ok, err)
	}
}

func TestRepo_SetRecurringActive_NoRows(t *testing.T) {
	r := &Repository{}
	ok, err := r.SetRecurringActive(ctx(), &fakeDB{execTag: pgconn.NewCommandTag("UPDATE 0")}, 1, true)
	if err != nil || ok {
		t.Errorf("got %v,%v", ok, err)
	}
}

func TestRepo_SetRecurringActive_Error(t *testing.T) {
	r := &Repository{}
	if _, err := r.SetRecurringActive(ctx(), &fakeDB{execErr: errors.New("boom")}, 1, true); err == nil {
		t.Error("expected error")
	}
}

func TestRepo_DeleteRecurring(t *testing.T) {
	r := &Repository{}
	if err := r.DeleteRecurring(ctx(), &fakeDB{}, 1); err != nil {
		t.Errorf("unexpected: %v", err)
	}
}

func TestRepo_DeleteRecurring_Error(t *testing.T) {
	r := &Repository{}
	if err := r.DeleteRecurring(ctx(), &fakeDB{execErr: errors.New("boom")}, 1); err == nil {
		t.Error("expected error")
	}
}

func TestRepo_UpdateRecurringNextRun(t *testing.T) {
	r := &Repository{}
	if err := r.UpdateRecurringNextRun(ctx(), &fakeDB{}, 1, time.Now()); err != nil {
		t.Errorf("unexpected: %v", err)
	}
}

func TestRepo_UpdateRecurringNextRun_Error(t *testing.T) {
	r := &Repository{}
	if err := r.UpdateRecurringNextRun(ctx(), &fakeDB{execErr: errors.New("boom")}, 1, time.Now()); err == nil {
		t.Error("expected error")
	}
}

// ---- NewRepository / Pool / fortest ----

func TestNewRepository_PoolNil(t *testing.T) {
	r := NewRepository(nil)
	if r.Pool() != nil {
		t.Error("expected nil pool")
	}
}

func TestNewRepositoryForTest(t *testing.T) {
	r := NewRepositoryForTest(nil)
	if r == nil {
		t.Fatal("expected repository")
	}
}
