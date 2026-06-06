package portfolio

import "testing"

func TestNewServiceForTest_Smoke(t *testing.T) {
	if NewServiceForTest(nil, nil, nil, nil, nil) == nil {
		t.Fatal("nil service")
	}
}
