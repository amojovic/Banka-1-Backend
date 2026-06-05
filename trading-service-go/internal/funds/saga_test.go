package funds

import (
	"context"
	"errors"
	"testing"

	"banka1/go-platform/rabbitmq"
	amqp "github.com/rabbitmq/amqp091-go"
)

// stubRMQPublisher records routing keys and can be forced to error.
type stubRMQPublisher struct {
	keys []string
	err  error
}

func (p *stubRMQPublisher) Publish(_ context.Context, routingKey string, _ any) error {
	p.keys = append(p.keys, routingKey)
	return p.err
}
func (p *stubRMQPublisher) PublishWithID(_ context.Context, routingKey, _ string, _ any) error {
	p.keys = append(p.keys, routingKey)
	return p.err
}
func (p *stubRMQPublisher) Close() error { return nil }

func TestRabbitSagaPublisher_Subscribe(t *testing.T) {
	pub := &stubRMQPublisher{}
	p := NewRabbitSagaPublisher(pub, discardLogger())
	if err := p.PublishSubscribeRequested(ctx(), FundSubscribeRequestedEvent{}); err != nil {
		t.Fatal(err)
	}
	if len(pub.keys) != 1 || pub.keys[0] != RoutingFundSubscribeRequested {
		t.Errorf("keys = %v", pub.keys)
	}
}

func TestRabbitSagaPublisher_RedeemLiquidEnough(t *testing.T) {
	pub := &stubRMQPublisher{}
	p := NewRabbitSagaPublisher(pub, discardLogger())
	if err := p.PublishRedeemRequested(ctx(), FundRedeemRequestedEvent{LiquidEnough: true}); err != nil {
		t.Fatal(err)
	}
	if pub.keys[0] != RoutingFundRedeemRequested {
		t.Errorf("key = %v", pub.keys[0])
	}
}

func TestRabbitSagaPublisher_RedeemNeedsLiquidation(t *testing.T) {
	pub := &stubRMQPublisher{}
	p := NewRabbitSagaPublisher(pub, discardLogger())
	if err := p.PublishRedeemRequested(ctx(), FundRedeemRequestedEvent{LiquidEnough: false}); err != nil {
		t.Fatal(err)
	}
	if pub.keys[0] != RoutingFundRedeemLiquidationRequest {
		t.Errorf("key = %v", pub.keys[0])
	}
}

func TestNoopSagaPublisher(t *testing.T) {
	p := NewNoopSagaPublisher(discardLogger())
	if err := p.PublishSubscribeRequested(ctx(), FundSubscribeRequestedEvent{TransactionID: "1"}); err != nil {
		t.Fatal(err)
	}
	if err := p.PublishRedeemRequested(ctx(), FundRedeemRequestedEvent{TransactionID: "1", LiquidEnough: true}); err != nil {
		t.Fatal(err)
	}
	if err := p.PublishRedeemRequested(ctx(), FundRedeemRequestedEvent{TransactionID: "1", LiquidEnough: false}); err != nil {
		t.Fatal(err)
	}
}

func TestInt64Or(t *testing.T) {
	v := int64(7)
	if int64Or(&v, 0) != 7 || int64Or(nil, 3) != 3 {
		t.Error("int64Or")
	}
}

func TestFlexibleInt64Or(t *testing.T) {
	v := FlexibleInt64(7)
	if flexibleInt64Or(&v, 0) != 7 || flexibleInt64Or(nil, 3) != 3 {
		t.Error("flexibleInt64Or")
	}
}

func TestIsInvestSaga(t *testing.T) {
	if !isInvestSaga(QueueFundSubscribeSuccess) {
		t.Error("subscribe queue is invest")
	}
	if isInvestSaga(QueueFundRedeemSuccess) {
		t.Error("redeem queue is not invest")
	}
}

func TestDecodeSagaEvent_Empty(t *testing.T) {
	var e SagaResultEvent
	if err := decodeSagaEvent(nil, &e); err != nil {
		t.Errorf("empty body should be nil err, got %v", err)
	}
}

func TestDecodeSagaEvent_BadJSON(t *testing.T) {
	var e SagaResultEvent
	if err := decodeSagaEvent([]byte("{bad"), &e); err == nil {
		t.Error("expected json error")
	}
}

// ---- buildSagaHandler ----

// completingService builds a *Service whose saga callbacks all succeed: the
// fake querier returns a PENDING tx on FindTransactionByID, an existing
// position, and a fund row, and Exec/insert succeed.
func completingService() (*Service, *fakeQuerier) {
	q := &fakeQuerier{
		rowFn: func(sql string, _ []any) *fakeRow {
			switch {
			case contains(sql, "FROM client_fund_transactions"):
				return &fakeRow{vals: txRow(1, 5, 2, "10.00", true, TxStatusPending)}
			case contains(sql, "FROM client_fund_positions"):
				return &fakeRow{vals: positionRow(1, 5, 2, "10.00")}
			case contains(sql, "FROM investment_funds"):
				return &fakeRow{vals: fundRow(2, "F", "100.00")}
			default:
				return &fakeRow{vals: []any{int64(1), int64(0)}} // RETURNING id,version
			}
		},
		execTag: tag("UPDATE 1"),
	}
	repo := NewRepositoryForTest(q)
	svc := NewServiceForTest(repo, nil, nil, nil, okMarket(), emptyAccount(), emptyEmployee(),
		&stubPublisher{}, FakeQRunner(q), discardLogger())
	return svc, q
}

