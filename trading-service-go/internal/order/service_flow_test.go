package order

import (
	"context"
	"errors"
	"testing"
	"time"

	"banka1/trading-service-go/internal/actuary"
	"banka1/trading-service-go/internal/api"
	"banka1/trading-service-go/internal/clients"
	"banka1/trading-service-go/internal/portfolio"

	"github.com/shopspring/decimal"
)

func ctx() context.Context { return context.Background() }

func agentUser(id int64) AuthUser { return AuthUser{UserID: id, Roles: []string{"AGENT"}} }
func clientUser(id int64) AuthUser {
	return AuthUser{UserID: id, Roles: []string{"CLIENT"}, Permissions: []string{"SECURITIES_TRADE"}}
}

// ---------------------------------------------------------------------------
// CreateBuyOrder
// ---------------------------------------------------------------------------

func TestCreateBuyOrder_ClientHappyPath(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.account.details[5] = acct(7, "", "1000000")
	resp, err := h.svc.CreateBuyOrder(ctx(), clientUser(7), api.CreateBuyOrderRequest{
		ListingID: int64Ptr(1), Quantity: intPtr(2), AccountID: int64Ptr(5),
	})
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if resp.Status != StatusPendingConfirmation {
		t.Errorf("status = %q", resp.Status)
	}
	if resp.ID == 0 {
		t.Error("expected persisted id")
	}
}

func TestCreateBuyOrder_ListingError(t *testing.T) {
	h := newHarness()
	h.market.listingErr = errors.New("boom")
	_, err := h.svc.CreateBuyOrder(ctx(), agentUser(1), api.CreateBuyOrderRequest{
		ListingID: int64Ptr(1), Quantity: intPtr(2),
	})
	if err == nil {
		t.Error("expected listing error")
	}
}

func TestCreateBuyOrder_BankOrder(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	bankID := int64(99)
	h.account.bank = &clients.BankAccount{ID: &bankID}
	h.account.details[99] = acct(0, "", "10000000")
	resp, err := h.svc.CreateBuyOrder(ctx(), agentUser(1), api.CreateBuyOrderRequest{
		ListingID: int64Ptr(1), Quantity: intPtr(2), PurchaseFor: strPtr("BANK"),
	})
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if resp.AccountID != 99 {
		t.Errorf("accountID = %d, want 99", resp.AccountID)
	}
}

func TestCreateBuyOrder_FundOrder(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.account.details[5] = acct(0, "", "10000000")
	resp, err := h.svc.CreateBuyOrder(ctx(), agentUser(1), api.CreateBuyOrderRequest{
		ListingID: int64Ptr(1), Quantity: intPtr(2), PurchaseFor: strPtr("INVESTMENT_FUND"),
		FundID: int64Ptr(10), AccountID: int64Ptr(5),
	})
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if resp.Fee.Sign() != 0 {
		t.Errorf("fund order fee must be zero, got %s", resp.Fee)
	}
	if resp.AccountID != 5 {
		t.Errorf("accountID = %d, want 5", resp.AccountID)
	}
}

func TestCreateBuyOrder_MarginNoPermission_Client_409(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.account.details[5] = acct(7, "", "1000000")
	margin := true
	_, err := h.svc.CreateBuyOrder(ctx(), clientUser(7), api.CreateBuyOrderRequest{
		ListingID: int64Ptr(1), Quantity: intPtr(2), AccountID: int64Ptr(5), Margin: &margin,
	})
	if err == nil {
		t.Error("expected margin permission error")
	}
}

func TestCreateBuyOrder_ClientInsufficientFunds(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.account.details[5] = acct(7, "", "1") // far too little for 2 @ 10
	_, err := h.svc.CreateBuyOrder(ctx(), clientUser(7), api.CreateBuyOrderRequest{
		ListingID: int64Ptr(1), Quantity: intPtr(2), AccountID: int64Ptr(5),
	})
	if err == nil {
		t.Error("expected insufficient funds")
	}
}

