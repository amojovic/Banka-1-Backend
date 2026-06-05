package tradingv1

import (
	"context"
	"errors"
	"testing"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
)

// TestGetters exercises every generated getter on both a populated value and a
// nil receiver (the `if x != nil` guard and the zero-value return branch).
func TestGetters(t *testing.T) {
	t.Run("PingResponse", func(t *testing.T) {
		populated := &PingResponse{JsonPayload: "hello"}
		if got := populated.GetJsonPayload(); got != "hello" {
			t.Fatalf("GetJsonPayload() = %q, want %q", got, "hello")
		}
		var nilResp *PingResponse
		if got := nilResp.GetJsonPayload(); got != "" {
			t.Fatalf("nil GetJsonPayload() = %q, want empty", got)
		}
	})

	t.Run("GetLatestAnalyticsRunResponse", func(t *testing.T) {
		populated := &GetLatestAnalyticsRunResponse{JsonPayload: "payload"}
		if got := populated.GetJsonPayload(); got != "payload" {
			t.Fatalf("GetJsonPayload() = %q, want %q", got, "payload")
		}
		var nilResp *GetLatestAnalyticsRunResponse
		if got := nilResp.GetJsonPayload(); got != "" {
			t.Fatalf("nil GetJsonPayload() = %q, want empty", got)
		}
	})
}

// protoMessage is the common surface every generated message implements, so the
// Reset/String/ProtoReflect/Descriptor table can run over all four types.
type protoMessage interface {
	proto.Message
	Reset()
	String() string
	ProtoMessage()
}

// TestMessageMethods covers Reset, String, ProtoReflect (populated + nil
// receiver → mi.MessageOf branch) and Descriptor for every message type.
func TestMessageMethods(t *testing.T) {
	cases := []struct {
		name string
		// fresh returns a brand-new, never-Reset message so the first
		// ProtoReflect call exercises the StoreMessageInfo branch
		// (LoadMessageInfo() == nil).
		fresh   func() protoMessage
		nilRecv protoMessage
	}{
		{
			name:    "PingRequest",
			fresh:   func() protoMessage { return &PingRequest{} },
			nilRecv: (*PingRequest)(nil),
		},
		{
			name:    "PingResponse",
			fresh:   func() protoMessage { return &PingResponse{JsonPayload: "x"} },
			nilRecv: (*PingResponse)(nil),
		},
		{
			name:    "GetLatestAnalyticsRunRequest",
			fresh:   func() protoMessage { return &GetLatestAnalyticsRunRequest{} },
			nilRecv: (*GetLatestAnalyticsRunRequest)(nil),
		},
		{
			name:    "GetLatestAnalyticsRunResponse",
			fresh:   func() protoMessage { return &GetLatestAnalyticsRunResponse{JsonPayload: "y"} },
			nilRecv: (*GetLatestAnalyticsRunResponse)(nil),
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			// ProtoReflect on a fresh message: the first call hits the
			// LoadMessageInfo()==nil → StoreMessageInfo branch.
			fresh := tc.fresh()
			m := fresh.ProtoReflect()
			if m == nil {
				t.Fatal("ProtoReflect() returned nil for fresh message")
			}
			// Second call on the same instance exercises the
			// LoadMessageInfo()!=nil short path.
			if m2 := fresh.ProtoReflect(); m2 == nil {
				t.Fatal("second ProtoReflect() returned nil")
			}

			// ProtoReflect on a nil receiver hits the mi.MessageOf(x) branch.
			if nm := tc.nilRecv.ProtoReflect(); nm == nil {
				t.Fatal("ProtoReflect() returned nil for nil receiver")
			}

			// Reset on a populated message.
			populated := tc.fresh()
			populated.Reset()

			// String on a populated message.
			_ = populated.String()

			// ProtoMessage marker method (no-op) — call directly to cover it.
			populated.ProtoMessage()

			// Sanity: the value satisfies proto.Message.
			if _, ok := populated.(proto.Message); !ok {
				t.Fatal("value does not satisfy proto.Message")
			}
		})
	}
}

