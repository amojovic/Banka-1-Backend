package store

import (
	"context"
	"testing"
	"time"

	"Banka1Back/credit-service-go/internal/model"

	"github.com/shopspring/decimal"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func sampleInstallment(loanID int64) model.Installment {
	now := time.Now()
	return model.Installment{
		LoanID:                loanID,
		InstallmentAmount:     decimal.NewFromFloat(4400),
		InterestRateAtPayment: decimal.NewFromFloat(0.01),
		Currency:              model.CurrencyRSD,
		ExpectedDueDate:       time.Date(now.Year(), now.Month()+1, now.Day(), 0, 0, 0, 0, now.Location()),
		PaymentStatus:         model.PaymentUnpaid,
		Retry:                 0,
	}
}

// ---------------------------------------------------------------------------
// LoanRequestStore.CreateLoanWithFirstInstallment
// ---------------------------------------------------------------------------

func TestLoanRequestStore_CreateLoanWithFirstInstallment_Success(t *testing.T) {
	skipIfNoDB(t)
	cleanupTables(testDB)

	store := NewLoanRequestStore(testDB)
	ctx := context.Background()

	loan := sampleLoan()
	installment := sampleInstallment(0) // loan ID filled in by the transaction

	result, err := store.CreateLoanWithFirstInstallment(ctx, loan, installment)
	require.NoError(t, err)
	assert.Greater(t, result.ID, int64(0))
	assert.Equal(t, model.LoanGotovinski, result.LoanType)
}

func TestLoanRequestStore_CreateLoanWithFirstInstallment_LoanInDB(t *testing.T) {
	skipIfNoDB(t)
	cleanupTables(testDB)

	store := NewLoanRequestStore(testDB)
	loanStore := NewLoanStore(testDB)
	ctx := context.Background()

	loan := sampleLoan()
	loan.AccountNumber = "CWFI-test-account"
	installment := sampleInstallment(0)

	created, err := store.CreateLoanWithFirstInstallment(ctx, loan, installment)
	require.NoError(t, err)
	require.Greater(t, created.ID, int64(0))

	// Verify loan is retrievable from the database
	fetched, err := loanStore.FindByID(ctx, created.ID)
	require.NoError(t, err)
	assert.Equal(t, "CWFI-test-account", fetched.AccountNumber)
}

func TestLoanRequestStore_CreateLoanWithFirstInstallment_InstallmentInDB(t *testing.T) {
	skipIfNoDB(t)
	cleanupTables(testDB)

	store := NewLoanRequestStore(testDB)
	installStore := NewInstallmentStore(testDB)
	ctx := context.Background()

	loan := sampleLoan()
	loan.AccountNumber = "CWFI-installment-account"
	installment := sampleInstallment(0)

	created, err := store.CreateLoanWithFirstInstallment(ctx, loan, installment)
	require.NoError(t, err)

	// Verify installment was created for the loan
	installments, err := installStore.FindByLoanID(ctx, created.ID)
	require.NoError(t, err)
	require.Len(t, installments, 1)
	assert.Equal(t, model.PaymentUnpaid, installments[0].PaymentStatus)
}

// ---------------------------------------------------------------------------
// RunMigrations
// ---------------------------------------------------------------------------

func TestRunMigrations_Success(t *testing.T) {
	skipIfNoDB(t)

	ctx := context.Background()
	// Migrations dir relative to this test's working directory (internal/store/)
	migrationsDir := "../../migrations"

	err := RunMigrations(ctx, testDB, migrationsDir)
	require.NoError(t, err)
}

func TestRunMigrations_Idempotent(t *testing.T) {
	skipIfNoDB(t)

	ctx := context.Background()
	migrationsDir := "../../migrations"

	// First run applies (or skips already-applied) migrations
	err := RunMigrations(ctx, testDB, migrationsDir)
	require.NoError(t, err)

	// Second run should be a no-op
	err = RunMigrations(ctx, testDB, migrationsDir)
	require.NoError(t, err)
}

func TestRunMigrations_EmptyDir_Succeeds(t *testing.T) {
	skipIfNoDB(t)

	ctx := context.Background()
	// A temp dir with no .sql files is valid — should succeed with no-op
	err := RunMigrations(ctx, testDB, t.TempDir())
	require.NoError(t, err)
}

func TestRunMigrations_NonExistentDir_Succeeds(t *testing.T) {
	skipIfNoDB(t)

	ctx := context.Background()
	// filepath.Glob on a missing path returns (nil, nil), so no error is expected
	err := RunMigrations(ctx, testDB, "/nonexistent/migrations/path")
	require.NoError(t, err)
}