func TestCreateBuyOrder_ClientNoAccountSelected_400(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	_, err := h.svc.CreateBuyOrder(ctx(), clientUser(7), api.CreateBuyOrderRequest{
		ListingID: int64Ptr(1), Quantity: intPtr(2),
	})
	if err == nil {
		t.Error("expected account-required error")
	}
}

func TestCreateBuyOrder_ClientAccountForeign_403(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.account.details[5] = acct(999, "", "1000000") // owned by someone else
	margin := true                                  // skip funds check so we reach validateClientAccount? margin needs perm
	_ = margin
	_, err := h.svc.CreateBuyOrder(ctx(), clientUser(7), api.CreateBuyOrderRequest{
		ListingID: int64Ptr(1), Quantity: intPtr(2), AccountID: int64Ptr(5),
	})
	if err == nil {
		t.Error("expected foreign-account 403")
	}
}

func TestCreateBuyOrder_ExchangeWindowError(t *testing.T) {
	h := newHarness()
	l := listing(1)
	l.ExchangeID = nil // resolveExchangeWindow errors on nil exchange id
	h.market.listings[1] = l
	_, err := h.svc.CreateBuyOrder(ctx(), agentUser(1), api.CreateBuyOrderRequest{
		ListingID: int64Ptr(1), Quantity: intPtr(2),
	})
	if err == nil {
		t.Error("expected exchange-window error")
	}
}

// ---------------------------------------------------------------------------
// CreateSellOrder
// ---------------------------------------------------------------------------

func TestCreateSellOrder_HappyPath(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.portfolios.positions[[2]int64{7, 1}] = &portfolio.Portfolio{ID: 1, UserID: 7, ListingID: 1, Quantity: 100}
	resp, err := h.svc.CreateSellOrder(ctx(), clientUser(7), api.CreateSellOrderRequest{
		ListingID: int64Ptr(1), Quantity: intPtr(5), AccountID: int64Ptr(5),
	})
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if resp.Direction != DirectionSell {
		t.Errorf("direction = %q", resp.Direction)
	}
}

func TestCreateSellOrder_NilAccount_400(t *testing.T) {
	h := newHarness()
	_, err := h.svc.CreateSellOrder(ctx(), clientUser(7), api.CreateSellOrderRequest{
		ListingID: int64Ptr(1), Quantity: intPtr(5),
	})
	if err == nil {
		t.Error("expected 400 for nil account")
	}
}

func TestCreateSellOrder_NoPortfolio_404(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	_, err := h.svc.CreateSellOrder(ctx(), clientUser(7), api.CreateSellOrderRequest{
		ListingID: int64Ptr(1), Quantity: intPtr(5), AccountID: int64Ptr(5),
	})
	if err == nil {
		t.Error("expected 404 no portfolio")
	}
}

func TestCreateSellOrder_InsufficientQuantity_409(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.portfolios.positions[[2]int64{7, 1}] = &portfolio.Portfolio{ID: 1, UserID: 7, ListingID: 1, Quantity: 2}
	_, err := h.svc.CreateSellOrder(ctx(), clientUser(7), api.CreateSellOrderRequest{
		ListingID: int64Ptr(1), Quantity: intPtr(5), AccountID: int64Ptr(5),
	})
	if err == nil {
		t.Error("expected 409 insufficient quantity")
	}
}

// ---------------------------------------------------------------------------
// Reads: GetOrders, GetMyOrders, GetMyOrdersPaged
// ---------------------------------------------------------------------------

func TestGetOrders_ALL_ExcludesDrafts(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.repo.all = []Order{
		{ID: 1, ListingID: 1, UserID: 7, Status: StatusApproved, OrderType: TypeMarket, Direction: DirectionBuy},
		{ID: 2, ListingID: 1, UserID: 7, Status: StatusPendingConfirmation, OrderType: TypeMarket, Direction: DirectionBuy},
	}
	page, err := h.svc.GetOrders(ctx(), "ALL", 0, 10)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if page.TotalElements != 1 {
		t.Errorf("total = %d, want 1 (draft excluded)", page.TotalElements)
	}
}

