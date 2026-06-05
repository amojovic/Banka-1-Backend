package main

import (
	"os"
	"os/exec"
	"strings"
	"testing"
)

// TestMain runs the subprocess guard: in MINTTOKEN_TEST_MODE the process runs
// main() (so coverage is recorded against it); otherwise it runs the test suite.
func TestMain(m *testing.M) {
	if os.Getenv("MINTTOKEN_TEST_MODE") != "" {
		main()
		os.Exit(0)
	}
	os.Exit(m.Run())
}

func runMain(t *testing.T, args []string) (string, string, error) {
	t.Helper()
	cmdArgs := append([]string{"-test.run=TestMain"}, args...)
	cmd := exec.Command(os.Args[0], cmdArgs...)
	cmd.Env = append(os.Environ(), "MINTTOKEN_TEST_MODE=run")
	var stdout, stderr strings.Builder
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	err := cmd.Run()
	return stdout.String(), stderr.String(), err
}

// jwtLooksValid checks the printed token is a three-segment JWT.
func jwtLooksValid(tok string) bool {
	parts := strings.Split(strings.TrimSpace(tok), ".")
	return len(parts) == 3 && parts[0] != "" && parts[1] != "" && parts[2] != ""
}

func TestMintDefault(t *testing.T) {
	stdout, stderr, err := runMain(t, nil)
	if err != nil {
		t.Fatalf("minttoken exited non-zero: %v\nstderr: %s", err, stderr)
	}
	if !jwtLooksValid(stdout) {
		t.Fatalf("output is not a JWT: %q", stdout)
	}
}

func TestMintWithFlags(t *testing.T) {
	stdout, stderr, err := runMain(t, []string{
		"-role", "SUPERVISOR",
		"-id", "42",
		"-subject", "sup@banka1.local",
		"-perms", "FUND_AGENT_MANAGE,OTHER",
		"-ttl", "30m",
	})
	if err != nil {
		t.Fatalf("minttoken exited non-zero: %v\nstderr: %s", err, stderr)
	}
	if !jwtLooksValid(stdout) {
		t.Fatalf("output is not a JWT: %q", stdout)
	}
}
