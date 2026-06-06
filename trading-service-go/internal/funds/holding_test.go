package funds

import (
	"errors"
	"testing"

	"banka1/trading-service-go/internal/api"
	"github.com/jackc/pgx/v5"
)

func TestNewHoldingService(t *testing.T) {
	if NewHoldingService(nil, nil, discardLogger()) == nil {
		t.Error("nil")
	}
}

func TestItoa(t *testing.T) {
	if itoa(-1000) != "-1000" {
		t.Errorf("got %s", itoa(-1000))
	}
}

func TestHolding_AddOrUpdate_BadInput(t *testing.T) {
	svc := NewHoldingServiceForTest(NewRepositoryForTest(&fakeQuerier{}), okMarket(), discardLogger())
	if _, err := svc.AddOrUpdate(ctx(), nil, 1, "AAPL", 0, dec("1")); err == nil {
		t.Error("expected error for qty<=0")
	}
	if _, err := svc.AddOrUpdate(ctx(), nil, 1, "AAPL", 1, dec("0")); err == nil {
		t.Error("expected error for price<=0")
	}
}

func TestHolding_AddOrUpdate_Insert(t *testing.T) {
	q := &fakeQuerier{
		rowFn: func(sql string, _ []any) *fakeRow {
			if contains(sql, "SELECT") {
				return &fakeRow{err: pgx.ErrNoRows} // no existing holding
			}
			return &fakeRow{vals: []any{int64(3), int64(0)}} // insert RETURNING
		},
	}
	svc := NewHoldingServiceForTest(NewRepositoryForTest(q), okMarket(), discardLogger())
	h, err := svc.AddOrUpdate(ctx(), q, 1, "AAPL", 5, dec("10.0000"))
	if err != nil {
		t.Fatal(err)
	}
	if h.Quantity != 5 || h.ID != 3 {
		t.Errorf("holding wrong: %+v", h)
	}
}

func TestHolding_AddOrUpdate_WeightedAverage(t *testing.T) {
	q := &fakeQuerier{
		rowFn: func(sql string, _ []any) *fakeRow {
			return &fakeRow{vals: holdingRow(7, 1, "AAPL", 10, "100.0000")}
		},
		execTag: tag("UPDATE 1"),
	}
	svc := NewHoldingServiceForTest(NewRepositoryForTest(q), okMarket(), discardLogger())
	// 10@100 + 10@200 → 20@150
	h, err := svc.AddOrUpdate(ctx(), q, 1, "AAPL", 10, dec("200.0000"))
	if err != nil {
		t.Fatal(err)
	}
	if h.Quantity != 20 || !h.AvgUnitPrice.Equal(dec("150.0000")) {
		t.Errorf("weighted avg wrong: qty=%d avg=%v", h.Quantity, h.AvgUnitPrice)
	}
}

func TestHolding_AddOrUpdate_FindError(t *testing.T) {
	boom := errors.New("db")
	q := &fakeQuerier{rowFn: func(_ string, _ []any) *fakeRow { return &fakeRow{err: boom} }}
	svc := NewHoldingServiceForTest(NewRepositoryForTest(q), okMarket(), discardLogger())
	if _, err := svc.AddOrUpdate(ctx(), q, 1, "AAPL", 1, dec("1")); !errors.Is(err, boom) {
		t.Errorf("got %v", err)
	}
}

func TestHolding_AddOrUpdate_InsertError(t *testing.T) {
	boom := errors.New("insert")
	q := &fakeQuerier{
		rowFn: func(sql string, _ []any) *fakeRow {
			if contains(sql, "SELECT") {
				return &fakeRow{err: pgx.ErrNoRows}
			}
			return &fakeRow{err: boom}
		},
	}
	svc := NewHoldingServiceForTest(NewRepositoryForTest(q), okMarket(), discardLogger())
	if _, err := svc.AddOrUpdate(ctx(), q, 1, "AAPL", 1, dec("1")); err == nil {
		t.Error("expected insert error")
	}
}

func TestHolding_AddOrUpdate_UpdateError(t *testing.T) {
	q := &fakeQuerier{
		rowFn:   func(_ string, _ []any) *fakeRow { return &fakeRow{vals: holdingRow(7, 1, "AAPL", 10, "100.0000")} },
		execErr: errors.New("update"),
	}
	svc := NewHoldingServiceForTest(NewRepositoryForTest(q), okMarket(), discardLogger())
	if _, err := svc.AddOrUpdate(ctx(), q, 1, "AAPL", 1, dec("1")); err == nil {
		t.Error("expected update error")
	}
}

