package worker

import "testing"

func TestSkipPersistedPath(t *testing.T) {
	accepted := map[string]struct{}{"skills/received": {}}
	retry := map[string]struct{}{"skills/retry": {}}
	cases := []struct {
		path       string
		checkpoint string
		skip       bool
	}{
		{"skills/received", "", true},
		{"skills/received", "skills/earlier", true},
		{"skills/earlier", "skills/retry", true},
		{"skills/retry", "skills/retry", false},
		{"skills/new", "skills/earlier", false},
	}
	for _, test := range cases {
		if got := skipPersistedPath(test.path, test.checkpoint, accepted, retry); got != test.skip {
			t.Errorf("path %q, checkpoint %q: skip = %t, want %t",
				test.path, test.checkpoint, got, test.skip)
		}
	}
}
