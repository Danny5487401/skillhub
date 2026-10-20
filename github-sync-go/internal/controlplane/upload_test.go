package controlplane

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"testing"
)

func TestStagingUploadUsesDirectTransport(t *testing.T) {
	transport, ok := directUploadClient().Transport.(*http.Transport)
	if !ok || transport.Proxy != nil {
		t.Fatal("staging upload must not use the GitHub proxy")
	}
}

func TestS3UploadUsesOnlySignedHeaders(t *testing.T) {
	const contents = "Skill package fixture"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		if request.Method != http.MethodPut || request.URL.Path != "/staging/package.zip" {
			t.Errorf("unexpected upload target: %s %s", request.Method, request.URL.Path)
		}
		if request.Header.Get("Authorization") != "" || request.Header.Get("X-Upload-Ticket") != "" {
			t.Error("service credentials reached object storage")
		}
		if request.Header.Get("Content-Type") != "application/zip" {
			t.Errorf("signed content type was not forwarded: %q", request.Header.Get("Content-Type"))
		}
		body, err := io.ReadAll(request.Body)
		if err != nil || string(body) != contents {
			t.Errorf("unexpected uploaded package: %q, %v", body, err)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	archive := filepath.Join(t.TempDir(), "package.zip")
	if err := os.WriteFile(archive, []byte(contents), 0600); err != nil {
		t.Fatal(err)
	}
	client, err := NewClient(server.URL+"/internal/github-sync/v1", "a-32-character-internal-service-token")
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := url.Parse(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	authorization := UploadAuthorization{
		Mode: "S3_PRESIGNED_PUT", UploadURL: server.URL + "/staging/package.zip",
		MaxBytes: int64(len(contents)), Headers: map[string]string{"Content-Type": "application/zip"},
	}
	if err := client.UploadPackage(context.Background(), authorization, archive, []string{parsed.Host}); err != nil {
		t.Fatal(err)
	}
}
