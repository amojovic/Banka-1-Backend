package otc

import "testing"

func TestForTestConstructors_Smoke(t *testing.T) {
	if NewServiceForTest(nil, nil, nil, nil, nil, nil, nil, nil, nil) == nil {
		t.Fatal("nil service")
	}
	if NewReservationServiceForTest(nil, nil, nil, nil) == nil {
		t.Fatal("nil reservation service")
	}
}