func TestHolding_Reduce_BadInput(t *testing.T) {
	svc := NewHoldingServiceForTest(NewRepositoryForTest(&fakeQuerier{}), okMarket(), discardLogger())
	if _, err := svc.Reduce(ctx(), nil, 1, "AAPL", 0); err == nil {
		t.Error("expected error for reduceBy<=0")
	}
}

func TestHolding_Reduce_NotFound(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{err: pgx.ErrNoRows}}
	svc := NewHoldingServiceForTest(NewRepositoryForTest(q), okMarket(), discardLogger())
	_, err := svc.Reduce(ctx(), q, 1, "AAPL", 5)
	var oe *api.DomainError
	if !errors.As(err, &oe) || oe.Shape != api.ShapeOtc {
		t.Errorf("expected OTC-shaped DomainError, got %v", err)
	}
}

func TestHolding_Reduce_Insufficient(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{vals: holdingRow(7, 1, "AAPL", 3, "100.0000")}}
	svc := NewHoldingServiceForTest(NewRepositoryForTest(q), okMarket(), discardLogger())
	if _, err := svc.Reduce(ctx(), q, 1, "AAPL", 5); err == nil {
		t.Error("expected insufficient error")
	}
}

func TestHolding_Reduce_PartialAndSoftDelete(t *testing.T) {
	q := &fakeQuerier{
		rowFn:   func(_ string, _ []any) *fakeRow { return &fakeRow{vals: holdingRow(7, 1, "AAPL", 5, "100.0000")} },
		execTag: tag("UPDATE 1"),
	}
	svc := NewHoldingServiceForTest(NewRepositoryForTest(q), okMarket(), discardLogger())
	// reduce all 5 → quantity 0 → soft-delete
	h, err := svc.Reduce(ctx(), q, 1, "AAPL", 5)
	if err != nil {
		t.Fatal(err)
	}
	if h.Quantity != 0 || !h.Deleted {
		t.Errorf("expected soft delete, got qty=%d deleted=%v", h.Quantity, h.Deleted)
	}
}

func TestHolding_Reduce_Partial(t *testing.T) {
	q := &fakeQuerier{
		rowFn:   func(_ string, _ []any) *fakeRow { return &fakeRow{vals: holdingRow(7, 1, "AAPL", 5, "100.0000")} },
		execTag: tag("UPDATE 1"),
	}
	svc := NewHoldingServiceForTest(NewRepositoryForTest(q), okMarket(), discardLogger())
	h, err := svc.Reduce(ctx(), q, 1, "AAPL", 2)
	if err != nil {
		t.Fatal(err)
	}
	if h.Quantity != 3 || h.Deleted {
		t.Errorf("expected qty 3 not deleted, got qty=%d deleted=%v", h.Quantity, h.Deleted)
	}
}

func TestHolding_Reduce_UpdateError(t *testing.T) {
	q := &fakeQuerier{
		rowFn:   func(_ string, _ []any) *fakeRow { return &fakeRow{vals: holdingRow(7, 1, "AAPL", 5, "100.0000")} },
		execErr: errors.New("update"),
	}
	svc := NewHoldingServiceForTest(NewRepositoryForTest(q), okMarket(), discardLogger())
	if _, err := svc.Reduce(ctx(), q, 1, "AAPL", 2); err == nil {
		t.Error("expected update error")
	}
}

func TestHolding_List(t *testing.T) {
	q := &fakeQuerier{rows: &fakeRows{data: [][]any{holdingRow(1, 1, "AAPL", 10, "100.0000")}}}
	svc := NewHoldingServiceForTest(NewRepositoryForTest(q), okMarket(), discardLogger())
	out, err := svc.List(ctx(), 1)
	if err != nil || len(out) != 1 {
		t.Fatalf("got %d %v", len(out), err)
	}
}

func TestHolding_EnrichedHoldings_Empty(t *testing.T) {
	q := &fakeQuerier{rows: &fakeRows{}}
	svc := NewHoldingServiceForTest(NewRepositoryForTest(q), okMarket(), discardLogger())
	out, err := svc.EnrichedHoldings(ctx(), 1)
	if err != nil || len(out) != 0 {
		t.Fatalf("expected empty, got %d %v", len(out), err)
	}
}

func TestHolding_EnrichedHoldings_ListError(t *testing.T) {
	q := &fakeQuerier{queryErr: errors.New("db")}
	svc := NewHoldingServiceForTest(NewRepositoryForTest(q), okMarket(), discardLogger())
	if _, err := svc.EnrichedHoldings(ctx(), 1); err == nil {
		t.Error("expected error")
	}
}

