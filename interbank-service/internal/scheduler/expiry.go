package scheduler

import (
	"context"
	"log/slog"
	"time"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
)

// DefaultExpiryInterval is the fallback sweep interval when none is configured.
const DefaultExpiryInterval = 5 * time.Minute

// ExpiryContractStore is the subset of *store.ContractStore the expiry sweeper needs.
type ExpiryContractStore interface {
	// ListExpirable returns ACTIVE contracts whose settlement_date is in the past.
	ListExpirable(ctx context.Context) ([]*store.Contract, error)
	// UpdateStatus flips a contract's status (e.g. ACTIVE→EXPIRED).
	UpdateStatus(ctx context.Context, id, status string) error
}

// OptionReleaser releases a seller-side option's HELD reservation so the k shares
// return to free balance. *client.TradingClient (service.TradingReserver) satisfies
// it via ReleaseOption.
type OptionReleaser interface {
	ReleaseOption(ctx context.Context, negotiationID protocol.ForeignBankId) error
}

// ExpiryScheduler periodically settles expired inter-bank option contracts (S10).
//
// Each tick lists every ACTIVE contract past its settlement date and, per contract:
//   - SELLER side (we host the OPTION pseudo-account): release the seller's HELD
//     reservation via ReleaseOption (the k shares return to free), then flip
//     EXPIRED. The premium STAYS with the seller forever (no money is moved).
//   - BUYER side (the partner hosts the option): the option simply lapses → flip
//     EXPIRED. For Banka 1 the buyer reserves the strike only at EXERCISE time, so
//     there is no standing strike reservation to release and no money to refund.
//
// The release + status flip is performed per contract; a failure on one contract is
// logged (one WARN line) and does NOT abort the remaining contracts.
type ExpiryScheduler struct {
	store     ExpiryContractStore
	releaser  OptionReleaser
	myRouting int
	log       *slog.Logger
	interval  time.Duration
}

// NewExpiryScheduler constructs the sweeper. interval defaults to 5 minutes if zero;
// releaser may be nil (seller-side release becomes a no-op, status flip still runs).
func NewExpiryScheduler(s ExpiryContractStore, releaser OptionReleaser, myRouting int, interval time.Duration, log *slog.Logger) *ExpiryScheduler {
	if interval <= 0 {
		interval = DefaultExpiryInterval
	}
	if log == nil {
		log = slog.Default()
	}
	return &ExpiryScheduler{
		store:     s,
		releaser:  releaser,
		myRouting: myRouting,
		log:       log,
		interval:  interval,
	}
}

// Run blocks until ctx is canceled, ticking every e.interval. It returns ctx.Err()
// when the context is cancelled. Mirrors RetryScheduler.Run.
func (e *ExpiryScheduler) Run(ctx context.Context) error {
	ticker := time.NewTicker(e.interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
			e.tickOnce(ctx)
		}
	}
}

// TickOnce executes one expiry sweep. Exported for testability.
func (e *ExpiryScheduler) TickOnce(ctx context.Context) {
	e.tickOnce(ctx)
}

func (e *ExpiryScheduler) tickOnce(ctx context.Context) {
	contracts, err := e.store.ListExpirable(ctx)
	if err != nil {
		e.log.WarnContext(ctx, "expiry scheduler: ListExpirable error", "err", err)
		return
	}
	if len(contracts) == 0 {
		return
	}
	e.log.InfoContext(ctx, "expiry scheduler: settling expired contracts", "count", len(contracts))

	for _, c := range contracts {
		e.expireOne(ctx, c)
	}
}

// expireOne settles a single expired contract: release the seller reservation (if we
// are the seller bank), then flip EXPIRED. Errors are logged (one WARN line) and do
// not propagate, so one bad contract never aborts the sweep.
func (e *ExpiryScheduler) expireOne(ctx context.Context, c *store.Contract) {
	if c.LocalPartyType == store.ContractPartySeller {
		// We host the OPTION pseudo-account — return the seller's HELD k shares to free.
		if e.releaser != nil {
			negID := protocol.ForeignBankId{RoutingNumber: e.myRouting, Id: c.NegotiationID}
			if err := e.releaser.ReleaseOption(ctx, negID); err != nil {
				e.log.WarnContext(ctx, "expiry scheduler: ReleaseOption failed — skipping status flip for this contract",
					"contract", c.ID, "neg", c.NegotiationID, "err", err)
				return
			}
		}
	}
	// BUYER side: option just lapses; no reservation to release, premium stays with seller.

	if err := e.store.UpdateStatus(ctx, c.ID, store.ContractStatusExpired); err != nil {
		e.log.WarnContext(ctx, "expiry scheduler: UpdateStatus(EXPIRED) failed",
			"contract", c.ID, "neg", c.NegotiationID, "party", c.LocalPartyType, "err", err)
		return
	}
	e.log.InfoContext(ctx, "expiry scheduler: contract EXPIRED",
		"contract", c.ID, "neg", c.NegotiationID, "party", c.LocalPartyType, "ticker", c.StockTicker, "amount", c.Amount)
}