func TestGetOrders_StatusFilter(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.repo.byStatus[StatusPending] = []Order{
		{ID: 3, ListingID: 1, UserID: 7, Status: StatusPending, OrderType: TypeMarket, Direction: DirectionBuy},
	}
	h.actuaries.idsIn[7] = true
	h.employees.employees[7] = &clients.Employee{Ime: sp("A"), Prezime: sp("B")}
	page, err := h.svc.GetOrders(ctx(), StatusPending, 0, 10)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if page.TotalElements != 1 {
		t.Errorf("total = %d, want 1", page.TotalElements)
	}
	if page.Content[0].AgentName == nil || *page.Content[0].AgentName != "A B" {
		t.Errorf("agent name = %v", page.Content[0].AgentName)
	}
}

func TestGetOrders_FindAllError(t *testing.T) {
	h := newHarness()
	h.repo.allErr = errors.New("boom")
	_, err := h.svc.GetOrders(ctx(), "ALL", 0, 10)
	if err == nil {
		t.Error("expected error")
	}
}

func TestGetOrders_StatusError(t *testing.T) {
	h := newHarness()
	h.repo.statusErr = errors.New("boom")
	_, err := h.svc.GetOrders(ctx(), StatusPending, 0, 10)
	if err == nil {
		t.Error("expected error")
	}
}

func TestGetOrders_ListingError(t *testing.T) {
	h := newHarness()
	h.repo.byStatus[StatusPending] = []Order{{ID: 1, ListingID: 1, UserID: 7, Status: StatusPending}}
	h.market.listingErr = errors.New("boom")
	_, err := h.svc.GetOrders(ctx(), StatusPending, 0, 10)
	if err == nil {
		t.Error("expected listing error")
	}
}

func TestGetOrders_ActuaryIDsError(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.repo.byStatus[StatusPending] = []Order{{ID: 1, ListingID: 1, UserID: 7, Status: StatusPending}}
	h.actuaries.idsInErr = errors.New("boom")
	_, err := h.svc.GetOrders(ctx(), StatusPending, 0, 10)
	if err == nil {
		t.Error("expected actuary ids error")
	}
}

func TestGetMyOrders_HappyPath(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	h.repo.byUser[7] = []Order{
		{ID: 1, ListingID: 1, UserID: 7, Status: StatusApproved, OrderType: TypeMarket, Direction: DirectionBuy, ContractSize: 1, Quantity: 2, PricePerUnit: decimal.RequireFromString("10")},
	}
	out, err := h.svc.GetMyOrders(ctx(), clientUser(7))
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(out) != 1 {
		t.Errorf("len = %d, want 1", len(out))
	}
}

func TestGetMyOrders_Forbidden(t *testing.T) {
	h := newHarness()
	_, err := h.svc.GetMyOrders(ctx(), AuthUser{Roles: []string{"NOBODY"}})
	if err == nil {
		t.Error("expected 403")
	}
}

func TestGetMyOrders_RepoError(t *testing.T) {
	h := newHarness()
	h.repo.userErr = errors.New("boom")
	_, err := h.svc.GetMyOrders(ctx(), clientUser(7))
	if err == nil {
		t.Error("expected repo error")
	}
}

func TestGetMyOrders_ListingError(t *testing.T) {
	h := newHarness()
	h.repo.byUser[7] = []Order{{ID: 1, ListingID: 1, UserID: 7}}
	h.market.listingErr = errors.New("boom")
	_, err := h.svc.GetMyOrders(ctx(), clientUser(7))
	if err == nil {
		t.Error("expected listing error")
	}
}

