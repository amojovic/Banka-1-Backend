package platform

import (
	"context"
	"testing"
)

func TestOpenPostgres_BadURL_ReturnsError(t *testing.T) {
	_, err := OpenPostgres(context.Background(), "not-a-valid-postgres-url")
	if err == nil {
		t.Error("expected error for invalid postgres URL")
	}
}

func TestOpenPostgres_EmptyURL_ReturnsError(t *testing.T) {
	_, err := OpenPostgres(context.Background(), "")
	if err == nil {
		t.Error("expected error for empty URL")
	}
}

func TestDBConfigDatabaseURLFor(t *testing.T) {
	cfg := DBConfig{
		Host: "db", Port: "5432", Name: "banka", User: "u", Password: "p", SSLMode: "disable",
	}
	got := cfg.databaseURLFor("postgres")
	want := "postgres://u:p@db:5432/postgres?sslmode=disable"
	if got != want {
		t.Fatalf("databaseURLFor = %q, want %q", got, want)
	}
}

func TestQuoteIdentifier(t *testing.T) {
	if quoteIdentifier(`foo"bar`) != `"foo""bar"` {
		t.Fatal("quoteIdentifier did not escape quotes")
	}
}
