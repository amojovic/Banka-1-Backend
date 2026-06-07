package interbank

import (
	"context"
	"errors"
	"testing"

	"banka1/trading-service-go/internal/clients"
	"banka1/trading-service-go/internal/portfolio"
)

// strptr is a tiny helper for *string fields on clients.StockListing.
func strptr(s string) *string { return &s }

// ---- CreditPortfolio (S7/S8 buyer-side delivery / S9 settlement leg p4) ----

// Existing buyer lot → increment its quantity by the delivered amount.
func TestCreditPortfolio_ExistingBuyerLot_Increments(t *testing.T) {
	port := &stubIBPortfolio{
		// resolveListingByTicker scans the buyer's positions; this one matches.
		positions: []portfolio.Portfolio{{ListingID: 5, Quantity: 4}},
		// FindByUserIDAndListingIDForUpdate returns the existing lot to increment.
		position: &portfolio.Portfolio{ID: 99, ListingID: 5, Quantity: 4},
	}
	mkt := &stubIBMarket{listing: &clients.StockListing{Ticker: strptr("AAPL")}}
	svc := newIBService(&stubIBRepo{}, port, mkt)

	if err := svc.CreditPortfolio(context.Background(), 7, "AAPL", 3); err != nil {
		t.Fatalf("CreditPortfolio: %v", err)
	}
	if port.updatedID != 99 || port.updatedQuantity != 7 {
		t.Errorf("expected UpdateQuantity(99, 4+3=7), got id=%d qty=%d", port.updatedID, port.updatedQuantity)
	}
	if port.insertedQty != 0 {
		t.Errorf("expected no Insert for an existing lot, got insertedQty=%d", port.insertedQty)
	}
}

// New buyer lot → resolve the listingId from the PUBLIC POOL (buyer holds nothing
// yet) and Insert a fresh lot. The buyer scan (FindByUserID) is empty; the public
// pool scan (FindAllPublicStocks) carries the seller's advertised AAPL position.
func TestCreditPortfolio_NewBuyerLot_InsertsFromPublicListing(t *testing.T) {
	port := &stubIBPortfolio{
		positions:       []portfolio.Portfolio{}, // buyer holds nothing → resolveListingByTicker misses
		position:        nil,                     // FindByUserIDAndListingIDForUpdate → nil → Insert
		hasPublic:       true,
		publicPositions: []portfolio.Portfolio{{ListingID: 5, ListingType: "STOCK", PublicQuantity: 10}},
	}
	mkt := &stubIBMarket{listing: &clients.StockListing{Ticker: strptr("AAPL")}}
	svc := newIBService(&stubIBRepo{}, port, mkt)

	if err := svc.CreditPortfolio(context.Background(), 7, "AAPL", 5); err != nil {
		t.Fatalf("CreditPortfolio: %v", err)
	}
	if port.insertedUserID != 7 || port.insertedListingID != 5 || port.insertedQty != 5 {
		t.Errorf("expected Insert(user=7, listing=5, qty=5), got user=%d listing=%d qty=%d",
			port.insertedUserID, port.insertedListingID, port.insertedQty)
	}
	if port.insertedListingType != "STOCK" {
		t.Errorf("expected listingType STOCK, got %q", port.insertedListingType)
	}
	if port.updatedQuantity != 0 {
		t.Errorf("expected no UpdateQuantity for a new lot, got %d", port.updatedQuantity)
	}
}

func TestCreditPortfolio_ZeroQuantity_Error(t *testing.T) {
	svc := newIBService(&stubIBRepo{}, &stubIBPortfolio{}, &stubIBMarket{})
	if err := svc.CreditPortfolio(context.Background(), 7, "AAPL", 0); err == nil {
		t.Error("expected error for zero quantity")
	}
}

func TestCreditPortfolio_BlankTicker_Error(t *testing.T) {
	svc := newIBService(&stubIBRepo{}, &stubIBPortfolio{}, &stubIBMarket{})
	if err := svc.CreditPortfolio(context.Background(), 7, "   ", 5); err == nil {
		t.Error("expected error for blank ticker")
	}
}

// No buyer position AND not in the public pool → 404 (cannot resolve listing).
func TestCreditPortfolio_UnresolvableTicker_Error(t *testing.T) {
	port := &stubIBPortfolio{positions: []portfolio.Portfolio{}} // empty pool
	mkt := &stubIBMarket{listing: &clients.StockListing{Ticker: strptr("ZZZ")}}
	svc := newIBService(&stubIBRepo{}, port, mkt)
	if err := svc.CreditPortfolio(context.Background(), 7, "AAPL", 5); err == nil {
		t.Error("expected error for unresolvable ticker")
	}
}

// A portfolio read error during listing resolution propagates.
func TestCreditPortfolio_ListingResolveError_Propagates(t *testing.T) {
	boom := errors.New("db boom")
	port := &stubIBPortfolio{posErr: boom}
	svc := newIBService(&stubIBRepo{}, port, &stubIBMarket{})
	if err := svc.CreditPortfolio(context.Background(), 7, "AAPL", 5); err == nil {
		t.Error("expected propagated portfolio read error")
	}
}