// TestDescriptors covers the deprecated Descriptor() funcs for each type.
func TestDescriptors(t *testing.T) {
	descriptors := []func() ([]byte, []int){
		(&PingRequest{}).Descriptor,
		(&PingResponse{}).Descriptor,
		(&GetLatestAnalyticsRunRequest{}).Descriptor,
		(&GetLatestAnalyticsRunResponse{}).Descriptor,
	}
	for i, d := range descriptors {
		raw, idx := d()
		if len(raw) == 0 {
			t.Fatalf("descriptor[%d] returned empty gzip bytes", i)
		}
		if len(idx) == 0 {
			t.Fatalf("descriptor[%d] returned empty index path", i)
		}
	}
}

// stubServer implements TradingServiceServer with no business logic so the
// _Handler funcs can dispatch into it. It embeds Unimplemented for forward
// compatibility but overrides both RPCs.
type stubServer struct {
	UnimplementedTradingServiceServer
	pingResp *PingResponse
	runResp  *GetLatestAnalyticsRunResponse
	err      error
}

func (s *stubServer) Ping(context.Context, *PingRequest) (*PingResponse, error) {
	return s.pingResp, s.err
}

func (s *stubServer) GetLatestAnalyticsRun(context.Context, *GetLatestAnalyticsRunRequest) (*GetLatestAnalyticsRunResponse, error) {
	return s.runResp, s.err
}

