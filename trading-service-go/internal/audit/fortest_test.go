package audit

import "testing"

// Exercises the test-only constructor so the package's own coverage reflects it
// (it is consumed cross-package by the internal/http audit handler tests).
func TestNewServiceForTest_Smoke(t *testing.T) {
	if NewServiceForTest(nil, nil) == nil {
		t.Fatal("nil service")
	}
}
