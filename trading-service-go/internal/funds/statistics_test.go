package funds

import (
	"testing"

	"github.com/shopspring/decimal"
)

func decs(vals ...string) []decimal.Decimal {
	out := make([]decimal.Decimal, len(vals))
	for i, v := range vals {
		out[i] = dec(v)
	}
	return out
}

// twelveGrowing returns 12 monotonically increasing values (enough samples).
func twelveGrowing() []decimal.Decimal {
	return decs("100", "110", "120", "130", "140", "150", "160", "170", "180", "190", "200", "210")
}

func TestNewStatisticsService(t *testing.T) {
	if NewStatisticsService(nil) == nil {
		t.Error("nil")
	}
}

func TestFromSnapshots_TooFew(t *testing.T) {
	s := NewStatisticsService(nil)
	snaps := []FundValueSnapshot{{TotalValue: dec("100")}}
	m := s.fromSnapshots(snaps)
	if m.MonthlySnapshotsUsed != 1 || m.AnnualizedReturn != nil {
		t.Errorf("expected nil metrics, got %+v", m)
	}
}

func TestFromSnapshots_Full(t *testing.T) {
	s := NewStatisticsService(nil)
	vals := twelveGrowing()
	snaps := make([]FundValueSnapshot, len(vals))
	for i, v := range vals {
		snaps[i] = FundValueSnapshot{TotalValue: v}
	}
	m := s.fromSnapshots(snaps)
	if m.MonthlySnapshotsUsed != 12 {
		t.Errorf("used = %d", m.MonthlySnapshotsUsed)
	}
	if m.AnnualizedReturn == nil || m.Volatility == nil || m.RewardToVariabilityRatio == nil || m.MaxDrawdown == nil {
		t.Errorf("metrics should be non-nil: %+v", m)
	}
}

func TestCalculateAnnualizedReturn(t *testing.T) {
	v := calculateAnnualizedReturn(twelveGrowing())
	if v == nil || v.Sign() <= 0 {
		t.Errorf("expected positive return, got %v", v)
	}
}

func TestCalculateAnnualizedReturn_ZeroStart(t *testing.T) {
	if v := calculateAnnualizedReturn(decs("0", "100")); v != nil {
		t.Errorf("expected nil for zero start, got %v", v)
	}
}

func TestCalculateAnnualizedReturn_YearsZero(t *testing.T) {
	// single value: years = (1-1)/12 = 0 → nil
	if v := calculateAnnualizedReturn(decs("100")); v != nil {
		t.Errorf("expected nil for years<=0, got %v", v)
	}
}

func TestCalculateVolatility(t *testing.T) {
	v := calculateVolatility(twelveGrowing())
	if v == nil {
		t.Fatal("expected non-nil volatility")
	}
}

func TestCalculateVolatility_NoReturns(t *testing.T) {
	if v := calculateVolatility(decs("100")); v != nil {
		t.Errorf("expected nil, got %v", v)
	}
}

func TestCalculateVolatility_SkipNonPositivePrev(t *testing.T) {
	// first prev=0 is skipped; the remaining single return yields zero variance.
	v := calculateVolatility(decs("0", "100", "110"))
	if v == nil {
		t.Fatal("expected non-nil")
	}
}

func TestCalculateMaxDrawdown(t *testing.T) {
	v := calculateMaxDrawdown(decs("100", "120", "60", "90"))
	if v == nil || v.Sign() <= 0 {
		t.Errorf("expected positive drawdown, got %v", v)
	}
}

func TestCalculateMaxDrawdown_NoDrop(t *testing.T) {
	v := calculateMaxDrawdown(twelveGrowing())
	if v == nil || v.Sign() != 0 {
		t.Errorf("expected zero drawdown, got %v", v)
	}
}

func TestMonthlyReturns_SkipsNonPositive(t *testing.T) {
	out := monthlyReturns(decs("0", "100", "110"))
	if len(out) != 1 {
		t.Errorf("expected 1 return (first prev skipped), got %d", len(out))
	}
}

func TestScaleOrNil(t *testing.T) {
	if scaleOrNil(nil) != nil {
		t.Error("nil in → nil out")
	}
	v := dec("1.23456789")
	got := scaleOrNil(&v)
	if got == nil || !got.Equal(dec("1.2346")) {
		t.Errorf("got %v, want 1.2346", got)
	}
}

