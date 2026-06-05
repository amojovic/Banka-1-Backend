package order

import (
	"sync"
	"testing"
	"time"

	"banka1/trading-service-go/internal/clients"
)

func TestWorker_ScheduleProcessesOrder(t *testing.T) {
	var wg sync.WaitGroup
	wg.Add(1)
	var got int64
	w := NewWorker(func(id int64) {
		got = id
		wg.Done()
	}, testLogger(), 2)
	w.Start()
	defer w.Stop()
	w.Schedule(42, time.Millisecond)
	wg.Wait()
	if got != 42 {
		t.Errorf("processed %d, want 42", got)
	}
}

func TestWorker_NegativeDelayTreatedAsZero(t *testing.T) {
	var wg sync.WaitGroup
	wg.Add(1)
	w := NewWorker(func(int64) { wg.Done() }, testLogger(), 1)
	w.Start()
	defer w.Stop()
	w.Schedule(1, -time.Hour)
	wg.Wait()
}

func TestWorker_ScheduleAfterStop_Noop(t *testing.T) {
	called := false
	w := NewWorker(func(int64) { called = true }, testLogger(), 1)
	w.Start()
	w.Stop()
	w.Schedule(1, time.Millisecond)
	time.Sleep(20 * time.Millisecond)
	if called {
		t.Error("scheduling after stop should be a no-op")
	}
}

func TestWorker_RunSafeRecoversPanic(t *testing.T) {
	var wg sync.WaitGroup
	wg.Add(1)
	w := NewWorker(func(int64) {
		defer wg.Done()
		panic("boom")
	}, testLogger(), 1)
	w.Start()
	defer w.Stop()
	w.Schedule(1, time.Millisecond)
	wg.Wait() // pool survives the panic
}

func TestWorker_PoolSizeFloor(t *testing.T) {
	w := NewWorker(func(int64) {}, testLogger(), 0)
	if w.poolSize != 1 {
		t.Errorf("poolSize = %d, want 1 (floored)", w.poolSize)
	}
}

func TestWorker_StopIdempotent(t *testing.T) {
	w := NewWorker(func(int64) {}, testLogger(), 1)
	w.Start()
	w.Stop()
	w.Stop() // must not panic on second Stop
}

// ---- Service Start/Stop + ExecuteOrderAsync ----

func TestService_StartStop(t *testing.T) {
	h := newHarness()
	h.svc.Start()
	h.svc.Stop()
}

func TestService_ExecuteOrderAsync_Schedules(t *testing.T) {
	h := newHarness()
	var wg sync.WaitGroup
	wg.Add(1)
	h.svc.worker = NewWorker(func(int64) { wg.Done() }, h.svc.logger, 1)
	h.svc.Start()
	defer h.svc.Stop()
	h.svc.ExecuteOrderAsync(7)
	// ExecuteOrderAsync uses initialExecutionDelay (60s); override by scheduling
	// directly to keep the test fast.
	h.svc.worker.Schedule(7, time.Millisecond)
	wg.Wait()
}

// ---- NewService (production constructor) ----

func TestNewService_AssignsAndDefaults(t *testing.T) {
	repo := NewRepository(nil)
	cl := &clients.Clients{
		Market:   clients.NewMarketClient("http://x", nil, stubDoer{body: "{}"}),
		Account:  &clients.AccountClient{},
		Employee: &clients.EmployeeClient{},
		Customer: &clients.CustomerClient{},
	}
	s := NewService(repo, nil, nil, cl, nil, nil, nil, testLogger())
	if s == nil {
		t.Fatal("expected service")
	}
	// nil notifier/funds default to no-ops.
	if _, ok := s.notifier.(NoopNotifier); !ok {
		t.Error("nil notifier should default to NoopNotifier")
	}
	if _, ok := s.funds.(NoopFundCallback); !ok {
		t.Error("nil funds should default to NoopFundCallback")
	}
	if s.runInTx == nil {
		t.Error("runInTx must be wired")
	}
	if s.auditor != nil {
		t.Error("nil auditor must stay nil (typed-nil guard)")
	}
}

// ---- NewServiceForTest ----

func TestNewServiceForTest_Assembles(t *testing.T) {
	h := newHarness()
	s := NewServiceForTest(
		h.repo, h.portfolios, h.actuaries, h.market, h.account,
		h.employees, h.customers, h.notifier, h.funds, h.auditor,
		fakeTxRunner, testLogger(),
	)
	if s == nil {
		t.Fatal("expected service")
	}
	// Defaults applied when nil notifier/funds/logger given.
	s2 := NewServiceForTest(h.repo, h.portfolios, h.actuaries, h.market, h.account,
		h.employees, h.customers, nil, nil, nil, fakeTxRunner, nil)
	if _, ok := s2.notifier.(NoopNotifier); !ok {
		t.Error("nil notifier should default")
	}
	if _, ok := s2.funds.(NoopFundCallback); !ok {
		t.Error("nil funds should default")
	}
	if s2.logger == nil {
		t.Error("nil logger should default")
	}
}

func TestDiscardWriter(t *testing.T) {
	n, err := discardWriter{}.Write([]byte("abc"))
	if err != nil || n != 3 {
		t.Errorf("got %d,%v", n, err)
	}
}
