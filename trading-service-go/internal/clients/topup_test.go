package clients

import (
	"context"
	"errors"
	"net/http"
	"testing"
	"time"

	gpauth "banka1/go-platform/auth"

	"github.com/stretchr/testify/assert"
)

// errDoer always fails the transport — exercises the doJSON transport-error branch
// in every client method.
type errDoer struct{}

func (errDoer) Do(*http.Request) (*http.Response, error) { return nil, errors.New("dial") }

// errResp returns a non-2xx, non-404 response — exercises the generic error branch.
func errResp(status int) HTTPDoer {
	return &captureDoer{resp: jsonResp(status, "boom")}
}

// ---- WithCallerAuth / callerAuthFromContext --------------------------------

func TestWithCallerAuth_SetsAndForwards(t *testing.T) {
	// non-blank auth -> stored on ctx; doJSON forwards it instead of minting
	d := &captureDoer{resp: jsonResp(200, `{}`)}
	c := NewMarketClient("http://m", nil, d)
	ctx := WithCallerAuth(context.Background(), "Bearer abc")
	if _, err := c.GetListing(ctx, 1); err != nil {
		t.Fatal(err)
	}
	if got := d.lastReq.Header.Get("Authorization"); got != "Bearer abc" {
		t.Errorf("Authorization = %q want forwarded caller bearer", got)
	}
}

func TestWithCallerAuth_BlankLeavesContextUnchanged(t *testing.T) {
	ctx := context.Background()
	if WithCallerAuth(ctx, "   ") != ctx {
		t.Error("blank auth should leave the context unchanged")
	}
	if callerAuthFromContext(ctx) != "" {
		t.Error("no caller auth should be empty")
	}
}

// ---- ServiceTokenProvider + doJSON token-mint paths ------------------------

func authService() *gpauth.Service {
	return gpauth.NewService(gpauth.Config{Secret: "test-secret", Issuer: "banka1",
		IDClaim: "id", RolesClaim: "roles", PermissionsClaim: "permissions", EmailClaim: "email"})
}

func TestNewServiceTokenProvider_BufferClamps(t *testing.T) {
	// ttl <= 0 -> 1h default; large ttl -> buffer clamped to 30s; tiny ttl -> 1s min
	p := NewServiceTokenProvider(authService(), "svc", 0)
	if p.ttl != time.Hour {
		t.Errorf("ttl default = %v", p.ttl)
	}
	if p.buffer != 30*time.Second {
		t.Errorf("buffer for 1h ttl = %v want 30s", p.buffer)
	}
	tiny := NewServiceTokenProvider(authService(), "svc", 2*time.Second)
	if tiny.buffer != time.Second {
		t.Errorf("buffer for tiny ttl = %v want 1s", tiny.buffer)
	}
}

func TestServiceTokenProvider_TokenMintsAndCaches(t *testing.T) {
	p := NewServiceTokenProvider(authService(), "svc", time.Hour)
	tok, err := p.Token()
	if err != nil || tok == "" {
		t.Fatalf("mint failed: %v", err)
	}
	tok2, err := p.Token() // cached
	if err != nil || tok2 != tok {
		t.Errorf("second call should return cached token")
	}
}

func TestServiceTokenProvider_TokenMintError(t *testing.T) {
	// empty secret -> GenerateServiceToken errors
	p := NewServiceTokenProvider(gpauth.NewService(gpauth.Config{}), "svc", time.Hour)
	if _, err := p.Token(); err == nil {
		t.Fatal("missing secret should error")
	}
}

func TestDoJSON_MintsServiceToken(t *testing.T) {
	d := &captureDoer{resp: jsonResp(200, `{}`)}
	tokens := NewServiceTokenProvider(authService(), "svc", time.Hour)
	c := NewMarketClient("http://m", tokens, d)
	if _, err := c.GetListing(context.Background(), 1); err != nil {
		t.Fatal(err)
	}
	if got := d.lastReq.Header.Get("Authorization"); got == "" {
		t.Error("should mint a SERVICE token when no caller auth")
	}
}

func TestDoJSON_MintError(t *testing.T) {
	d := &captureDoer{resp: jsonResp(200, `{}`)}
	tokens := NewServiceTokenProvider(gpauth.NewService(gpauth.Config{}), "svc", time.Hour)
	c := NewMarketClient("http://m", tokens, d)
	if _, err := c.GetListing(context.Background(), 1); err == nil {
		t.Fatal("token mint error should propagate")
	}
}

func TestNew_WiresAllClients(t *testing.T) {
	c := New(authService(), "http://m", "http://b", "http://u", time.Hour)
	if c.Market == nil || c.Account == nil || c.Employee == nil || c.Customer == nil {
		t.Errorf("New should wire all clients: %+v", c)
	}
}

// ---- newBaseClient default doer (nil) --------------------------------------

func TestNewBaseClient_DefaultDoer(t *testing.T) {
	c := NewMarketClient("http://m/", nil, nil) // nil doer -> default http.Client
	if c.base.http == nil {
		t.Error("nil doer should default to an http.Client")
	}
	if c.base.baseURL != "http://m" {
		t.Errorf("baseURL trailing slash not trimmed: %q", c.base.baseURL)
	}
}

// ---- OwnerAccount.OwnerIDValue / Number nil --------------------------------

func TestOwnerAccount_OwnerIDValue(t *testing.T) {
	id := int64(42)
	assert.Equal(t, int64(42), OwnerAccount{OwnerID: &id}.OwnerIDValue())
	assert.Equal(t, int64(0), OwnerAccount{}.OwnerIDValue())
}

