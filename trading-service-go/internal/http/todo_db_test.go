package http

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/stretchr/testify/assert"
)

// todoDB serves canned rows for the watchlist / price-alert SQL so the GET list
// loops and the POST insert (RETURNING id) success paths execute.
type todoDB struct{}

func (todoDB) Exec(context.Context, string, ...any) (pgconn.CommandTag, error) {
	return pgconn.NewCommandTag("UPDATE 1"), nil
}
func (todoDB) Query(_ context.Context, sql string, _ ...any) (pgx.Rows, error) {
	if strings.Contains(sql, "price_alerts") {
		return &cRows{data: [][]any{{int64(1), "AAPL", "ABOVE", "100", "EMAIL", true, time.Now(), (*time.Time)(nil)}}}, nil
	}
	return &cRows{data: [][]any{{int64(1), "Default", json.RawMessage("[]")}}}, nil
}
func (todoDB) QueryRow(context.Context, string, ...any) pgx.Row {
	return cRow{vals: []any{int64(1)}} // INSERT ... RETURNING id
}

func newTodoHandlers() *Handlers { return &Handlers{app: &App{DB: todoDB{}}} }

func TestWatchlists_GetList(t *testing.T) {
	w := httptest.NewRecorder()
	newTodoHandlers().Watchlists(w, fundsReq(http.MethodGet, "/watchlists", ""))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestWatchlists_Create(t *testing.T) {
	w := httptest.NewRecorder()
	newTodoHandlers().Watchlists(w, fundsReq(http.MethodPost, "/watchlists", `{"name":"Tech","ticker":"AAPL","listingType":"STOCK"}`))
	assert.Equal(t, http.StatusCreated, w.Code)
}

func TestWatchlists_CreateNoTicker(t *testing.T) {
	w := httptest.NewRecorder()
	newTodoHandlers().Watchlists(w, fundsReq(http.MethodPost, "/watchlists", `{"name":""}`))
	assert.Equal(t, http.StatusCreated, w.Code)
}

func TestWatchlistItemDelete_Success(t *testing.T) {
	r := fundsReq(http.MethodDelete, "/watchlists/items/5", "")
	r.SetPathValue("id", "5")
	w := httptest.NewRecorder()
	newTodoHandlers().WatchlistItemDelete(w, r)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestPriceAlerts_GetList(t *testing.T) {
	w := httptest.NewRecorder()
	newTodoHandlers().PriceAlerts(w, fundsReq(http.MethodGet, "/price-alerts", ""))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestPriceAlerts_Create(t *testing.T) {
	w := httptest.NewRecorder()
	newTodoHandlers().PriceAlerts(w, fundsReq(http.MethodPost, "/price-alerts", `{"ticker":"AAPL","condition":"ABOVE","threshold":"100","notificationType":"EMAIL"}`))
	assert.Equal(t, http.StatusCreated, w.Code)
}