func TestDecimalEq(t *testing.T) {
	a, b := dec("1"), dec("1")
	if !decimalEq(&a, &b) || !decimalEq(nil, nil) {
		t.Error("eq")
	}
	if decimalEq(&a, nil) || decimalEq(nil, &a) {
		t.Error("nil mismatch should be false")
	}
	c := dec("2")
	if decimalEq(&a, &c) {
		t.Error("1 != 2")
	}
}

func TestDecimalCompareNullsLast(t *testing.T) {
	a, b := dec("1"), dec("2")
	if decimalCompareNullsLast(nil, nil, false) != 0 {
		t.Error("both nil = 0")
	}
	if decimalCompareNullsLast(nil, &b, false) != 1 {
		t.Error("a nil → last")
	}
	if decimalCompareNullsLast(&a, nil, false) != -1 {
		t.Error("b nil → last")
	}
	if decimalCompareNullsLast(&a, &b, false) >= 0 {
		t.Error("1<2 asc")
	}
	if decimalCompareNullsLast(&a, &b, true) <= 0 {
		t.Error("1<2 desc → reversed")
	}
}

func TestCaseInsensitiveCompare(t *testing.T) {
	if caseInsensitiveCompare("Apple", "apple") != 0 {
		t.Error("case-insensitive equal")
	}
	if caseInsensitiveCompare("a", "b") >= 0 {
		t.Error("a<b")
	}
}

func fv(naziv string, tv *decimal.Decimal) FundView {
	return FundView{Naziv: naziv, TotalValue: tv, Profit: tv, AnnualizedReturn: tv,
		RewardToVariabilityRatio: tv, MaxDrawdown: tv, Volatility: tv, Source: FundDto{Naziv: naziv}}
}

func TestSort_ByNameDefault(t *testing.T) {
	s := NewStatisticsService(nil)
	items := []FundView{fv("Charlie", nil), fv("alpha", nil), fv("Bravo", nil)}
	out := s.Sort(items, "", "")
	if out[0].Naziv != "alpha" || out[2].Naziv != "Charlie" {
		t.Errorf("name sort wrong: %v %v %v", out[0].Naziv, out[1].Naziv, out[2].Naziv)
	}
}

func TestSort_ByNameDesc(t *testing.T) {
	s := NewStatisticsService(nil)
	items := []FundView{fv("alpha", nil), fv("Bravo", nil)}
	out := s.Sort(items, SortByName, SortDesc)
	if out[0].Naziv != "Bravo" {
		t.Errorf("desc name sort wrong: %v", out[0].Naziv)
	}
}

func TestSort_ByTotalValueNullsLast(t *testing.T) {
	s := NewStatisticsService(nil)
	v1, v2 := dec("100"), dec("200")
	items := []FundView{fv("A", nil), fv("B", &v2), fv("C", &v1)}
	out := s.Sort(items, SortByTotalValue, SortAsc)
	// non-nil ascending first (100, 200), nil last.
	if out[0].Naziv != "C" || out[1].Naziv != "B" || out[2].Naziv != "A" {
		t.Errorf("total-value sort wrong: %v", []string{out[0].Naziv, out[1].Naziv, out[2].Naziv})
	}
}

func TestSort_AllFieldsExercised(t *testing.T) {
	s := NewStatisticsService(nil)
	v := dec("5")
	items := []FundView{fv("A", &v), fv("B", &v)}
	for _, field := range []string{SortByProfit, SortByAnnualizedReturn,
		SortByRewardToVariabilityRat, SortByMaxDrawdown, SortByVolatility, "UNKNOWN"} {
		out := s.Sort(items, field, SortAsc)
		if len(out) != 2 {
			t.Errorf("field %s lost items", field)
		}
	}
}

func TestPrimaryKeyEqual(t *testing.T) {
	v := dec("5")
	a := fv("A", &v)
	b := fv("B", &v)
	for _, field := range []string{SortByTotalValue, SortByProfit, SortByAnnualizedReturn,
		SortByRewardToVariabilityRat, SortByMaxDrawdown, SortByVolatility} {
		if !primaryKeyEqual(field, a, b) {
			t.Errorf("field %s should be equal", field)
		}
	}
	if primaryKeyEqual(SortByName, a, b) {
		t.Error("default (name) → false")
	}
}