func TestGetMyOrdersPaged_FiltersAndSorts(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	now := time.Now()
	h.repo.byUser[7] = []Order{
		{ID: 1, ListingID: 1, UserID: 7, Status: StatusApproved, OrderType: TypeMarket, Direction: DirectionBuy, ContractSize: 1, Quantity: 2, PricePerUnit: decimal.RequireFromString("10"), CreatedAt: now.Add(-time.Hour)},
		{ID: 2, ListingID: 1, UserID: 7, Status: StatusDone, OrderType: TypeMarket, Direction: DirectionBuy, ContractSize: 1, Quantity: 2, PricePerUnit: decimal.RequireFromString("10"), CreatedAt: now},
	}
	page, err := h.svc.GetMyOrdersPaged(ctx(), clientUser(7), StatusApproved, nil, nil, nil, 0, 10)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if page.TotalElements != 1 {
		t.Errorf("total = %d, want 1", page.TotalElements)
	}
}

func TestGetMyOrdersPaged_Forbidden(t *testing.T) {
	h := newHarness()
	_, err := h.svc.GetMyOrdersPaged(ctx(), AuthUser{}, "ALL", nil, nil, nil, 0, 10)
	if err == nil {
		t.Error("expected 403")
	}
}

func TestGetMyOrdersPaged_RepoError(t *testing.T) {
	h := newHarness()
	h.repo.userErr = errors.New("boom")
	_, err := h.svc.GetMyOrdersPaged(ctx(), clientUser(7), "ALL", nil, nil, nil, 0, 10)
	if err == nil {
		t.Error("expected repo error")
	}
}

func TestGetMyOrdersPaged_ListingError(t *testing.T) {
	h := newHarness()
	h.repo.byUser[7] = []Order{{ID: 1, ListingID: 1, UserID: 7}}
	h.market.listingErr = errors.New("boom")
	_, err := h.svc.GetMyOrdersPaged(ctx(), clientUser(7), "ALL", nil, nil, nil, 0, 10)
	if err == nil {
		t.Error("expected listing error")
	}
}

func TestGetMyOrdersPaged_ListingTypeAndDateFilter(t *testing.T) {
	h := newHarness()
	h.market.listings[1] = listing(1)
	now := time.Now().UTC()
	from := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, time.UTC).AddDate(0, 0, -1)
	to := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, time.UTC).AddDate(0, 0, 1)
	h.repo.byUser[7] = []Order{
		{ID: 1, ListingID: 1, UserID: 7, Status: StatusApproved, OrderType: TypeMarket, Direction: DirectionBuy, ContractSize: 1, Quantity: 2, PricePerUnit: decimal.RequireFromString("10"), CreatedAt: now},
	}
	page, err := h.svc.GetMyOrdersPaged(ctx(), clientUser(7), "ALL", strPtr("STOCK"), &from, &to, 0, 10)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if page.TotalElements != 1 {
		t.Errorf("total = %d, want 1", page.TotalElements)
	}
	// Wrong listing type filters it out.
	page2, _ := h.svc.GetMyOrdersPaged(ctx(), clientUser(7), "ALL", strPtr("BOND"), nil, nil, 0, 10)
	if page2.TotalElements != 0 {
		t.Errorf("wrong-type total = %d, want 0", page2.TotalElements)
	}
}

// ---------------------------------------------------------------------------
// calculateExecutionPrice / enrich
// ---------------------------------------------------------------------------

func TestCalculateExecutionPrice_WeightedAvg(t *testing.T) {
	h := newHarness()
	h.repo.transactions[1] = []Transaction{
		{OrderID: 1, Quantity: 2, PricePerUnit: decimal.RequireFromString("10")},
		{OrderID: 1, Quantity: 3, PricePerUnit: decimal.RequireFromString("20")},
	}
	p, err := h.svc.calculateExecutionPrice(ctx(), 1)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	// (2*10 + 3*20) / 5 = 80/5 = 16
	if p == nil || !p.Equal(decimal.RequireFromString("16")) {
		t.Errorf("avg = %v, want 16", p)
	}
}

func TestCalculateExecutionPrice_NoTransactions(t *testing.T) {
	h := newHarness()
	p, err := h.svc.calculateExecutionPrice(ctx(), 1)
	if err != nil || p != nil {
		t.Errorf("got %v, %v want nil,nil", p, err)
	}
}

