package main

import (
	"bufio"
	"encoding/binary"
	"encoding/json"
	"errors"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"
)

func TestUSBDetection(t *testing.T) {
	for _, tc := range []struct {
		name, output, state string
		pnp                 bool
	}{
		{"device missing", "error: no devices/emulators found", "no_data", false},
		{"data device without debugger", "error: no devices/emulators found", "debugging_unavailable", true},
		{"first authorization", "error: device unauthorized", "unauthorized", true},
		{"authorized", "device", "authorized", true},
		{"offline", "error: device offline", "reconnecting", true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			a := adbTunnel{usbPresent: func() bool { return tc.pnp }, run: func(args ...string) (string, error) {
				if args[1] == "get-serialno" {
					return "wired-phone", nil
				}
				if tc.output == "device" {
					return tc.output, nil
				}
				return tc.output, errors.New("adb rejected request")
			}}
			id, status := a.detect()
			if status.State != tc.state {
				t.Fatalf("status %+v", status)
			}
			if (id == "wired-phone") != (tc.state == "authorized") {
				t.Fatalf("serial %q", id)
			}
		})
	}
}

func TestTransportPriority(t *testing.T) {
	usb := endpoint{kind: transportUSB, host: "127.0.0.1", control: 50001}
	got := transportOrder(usb, "192.168.1.2", "192.168.1.3", "192.168.1.4")
	if len(got) != 4 || got[0] != usb || got[1].host != "192.168.1.2" || got[2].host != "192.168.1.3" || got[3].host != "192.168.1.4" {
		t.Fatal(got)
	}
	if got := transportOrder(endpoint{}, "", "192.168.1.3", "192.168.1.3"); len(got) != 1 {
		t.Fatal(got)
	}
}

type fakeTransportPhone struct {
	control     net.Listener
	file        net.Listener
	id          string
	mu          sync.Mutex
	captures    []string
	omitReceipt bool
}

func startFakeTransportPhone(t *testing.T, bindControl, bindFile string, omitReceipt bool) *fakeTransportPhone {
	t.Helper()
	listen := func(addr string) net.Listener {
		l, err := net.Listen("tcp", addr)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = l.Close() })
		return l
	}
	f := &fakeTransportPhone{control: listen(bindControl), file: listen(bindFile), id: "test-handset", omitReceipt: omitReceipt}
	go func() {
		for {
			c, err := f.control.Accept()
			if err != nil {
				return
			}
			go f.serveControl(c)
		}
	}()
	go func() {
		for {
			c, err := f.file.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				if _, err := bufio.NewReader(c).ReadBytes('\n'); err != nil {
					return
				}
				data := []byte("test image")
				_ = binary.Write(c, binary.BigEndian, int64(len(data)))
				_, _ = c.Write(data)
			}()
		}
	}()
	return f
}

func (f *fakeTransportPhone) serveControl(c net.Conn) {
	defer c.Close()
	in := json.NewDecoder(c)
	out := json.NewEncoder(c)
	for {
		var request struct {
			ID      string
			Cmd     string
			Payload map[string]any
		}
		if in.Decode(&request) != nil {
			return
		}
		response := map[string]any{"id": request.ID, "ok": true}
		switch request.Cmd {
		case "hello", "probe":
			response["app"], response["protocol"], response["device_id"] = "TOD Astro Photography", 2, f.id
		case "capabilities":
			response["camera_id"] = "main"
		}
		if out.Encode(response) != nil {
			return
		}
		if request.Cmd != "capture" {
			continue
		}
		id, _ := request.Payload["capture_id"].(string)
		f.mu.Lock()
		f.captures = append(f.captures, id)
		f.mu.Unlock()
		if f.omitReceipt {
			continue
		}
		if out.Encode(map[string]any{"event": "capture_saved", "capture_id": id, "files": []string{"/captures/eclipse.jpg"}}) != nil {
			return
		}
	}
}

func fakeADB(state *string, usb *fakeTransportPhone) *adbTunnel {
	return &adbTunnel{run: func(args ...string) (string, error) {
		switch args[1] {
		case "get-state":
			if *state == "device" {
				return "device", nil
			}
			if *state == "unauthorized" {
				return "error: device unauthorized", errors.New("unauthorized")
			}
			return "error: no devices/emulators found", errors.New("no device")
		case "get-serialno":
			return "wired", nil
		}
		if args[3] == "--remove" {
			return "", nil
		}
		p := 61091
		switch args[len(args)-1] {
		case "tcp:47000":
			p = usb.control.Addr().(*net.TCPAddr).Port
		case "tcp:47002":
			p = usb.file.Addr().(*net.TCPAddr).Port
		}
		return strconv.Itoa(p), nil
	}}
}

