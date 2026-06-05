package http

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// This file provides shared test doubles reused across the handler tests: a
// universal fake Querier (every domain's Querier interface has the identical
// Exec/Query/QueryRow shape, so one type satisfies them all) and an HTTP doer
// stub for building real clients.* over a deterministic in-memory transport.

func quietLogger() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

// emptyRows is a pgx.Rows that yields no rows.
type emptyRows struct{ err error }

func (r *emptyRows) Close()                                       {}
func (r *emptyRows) Err() error                                   { return r.err }
func (r *emptyRows) CommandTag() pgconn.CommandTag                { return pgconn.CommandTag{} }
func (r *emptyRows) FieldDescriptions() []pgconn.FieldDescription { return nil }
func (r *emptyRows) Next() bool                                   { return false }
func (r *emptyRows) Scan(...any) error                            { return nil }
func (r *emptyRows) Values() ([]any, error)                       { return nil, nil }
func (r *emptyRows) RawValues() [][]byte                          { return nil }
func (r *emptyRows) Conn() *pgx.Conn                              { return nil }

// errRow is a pgx.Row whose Scan returns ErrNoRows (mapped to NotFound by the
// repositories).
type errRow struct{}

func (errRow) Scan(...any) error { return pgx.ErrNoRows }

// fakeDB satisfies every domain's Querier interface. Query returns no rows,
// QueryRow returns ErrNoRows, Exec reports zero rows affected. queryErr, when
// set, fails Query/QueryRow so the error branches are reachable.
type fakeDB struct {
	queryErr error
	execErr  error
}

func (d *fakeDB) Exec(context.Context, string, ...any) (pgconn.CommandTag, error) {
	return pgconn.CommandTag{}, d.execErr
}
func (d *fakeDB) Query(context.Context, string, ...any) (pgx.Rows, error) {
	if d.queryErr != nil {
		return nil, d.queryErr
	}
	return &emptyRows{}, nil
}
func (d *fakeDB) QueryRow(context.Context, string, ...any) pgx.Row { return errRow{} }

// okDoer is a clients.HTTPDoer that 200s "{}" for object endpoints and "[]" for
// list endpoints (a path containing "search" or ending in "s"), so client JSON
// decoding never fails. doerErr, when set, fails every call.
type okDoer struct{ err error }

func (d okDoer) Do(req *http.Request) (*http.Response, error) {
	if d.err != nil {
		return nil, d.err
	}
	body := "{}"
	if strings.Contains(req.URL.Path, "search") {
		body = `{"content":[],"totalPages":1}`
	}
	return &http.Response{
		StatusCode: 200,
		Body:       io.NopCloser(strings.NewReader(body)),
		Header:     http.Header{"Content-Type": []string{"application/json"}},
	}, nil
}