func TestCalculateExecutionPrice_RepoError(t *testing.T) {
	h := newHarness()
	h.repo.txnErr = errors.New("boom")
	_, err := h.svc.calculateExecutionPrice(ctx(), 1)
	if err == nil {
		t.Error("expected repo error")
	}
}

// ---------------------------------------------------------------------------
// Funding / fee helpers
// ---------------------------------------------------------------------------

func TestDetermineFundingAccountID_Actuary_UsesBank(t *testing.T) {
	h := newHarness()
	h.actuaries.infos[1] = &actuary.ActuaryInfo{EmployeeID: 1}
	bankID := int64(50)
	h.account.bank = &clients.BankAccount{ID: &bankID}
	id, err := h.svc.determineFundingAccountID(ctx(), 1, nil, "USD")
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if id != 50 {
		t.Errorf("id = %d, want 50", id)
	}
}

func TestDetermineFundingAccountID_EmployeeSelected(t *testing.T) {
	h := newHarness()
	h.employees.employees[1] = &clients.Employee{ID: 1}
	id, err := h.svc.determineFundingAccountID(ctx(), 1, int64Ptr(77), "USD")
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if id != 77 {
		t.Errorf("id = %d, want 77", id)
	}
}

func TestDetermineFundingAccountID_EmployeeNoSelection_Bank(t *testing.T) {
	h := newHarness()
	h.employees.employees[1] = &clients.Employee{ID: 1}
	bankID := int64(60)
	h.account.bank = &clients.BankAccount{ID: &bankID}
	id, err := h.svc.determineFundingAccountID(ctx(), 1, nil, "USD")
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if id != 60 {
		t.Errorf("id = %d, want 60", id)
	}
}

func TestDetermineFundingAccountID_UnknownSelected(t *testing.T) {
	h := newHarness()
	id, err := h.svc.determineFundingAccountID(ctx(), 1, int64Ptr(88), "USD")
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if id != 88 {
		t.Errorf("id = %d, want 88", id)
	}
}

func TestDetermineFundingAccountID_UnknownNoSelection_Zero(t *testing.T) {
	h := newHarness()
	id, err := h.svc.determineFundingAccountID(ctx(), 1, nil, "USD")
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if id != 0 {
		t.Errorf("id = %d, want 0", id)
	}
}

func TestIsEmployeeUser(t *testing.T) {
	h := newHarness()
	h.actuaries.infos[1] = &actuary.ActuaryInfo{EmployeeID: 1}
	if !h.svc.isEmployeeUser(ctx(), 1) {
		t.Error("actuary should be employee")
	}
	h.employees.employees[2] = &clients.Employee{ID: 2}
	if !h.svc.isEmployeeUser(ctx(), 2) {
		t.Error("employee should be employee")
	}
	if h.svc.isEmployeeUser(ctx(), 3) {
		t.Error("unknown should not be employee")
	}
}

func TestCheckFunds_AccountNotFound(t *testing.T) {
	h := newHarness()
	err := h.svc.checkFunds(ctx(), 99, decimal.RequireFromString("10"), "USD")
	if err == nil {
		t.Error("expected account-not-found error")
	}
}

func TestCheckFunds_OtherError(t *testing.T) {
	h := newHarness()
	h.account.detailsErr[99] = errors.New("boom")
	err := h.svc.checkFunds(ctx(), 99, decimal.RequireFromString("10"), "USD")
	if err == nil {
		t.Error("expected error")
	}
}

func TestCheckFunds_Sufficient(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(7, "USD", "1000")
	if err := h.svc.checkFunds(ctx(), 5, decimal.RequireFromString("10"), "USD"); err != nil {
		t.Errorf("unexpected: %v", err)
	}
}

func TestCheckMarginRequirements_NoPermission(t *testing.T) {
	h := newHarness()
	err := h.svc.checkMarginRequirements(ctx(), clientUser(7), 5, listing(1), 1)
	if err == nil {
		t.Error("expected no-permission error")
	}
}

