package main

import (
	"bufio"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestSequenceCountsOnlyMatchingSuccessfulCapture(t *testing.T) {
	t.Setenv("USERPROFILE", t.TempDir())
	t.Setenv("HOME", t.TempDir())
	a := newApp()
	suite, phone := net.Pipe()
	defer suite.Close()
	defer phone.Close()
	a.device.Connected = true
	a.control = suite
	a.writer = bufio.NewWriter(suite)
	go a.controlReadLoop(suite)

	if err := a.startSequence(map[string]any{"count": 2}); err != nil {
		t.Fatal(err)
	}
	if err := phone.SetDeadline(time.Now().Add(3 * time.Second)); err != nil {
		t.Fatal(err)
	}
	in, out := json.NewDecoder(phone), json.NewEncoder(phone)
	for i := 0; i < 2; i++ {
		var command struct {
			ID      string         `json:"id"`
			Payload map[string]any `json:"payload"`
		}
		if err := in.Decode(&command); err != nil {
			t.Fatal(err)
		}
		if err := out.Encode(map[string]any{"id": command.ID, "ok": true}); err != nil {
			t.Fatal(err)
		}
		id := command.Payload["capture_id"].(string)
		a.completeCapture(captureResult{id: "unrelated"})
		if i == 0 {
			a.completeCapture(captureResult{id: id})
		} else if err := out.Encode(map[string]any{"event": "capture_error", "capture_id": id, "message": "Camera write failed"}); err != nil {
			t.Fatal(err)
		}
	}

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		state := a.snapshot().Sequence
		if !state.Running {
			if state.Completed != 1 || state.Message != "Capture failed" {
				t.Fatalf("unexpected sequence result: %+v", state)
			}
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("sequence did not finish")
}

func TestEmbeddedDashboardServesCaptureControls(t *testing.T) {
	t.Setenv("USERPROFILE", t.TempDir())
	t.Setenv("HOME", t.TempDir())
	handler := newApp().routes()
	for path, content := range map[string]string{
		"/":              "1.0 Engineering RC",
		"/assets/app.js": "/api/video/start",
	} {
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, path, nil))
		if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), content) {
			t.Errorf("%s returned HTTP %d without %q", path, response.Code, content)
		}
	}
}

func TestSequenceWaitsForReconnectBetweenExposures(t *testing.T) {
	t.Setenv("USERPROFILE", t.TempDir())
	t.Setenv("HOME", t.TempDir())
	a := newApp()
	a.sequence.Message = "Capturing"
	cancel := make(chan struct{})
	defer close(cancel)
	dir := t.TempDir()

	result := make(chan error, 1)
	go func() { result <- a.waitForSequenceLink(cancel, dir) }()
	deadline := time.Now().Add(2 * time.Second)
	waiting := false
	for time.Now().Before(deadline) {
		if a.snapshot().Sequence.Message == "Waiting for phone" {
			waiting = true
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !waiting {
		t.Fatal("sequence did not enter the reconnect state")
	}
	select {
	case err := <-result:
		t.Fatalf("wait returned before reconnect: %v", err)
	default:
	}
	a.mu.Lock()
	a.device.Connected = true
	a.mu.Unlock()
	select {
	case err := <-result:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("wait did not return after reconnect")
	}
	if got := a.snapshot().Sequence.Message; got != "Capturing" {
		t.Fatalf("expected Capturing after reconnect, got %s", got)
	}
}
