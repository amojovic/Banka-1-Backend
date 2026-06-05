package dividend

import (
	"testing"

	"github.com/shopspring/decimal"
)

func TestNewServiceForTest_Smoke(t *testing.T) {
	if NewServiceForTest(nil, nil, nil, nil, nil, nil, decimal.Zero, nil) == nil {
		t.Fatal("nil service")
	}
}