func TestCheckMarginRequirements_Satisfied(t *testing.T) {
	h := newHarness()
	u := AuthUser{UserID: 7, Roles: []string{"CLIENT"}, Permissions: []string{"MARGIN_TRADE"}}
	h.account.details[5] = acct(7, "USD", "1000000")
	if err := h.svc.checkMarginRequirements(ctx(), u, 5, listing(1), 1); err != nil {
		t.Errorf("unexpected: %v", err)
	}
}

func TestCheckMarginRequirements_NotSatisfied(t *testing.T) {
	h := newHarness()
	u := AuthUser{UserID: 7, Roles: []string{"CLIENT"}, Permissions: []string{"MARGIN_TRADE"}}
	h.account.details[5] = acct(7, "USD", "0")
	if err := h.svc.checkMarginRequirements(ctx(), u, 5, listing(1), 1); err == nil {
		t.Error("expected not-satisfied error")
	}
}

func TestValidateClientAccount_NotFound(t *testing.T) {
	h := newHarness()
	if err := h.svc.validateClientAccount(ctx(), 7, 99); err == nil {
		t.Error("expected not-found error")
	}
}

func TestValidateClientAccount_Foreign(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(999, "USD", "100")
	if err := h.svc.validateClientAccount(ctx(), 7, 5); err == nil {
		t.Error("expected foreign-account error")
	}
}

func TestValidateClientAccount_OK(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(7, "USD", "100")
	if err := h.svc.validateClientAccount(ctx(), 7, 5); err != nil {
		t.Errorf("unexpected: %v", err)
	}
}

func TestTransferFee_FundingIsBank_Noop(t *testing.T) {
	h := newHarness()
	bankID := int64(50)
	h.account.bank = &clients.BankAccount{ID: &bankID}
	amt, err := h.svc.transferFee(ctx(), 50, decimal.RequireFromString("5"), "USD", true)
	if err != nil || amt.Sign() != 0 {
		t.Errorf("got %s, %v want 0,nil", amt, err)
	}
}

func TestTransferFee_SameCurrencyTransfer(t *testing.T) {
	h := newHarness()
	bankID := int64(50)
	h.account.bank = &clients.BankAccount{ID: &bankID}
	h.account.details[5] = acct(7, "USD", "1000")
	h.account.details[50] = acct(7, "USD", "0") // same owner -> Transfer
	amt, err := h.svc.transferFee(ctx(), 5, decimal.RequireFromString("5"), "USD", true)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if !amt.Equal(decimal.RequireFromString("5")) {
		t.Errorf("amt = %s, want 5", amt)
	}
	if len(h.account.transfers) != 1 {
		t.Errorf("expected 1 transfer, got %d", len(h.account.transfers))
	}
}

func TestTransferFee_BankError(t *testing.T) {
	h := newHarness()
	h.account.bankErr = errors.New("boom")
	_, err := h.svc.transferFee(ctx(), 5, decimal.RequireFromString("5"), "USD", true)
	if err == nil {
		t.Error("expected bank error")
	}
}

func TestTransferWithConversion_CrossOwner_Transaction(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(7, "USD", "1000")
	h.account.details[6] = acct(8, "USD", "0") // different owner -> Transaction
	amt, err := h.svc.transferWithConversionIfNeeded(ctx(), 5, 6, decimal.RequireFromString("5"), "USD", true)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if !amt.Equal(decimal.RequireFromString("5")) {
		t.Errorf("amt = %s", amt)
	}
	if len(h.account.transactions) != 1 {
		t.Errorf("expected 1 transaction, got %d", len(h.account.transactions))
	}
}