func TestOwnerAccount_NumberNil(t *testing.T) {
	assert.Equal(t, "", OwnerAccount{}.Number())
	n := "x"
	assert.Equal(t, "x", OwnerAccount{AccountNumber: &n}.Number())
}

// ---- error branches for each client method ---------------------------------

func TestMarketClient_ErrorBranches(t *testing.T) {
	ctx := context.Background()
	cE := NewMarketClient("http://m", nil, errDoer{})
	if _, err := cE.Calculate(ctx, "USD", "RSD", dec("1")); err == nil {
		t.Error("Calculate transport error")
	}
	if _, err := cE.CalculateWithoutCommission(ctx, "USD", "RSD", dec("1")); err == nil {
		t.Error("CalculateWithoutCommission transport error")
	}
	if _, err := cE.GetExchangeStatus(ctx, 1); err == nil {
		t.Error("GetExchangeStatus transport error")
	}
	c5 := NewMarketClient("http://m", nil, errResp(500))
	if _, err := c5.GetExchangeStatus(ctx, 1); err == nil {
		t.Error("GetExchangeStatus 500")
	}
	// ConvertNoCommission failure -> !ok
	if _, ok := cE.ConvertNoCommission(ctx, dec("100"), "USD", "RSD"); ok {
		t.Error("ConvertNoCommission transport failure should be !ok")
	}
}

func TestCustomerClient_ErrorBranches(t *testing.T) {
	ctx := context.Background()
	cE := NewCustomerClient("http://u", nil, errDoer{})
	if _, err := cE.GetCustomer(ctx, 1); err == nil {
		t.Error("GetCustomer transport error")
	}
	ime := "x"
	if _, err := cE.SearchCustomers(ctx, &ime, nil, 0, 10); err == nil {
		t.Error("SearchCustomers transport error")
	}
}

func TestEmployeeClient_ErrorBranches(t *testing.T) {
	ctx := context.Background()
	cE := NewEmployeeClient("http://u", nil, errDoer{})
	if _, err := cE.GetEmployee(ctx, 1); err == nil {
		t.Error("GetEmployee transport error")
	}
	email := "a@b.com"
	if _, err := cE.SearchEmployees(ctx, &email, ptr("Ime"), ptr("Prez"), ptr("Poz"), 0, 10); err == nil {
		t.Error("SearchEmployees transport error")
	}
	if len(cE.ActuaryClientIDs(ctx)) != 0 {
		t.Error("ActuaryClientIDs transport error -> empty")
	}
}

func TestAccountClient_ErrorBranches(t *testing.T) {
	ctx := context.Background()
	cE := NewAccountClient("http://b", nil, errDoer{})
	if _, err := cE.GetAccountDetailsByNumber(ctx, "123"); err == nil {
		t.Error("GetAccountDetailsByNumber transport error")
	}
	// GetAccountDetailsByID: non-404 error from the id path propagates
	if _, err := cE.GetAccountDetailsByID(ctx, 42); err == nil {
		t.Error("GetAccountDetailsByID transport error")
	}
	if _, err := cE.GetGovernmentBankAccountRsd(ctx); err == nil {
		t.Error("GetGovernmentBankAccountRsd transport error")
	}
	if _, err := cE.GetBankAccount(ctx, "RSD"); err == nil {
		t.Error("GetBankAccount transport error")
	}
	if _, err := cE.CreateSystemAccount(ctx, "123", 5, "RSD", "Fund", dec("0")); err == nil {
		t.Error("CreateSystemAccount transport error")
	}
	if err := cE.Transaction(ctx, Payment{}); err == nil {
		t.Error("Transaction transport error")
	}
	if cE.GetAccountInCurrency(ctx, 5, "RSD") != nil {
		t.Error("GetAccountInCurrency transport error -> nil")
	}
	if cE.GetStateRsdOwnerAccount(ctx) != nil {
		t.Error("GetStateRsdOwnerAccount transport error -> nil")
	}
	if cE.GetBankRsdOwnerAccount(ctx) != nil {
		t.Error("GetBankRsdOwnerAccount transport error -> nil")
	}
}

func TestAccountClient_GetAccountInCurrency_BlankNumber(t *testing.T) {
	// 200 with a blank accountNumber field -> nil
	d := &captureDoer{resp: jsonResp(200, `{"accountNumber":""}`)}
	c := NewAccountClient("http://b", nil, d)
	if c.GetAccountInCurrency(context.Background(), 5, "RSD") != nil {
		t.Error("blank accountNumber -> nil")
	}
}

func TestAccountClient_OwnerAccount_BlankNumber(t *testing.T) {
	d := &captureDoer{resp: jsonResp(200, `{"accountNumber":""}`)}
	c := NewAccountClient("http://b", nil, d)
	if c.GetStateRsdOwnerAccount(context.Background()) != nil {
		t.Error("blank accountNumber -> nil")
	}
}

func TestAccountClient_GetGovernmentBankAccountRsd_OK(t *testing.T) {
	d := &captureDoer{resp: jsonResp(200, `{"accountNumber":"st"}`)}
	c := NewAccountClient("http://b", nil, d)
	if _, err := c.GetGovernmentBankAccountRsd(context.Background()); err != nil {
		t.Fatal(err)
	}
}

func ptr[T any](v T) *T { return &v }
