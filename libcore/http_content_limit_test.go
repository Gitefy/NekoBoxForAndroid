package libcore

import (
	"io"
	"net/http"
	"strings"
	"testing"
)

type countedBody struct {
	*strings.Reader
	read   int
	closed bool
}

func (b *countedBody) Read(p []byte) (int, error) {
	n, err := b.Reader.Read(p)
	b.read += n
	return n, err
}
func (b *countedBody) Close() error { b.closed = true; return nil }

func TestHTTPContentSizeLimit(t *testing.T) {
	for _, size := range []int{0, 8, 9, 10000} {
		body := &countedBody{Reader: strings.NewReader(strings.Repeat("a", size))}
		response := &httpResponse{Response: &http.Response{Body: body}}
		content, err := response.GetContentStringLimited(8)
		if (err != nil) != (size > 8) {
			t.Fatalf("size %d: unexpected error %v", size, err)
		}
		if size <= 8 && content.Value != strings.Repeat("a", size) {
			t.Fatal("content changed")
		}
		if !body.closed || body.read > 9 {
			t.Fatalf("unbounded read or open body: %+v", body)
		}
	}
}

func TestHTTPUnlimitedContentStillWorks(t *testing.T) {
	response := &httpResponse{Response: &http.Response{Body: io.NopCloser(strings.NewReader("ordinary response"))}}
	data, err := response.GetContent()
	if err != nil || string(data) != "ordinary response" {
		t.Fatalf("%q: %v", data, err)
	}
}

func TestHTTPSizeFailureCannotReturnTruncatedSuccess(t *testing.T) {
	response := &httpResponse{Response: &http.Response{Body: io.NopCloser(strings.NewReader("oversized response"))}}
	if _, err := response.GetContentStringLimited(4); err == nil {
		t.Fatal("expected size failure")
	}
	if data, err := response.GetContent(); err == nil {
		t.Fatalf("truncated content reported as success: %q", data)
	}
}