// TestUnaryHandlers exercises both generated _Handler funcs across all three
// branches: dec error, no-interceptor direct dispatch, and interceptor path.
func TestUnaryHandlers(t *testing.T) {
	srv := &stubServer{
		pingResp: &PingResponse{JsonPayload: "pong"},
		runResp:  &GetLatestAnalyticsRunResponse{JsonPayload: "run"},
	}

	handlers := []struct {
		name string
		fn   func(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error)
	}{
		{"Ping", _TradingService_Ping_Handler},
		{"GetLatestAnalyticsRun", _TradingService_GetLatestAnalyticsRun_Handler},
	}

	for _, h := range handlers {
		t.Run(h.name+"/dec-error", func(t *testing.T) {
			decErr := errors.New("decode boom")
			dec := func(interface{}) error { return decErr }
			_, err := h.fn(srv, context.Background(), dec, nil)
			if !errors.Is(err, decErr) {
				t.Fatalf("dec-error path err = %v, want %v", err, decErr)
			}
		})

		t.Run(h.name+"/no-interceptor", func(t *testing.T) {
			dec := func(interface{}) error { return nil }
			resp, err := h.fn(srv, context.Background(), dec, nil)
			if err != nil {
				t.Fatalf("no-interceptor path err = %v", err)
			}
			if resp == nil {
				t.Fatal("no-interceptor path returned nil response")
			}
		})

		t.Run(h.name+"/interceptor", func(t *testing.T) {
			dec := func(interface{}) error { return nil }
			var sawInfo bool
			interceptor := func(ctx context.Context, req interface{}, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (interface{}, error) {
				sawInfo = info != nil
				return handler(ctx, req)
			}
			resp, err := h.fn(srv, context.Background(), dec, interceptor)
			if err != nil {
				t.Fatalf("interceptor path err = %v", err)
			}
			if resp == nil {
				t.Fatal("interceptor path returned nil response")
			}
			if !sawInfo {
				t.Fatal("interceptor was not invoked with info")
			}
		})
	}
}

// TestUnimplementedServer covers the Unimplemented* fallbacks and the embedded
// marker methods.
func TestUnimplementedServer(t *testing.T) {
	var u UnimplementedTradingServiceServer

	if _, err := u.Ping(context.Background(), &PingRequest{}); status.Code(err) != codes.Unimplemented {
		t.Fatalf("Ping err code = %v, want Unimplemented", status.Code(err))
	}
	if _, err := u.GetLatestAnalyticsRun(context.Background(), &GetLatestAnalyticsRunRequest{}); status.Code(err) != codes.Unimplemented {
		t.Fatalf("GetLatestAnalyticsRun err code = %v, want Unimplemented", status.Code(err))
	}

	// Marker methods (no-ops) — call to cover them.
	u.mustEmbedUnimplementedTradingServiceServer()
	u.testEmbeddedByValue()
}

// fakeRegistrar captures the ServiceDesc/impl passed to RegisterService so we can
// cover RegisterTradingServiceServer (including the testEmbeddedByValue branch).
type fakeRegistrar struct {
	desc *grpc.ServiceDesc
	impl interface{}
}

func (f *fakeRegistrar) RegisterService(desc *grpc.ServiceDesc, impl interface{}) {
	f.desc = desc
	f.impl = impl
}

func TestRegisterTradingServiceServer(t *testing.T) {
	reg := &fakeRegistrar{}
	srv := &stubServer{}
	RegisterTradingServiceServer(reg, srv)
	if reg.desc != &TradingService_ServiceDesc {
		t.Fatal("RegisterService not called with TradingService_ServiceDesc")
	}
	if reg.impl != srv {
		t.Fatal("RegisterService not called with the provided server")
	}
	if reg.desc.ServiceName != "trading.v1.TradingService" {
		t.Fatalf("ServiceName = %q", reg.desc.ServiceName)
	}
	if len(reg.desc.Methods) != 2 {
		t.Fatalf("len(Methods) = %d, want 2", len(reg.desc.Methods))
	}
}

// fakeClientConn is a stub grpc.ClientConnInterface used to drive the generated
// client Invoke wrappers down both the success and error branches.
type fakeClientConn struct {
	err          error
	lastMethod   string
	fillResponse func(reply interface{})
}

func (f *fakeClientConn) Invoke(ctx context.Context, method string, args, reply interface{}, opts ...grpc.CallOption) error {
	f.lastMethod = method
	if f.err != nil {
		return f.err
	}
	if f.fillResponse != nil {
		f.fillResponse(reply)
	}
	return nil
}

func (f *fakeClientConn) NewStream(ctx context.Context, desc *grpc.StreamDesc, method string, opts ...grpc.CallOption) (grpc.ClientStream, error) {
	return nil, errors.New("streaming not supported")
}

func TestClientPing(t *testing.T) {
	t.Run("ok", func(t *testing.T) {
		conn := &fakeClientConn{
			fillResponse: func(reply interface{}) {
				reply.(*PingResponse).JsonPayload = "pong"
			},
		}
		client := NewTradingServiceClient(conn)
		resp, err := client.Ping(context.Background(), &PingRequest{})
		if err != nil {
			t.Fatalf("Ping err = %v", err)
		}
		if resp.GetJsonPayload() != "pong" {
			t.Fatalf("Ping resp = %q", resp.GetJsonPayload())
		}
		if conn.lastMethod != TradingService_Ping_FullMethodName {
			t.Fatalf("method = %q", conn.lastMethod)
		}
	})

	t.Run("error", func(t *testing.T) {
		conn := &fakeClientConn{err: errors.New("rpc boom")}
		client := NewTradingServiceClient(conn)
		resp, err := client.Ping(context.Background(), &PingRequest{})
		if err == nil {
			t.Fatal("expected error")
		}
		if resp != nil {
			t.Fatal("expected nil response on error")
		}
	})
}

func TestClientGetLatestAnalyticsRun(t *testing.T) {
	t.Run("ok", func(t *testing.T) {
		conn := &fakeClientConn{
			fillResponse: func(reply interface{}) {
				reply.(*GetLatestAnalyticsRunResponse).JsonPayload = "run"
			},
		}
		client := NewTradingServiceClient(conn)
		resp, err := client.GetLatestAnalyticsRun(context.Background(), &GetLatestAnalyticsRunRequest{})
		if err != nil {
			t.Fatalf("GetLatestAnalyticsRun err = %v", err)
		}
		if resp.GetJsonPayload() != "run" {
			t.Fatalf("resp = %q", resp.GetJsonPayload())
		}
		if conn.lastMethod != TradingService_GetLatestAnalyticsRun_FullMethodName {
			t.Fatalf("method = %q", conn.lastMethod)
		}
	})

	t.Run("error", func(t *testing.T) {
		conn := &fakeClientConn{err: errors.New("rpc boom")}
		client := NewTradingServiceClient(conn)
		resp, err := client.GetLatestAnalyticsRun(context.Background(), &GetLatestAnalyticsRunRequest{})
		if err == nil {
			t.Fatal("expected error")
		}
		if resp != nil {
			t.Fatal("expected nil response on error")
		}
	})
}
