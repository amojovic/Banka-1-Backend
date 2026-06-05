package funds

import (
	"encoding/json"
	"testing"
)

func TestFlexibleInt64_Number(t *testing.T) {
	var v FlexibleInt64
	if err := json.Unmarshal([]byte("42"), &v); err != nil {
		t.Fatal(err)
	}
	if v.Int64() != 42 {
		t.Errorf("got %d", v.Int64())
	}
}

func TestFlexibleInt64_String(t *testing.T) {
	var v FlexibleInt64
	if err := json.Unmarshal([]byte(`"99"`), &v); err != nil {
		t.Fatal(err)
	}
	if v.Int64() != 99 {
		t.Errorf("got %d", v.Int64())
	}
}

func TestFlexibleInt64_Null(t *testing.T) {
	v := FlexibleInt64(5)
	if err := json.Unmarshal([]byte("null"), &v); err != nil {
		t.Fatal(err)
	}
	if v.Int64() != 5 {
		t.Errorf("null should leave value unchanged, got %d", v.Int64())
	}
}

func TestFlexibleInt64_WhitespaceTrimmed(t *testing.T) {
	var v FlexibleInt64
	if err := json.Unmarshal([]byte("  7  "), &v); err != nil {
		t.Fatal(err)
	}
	if v.Int64() != 7 {
		t.Errorf("got %d", v.Int64())
	}
}

func TestFlexibleInt64_BadString(t *testing.T) {
	var v FlexibleInt64
	if err := json.Unmarshal([]byte(`"not-a-number"`), &v); err == nil {
		t.Error("expected parse error")
	}
}

func TestFlexibleInt64_BadNumber(t *testing.T) {
	var v FlexibleInt64
	if err := json.Unmarshal([]byte("1.5"), &v); err == nil {
		t.Error("expected parse error for non-int")
	}
}

func TestFlexibleInt64_BadQuote(t *testing.T) {
	var v FlexibleInt64
	// opening+closing quote present but contents are an invalid quoted string.
	if err := json.Unmarshal([]byte(`"\x"`), &v); err == nil {
		t.Error("expected unquote error")
	}
}

func TestSagaResultEvent_DecodeMixed(t *testing.T) {
	body := []byte(`{"transactionId":"123","clientId":5,"fundId":2,"amount":"10.00","failureReason":"x"}`)
	var e SagaResultEvent
	if err := decodeSagaEvent(body, &e); err != nil {
		t.Fatal(err)
	}
	if e.TransactionID == nil || e.TransactionID.Int64() != 123 {
		t.Errorf("txID wrong: %v", e.TransactionID)
	}
	if e.ClientID == nil || *e.ClientID != 5 {
		t.Errorf("clientID wrong")
	}
}