func TestTransferWithConversion_DifferentCurrency_WithCommission(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(7, "RSD", "100000")
	h.account.details[6] = acct(7, "USD", "0")
	conv := decimal.RequireFromString("600")
	comm := decimal.RequireFromString("6")
	h.market.calc = &clients.ExchangeRate{ConvertedAmount: &conv, Commission: &comm}
	amt, err := h.svc.transferWithConversionIfNeeded(ctx(), 5, 6, decimal.RequireFromString("5"), "USD", true)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if !amt.Equal(decimal.RequireFromString("600")) {
		t.Errorf("fromAmount = %s, want 600", amt)
	}
}

func TestTransferWithConversion_DifferentCurrency_NoCommission(t *testing.T) {
	h := newHarness()
	h.account.details[5] = acct(7, "RSD", "100000")
	h.account.details[6] = acct(7, "USD", "0")
	conv := decimal.RequireFromString("590")
	h.market.calcNoComm = &clients.ExchangeRate{ConvertedAmount: &conv}
	amt, err := h.svc.transferWithConversionIfNeeded(ctx(), 5, 6, decimal.RequireFromString("5"), "USD", false)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if !amt.Equal(decimal.RequireFromString("590")) {
		t.Errorf("fromAmount = %s, want 590", amt)
	}
}

func TestTransferWithConversion_FromError(t *testing.T) {
	h := newHarness()
	h.account.detailsErr[5] = errors.New("boom")
	_, err := h.svc.transferWithConversionIfNeeded(ctx(), 5, 6, decimal.RequireFromString("5"), "USD", true)
	if err == nil {
		t.Error("expected from error")
	}
}

func TestResolveExchangeWindow_NilExchangeID(t *testing.T) {
	h := newHarness()
	l := listing(1)
	l.ExchangeID = nil
	_, _, err := h.svc.resolveExchangeWindow(ctx(), l)
	if err == nil {
		t.Error("expected nil-exchange error")
	}
}

func TestResolveExchangeWindow_StatusError(t *testing.T) {
	h := newHarness()
	h.market.exchangeErr = errors.New("boom")
	_, _, err := h.svc.resolveExchangeWindow(ctx(), listing(1))
	if err == nil {
		t.Error("expected status error")
	}
}

func TestResolveExchangeWindow_OK(t *testing.T) {
	h := newHarness()
	closed := true
	after := true
	h.market.exchange = &clients.ExchangeStatus{Closed: &closed, AfterHours: &after}
	c, a, err := h.svc.resolveExchangeWindow(ctx(), listing(1))
	if err != nil || !c || !a {
		t.Errorf("got %v,%v,%v", c, a, err)
	}
}

func TestEnsurePortfolioOwnership_RepoError(t *testing.T) {
	h := newHarness()
	h.portfolios.findErr = errors.New("boom")
	if err := h.svc.ensurePortfolioOwnership(ctx(), 7, 1, 5); err == nil {
		t.Error("expected repo error")
	}
}

// ---------------------------------------------------------------------------
// Mappers / payloads
// ---------------------------------------------------------------------------

func TestMapToOverviewResponse_NonActuary_NilName(t *testing.T) {
	h := newHarness()
	o := &Order{ID: 1, ListingID: 1, UserID: 7}
	resp := h.svc.mapToOverviewResponse(ctx(), o, map[int64]*clients.StockListing{1: listing(1)},
		map[int64]*clients.Employee{}, map[int64]bool{})
	if resp.AgentName != nil {
		t.Error("non-actuary should have nil agent name")
	}
}

func TestResolveAgentName_LookupError_CachesNil(t *testing.T) {
	h := newHarness()
	h.employees.err = errors.New("boom")
	cache := map[int64]*clients.Employee{}
	name := h.svc.resolveAgentName(ctx(), 7, cache, map[int64]bool{7: true})
	if name != nil {
		t.Error("expected nil on lookup error")
	}
	if _, ok := cache[7]; !ok {
		t.Error("expected nil cached")
	}
}

