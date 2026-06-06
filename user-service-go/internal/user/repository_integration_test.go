package user

import (
	"context"
	"os"
	"testing"
	"time"

	"banka1/user-service-go/internal/platform"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

var testRepo *Repository

func TestMain(m *testing.M) {
	dbURL := os.Getenv("TEST_DATABASE_URL")
	if dbURL == "" {
		os.Exit(m.Run())
	}

	pool, err := pgxpool.New(context.Background(), dbURL)
	if err != nil {
		panic(err)
	}
	defer pool.Close()

	if err := pool.Ping(context.Background()); err != nil {
		panic(err)
	}

	setupUserSchema(pool)
	testRepo = NewRepository(pool, mustJMBGCrypto())
	code := m.Run()
	cleanupUserTables(pool)
	os.Exit(code)
}

func mustJMBGCrypto() *platform.JMBGCrypto {
	crypto, err := platform.NewJMBGCrypto(platform.JMBGConfig{
		AESKeyBase64: "VGhpc0lzQURldk9ubHkzMkJ5dGVBRVNLZXktMTIzNDU=",
	})
	if err != nil {
		panic(err)
	}
	return crypto
}

func setupUserSchema(pool *pgxpool.Pool) {
	ctx := context.Background()
	_, _ = pool.Exec(ctx, `
		CREATE TABLE IF NOT EXISTS employees (
			id BIGSERIAL PRIMARY KEY,
			ime VARCHAR(255) NOT NULL,
			prezime VARCHAR(255) NOT NULL,
			datum_rodjenja DATE NOT NULL,
			pol VARCHAR(10) NOT NULL,
			email VARCHAR(255) NOT NULL UNIQUE,
			broj_telefona VARCHAR(255),
			adresa VARCHAR(255),
			username VARCHAR(255) NOT NULL UNIQUE,
			password VARCHAR(255),
			pozicija VARCHAR(255) NOT NULL,
			departman VARCHAR(255) NOT NULL,
			aktivan BOOLEAN NOT NULL DEFAULT TRUE,
			role VARCHAR(50) NOT NULL,
			failed_login_attempts INTEGER NOT NULL DEFAULT 0,
			locked_until TIMESTAMP,
			version BIGINT DEFAULT 0,
			deleted BOOLEAN NOT NULL DEFAULT FALSE,
			created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
			updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
		);
		CREATE TABLE IF NOT EXISTS refresh_tokens (
			id BIGSERIAL PRIMARY KEY,
			value VARCHAR(255) NOT NULL UNIQUE,
			expiration_date_time TIMESTAMP NOT NULL,
			zaposlen_id BIGINT NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
			version BIGINT DEFAULT 0,
			deleted BOOLEAN NOT NULL DEFAULT FALSE,
			created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
			updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
		);
		CREATE TABLE IF NOT EXISTS confirmation_token (
			id BIGSERIAL PRIMARY KEY,
			value VARCHAR(255) NOT NULL UNIQUE,
			expiration_date_time TIMESTAMP,
			zaposlen_id BIGINT NOT NULL UNIQUE REFERENCES employees(id) ON DELETE CASCADE,
			version BIGINT DEFAULT 0,
			deleted BOOLEAN NOT NULL DEFAULT FALSE,
			created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
			updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
		);
		CREATE TABLE IF NOT EXISTS zaposlen_permissions (
			zaposlen_id BIGINT NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
			permission VARCHAR(100) NOT NULL,
			PRIMARY KEY (zaposlen_id, permission)
		);
		CREATE TABLE IF NOT EXISTS clients (
			id BIGSERIAL PRIMARY KEY,
			ime VARCHAR(255) NOT NULL,
			prezime VARCHAR(255) NOT NULL,
			datum_rodjenja BIGINT NOT NULL,
			pol VARCHAR(10) NOT NULL,
			email VARCHAR(255) NOT NULL UNIQUE,
			broj_telefona VARCHAR(255),
			adresa VARCHAR(255),
			password VARCHAR(255),
			jmbg_encrypted TEXT,
			aktivan BOOLEAN NOT NULL DEFAULT FALSE,
			role VARCHAR(50) NOT NULL DEFAULT 'CLIENT_BASIC',
			version BIGINT DEFAULT 0,
			deleted BOOLEAN NOT NULL DEFAULT FALSE,
			created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
			updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
		);
		CREATE TABLE IF NOT EXISTS client_permissions (
			client_id BIGINT NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
			permission VARCHAR(100) NOT NULL,
			PRIMARY KEY (client_id, permission)
		);
		CREATE TABLE IF NOT EXISTS client_confirmation_token (
			id BIGSERIAL PRIMARY KEY,
			value VARCHAR(255) NOT NULL UNIQUE,
			expiration_date_time TIMESTAMP,
			klijent_id BIGINT NOT NULL UNIQUE REFERENCES clients(id),
			version BIGINT DEFAULT 0,
			deleted BOOLEAN NOT NULL DEFAULT FALSE,
			created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
			updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
		);
	`)
}

func cleanupUserTables(pool *pgxpool.Pool) {
	ctx := context.Background()
	for _, table := range []string{
		"client_confirmation_token", "client_permissions", "clients",
		"refresh_tokens", "confirmation_token", "zaposlen_permissions", "employees",
	} {
		_, _ = pool.Exec(ctx, "TRUNCATE TABLE "+table+" RESTART IDENTITY CASCADE")
	}
}

func requireRepo(t *testing.T) *Repository {
	t.Helper()
	if testRepo == nil {
		t.Skip("TEST_DATABASE_URL not set")
	}
	return testRepo
}

func TestRepository_EmployeeLifecycle(t *testing.T) {
	repo := requireRepo(t)
	ctx := context.Background()

	created, err := repo.CreateEmployee(ctx, EmployeeCreateRequest{
		Ime:           "Test",
		Prezime:       "User",
		DatumRodjenja: "1990-05-01",
		Pol:           "M",
		Email:         "repo.emp@test.com",
		Username:      "repo.emp",
		Pozicija:      "Dev",
		Departman:     "IT",
		Role:          "BASIC",
	}, []string{"BANKING_BASIC"})
	require.NoError(t, err)

	got, err := repo.EmployeeByLogin(ctx, "repo.emp@test.com")
	require.NoError(t, err)
	assert.Equal(t, created.ID, got.ID)

	perms := repo.EmployeePermissions(ctx, got.ID, got.Role)
	assert.Contains(t, perms, "BANKING_BASIC")

	require.NoError(t, repo.ReplaceEmployeePermissions(ctx, got.ID, []string{"CLIENT_MANAGE"}))
	perms = repo.EmployeePermissions(ctx, got.ID, got.Role)
	assert.Contains(t, perms, "CLIENT_MANAGE")

	updated, err := repo.UpdateEmployee(ctx, got.ID, EmployeeUpdateRequest{
		Ime: strPtr("Updated"),
	})
	require.NoError(t, err)
	assert.Equal(t, "Updated", updated.Ime)

	employees, total, err := repo.SearchEmployees(ctx, SearchQuery{Ime: "Updated", Page: 0, Size: 10})
	require.NoError(t, err)
	assert.GreaterOrEqual(t, total, 1)
	assert.NotEmpty(t, employees)

	require.NoError(t, repo.SoftDeleteEmployee(ctx, got.ID))
	_, err = repo.EmployeeByID(ctx, got.ID)
	assert.ErrorIs(t, err, ErrNotFound)
}

func TestRepository_RefreshAndConfirmationTokens(t *testing.T) {
	repo := requireRepo(t)
	ctx := context.Background()

	emp, err := repo.CreateEmployee(ctx, EmployeeCreateRequest{
		Ime: "Tok", Prezime: "En", DatumRodjenja: "1991-01-01", Pol: "M",
		Email: "tok.en@test.com", Username: "tok.en", Pozicija: "Dev", Departman: "IT",
	}, []string{"BANKING_BASIC"})
	require.NoError(t, err)

	refresh := "refresh-token-value"
	require.NoError(t, repo.StoreEmployeeRefreshToken(ctx, emp.ID, refresh, time.Now().Add(time.Hour)))

	byRefresh, err := repo.EmployeeByRefreshToken(ctx, refresh)
	require.NoError(t, err)
	assert.Equal(t, emp.ID, byRefresh.ID)

	require.NoError(t, repo.DeleteRefreshToken(ctx, refresh))

	confirm := "confirm-token"
	require.NoError(t, repo.UpsertEmployeeConfirmation(ctx, emp.ID, confirm, time.Now().Add(time.Hour)))
	id, err := repo.ConfirmationIDByToken(ctx, "confirmation_token", "zaposlen_id", confirm)
	require.NoError(t, err)
	assert.NotZero(t, id)
}

func TestRepository_ClientLifecycleAndOTC(t *testing.T) {
	repo := requireRepo(t)
	ctx := context.Background()

	client, err := repo.CreateClient(ctx, ClientCreateRequest{
		Ime: "Mira", Prezime: "Markovic", DatumRodjenja: 694310400000, Pol: "Z",
		Email: "mira.client@test.com", JMBG: "1234567890123", Role: "CLIENT_TRADING",
	}, []string{"CLIENT_OTC_TRADE"})
	require.NoError(t, err)

	_, err = repo.ClientByEmail(ctx, "mira.client@test.com")
	require.NoError(t, err)

	byJMBG, err := repo.ClientByPlainJMBG(ctx, "1234567890123")
	require.NoError(t, err)
	assert.Equal(t, client.ID, byJMBG.ID)

	confirm := "client-confirm-token"
	tokenHash := platform.SHA256Hex(confirm)
	passwordHash, err := platform.HashPassword("StrongPass123!")
	require.NoError(t, err)
	require.NoError(t, repo.UpsertClientConfirmation(ctx, client.ID, tokenHash, time.Now().Add(time.Hour)))
	confirmID, err := repo.ConfirmationIDByToken(ctx, "client_confirmation_token", "klijent_id", tokenHash)
	require.NoError(t, err)
	require.NoError(t, repo.ActivateClientPassword(ctx, confirmID, tokenHash, passwordHash))

	ids, err := repo.OTCTradingClientIDs(ctx)
	require.NoError(t, err)
	assert.Contains(t, ids, client.ID)

	require.NoError(t, repo.AddClientMarginPermission(ctx, client.ID))
	require.NoError(t, repo.SoftDeleteClient(ctx, client.ID))
}

func TestRepository_LoginFailuresAndFirstActive(t *testing.T) {
	repo := requireRepo(t)
	ctx := context.Background()

	emp, err := repo.CreateEmployee(ctx, EmployeeCreateRequest{
		Ime: "Lock", Prezime: "Test", DatumRodjenja: "1992-02-02", Pol: "M",
		Email: "lock.test@test.com", Username: "lock.test", Pozicija: "Dev", Departman: "IT",
		Role: "SUPERVISOR",
	}, []string{"TRADE_UNLIMITED"})
	require.NoError(t, err)

	require.NoError(t, repo.RegisterFailedEmployeeLogin(ctx, emp, 3, 10*time.Minute))
	require.NoError(t, repo.ResetEmployeeLoginFailures(ctx, emp.ID))

	otherID, err := repo.FirstActiveEmployeeIDByRoleExcluding(ctx, "SUPERVISOR", emp.ID)
	if err == nil {
		assert.NotEqual(t, emp.ID, otherID)
	}
}

func TestRepository_ActivateEmployeePassword(t *testing.T) {
	repo := requireRepo(t)
	ctx := context.Background()

	emp, err := repo.CreateEmployee(ctx, EmployeeCreateRequest{
		Ime: "Act", Prezime: "Ivate", DatumRodjenja: "1993-03-03", Pol: "M",
		Email: "act.ivate@test.com", Username: "act.ivate", Pozicija: "Dev", Departman: "IT",
	}, []string{"BANKING_BASIC"})
	require.NoError(t, err)

	token := "employee-activation-token"
	tokenHash := platform.SHA256Hex(token)
	require.NoError(t, repo.UpsertEmployeeConfirmation(ctx, emp.ID, tokenHash, time.Now().Add(time.Hour)))
	confirmID, err := repo.ConfirmationIDByToken(ctx, "confirmation_token", "zaposlen_id", tokenHash)
	require.NoError(t, err)

	passwordHash, err := platform.HashPassword("StrongPass123!")
	require.NoError(t, err)
	require.NoError(t, repo.ActivateEmployeePassword(ctx, confirmID, tokenHash, passwordHash))

	got, err := repo.EmployeeByLogin(ctx, "act.ivate@test.com")
	require.NoError(t, err)
	assert.True(t, got.Aktivan)
	assert.NotNil(t, got.PasswordHash)
}

func TestRepository_SearchClientsAndPermissions(t *testing.T) {
	repo := requireRepo(t)
	ctx := context.Background()

	client, err := repo.CreateClient(ctx, ClientCreateRequest{
		Ime: "Search", Prezime: "Client", DatumRodjenja: 694310400000, Pol: "Z",
		Email: "search.client@test.com", JMBG: "9876543210987", Role: "CLIENT_BASIC",
	}, []string{"CLIENT_SECURITIES_TRADE"})
	require.NoError(t, err)

	perms := repo.ClientPermissions(ctx, client.ID, client.Role)
	assert.Contains(t, perms, "CLIENT_SECURITIES_TRADE")

	clients, total, err := repo.SearchClients(ctx, SearchQuery{Ime: "Search", Page: 0, Size: 10})
	require.NoError(t, err)
	assert.GreaterOrEqual(t, total, 1)
	assert.NotEmpty(t, clients)
}

func strPtr(v string) *string { return &v }
