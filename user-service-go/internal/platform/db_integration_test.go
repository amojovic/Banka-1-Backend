package platform

import (
	"context"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"
)

func testDatabaseURL(t *testing.T) string {
	t.Helper()
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("TEST_DATABASE_URL not set")
	}
	return url
}

func migrationsDir(t *testing.T) string {
	t.Helper()
	dir, err := filepath.Abs(filepath.Join("..", "..", "migrations"))
	if err != nil {
		t.Fatal(err)
	}
	return dir
}

func TestOpenPostgres_LiveConnection(t *testing.T) {
	pool, err := OpenPostgres(context.Background(), testDatabaseURL(t))
	if err != nil {
		t.Fatalf("OpenPostgres: %v", err)
	}
	defer pool.Close()
}

func TestRunMigrations_LiveDatabase(t *testing.T) {
	ctx := context.Background()
	pool, err := OpenPostgres(ctx, testDatabaseURL(t))
	if err != nil {
		t.Fatalf("OpenPostgres: %v", err)
	}
	defer pool.Close()

	dir := migrationsDir(t)
	if err := RunMigrations(ctx, pool, dir); err != nil {
		t.Fatalf("RunMigrations: %v", err)
	}
	if err := RunMigrations(ctx, pool, dir); err != nil {
		t.Fatalf("RunMigrations second run: %v", err)
	}
}

func TestEnsurePostgresDatabase_InvalidHost_ReturnsError(t *testing.T) {
	err := EnsurePostgresDatabase(context.Background(), DBConfig{
		Host: "127.0.0.1", Port: "1", Name: "missing", User: "u", Password: "p", SSLMode: "disable",
	})
	if err == nil {
		t.Fatal("expected error for unreachable postgres host")
	}
}

func TestEnsurePostgresDatabase_CreatesAndReusesDatabase(t *testing.T) {
	ctx := context.Background()
	cfg := dbConfigFromTestURL(t, "banka_user_cov_ensure")
	if err := EnsurePostgresDatabase(ctx, cfg); err != nil {
		t.Fatalf("EnsurePostgresDatabase create: %v", err)
	}
	if err := EnsurePostgresDatabase(ctx, cfg); err != nil {
		t.Fatalf("EnsurePostgresDatabase reuse: %v", err)
	}
	pool, err := OpenPostgres(ctx, cfg.databaseURLFor(cfg.Name))
	if err != nil {
		t.Fatalf("OpenPostgres created db: %v", err)
	}
	pool.Close()
}

func TestRunMigrations_BaselinesExistingSchema(t *testing.T) {
	ctx := context.Background()
	pool, err := OpenPostgres(ctx, testDatabaseURL(t))
	if err != nil {
		t.Fatalf("OpenPostgres: %v", err)
	}
	defer pool.Close()

	_, _ = pool.Exec(ctx, `DROP TABLE IF EXISTS go_schema_migrations`)
	setupExistingUserTables(ctx, pool)

	dir := migrationsDir(t)
	if err := RunMigrations(ctx, pool, dir); err != nil {
		t.Fatalf("RunMigrations baseline: %v", err)
	}
}

func dbConfigFromTestURL(t *testing.T, dbName string) DBConfig {
	t.Helper()
	raw := testDatabaseURL(t)
	u, err := url.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	host, port, err := net.SplitHostPort(u.Host)
	if err != nil {
		t.Fatal(err)
	}
	pass, _ := u.User.Password()
	return DBConfig{
		Host: host, Port: port, Name: dbName,
		User: u.User.Username(), Password: pass, SSLMode: "disable",
	}
}

func setupExistingUserTables(ctx context.Context, pool *pgxpool.Pool) {
	_, _ = pool.Exec(ctx, `
		CREATE TABLE IF NOT EXISTS employees (id BIGSERIAL PRIMARY KEY);
		CREATE TABLE IF NOT EXISTS refresh_tokens (id BIGSERIAL PRIMARY KEY);
		CREATE TABLE IF NOT EXISTS confirmation_token (id BIGSERIAL PRIMARY KEY);
		CREATE TABLE IF NOT EXISTS zaposlen_permissions (zaposlen_id BIGINT, permission TEXT);
		CREATE TABLE IF NOT EXISTS clients (id BIGSERIAL PRIMARY KEY);
		CREATE TABLE IF NOT EXISTS client_permissions (client_id BIGINT, permission TEXT);
		CREATE TABLE IF NOT EXISTS client_confirmation_token (id BIGSERIAL PRIMARY KEY);
	`)
}