func TestBuildDecisionPayload(t *testing.T) {
	h := newHarness()
	h.employees.employees[7] = &clients.Employee{Ime: sp("A"), Prezime: sp("B"), Email: sp("a@b.c")}
	o := &Order{ID: 1, UserID: 7, ListingID: 1, OrderType: TypeMarket, Direction: DirectionBuy}
	p := h.svc.buildDecisionPayload(ctx(), o, 99, StatusApproved)
	if p.Username == nil || *p.Username != "A B" {
		t.Errorf("username = %v", p.Username)
	}
	if p.SupervisorID != 99 {
		t.Errorf("supervisorID = %d", p.SupervisorID)
	}
}

func TestBuildLifecyclePayload_CustomerRecipient(t *testing.T) {
	h := newHarness()
	h.customers.customers[7] = &clients.Customer{FirstName: sp("Jane"), LastName: sp("Doe"), Email: sp("jane@x.y")}
	o := &Order{ID: 1, UserID: 7, ListingID: 1, Status: StatusApproved, OrderType: TypeMarket, Direction: DirectionBuy}
	p := h.svc.buildLifecyclePayload(ctx(), o, eventCreated, "AAPL", decimal.RequireFromString("10"))
	if p.Username == nil || *p.Username != "Jane Doe" {
		t.Errorf("username = %v", p.Username)
	}
	if p.TemplateVariables["ticker"] != "AAPL" {
		t.Error("ticker not set")
	}
}

func TestResolveRecipient_EmployeeFallback(t *testing.T) {
	h := newHarness()
	h.employees.employees[7] = &clients.Employee{Ime: sp("E"), Email: sp("e@x.y")}
	name, email := h.svc.resolveRecipient(ctx(), 7)
	if name == nil || *name != "E" || email == nil {
		t.Errorf("got %v,%v", name, email)
	}
}

func TestResolveRecipient_None(t *testing.T) {
	h := newHarness()
	name, email := h.svc.resolveRecipient(ctx(), 7)
	if name != nil || email != nil {
		t.Errorf("expected nil,nil got %v,%v", name, email)
	}
}

func TestCustomerDisplayName(t *testing.T) {
	c := &clients.Customer{FirstName: sp(" Jane "), LastName: sp(" Doe ")}
	if n := customerDisplayName(c); n == nil || *n != "Jane Doe" {
		t.Errorf("got %v", n)
	}
	empty := &clients.Customer{}
	if n := customerDisplayName(empty); n != nil {
		t.Errorf("empty should be nil, got %v", n)
	}
}

func TestPublishOrderAuditEvent_SystemActor(t *testing.T) {
	h := newHarness()
	o := &Order{ID: 1}
	h.svc.publishOrderAuditEvent(ctx(), o, systemApproval, false)
	if len(h.auditor.events) != 1 {
		t.Fatalf("expected 1 audit event, got %d", len(h.auditor.events))
	}
	if h.auditor.events[0].ActorID != nil {
		t.Error("system actor should have nil actorID")
	}
}

func TestPublishOrderAuditEvent_NamedActorApproved(t *testing.T) {
	h := newHarness()
	h.employees.employees[42] = &clients.Employee{Ime: sp("Sup"), Prezime: sp("Visor")}
	o := &Order{ID: 1}
	h.svc.publishOrderAuditEvent(ctx(), o, 42, true)
	if len(h.auditor.events) != 1 {
		t.Fatalf("expected 1 event")
	}
	ev := h.auditor.events[0]
	if ev.ActorID == nil || *ev.ActorID != 42 {
		t.Error("expected actorID 42")
	}
	if ev.ActorName == nil || *ev.ActorName != "Sup Visor" {
		t.Errorf("actorName = %v", ev.ActorName)
	}
}

func TestPublishOrderAuditEvent_NilAuditor(t *testing.T) {
	h := newHarness()
	h.svc.auditor = nil
	h.svc.publishOrderAuditEvent(ctx(), &Order{ID: 1}, 42, true) // must not panic
}

func TestResolveActorName_Fallback(t *testing.T) {
	h := newHarness()
	if name := h.svc.resolveActorName(ctx(), 42); name != "42" {
		t.Errorf("got %q, want 42", name)
	}
}
