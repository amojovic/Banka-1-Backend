package clients

import (
	"errors"
	"io"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type failingReader struct{}

func (failingReader) Read([]byte) (int, error) {
	return 0, errors.New("read failed")
}

func TestIOReadAllReturnsBytes(t *testing.T) {
	t.Parallel()

	got, err := ioReadAll(strings.NewReader("payload"))

	require.NoError(t, err)
	assert.Equal(t, []byte("payload"), got)
}

func TestIOReadAllReturnsReadError(t *testing.T) {
	t.Parallel()

	got, err := ioReadAll(failingReader{})

	require.Error(t, err)
	assert.Empty(t, got)
	assert.Contains(t, err.Error(), "read failed")
}

func TestIOReadAllHandlesEmptyReader(t *testing.T) {
	t.Parallel()

	got, err := ioReadAll(io.LimitReader(strings.NewReader("payload"), 0))

	require.NoError(t, err)
	assert.Empty(t, got)
}
