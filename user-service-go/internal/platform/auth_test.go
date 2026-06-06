package platform

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	gpauth "banka1/go-platform/auth"
)

func testJWTService(t *testing.T) *JWTService {
	t.Helper()
	return NewJWTService(JWTConfig{
		Secret:              "test-secret-key-for-user-service-go",
		Issuer:              "test-issuer",
		IDClaim:             "id",
		RolesClaim:          "roles",
		PermissionsClaim:    "permissions",
		AccessTokenDuration: time.Hour,
	})
}

func TestNewJWTService_GeneratesAndValidatesToken(t *testing.T) {
	auth := testJWTService(t)
	token, err := auth.GenerateAccessToken(1, "user@test.com", "BASIC", []string{"BANKING_BASIC"})
	if err != nil {
		t.Fatal(err)
	}
	principal, err := auth.Parse(token)
	if err != nil {
		t.Fatal(err)
	}
	if principal.ID != 1 || principal.Role != "BASIC" {
		t.Fatalf("principal = %#v", principal)
	}
}

func TestPrincipalFromContext(t *testing.T) {
	principal := Principal{ID: 7, Email: "a@test.com", Role: "ADMIN"}
	ctx := gpauth.WithPrincipal(context.Background(), principal)
	got, ok := PrincipalFromContext(ctx)
	if !ok || got.ID != 7 {
		t.Fatalf("principal = %#v ok=%v", got, ok)
	}
}

func TestRequireAnyRole_AllowsMatchingRole(t *testing.T) {
	auth := testJWTService(t)
	token, err := auth.GenerateAccessToken(1, "admin@test.com", "ADMIN", nil)
	if err != nil {
		t.Fatal(err)
	}

	called := false
	handler := RequireAnyRole("ADMIN")(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		w.WriteHeader(http.StatusOK)
	}))
	protected := auth.Middleware(handler)

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	protected.ServeHTTP(w, req)

	if !called || w.Code != http.StatusOK {
		t.Fatalf("called=%v status=%d", called, w.Code)
	}
}

func TestRandomURLToken_And_SHA256Hex(t *testing.T) {
	token, err := RandomURLToken()
	if err != nil || token == "" {
		t.Fatalf("token=%q err=%v", token, err)
	}
	hash := SHA256Hex("abc")
	if len(hash) != 64 {
		t.Fatalf("hash len = %d", len(hash))
	}
}
