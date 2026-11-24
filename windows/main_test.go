package main

import (
	"bufio"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
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
		"/":              "1.0.1 Stability RC",
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

func TestPreviewFreshnessAndMeasuredTraffic(t *testing.T) {
	a := &App{device: DeviceState{Connected: true}}
	start := time.Now().Add(-time.Second)
	for i := 0; i <= 20; i++ {
		a.storeFrameLocked(make([]byte, 1000+i*100), start.Add(time.Duration(i)*50*time.Millisecond))
	}
	m := a.snapshot().Metrics
	if !m.Live || m.LastFrame == nil || m.Frames != 21 || m.FPS != 20 || m.BitrateMbps != 0.32864 {
		t.Fatalf("unexpected measured traffic: %+v", m)
	}
	handler := a.routes()
	fresh := httptest.NewRecorder()
	handler.ServeHTTP(fresh, httptest.NewRequest(http.MethodGet, "/api/preview.jpg", nil))
	if fresh.Code != http.StatusOK || fresh.Body.Len() != 3000 {
		t.Fatalf("fresh frame: HTTP %d, %d bytes", fresh.Code, fresh.Body.Len())
	}
	a.lastFrameTime = time.Now().Add(-4 * time.Second)
	stale := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/api/preview.jpg", nil)
	request.Header.Set("If-None-Match", fresh.Header().Get("ETag"))
	handler.ServeHTTP(stale, request)
	if stale.Code != http.StatusNoContent || stale.Body.Len() != 0 {
		t.Fatalf("stale frame served: HTTP %d", stale.Code)
	}
	m = a.snapshot().Metrics
	if m.Live || m.FPS != 0 || m.BitrateMbps != 0 || m.LastFrame == nil {
		t.Fatalf("stale stream displayed as live: %+v", m)
	}
	a.storeFrameLocked([]byte("new frame"), time.Now())
	if !a.snapshot().Metrics.Live {
		t.Fatal("preview did not recover when a fresh frame arrived")
	}
}

func TestCancelCaptureAcknowledgmentReleasesLock(t *testing.T) {
	a := transportTestApp(t)
	desktop, phone := net.Pipe()
	defer phone.Close()
	_ = phone.SetDeadline(time.Now().Add(4 * time.Second))
	a.control, a.writer = desktop, bufio.NewWriter(desktop)
	a.device.Connected = true
	go a.controlReadLoop(desktop)
	decoder, encoder := json.NewDecoder(phone), json.NewEncoder(phone)
	command := func(want string) string {
		t.Helper()
		var request struct {
			ID      string
			Cmd     string
			Payload map[string]any
		}
		if err := decoder.Decode(&request); err != nil {
			t.Fatal(err)
		}
		if request.Cmd != want {
			t.Fatalf("received %s, expected %s", request.Cmd, want)
		}
		if err := encoder.Encode(map[string]any{"id": request.ID, "ok": true}); err != nil {
			t.Fatal(err)
		}
		return request.Payload["capture_id"].(string)
	}
	if err := a.startSequence(map[string]any{"count": 2}); err != nil {
		t.Fatal(err)
	}
	id := command("capture")
	a.stopSequence()
	if s := a.snapshot().Sequence; !s.Running || s.Phase != "stopping" {
		t.Fatalf("unexpected cancel state: %+v", s)
	}
	if cancelled := command("cancel_capture"); cancelled != id {
		t.Fatalf("cancelled %s rather than %s", cancelled, id)
	}
	waitFor := func(want string) {
		t.Helper()
		for until := time.Now().Add(time.Second); time.Now().Before(until); {
			if s := a.snapshot().Sequence; !s.Running {
				if s.Phase != "completed" || s.Message != want {
					t.Fatalf("unexpected final state: %+v", s)
				}
				return
			}
			time.Sleep(5 * time.Millisecond)
		}
		t.Fatal("capture lock stayed active")
	}
	waitFor("Stopped")
	if err := a.runSequence(map[string]any{"jpeg": true}, nil, true); err != nil {
		t.Fatal(err)
	}
	restarted := command("capture")
	if restarted == id {
		t.Fatal("restart reused the cancelled capture ID")
	}
	a.completeCapture(captureResult{id: restarted})
	waitFor("Complete")
}

func TestOccupiedPortOnlyReusesCurrentDashboard(t *testing.T) {
	var current atomic.Bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		oldVersion := "1.0 Engineering RC"
		if current.Load() {
			oldVersion = version
		}
		writeJSON(w, map[string]string{"app": appName, "version": oldVersion})
	}))
	defer server.Close()
	address := strings.TrimPrefix(server.URL, "http://")
	if runningVersionMatches(address) {
		t.Fatal("new executable would open an older dashboard")
	}
	current.Store(true)
	if !runningVersionMatches(address) {
		t.Fatal("current dashboard would not be reused")
	}
}
