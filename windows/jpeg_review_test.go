package main

import (
	"bytes"
	"image"
	"image/jpeg"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestSavedJPEGReviewServesOriginalStill(t *testing.T) {
	t.Setenv("USERPROFILE", t.TempDir())
	a := newApp()
	var original bytes.Buffer
	if err := jpeg.Encode(&original, image.NewGray(image.Rect(0, 0, 640, 480)), &jpeg.Options{Quality: 100}); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "validation.jpg")
	if err := os.WriteFile(path, original.Bytes(), 0600); err != nil {
		t.Fatal(err)
	}
	a.rememberJPEG(path, map[string]any{"jpeg_processing": "Validation JPEG · HAL high quality requested"})
	saved := a.snapshot().LastJPEG
	if saved == nil || saved.Width != 640 || saved.Height != 480 || saved.Name != "validation.jpg" {
		t.Fatalf("wrong saved still metadata: %#v", saved)
	}
	response := httptest.NewRecorder()
	a.routes().ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/api/last-jpeg", nil))
	if response.Code != http.StatusOK || !bytes.Equal(response.Body.Bytes(), original.Bytes()) {
		t.Fatalf("review did not return original JPEG bytes: status %d", response.Code)
	}
}