func TestHolding_EnrichedHoldings_WithSnapshot(t *testing.T) {
	q := &fakeQuerier{rows: &fakeRows{data: [][]any{holdingRow(1, 1, "AAPL", 10, "100.0000")}}}
	// market returns a snapshot with price/change/volume for AAPL.
	market := newMarket(&routeStub{routes: map[string]stubResp{
		"GET /stocks/price-feed/current": {200, `[{"ticker":"AAPL","currentPrice":"123.45","changePercent":"1.2","volume":999}]`},
	}})
	svc := NewHoldingServiceForTest(NewRepositoryForTest(q), market, discardLogger())
	out, err := svc.EnrichedHoldings(ctx(), 1)
	if err != nil || len(out) != 1 {
		t.Fatalf("got %d %v", len(out), err)
	}
	if out[0].Price == nil || !out[0].Price.Equal(dec("123.45")) {
		t.Errorf("price = %v", out[0].Price)
	}
	if out[0].Volume != 999 {
		t.Errorf("volume = %d", out[0].Volume)
	}
	if !out[0].InitialMarginCost.Equal(dec("1000.0000")) {
		t.Errorf("initialMargin = %v", out[0].InitialMarginCost)
	}
}

func TestHolding_CalculateHoldingsValue_Empty(t *testing.T) {
	q := &fakeQuerier{rows: &fakeRows{}}
	svc := NewHoldingServiceForTest(NewRepositoryForTest(q), okMarket(), discardLogger())
	if v := svc.CalculateHoldingsValue(ctx(), 1); v.Sign() != 0 {
		t.Errorf("expected zero, got %v", v)
	}
}

func TestHolding_CalculateHoldingsValue_QueryError(t *testing.T) {
	q := &fakeQuerier{queryErr: errors.New("db")}
	svc := NewHoldingServiceForTest(NewRepositoryForTest(q), okMarket(), discardLogger())
	if v := svc.CalculateHoldingsValue(ctx(), 1); v.Sign() != 0 {
		t.Errorf("expected zero on error, got %v", v)
	}
}

func TestHolding_CalculateHoldingsValue_FallbackAvg(t *testing.T) {
	// market 200s null → no live price → falls back to avgUnitPrice (100) * 10.
	q := &fakeQuerier{rows: &fakeRows{data: [][]any{holdingRow(1, 1, "AAPL", 10, "100.0000")}}}
	svc := NewHoldingServiceForTest(NewRepositoryForTest(q), okMarket(), discardLogger())
	v := svc.CalculateHoldingsValue(ctx(), 1)
	// no FX → fallback to USD amount unchanged = 1000.00
	if !v.Equal(dec("1000.00")) {
		t.Errorf("expected 1000.00, got %v", v)
	}
}

func TestHolding_CalculateHoldingsValue_LivePrice(t *testing.T) {
	q := &fakeQuerier{rows: &fakeRows{data: [][]any{holdingRow(1, 1, "AAPL", 2, "100.0000")}}}
	market := newMarket(&routeStub{routes: map[string]stubResp{
		"GET /stocks/price-feed/current": {200, `[{"ticker":"AAPL","currentPrice":"50.00"}]`},
	}})
	svc := NewHoldingServiceForTest(NewRepositoryForTest(q), market, discardLogger())
	v := svc.CalculateHoldingsValue(ctx(), 1)
	// 2 * 50 = 100 USD → no FX → 100.00
	if !v.Equal(dec("100.00")) {
		t.Errorf("expected 100.00, got %v", v)
	}
}

func TestUniqueTickers(t *testing.T) {
	hs := []FundHolding{{StockTicker: "A"}, {StockTicker: "A"}, {StockTicker: "B"}}
	out := uniqueTickers(hs)
	if len(out) != 2 {
		t.Errorf("expected 2 unique, got %v", out)
	}
}

func TestConvertUsdToRsd_Zero(t *testing.T) {
	if v := convertUsdToRsd(ctx(), okMarket(), dec("0")); v.Sign() != 0 {
		t.Errorf("zero → zero, got %v", v)
	}
}

func TestConvertUsdToRsd_FxSuccess(t *testing.T) {
	market := newMarket(&routeStub{routes: map[string]stubResp{
		"GET /internal/calculate/no-commission": {200, `{"convertedAmount":"117.00"}`},
	}})
	v := convertUsdToRsd(ctx(), market, dec("1.00"))
	if !v.Equal(dec("117.00")) {
		t.Errorf("expected 117.00, got %v", v)
	}
}

func TestStrEqualFold(t *testing.T) {
	if !strEqualFold("AAPL", "aapl") {
		t.Error("case-insensitive equal")
	}
	if strEqualFold("AAPL", "MSFT") || strEqualFold("AA", "AAA") {
		t.Error("should differ")
	}
}
