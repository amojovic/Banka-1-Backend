package interbank

import "testing"

func TestNewServiceForTest_Smoke(t *testing.T) {
	if NewServiceForTest(nil, nil, nil, nil, 111, nil) == nil {
		t.Fatal("nil service")
	}
}