func handlerFor(svc *Service, queue, key string, onSuccess bool) rabbitmq.Handler {
	b := consumerBinding{queue: queue, bindingKey: key, onSuccess: onSuccess, label: "test"}
	return buildSagaHandler(svc, b, discardLogger())
}

func env(body string) rabbitmq.Envelope { return rabbitmq.Envelope{Body: []byte(body)} }

func TestBuildSagaHandler_DecodeError(t *testing.T) {
	svc, _ := completingService()
	h := handlerFor(svc, QueueFundSubscribeSuccess, RoutingFundSubscribeSuccess, true)
	if got := h(ctx(), env("{bad"), amqp.Delivery{}); got != rabbitmq.Reject {
		t.Errorf("expected Reject, got %v", got)
	}
}

func TestBuildSagaHandler_MissingTxID(t *testing.T) {
	svc, _ := completingService()
	h := handlerFor(svc, QueueFundSubscribeSuccess, RoutingFundSubscribeSuccess, true)
	if got := h(ctx(), env(`{"clientId":5}`), amqp.Delivery{RoutingKey: "k"}); got != rabbitmq.Ack {
		t.Errorf("expected Ack (skip), got %v", got)
	}
}

func TestBuildSagaHandler_InvestSuccess(t *testing.T) {
	svc, _ := completingService()
	h := handlerFor(svc, QueueFundSubscribeSuccess, RoutingFundSubscribeSuccess, true)
	body := `{"transactionId":"1","clientId":5,"fundId":2,"amount":"10.00"}`
	if got := h(ctx(), env(body), amqp.Delivery{}); got != rabbitmq.Ack {
		t.Errorf("expected Ack, got %v", got)
	}
}

func TestBuildSagaHandler_RedeemSuccess(t *testing.T) {
	svc, _ := completingService()
	h := handlerFor(svc, QueueFundRedeemSuccess, RoutingFundRedeemSuccess, true)
	body := `{"transactionId":"1","clientId":5,"fundId":2,"amount":"5.00"}`
	if got := h(ctx(), env(body), amqp.Delivery{}); got != rabbitmq.Ack {
		t.Errorf("expected Ack, got %v", got)
	}
}

func TestBuildSagaHandler_Failure(t *testing.T) {
	svc, _ := completingService()
	h := handlerFor(svc, QueueFundSubscribeFailure, RoutingFundSubscribeFailure, false)
	body := `{"transactionId":"1","failureReason":"declined"}`
	if got := h(ctx(), env(body), amqp.Delivery{}); got != rabbitmq.Ack {
		t.Errorf("expected Ack, got %v", got)
	}
}

func TestBuildSagaHandler_Failure_NoReason(t *testing.T) {
	svc, _ := completingService()
	h := handlerFor(svc, QueueFundSubscribeFailure, RoutingFundSubscribeFailure, false)
	body := `{"transactionId":"1"}`
	if got := h(ctx(), env(body), amqp.Delivery{}); got != rabbitmq.Ack {
		t.Errorf("expected Ack, got %v", got)
	}
}

// erroringService builds a *Service whose callbacks fail (querier QueryRow
// errors), so the handler returns Reject.
func erroringService() *Service {
	boom := errors.New("db down")
	q := &fakeQuerier{
		rowFn:   func(_ string, _ []any) *fakeRow { return &fakeRow{err: boom} },
		execErr: boom,
	}
	repo := NewRepositoryForTest(q)
	return NewServiceForTest(repo, nil, nil, nil, okMarket(), emptyAccount(), emptyEmployee(),
		&stubPublisher{}, FakeQRunner(q), discardLogger())
}

func TestBuildSagaHandler_InvestError(t *testing.T) {
	svc := erroringService()
	h := handlerFor(svc, QueueFundSubscribeSuccess, RoutingFundSubscribeSuccess, true)
	body := `{"transactionId":"1","clientId":5,"fundId":2,"amount":"10.00"}`
	if got := h(ctx(), env(body), amqp.Delivery{}); got != rabbitmq.Reject {
		t.Errorf("expected Reject, got %v", got)
	}
}

func TestBuildSagaHandler_RedeemError(t *testing.T) {
	svc := erroringService()
	h := handlerFor(svc, QueueFundRedeemSuccess, RoutingFundRedeemSuccess, true)
	body := `{"transactionId":"1","clientId":5,"fundId":2,"amount":"5.00"}`
	if got := h(ctx(), env(body), amqp.Delivery{}); got != rabbitmq.Reject {
		t.Errorf("expected Reject, got %v", got)
	}
}

func TestBuildSagaHandler_FailError(t *testing.T) {
	svc := erroringService()
	h := handlerFor(svc, QueueFundSubscribeFailure, RoutingFundSubscribeFailure, false)
	body := `{"transactionId":"1","failureReason":"x"}`
	if got := h(ctx(), env(body), amqp.Delivery{}); got != rabbitmq.Reject {
		t.Errorf("expected Reject, got %v", got)
	}
}