func transportTestApp(t *testing.T) *App {
	t.Helper()
	t.Setenv("USERPROFILE", t.TempDir())
	a := newApp()
	a.lanPath, a.knownLAN = "", ""
	t.Cleanup(func() {
		close(a.stop)
		a.disconnect("")
		if a.usb != nil {
			a.usb.close()
		}
	})
	return a
}

func wantTransport(t *testing.T, a *App, kind, state string) {
	t.Helper()
	d := a.snapshot().Device
	if d.Transport != kind || d.USB.State != state {
		t.Fatalf("transport=%s USB=%+v; want %s/%s", d.Transport, d.USB, kind, state)
	}
}

func TestUSBTransportTransitions(t *testing.T) {
	lan := startFakeTransportPhone(t, "127.0.0.1:47000", "127.0.0.1:47002", false)
	usb := startFakeTransportPhone(t, "127.0.0.1:0", "127.0.0.1:0", false)
	a := transportTestApp(t)
	state := "missing"
	a.usb = fakeADB(&state, usb)
	discoveryCount := 0
	lanIP := "127.0.0.1"
	a.discover = func() string { discoveryCount++; return lanIP }
	a.refreshTransport()
	wantTransport(t, a, "LAN", "no_data")
	state = "unauthorized"
	a.refreshTransport()
	wantTransport(t, a, "LAN", "unauthorized")
	state = "device"
	a.refreshTransport()
	wantTransport(t, a, "USB", "connected")
	previous := discoveryCount
	lanIP = "192.0.2.55"
	a.refreshTransport()
	wantTransport(t, a, "USB", "connected")
	if discoveryCount != previous {
		t.Fatal("USB unexpectedly depended on phone LAN IP")
	}
	a.mu.RLock()
	target := a.active
	a.mu.RUnlock()
	dir := t.TempDir()
	file, err := a.fetchFile(target, "/captures/frame.dng", dir, dir)
	if err != nil {
		t.Fatal(err)
	}
	if b, err := os.ReadFile(file); err != nil || string(b) != "test image" {
		t.Fatalf("USB data transfer = %q, %v", b, err)
	}
	state = "missing"
	lanIP = ""
	a.refreshTransport()
	wantTransport(t, a, "LAN", "lost")
	if lan.id != a.phoneID {
		t.Fatal("session phone identity changed")
	}
	state = "device"
	a.refreshTransport()
	wantTransport(t, a, "USB", "connected")
}

func TestSequenceContinuesAfterUSBDisconnection(t *testing.T) {
	lan := startFakeTransportPhone(t, "127.0.0.1:47000", "127.0.0.1:47002", false)
	usb := startFakeTransportPhone(t, "127.0.0.1:0", "127.0.0.1:0", true)
	a := transportTestApp(t)
	state := "device"
	a.usb = fakeADB(&state, usb)
	a.discover = func() string { return "127.0.0.1" }
	a.refreshTransport()
	wantTransport(t, a, "USB", "connected")
	if err := a.startSequence(map[string]any{"count": 1, "target": "Moon"}); err != nil {
		t.Fatal(err)
	}
	var first string
	until(t, func() bool {
		usb.mu.Lock()
		defer usb.mu.Unlock()
		if len(usb.captures) == 0 {
			return false
		}
		first = usb.captures[0]
		return true
	})
	original := a.snapshot().Session.CurrentDir
	state = "missing"
	a.refreshTransport()
	wantTransport(t, a, "LAN", "lost")
	until(t, func() bool { return a.snapshot().Sequence.Completed == 1 })
	lan.mu.Lock()
	defer lan.mu.Unlock()
	if len(lan.captures) != 1 || lan.captures[0] != first {
		t.Fatalf("capture ID changed on fallback: %s %v", first, lan.captures)
	}
	if _, err := os.Stat(filepath.Join(original, "Light", "eclipse.jpg")); err != nil {
		t.Fatal(err)
	}
}

func until(t *testing.T, check func() bool) {
	t.Helper()
	deadline := time.Now().Add(6 * time.Second)
	for time.Now().Before(deadline) {
		if check() {
			return
		}
		time.Sleep(15 * time.Millisecond)
	}
	t.Fatal("transport state did not settle")
}
