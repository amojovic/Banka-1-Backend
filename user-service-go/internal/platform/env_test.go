package platform

import "testing"

func TestEnvHasKey(t *testing.T) {
	t.Setenv("USER_SERVICE_GO_TEST_ENV_KEY", "1")
	if !envHasKey("USER_SERVICE_GO_TEST_ENV_KEY") {
		t.Fatal("expected envHasKey true for set variable")
	}
	if envHasKey("USER_SERVICE_GO_TEST_ENV_KEY_MISSING_12345") {
		t.Fatal("expected envHasKey false for missing variable")
	}
}
