package main

import (
	"bufio"
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"embed"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"image"
	"image/jpeg"
	"io"
	"io/fs"
	"mime"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	appName          = "TOD Astro Suite"
	version          = "1.0.1 Stability RC"
	controlPort      = 47000
	previewPort      = 47001
	filePort         = 47002
	previewFreshness = 3 * time.Second
	discoveryPort    = 47003
	httpAddr         = "127.0.0.1:47871"
)

//go:embed web/*
var webFS embed.FS

type Metrics struct {
	LastFrame     *time.Time    `json:"last_frame,omitempty"`
	Live          bool          `json:"live"`
	Star          *StellarFocus `json:"star,omitempty"`
	FocusHistory  []float64     `json:"focus_history"`
	FPS           float64       `json:"fps"`
	BitrateMbps   float64       `json:"bitrate_mbps"`
	FocusScore    float64       `json:"focus_score"`
	Mean          float64       `json:"mean"`
	Median        int           `json:"median"`
	ShadowClip    float64       `json:"shadow_clip"`
	HighlightClip float64       `json:"highlight_clip"`
	Histogram     [256]uint64   `json:"histogram"`
	Frames        uint64        `json:"frames"`
	Dropped       uint64        `json:"dropped"`
}

type DeviceState struct {
	Transport    string         `json:"transport"`
	USB          usbStatus      `json:"usb"`
	Connected    bool           `json:"connected"`
	Host         string         `json:"host"`
	Status       string         `json:"status"`
	Camera       string         `json:"camera"`
	Protocol     int            `json:"protocol"`
	Capabilities map[string]any `json:"capabilities"`
	Telemetry    map[string]any `json:"telemetry"`
	LastError    string         `json:"last_error"`
}

type VideoState struct {
	CameraFrames   int            `json:"camera_frames"`
	CameraFailures int            `json:"camera_failures"`
	Review         map[string]any `json:"review,omitempty"`
	Recording      bool           `json:"recording"`
	Path           string         `json:"path"`
	Width          int            `json:"width"`
	Height         int            `json:"height"`
	FPS            int            `json:"fps"`
	MinFPS         int            `json:"fps_min,omitempty"`
	StartedAt      string         `json:"started_at"`
	Message        string         `json:"message"`
}

type SequenceState struct {
	Phase         string `json:"phase"`
	Running       bool   `json:"running"`
	Target        string `json:"target"`
	Mode          string `json:"mode"`
	FrameType     string `json:"frame_type"`
	Requested     int    `json:"requested"`
	Completed     int    `json:"completed"`
	IntervalMs    int    `json:"interval_ms"`
	StartedAt     string `json:"started_at"`
	IntegrationUS int64  `json:"integration_us"`
	Message       string `json:"message"`
}

type SessionState struct {
	Target        string   `json:"target"`
	Mode          string   `json:"mode"`
	Root          string   `json:"root"`
	CurrentDir    string   `json:"current_dir"`
	CapturedFiles []string `json:"captured_files"`
	SirilStatus   string   `json:"siril_status"`
	SirilPath     string   `json:"siril_path"`
}

type JPEGReview struct {
	Name       string    `json:"name"`
	Width      int       `json:"width"`
	Height     int       `json:"height"`
	Processing string    `json:"processing"`
	Received   time.Time `json:"received"`
	path       string
}

type StateSnapshot struct {
	LastJPEG   *JPEGReview   `json:"last_jpeg,omitempty"`
	FocusPoint *FocusPoint   `json:"focus_point,omitempty"`
	App        string        `json:"app"`
	Version    string        `json:"version"`
	Device     DeviceState   `json:"device"`
	Metrics    Metrics       `json:"metrics"`
	Video      VideoState    `json:"video"`
	Sequence   SequenceState `json:"sequence"`
	Session    SessionState  `json:"session"`
	Stack      StackStats    `json:"stack"`
}

type controlResponse struct {
	ID    string `json:"id"`
	OK    bool   `json:"ok"`
	Error string `json:"error"`
}

type captureResult struct {
	id  string
	err error
}

type App struct {
	lastJPEG         *JPEGReview
	focusPoint       *FocusPoint
	active           endpoint
	phoneID          string
	clientSession    string
	linkChanged      chan struct{}
	knownLAN         string
	manualLAN        string
	lanPath          string
	usb              *adbTunnel
	discover         func() string
	discoveryDone    chan struct{}
	captureTransfers map[string]bool
	mu               sync.RWMutex
	device           DeviceState
	metrics          Metrics
	video            VideoState
	sequence         SequenceState
	session          SessionState
	sessionID        string
	currentControls  map[string]any
	videoSession     string
	videoSessions    map[string]string
	stack            *LiveStack

	latestFrame     []byte
	frameETag       string
	lastFrameTime   time.Time
	frameBytes      uint64
	windowFrames    uint64
	windowStart     time.Time
	lastHeartbeat   time.Time
	videoTransition time.Time
	frameCounter    uint64

	connMu           sync.Mutex
	control          net.Conn
	writer           *bufio.Writer
	pending          map[string]chan map[string]any
	seqID            atomic.Uint64
	stop             chan struct{}
	reconnect        chan struct{}
	captureDone      chan captureResult
	captureID        string
	sequenceCancel   chan struct{}
	sequenceFinished chan struct{}
	sequenceStacking bool
	diagnostics      []diagnosticEvent
	quitApp          chan struct{}
}

func newApp() *App {
	pictures := filepath.Join(userHome(), "Pictures", "TOD Astro Photography")
	_ = os.MkdirAll(pictures, 0755)
	path := lanPreferencePath()
	var clientKey [16]byte
	_, _ = rand.Read(clientKey[:])
	a := &App{
		linkChanged: make(chan struct{}), discoveryDone: make(chan struct{}), captureTransfers: make(map[string]bool),
		lanPath: path, knownLAN: loadLANPreference(path), discover: discoverPhone, usb: bundledADB(), clientSession: hex.EncodeToString(clientKey[:]),
		device:   DeviceState{Transport: "Offline", Status: "Waiting for TOD Astro Photography", Capabilities: map[string]any{}, Telemetry: map[string]any{}},
		video:    VideoState{Message: "Idle"},
		sequence: SequenceState{Mode: "Planetary", FrameType: "Light", Phase: "idle", Message: "Idle"},
		session:  SessionState{Target: "Jupiter", Mode: "Planetary", Root: pictures, CurrentDir: pictures, SirilPath: detectSiril()},
		pending:  map[string]chan map[string]any{}, stop: make(chan struct{}), reconnect: make(chan struct{}, 1), captureDone: make(chan captureResult, 1), quitApp: make(chan struct{}, 1),
	}
	return a
}

func userHome() string {
	if v := os.Getenv("USERPROFILE"); v != "" {
		return v
	}
	if v, err := os.UserHomeDir(); err == nil {
		return v
	}
	return "."
}

func detectSiril() string {
	if exe, err := os.Executable(); err == nil {
		bundled := bundledSiril(filepath.Join(filepath.Dir(exe), "siril"))
		if bundled != "" {
			return bundled
		}
	}
	candidates := []string{
		filepath.Join(os.Getenv("ProgramFiles"), "Siril", "bin", "siril-cli.exe"),
		filepath.Join(os.Getenv("ProgramFiles"), "Siril", "siril-cli.exe"),
		filepath.Join(os.Getenv("LOCALAPPDATA"), "Programs", "Siril", "bin", "siril-cli.exe"),
	}
	for _, p := range candidates {
		if p != "" {
			if st, err := os.Stat(p); err == nil && !st.IsDir() {
				return p
			}
		}
	}
	if p, err := exec.LookPath("siril-cli.exe"); err == nil {
		return p
	}
	return ""
}

func bundledSiril(directory string) string {
	var executable string
	_ = filepath.WalkDir(directory, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.Type().IsRegular() && strings.EqualFold(entry.Name(), "siril-cli.exe") {
			executable = path
			return fs.SkipAll
		}
		return nil
	})
	return executable
}

func (a *App) snapshot() StateSnapshot {
	a.mu.RLock()
	defer a.mu.RUnlock()
	d := a.device
	d.Capabilities = cloneMap(a.device.Capabilities)
	d.Telemetry = cloneMap(a.device.Telemetry)
	s := a.session
	s.CapturedFiles = append([]string(nil), a.session.CapturedFiles...)
	var stack StackStats
	if a.stack != nil {
		stack = a.stack.snapshot()
	}
	metrics := a.metrics
	if !a.lastFrameTime.IsZero() {
		last := a.lastFrameTime
		metrics.LastFrame = &last
	}
	metrics.Live = d.Connected && a.previewLiveLocked(time.Now())
	if !metrics.Live {
		metrics.FPS, metrics.BitrateMbps = 0, 0
	}
	return StateSnapshot{LastJPEG: a.lastJPEG, FocusPoint: a.focusPoint, Stack: stack, App: appName, Version: version, Device: d, Metrics: metrics, Video: a.video, Sequence: a.sequence, Session: s}
}
func cloneMap(src map[string]any) map[string]any {
	out := map[string]any{}
	for k, v := range src {
		out[k] = v
	}
	return out
}

type diagnosticEvent struct {
	Time   time.Time `json:"time"`
	Kind   string    `json:"kind"`
	Detail string    `json:"detail"`
}

func (a *App) recordDiagnosticLocked(kind, detail string) {
	const eventLimit = 200
	if len(a.diagnostics) == eventLimit {
		copy(a.diagnostics, a.diagnostics[1:])
		a.diagnostics = a.diagnostics[:eventLimit-1]
	}
	a.diagnostics = append(a.diagnostics, diagnosticEvent{time.Now(), kind, detail})
}

func (a *App) setError(err error) {
	if err == nil {
		return
	}
	a.mu.Lock()
	a.device.LastError = err.Error()
	a.recordDiagnosticLocked("error", err.Error())
	a.mu.Unlock()
}

func (a *App) discoveryLoop() {
	defer close(a.discoveryDone)
	if a.usb != nil {
		defer a.usb.close()
	}
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-a.stop:
			return
		default:
		}
		a.refreshTransport()
		select {
		case <-a.stop:
			return
		case <-ticker.C:
		case <-a.reconnect:
		}
	}
}

func (a *App) refreshTransport() {
	var wired endpoint
	info := usbStatus{"unavailable", "USB components are missing. Extract the entire Windows download before opening the app."}
	if a.usb != nil {
		serial, status := a.usb.detect()
		info = status
		if serial != "" {
			var err error
			wired, err = a.usb.forwards(serial)
			if err != nil {
				info = usbStatus{"reconnecting", "Could not open the USB camera connection. Reconnecting…"}
			}
		} else if a.usb.link.serial != "" && (status.State == "unavailable" || status.State == "multiple") {
			wired = a.usb.link.endpoint
		} else if a.usb.link.serial != "" {
			a.usb.close()
			if status.State == "no_data" {
				info = usbStatus{"lost", "USB connection lost. Reconnect the phone with a USB data cable."}
			}
		}
	}
	a.mu.RLock()
	active, connected := a.active, a.device.Connected
	needsCaps := len(a.device.Capabilities) == 0
	lastHeartbeat := a.lastHeartbeat
	busy := a.captureID != "" || a.video.Recording || a.video.Message == "Starting"
	known, manual := a.knownLAN, a.manualLAN
	usbPower, _ := a.device.Telemetry["usb_power"].(bool)
	a.mu.RUnlock()
	if info.State == "no_data" && usbPower {
		info = usbStatus{"power_only", "The phone reports USB power, but Windows sees no Android USB data. Try a USB data cable and another port."}
	}
	a.setUSBStatus(info)
	if connected && (active.kind != transportUSB || active == wired) {
		if _, err := a.command("ping", nil, 2*time.Second); err == nil {
			a.mu.Lock()
			a.lastHeartbeat = time.Now()
			a.mu.Unlock()
			if active.kind == transportUSB {
				a.setUSBStatus(usbStatus{"connected", "USB debugging authorized · PC connected"})
			} else if wired.control != 0 && !busy {
				a.setUSBStatus(usbStatus{"connecting", "USB authorized. Connecting to the phone app…"})
				if a.connectEndpoint(wired) {
					a.setUSBStatus(usbStatus{"connected", "USB debugging authorized · PC connected"})
					return
				}
				a.resetFailedUSB(info)
			}
			if needsCaps {
				a.queryCapabilities()
			}
			return
		}
		// A delayed response while Camera2 is configuring a session is not
		// a lost link. The reader handles actual EOF without waiting for ping.
		if time.Since(lastHeartbeat) < 10*time.Second {
			return
		}
	}
	if connected {
		a.disconnect("Phone connection lost")
	}
	if wired.control != 0 {
		a.setUSBStatus(usbStatus{"connecting", "USB authorized. Connecting to the phone app…"})
		if a.connectEndpoint(wired) {
			a.setUSBStatus(usbStatus{"connected", "USB debugging authorized · PC connected"})
			return
		}
		a.resetFailedUSB(info)
	}
	for _, candidate := range transportOrder(endpoint{}, a.discover(), known, manual) {
		if a.connectEndpoint(candidate) {
			return
		}
	}
}

func (a *App) resetFailedUSB(fallback usbStatus) {
	// Forget the local ports if ADB restarted or the app was closed.
	a.usb.close()
	a.mu.Lock()
	if a.device.USB.State != "different_phone" {
		a.device.USB = fallback
	}
	a.mu.Unlock()
}

func (a *App) setUSBStatus(status usbStatus) {
	a.mu.Lock()
	a.device.USB = status
	a.mu.Unlock()
}

func discoverPhone() string {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		return ""
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(700 * time.Millisecond))
	msg := []byte("TOD_ASTRO_DISCOVER_V2")
	_ = conn.SetWriteBuffer(4096)
	targets := []*net.UDPAddr{{IP: net.IPv4bcast, Port: discoveryPort}}
	ifaces, _ := net.Interfaces()
	for _, iface := range ifaces {
		addrs, _ := iface.Addrs()
		for _, ad := range addrs {
			ipnet, ok := ad.(*net.IPNet)
			if !ok {
				continue
			}
			ip := ipnet.IP.To4()
			if ip == nil {
				continue
			}
			mask := ipnet.Mask
			if len(mask) != 4 {
				continue
			}
			b := net.IPv4(ip[0]|^mask[0], ip[1]|^mask[1], ip[2]|^mask[2], ip[3]|^mask[3])
			targets = append(targets, &net.UDPAddr{IP: b, Port: discoveryPort})
		}
	}
	for _, t := range targets {
		_, _ = conn.WriteToUDP(msg, t)
	}
	buf := make([]byte, 2048)
	for {
		n, addr, err := conn.ReadFromUDP(buf)
		if err != nil {
			return ""
		}
		var r map[string]any
		if json.Unmarshal(buf[:n], &r) == nil && fmt.Sprint(r["app"]) == "TOD Astro Photography" {
			return addr.IP.String()
		}
	}
}

func (a *App) connectEndpoint(candidate endpoint) bool {
	c, err := candidate.dial(candidate.control, 900*time.Millisecond)
	if err != nil {
		return false
	}
	adopted := false
	defer func() {
		if !adopted {
			_ = c.Close()
		}
	}()
	_ = c.SetDeadline(time.Now().Add(1500 * time.Millisecond))
	reader := bufio.NewReader(c)
	exchange := func(command string) (map[string]any, error) {
		request, _ := json.Marshal(map[string]any{"id": command, "cmd": command, "payload": map[string]any{
			"client": appName, "version": version, "transport": candidate.kind, "client_session": a.clientSession,
		}})
		if _, err := c.Write(append(request, '\n')); err != nil {
			return nil, err
		}
		for {
			line, err := reader.ReadSlice('\n')
			if err != nil {
				return nil, err
			}
			var result map[string]any
			if json.Unmarshal(line, &result) == nil && result["id"] == command {
				return result, nil
			}
		}
	}
	probe, err := exchange("probe")
	if err != nil {
		return false
	}
	id, _ := probe["device_id"].(string)
	if candidate.kind == transportUSB && (probe["app"] != "TOD Astro Photography" || id == "") {
		return false
	}
	a.mu.RLock()
	expected := a.phoneID
	explicit := candidate.kind == transportLAN && candidate.host == a.manualLAN &&
		!a.sequence.Running && !a.video.Recording && !a.device.Connected
	a.mu.RUnlock()
	if expected != "" && id != expected && !explicit {
		if candidate.kind == transportUSB {
			a.setUSBStatus(usbStatus{"different_phone", "The USB phone differs from the phone bound to this session."})
		}
		return false
	}
	hello, err := exchange("hello")
	if err != nil || hello["ok"] != true || hello["app"] != "TOD Astro Photography" {
		return false
	}
	if id != "" && hello["device_id"] != id {
		return false
	}
	if candidate.kind == transportUSB && id == "" {
		return false
	}
	select {
	case <-a.stop:
		return false
	default:
	}
	_ = c.SetDeadline(time.Time{})
	a.connMu.Lock()
	a.mu.Lock()
	old := a.control
	a.control, a.writer = c, bufio.NewWriter(c)
	a.active = candidate
	a.phoneID = id
	a.device.Connected = true
	a.device.Host = candidate.host
	a.device.Transport = candidate.kind
	a.device.Status = "Connected"
	a.device.LastError = ""
	a.device.Protocol, _ = asInt(hello["protocol"])
	a.lastHeartbeat = time.Now()
	a.resetPreviewLocked()
	a.recordDiagnosticLocked("connected", string(candidate.kind))
	if recording, ok := hello["recording"].(bool); ok {
		a.video.Recording = recording
		if recording {
			a.video.Message = "Recording"
		} else if a.video.Message == "Recording" || a.video.Message == "Starting" || a.video.Message == "Stopping" {
			a.video.Message = "Idle"
		}
	}
	a.notifyLinkLocked()
	if candidate.kind == transportLAN {
		a.knownLAN = candidate.host
	}
	a.mu.Unlock()
	a.connMu.Unlock()
	adopted = true
	if old != nil {
		_ = old.Close()
	}
	if candidate.kind == transportLAN {
		saveLANPreference(a.lanPath, candidate.host)
	}
	go a.scanControl(c, reader)
	go a.previewLoop(candidate, c)
	a.queryCapabilities()
	return true
}

func (a *App) notifyLinkLocked() {
	close(a.linkChanged)
	a.linkChanged = make(chan struct{})
}

func (a *App) queryCapabilities() {
	if r, err := a.command("capabilities", nil, 4*time.Second); err == nil {
		a.mu.Lock()
		a.device.Capabilities = r
		a.device.LastError = ""
		if id := fmt.Sprint(r["camera_id"]); id != "<nil>" {
			a.device.Camera = id
		}
		a.mu.Unlock()
	} else {
		a.setError(err)
	}
}

func (a *App) disconnect(reason string) {
	a.disconnectControl(nil, reason)
}

func (a *App) disconnectControl(expected net.Conn, reason string) {
	a.connMu.Lock()
	if a.control == nil || (expected != nil && expected != a.control) {
		a.connMu.Unlock()
		return
	}
	old := a.control
	a.control, a.writer = nil, nil
	a.mu.Lock()
	a.active = endpoint{}
	a.device.Connected = false
	a.device.Transport = "Reconnecting"
	a.device.Status = "Reconnecting"
	a.device.Capabilities = map[string]any{}
	a.device.Telemetry = map[string]any{}
	a.resetPreviewLocked()
	a.recordDiagnosticLocked("disconnected", reason)
	if reason != "" {
		a.device.LastError = reason
	}
	a.notifyLinkLocked()
	a.mu.Unlock()
	a.connMu.Unlock()
	_ = old.Close()
	select {
	case a.reconnect <- struct{}{}:
	default:
	}
}

func (a *App) controlReadLoop(c net.Conn) {
	a.scanControl(c, c)
}

func (a *App) scanControl(c net.Conn, r io.Reader) {
	scan := bufio.NewScanner(r)
	buf := make([]byte, 0, 64*1024)
	scan.Buffer(buf, 1024*1024)
	for {
		_ = c.SetReadDeadline(time.Now().Add(15 * time.Second))
		if !scan.Scan() {
			break
		}
		var m map[string]any
		if json.Unmarshal(scan.Bytes(), &m) != nil {
			continue
		}
		id := fmt.Sprint(m["id"])
		if id != "" && id != "<nil>" {
			a.connMu.Lock()
			ch := a.pending[id]
			if ch != nil {
				delete(a.pending, id)
			}
			a.connMu.Unlock()
			if ch != nil {
				ch <- m
				close(ch)
				continue
			}
		}
		a.connMu.Lock()
		current := a.control == c
		a.connMu.Unlock()
		if !current {
			break
		}
		a.handleEvent(m)
	}
	a.disconnectControl(c, "Phone connection lost")
}

func (a *App) handleEvent(m map[string]any) {
	ev := fmt.Sprint(m["event"])
	switch ev {
	case "camera_changed":
		a.mu.Lock()
		a.device.Capabilities = map[string]any{}
		a.resetPreviewLocked()
		a.focusPoint = nil
		a.mu.Unlock()
	case "display_zoom":
		a.mu.Lock()
		a.device.Telemetry = cloneMap(a.device.Telemetry)
		a.device.Telemetry["display_zoom"] = m["ratio"]
		a.mu.Unlock()
	case "telemetry":
		a.mu.Lock()
		previousDiagnostics, _ := a.device.Telemetry["diagnostics"].(map[string]any)
		diagnostics, _ := m["diagnostics"].(map[string]any)
		if rotation, ok := diagnostics["preview_rotation"].(float64); ok && previousDiagnostics["preview_rotation"] != nil &&
			previousDiagnostics["preview_rotation"] != rotation {
			a.focusPoint = nil
			a.metrics.FocusHistory = nil
			a.metrics.Star = nil
		}
		a.device.Telemetry = m
		if recording, ok := m["recording"].(bool); ok {
			if recording && a.video.Message != "Stopping" {
				a.video.Recording = true
				a.video.Message = "Recording"
			} else if !recording && ((a.video.Message == "Recording" || a.video.Message == "Stopping") ||
				(a.video.Message == "Starting" && time.Since(a.videoTransition) > 15*time.Second)) {
				a.video.Recording = false
				a.video.Message = "Idle"
			}
		}
		a.mu.Unlock()
	case "video_progress":
		a.mu.Lock()
		if a.video.Path == fmt.Sprint(m["path"]) {
			a.video.CameraFrames, _ = asInt(m["camera_frames_started"])
			a.video.CameraFailures, _ = asInt(m["camera_failures"])
		}
		a.mu.Unlock()
	case "video_review":
		a.mu.Lock()
		target := a.active
		path := fmt.Sprint(m["path"])
		dir := a.videoSessions[path]
		delete(a.videoSessions, path)
		if a.video.Path == fmt.Sprint(m["path"]) {
			a.video.Review = m
		}
		a.mu.Unlock()
		if dir == "" {
			return
		}
		go func() {
			appendSessionLog(dir, cloneMap(m))
			frames, _ := m["best_frames"].([]any)
			for _, entry := range frames {
				frame, ok := entry.(map[string]any)
				if !ok {
					continue
				}
				if _, err := a.fetchAcrossLink(target, fmt.Sprint(frame["path"]), filepath.Join(dir, "Best frames"), dir); err != nil {
					a.setError(err)
				}
			}
		}()
	case "video_started":
		a.mu.Lock()
		a.video.Recording = true
		a.video.Review = nil
		a.video.CameraFrames, a.video.CameraFailures = 0, 0
		a.videoSession = a.ensureSessionDirLocked()
		a.video.Path = fmt.Sprint(m["path"])
		if a.videoSessions == nil {
			a.videoSessions = make(map[string]string)
		}
		a.videoSessions[a.video.Path] = a.videoSession
		a.video.Width, _ = asInt(m["width"])
		a.video.Height, _ = asInt(m["height"])
		a.video.FPS, _ = asInt(m["fps"])
		a.video.MinFPS, _ = asInt(m["fps_min"])
		a.video.StartedAt = time.Now().Format(time.RFC3339)
		a.video.Message = "Recording"
		a.mu.Unlock()
	case "capture_saved":
		if fmt.Sprint(m["kind"]) == "video" {
			a.mu.Lock()
			a.video.Recording = false
			a.video.Message = "Saved"
			a.mu.Unlock()
		}
		files, _ := m["files"].([]any)
		id, _ := m["capture_id"].(string)
		a.mu.Lock()
		target := a.active
		if id != "" {
			if id != a.captureID || a.captureTransfers[id] {
				a.mu.Unlock()
				return
			}
			a.captureTransfers[id] = true
		}
		dir := a.ensureSessionDirLocked()
		if m["kind"] == "video" && a.videoSession != "" {
			dir = a.videoSession
		}
		frameType, _ := m["frame_type"].(string)
		if id != "" && id == a.captureID {
			frameType = a.sequence.FrameType
		}
		if frameType == "" {
			frameType = "Light"
		}
		sessionDir := dir
		dir = filepath.Join(sessionDir, sanitize(frameType))
		var stack *LiveStack
		exposure, _ := asInt(a.currentControls["exposure_us"])
		if a.currentControls["manual"] != true {
			exposure = 0
		}
		if id != "" && id == a.captureID && a.sequence.Mode == "Live Stack" && frameType == "Light" && a.sequenceStacking {
			stack = a.stack
		}
		a.mu.Unlock()
		go func() {
			var err error
			if len(files) == 0 {
				err = fmt.Errorf("phone saved no files")
			}
			for _, file := range files {
				path, transferErr := a.fetchAcrossLink(target, fmt.Sprint(file), dir, sessionDir)
				if transferErr != nil {
					err = transferErr
					break
				}
				if strings.EqualFold(filepath.Ext(path), ".jpg") {
					a.rememberJPEG(path, m)
				}
				if stack != nil && strings.EqualFold(filepath.Ext(path), stack.extension) {
					if stackErr := stack.addFrame(path, int64(exposure)); stackErr != nil {
						err = stackErr
						break
					}
				}
			}
			if err != nil {
				a.setError(err)
			}
			if diagnostics, ok := m["diagnostics"].(map[string]any); ok {
				appendSessionLog(sessionDir, map[string]any{"event": "camera_capture", "capture_id": id,
					"diagnostics": diagnostics, "effective": m["effective"], "jpeg_processing": m["jpeg_processing"],
					"jpeg_width": m["jpeg_width"], "jpeg_height": m["jpeg_height"]})
			}
			a.mu.Lock()
			delete(a.captureTransfers, id)
			a.mu.Unlock()
			a.completeCapture(captureResult{id: id, err: err})
		}()

	case "warning":
		a.mu.Lock()
		a.device.LastError = fmt.Sprint(m["message"])
		a.mu.Unlock()
	case "capture_error":
		a.mu.Lock()
		a.device.LastError = fmt.Sprint(m["message"])
		a.recordDiagnosticLocked("capture_error", a.device.LastError)
		if m["kind"] == "video" {
			a.video.Recording = false
			a.video.Message = "Error"
		}
		a.mu.Unlock()
		id, _ := m["capture_id"].(string)
		a.completeCapture(captureResult{id: id, err: fmt.Errorf("%s", m["message"])})
	}
}

func (a *App) completeCapture(result captureResult) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if result.id == "" || result.id != a.captureID {
		return
	}
	select {
	case a.captureDone <- result:
	default:
	}
}

func (a *App) command(cmd string, payload map[string]any, timeout time.Duration) (map[string]any, error) {
	id := fmt.Sprintf("%d", a.seqID.Add(1))
	req := map[string]any{"id": id, "cmd": cmd, "payload": payload}
	b, _ := json.Marshal(req)
	ch := make(chan map[string]any, 1)
	a.connMu.Lock()
	if a.writer == nil {
		a.connMu.Unlock()
		return nil, fmt.Errorf("phone not connected")
	}
	a.pending[id] = ch
	defer func() {
		a.connMu.Lock()
		delete(a.pending, id)
		a.connMu.Unlock()
	}()
	_ = a.control.SetWriteDeadline(time.Now().Add(timeout))
	_, err := a.writer.Write(append(b, '\n'))
	if err == nil {
		err = a.writer.Flush()
	}
	a.connMu.Unlock()
	if err != nil {
		return nil, err
	}
	select {
	case r := <-ch:
		if ok, exists := r["ok"].(bool); exists && !ok {
			return r, fmt.Errorf("%v", r["error"])
		}
		return r, nil
	case <-time.After(timeout):
		return nil, fmt.Errorf("command timeout: %s", cmd)
	}
}

func (a *App) previewLoop(target endpoint, control net.Conn) {
	analysis := make(chan []byte)
	defer close(analysis)
	go func() {
		for frame := range analysis {
			a.analyzeFrame(frame, control)
		}
	}()
	for {
		a.connMu.Lock()
		active := a.control == control
		a.connMu.Unlock()
		if !active {
			return
		}
		a.readPreview(target, control, analysis)
		select {
		case <-a.stop:
			return
		case <-time.After(time.Second):
		}
	}
}

func (a *App) readPreview(target endpoint, control net.Conn, analysis chan<- []byte) {
	c, err := target.dial(target.preview, 2*time.Second)
	if err != nil {
		return
	}
	defer c.Close()
	for {
		_ = c.SetReadDeadline(time.Now().Add(previewFreshness))
		var n uint32
		if err := binary.Read(c, binary.BigEndian, &n); err != nil {
			return
		}
		if n == 0 || n > 32*1024*1024 {
			return
		}
		b := make([]byte, n)
		if _, err := io.ReadFull(c, b); err != nil {
			return
		}
		if len(b) < 4 || b[0] != 0xff || b[1] != 0xd8 {
			return
		}
		a.connMu.Lock()
		if a.control != control {
			a.connMu.Unlock()
			return
		}
		a.mu.Lock()
		a.storeFrameLocked(b, time.Now())
		frameNumber := a.frameCounter
		a.mu.Unlock()
		a.connMu.Unlock()
		if frameNumber%3 == 0 {
			select {
			case analysis <- b:
			default:
			}
		}
	}
}

func (a *App) previewLiveLocked(now time.Time) bool {
	return !a.lastFrameTime.IsZero() && now.Sub(a.lastFrameTime) <= previewFreshness
}

func (a *App) resetPreviewLocked() {
	a.latestFrame, a.frameETag = nil, ""
	a.lastFrameTime, a.windowStart = time.Time{}, time.Time{}
	a.windowFrames, a.frameBytes = 0, 0
	a.metrics = Metrics{}
}

func (a *App) storeFrameLocked(b []byte, now time.Time) {
	newWindow := !a.previewLiveLocked(now)
	if newWindow {
		a.windowStart = now
		a.windowFrames, a.frameBytes = 0, 0
		a.metrics.FPS, a.metrics.BitrateMbps = 0, 0
	}
	a.frameCounter++
	a.latestFrame = b
	a.frameETag = strconv.FormatUint(a.frameCounter, 10)
	a.lastFrameTime = now
	a.metrics.Frames = a.frameCounter
	if !newWindow {
		a.windowFrames++
		a.frameBytes += uint64(len(b) + 4)
	}
	if elapsed := now.Sub(a.windowStart).Seconds(); elapsed >= 1 {
		a.metrics.FPS = float64(a.windowFrames) / elapsed
		a.metrics.BitrateMbps = float64(a.frameBytes) * 8 / elapsed / 1e6
		a.windowStart = now
		a.windowFrames, a.frameBytes = 0, 0
	}
}

func (a *App) analyzeFrame(b []byte, owner net.Conn) {
	img, _, err := image.Decode(bytes.NewReader(b))
	if err != nil {
		return
	}
	bounds := img.Bounds()
	w, h := bounds.Dx(), bounds.Dy()
	if w < 3 || h < 3 {
		return
	}
	step := 1
	if w*h > 600000 {
		step = 2
	}
	if w*h > 2000000 {
		step = 4
	}
	var hist [256]uint64
	var total, sum uint64
	var focus float64
	var focusN uint64
	lumAt := func(x, y int) int {
		r, g, bb, _ := img.At(bounds.Min.X+x, bounds.Min.Y+y).RGBA()
		return int((299*uint64(r) + 587*uint64(g) + 114*uint64(bb)) / 1000 >> 8)
	}
	for y := 0; y < h; y += step {
		for x := 0; x < w; x += step {
			l := lumAt(x, y)
			hist[l]++
			total++
			sum += uint64(l)
		}
	}
	a.mu.RLock()
	point := a.focusPoint
	a.mu.RUnlock()
	center := focusPosition(bounds, point)
	region := bounds
	if point != nil {
		region = image.Rect(center.X-48, center.Y-48, center.X+48, center.Y+48).Intersect(bounds)
	}
	for y := region.Min.Y - bounds.Min.Y + step; y < region.Max.Y-bounds.Min.Y-step; y += step * 2 {
		for x := region.Min.X - bounds.Min.X + step; x < region.Max.X-bounds.Min.X-step; x += step * 2 {
			c := lumAt(x, y)
			lap := 4*c - lumAt(x-step, y) - lumAt(x+step, y) - lumAt(x, y-step) - lumAt(x, y+step)
			if lap < 0 {
				lap = -lap
			}
			focus += float64(lap)
			focusN++
		}
	}
	median := 0
	var acc uint64
	for i, v := range hist {
		acc += v
		if acc*2 >= total {
			median = i
			break
		}
	}
	var low, high uint64
	for i := 0; i <= 2; i++ {
		low += hist[i]
	}
	for i := 253; i < 256; i++ {
		high += hist[i]
	}
	m := Metrics{Histogram: hist, Star: stellarFocusAt(img, center)}
	if total > 0 {
		m.Mean = float64(sum) / float64(total)
		m.Median = median
		m.ShadowClip = float64(low) * 100 / float64(total)
		m.HighlightClip = float64(high) * 100 / float64(total)
	}
	if focusN > 0 {
		m.FocusScore = focus / float64(focusN)
	}
	a.connMu.Lock()
	defer a.connMu.Unlock()
	a.mu.Lock()
	if a.control != owner || a.focusPoint != point || !a.previewLiveLocked(time.Now()) {
		a.mu.Unlock()
		return
	}
	m.FocusHistory = append(append([]float64(nil), a.metrics.FocusHistory...), m.FocusScore)
	if len(m.FocusHistory) > 90 {
		m.FocusHistory = m.FocusHistory[1:]
	}
	m.Frames = a.frameCounter
	m.FPS = a.metrics.FPS
	m.BitrateMbps = a.metrics.BitrateMbps
	m.Dropped = a.metrics.Dropped
	a.metrics = m
	a.mu.Unlock()
}

func (a *App) fetchFile(target endpoint, path, dir, sessionDir string) (string, error) {
	c, err := target.dial(target.file, 3*time.Second)
	if err != nil {
		return "", err
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(2 * time.Minute))
	req, _ := json.Marshal(map[string]any{"path": path})
	if _, err := c.Write(append(req, '\n')); err != nil {
		return "", err
	}
	var size int64
	if err := binary.Read(c, binary.BigEndian, &size); err != nil {
		return "", err
	}
	if size <= 0 {
		return "", fmt.Errorf("phone could not open %q", path)
	}
	if err := os.MkdirAll(dir, 0755); err != nil {
		return "", err
	}
	name := sanitize(filepath.Base(strings.ReplaceAll(path, "\\", "/")))
	f, err := os.CreateTemp(dir, ".capture-*.part")
	if err != nil {
		return "", err
	}
	defer os.Remove(f.Name())
	h := sha256.New()
	_, copyErr := io.CopyN(io.MultiWriter(f, h), c, size)
	closeErr := f.Close()
	if copyErr != nil {
		return "", copyErr
	}
	if closeErr != nil {
		return "", closeErr
	}
	final := filepath.Join(dir, name)
	if existing, statErr := os.Stat(final); statErr == nil && existing.Size() == size {
		previous, openErr := os.Open(final)
		if openErr != nil {
			return "", openErr
		}
		oldHash := sha256.New()
		_, hashErr := io.Copy(oldHash, previous)
		_ = previous.Close()
		if hashErr != nil {
			return "", hashErr
		}
		if bytes.Equal(oldHash.Sum(nil), h.Sum(nil)) {
			return final, nil
		}
	}
	if err := os.Rename(f.Name(), final); err != nil {
		return "", err
	}
	a.mu.Lock()
	if a.session.CurrentDir == sessionDir {
		a.session.CapturedFiles = append(a.session.CapturedFiles, final)
	}
	a.mu.Unlock()
	appendSessionLog(sessionDir, map[string]any{"event": "file_received", "path": final, "size": size, "sha256": hex.EncodeToString(h.Sum(nil)), "time": time.Now().Format(time.RFC3339)})
	return final, nil
}

func (a *App) rememberJPEG(path string, metadata map[string]any) {
	f, err := os.Open(path)
	if err != nil {
		return
	}
	defer f.Close()
	shape, err := jpeg.DecodeConfig(f)
	if err != nil {
		return
	}
	processing, _ := metadata["jpeg_processing"].(string)
	if processing == "" {
		processing = "Native Camera2 JPEG"
	}
	a.mu.Lock()
	a.lastJPEG = &JPEGReview{Name: filepath.Base(path), Width: shape.Width, Height: shape.Height,
		Processing: processing, Received: time.Now(), path: path}
	a.mu.Unlock()
}

func sanitize(s string) string {
	s = strings.TrimSpace(s)
	if s == "" || s == "." || s == ".." {
		return "Untitled"
	}
	bad := `<>:"/\\|?*`
	return strings.Map(func(r rune) rune {
		if r < 32 || strings.ContainsRune(bad, r) {
			return '_'
		}
		return r
	}, s)
}
func (a *App) ensureSessionDirLocked() string {
	date := time.Now().Format("2006-01-02")
	if a.sessionID == "" {
		a.sessionID = time.Now().Format("150405.000000000") + "-" + sanitize(a.session.Mode)
	}
	dir := filepath.Join(a.session.Root, date, sanitize(a.session.Target), a.sessionID)
	if strings.HasPrefix(a.session.CurrentDir, a.session.Root+string(filepath.Separator)) &&
		filepath.Base(a.session.CurrentDir) == a.sessionID {
		return a.session.CurrentDir
	}
	a.session.CapturedFiles = nil
	a.session.CurrentDir = dir
	return dir
}
func appendSessionLog(dir string, m map[string]any) {
	if err := os.MkdirAll(dir, 0755); err != nil {
		return
	}
	m["time"] = time.Now().Format(time.RFC3339Nano)
	f, err := os.OpenFile(filepath.Join(dir, "session.jsonl"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		return
	}
	defer f.Close()
	b, _ := json.Marshal(m)
	_, _ = f.Write(append(append([]byte{'\n'}, b...), '\n'))
}

func asInt(v any) (int, bool) {
	switch x := v.(type) {
	case float64:
		return int(x), true
	case int:
		return x, true
	case json.Number:
		i, e := x.Int64()
		return int(i), e == nil
	case string:
		i, e := strconv.Atoi(x)
		return i, e == nil
	}
	return 0, false
}

func (a *App) startSequence(req map[string]any) error {
	return a.runSequence(req, nil, false)
}

func (a *App) runSequence(req map[string]any, resume *SessionRecord, single bool) error {
	a.mu.Lock()
	if a.sequence.Running || a.videoBusyLocked() {
		a.mu.Unlock()
		return fmt.Errorf("A capture is %s", a.captureActivityLocked())
	}
	if !a.device.Connected && resume == nil {
		a.mu.Unlock()
		return fmt.Errorf("Phone is offline")
	}
	count, _ := asInt(req["count"])
	if single {
		count = 1
	}
	if count < 1 {
		count = 1
	}
	interval, _ := asInt(req["interval_ms"])
	if interval < 0 {
		interval = 0
	}
	target := fmt.Sprint(req["target"])
	if target == "" || target == "<nil>" {
		target = a.session.Target
	}
	mode := fmt.Sprint(req["mode"])
	if mode == "" || mode == "<nil>" {
		mode = a.session.Mode
	}
	ft := fmt.Sprint(req["frame_type"])
	if ft == "" || ft == "<nil>" {
		ft = "Light"
	}
	var stack *LiveStack
	calibrationSelection := map[string]string{}
	if values, ok := req["calibration"].(map[string]any); ok {
		for key, value := range values {
			if id, ok := value.(string); ok {
				calibrationSelection[key] = id
			}
		}
	}
	if resume != nil {
		calibrationSelection = resume.Calibration
	}
	if mode == "Live Stack" && !single {
		if ft != "Light" {
			a.mu.Unlock()
			return fmt.Errorf("live stacking requires light frames")
		}
		extension := ".jpg"
		if raw, _ := req["raw"].(bool); raw {
			extension = ".dng"
		}
		stack = &LiveStack{extension: extension}
		a.stack = stack
	}
	if !single || a.session.Target != target || a.session.Mode != mode {
		a.sessionID = ""
	}
	a.session.Target = target
	a.session.Mode = mode
	dir := a.ensureSessionDirLocked()
	controls := cloneMap(a.currentControls)
	completed := 0
	if resume != nil {
		dir, _ = sessionPath(a.session.Root, resume.ID)
		a.session.CurrentDir, a.sessionID = dir, filepath.Base(dir)
		a.session.CapturedFiles = append([]string(nil), resume.Paths...)
		controls = cloneMap(resume.Controls)
		completed = resume.State.Completed
	}
	capabilities := cloneMap(a.device.Capabilities)
	telemetry := cloneMap(a.device.Telemetry)
	exposure, _ := asInt(controls["exposure_us"])
	if controls["manual"] != true {
		exposure = 0
	}
	a.sequence = SequenceState{Running: true, Phase: "starting", Target: target, Mode: mode, FrameType: ft, Requested: count, Completed: completed, IntervalMs: interval, StartedAt: time.Now().Format(time.RFC3339), Message: "Starting"}
	if resume != nil {
		a.sequence.IntegrationUS = resume.State.IntegrationUS
		a.sequence.StartedAt = resume.State.StartedAt
	}
	cancel := make(chan struct{})
	finished := make(chan struct{})
	a.sequenceCancel = cancel
	a.sequenceFinished = finished
	a.sequenceStacking = stack != nil
	a.mu.Unlock()
	raw, _ := req["raw"].(bool)
	jpeg := true
	if v, ok := req["jpeg"].(bool); ok {
		jpeg = v
	}
	go func() {
		defer close(finished)
		if resume == nil && !single {
			appendSessionLog(dir, map[string]any{
				"event": "sequence_started", "target": target, "mode": mode, "frame_type": ft,
				"requested": count, "interval_ms": interval, "controls": controls,
				"capabilities": capabilities, "telemetry": telemetry, "raw": raw, "jpeg": jpeg,
				"calibration": req["calibration"],
			})
		} else if resume != nil {
			appendSessionLog(dir, map[string]any{"event": "sequence_resumed", "completed": completed})
		}

		defer func() {
			a.mu.RLock()
			id, stopping := a.captureID, a.sequence.Phase == "stopping"
			a.mu.RUnlock()
			var cancelErr error
			if stopping && id != "" {
				_, cancelErr = a.command("cancel_capture", map[string]any{"capture_id": id}, 4*time.Second)
			}
			a.mu.Lock()
			a.sequence.Running = false
			a.sequenceCancel = nil
			a.sequenceStacking = false
			a.captureID = ""
			switch {
			case a.sequence.Phase == "stopping":
				a.sequence.Phase, a.sequence.Message = "completed", "Stopped"
				if cancelErr != nil {
					a.sequence.Phase, a.sequence.Message = "failed", "Phone did not confirm cancellation"
					a.device.LastError = cancelErr.Error()
				}
			case a.sequence.Completed == a.sequence.Requested:
				a.sequence.Phase, a.sequence.Message = "completed", "Complete"
			default:
				a.sequence.Phase = "failed"
			}
			sequence, metrics, telemetry := a.sequence, a.metrics, cloneMap(a.device.Telemetry)
			a.recordDiagnosticLocked("capture", sequence.Message)
			a.mu.Unlock()
			event := "sequence_finished"
			if single {
				event = "single_capture_finished"
			}
			appendSessionLog(dir, map[string]any{"event": event, "sequence": sequence,
				"received_preview_frames": metrics.Frames, "telemetry": telemetry})
		}()
		if stack != nil {
			master, err := loadCalibration(a.session.Root, calibrationSelection, stack.extension)
			if err != nil {
				a.setError(err)
				a.mu.Lock()
				a.sequence.Message = "Calibration failed"
				a.mu.Unlock()
				return
			}
			stack.calibration = master
			if resume != nil {
				entries, _ := os.ReadDir(filepath.Join(dir, "Light"))
				for _, entry := range entries {
					if strings.EqualFold(filepath.Ext(entry.Name()), stack.extension) {
						if err := stack.addFrame(filepath.Join(dir, "Light", entry.Name()), int64(exposure)); err != nil {
							a.setError(err)
						}
					}
				}
			}
		}

		for i := completed; i < count; i++ {
			select {
			case <-cancel:
				return
			default:
			}
			if err := a.waitForSequenceLink(cancel, dir); err != nil {
				a.mu.Lock()
				if a.sequence.Phase != "stopping" {
					a.sequence.Message = "Reconnect timed out"
					a.device.LastError = err.Error()
				}
				a.mu.Unlock()
				return
			}
			if resume != nil && i == completed && len(controls) > 0 {
				if _, err := a.command("set_controls", controls, 5*time.Second); err != nil {
					a.setError(err)
					a.mu.Lock()
					a.sequence.Message = "Could not restore camera settings"
					a.mu.Unlock()
					return
				}
				a.mu.Lock()
				a.currentControls = controls
				a.mu.Unlock()
			}
			id := fmt.Sprintf("%d-%d", time.Now().UnixNano(), i)
			if resume != nil && resume.PendingIndex == i && resume.PendingID != "" {
				id = resume.PendingID
			}
			if !single {
				appendSessionLog(dir, map[string]any{"event": "capture_requested", "capture_id": id, "exposure_index": i})
			}
			a.mu.Lock()
			select {
			case <-a.captureDone:
			default:
			}
			a.captureID = id
			if a.sequence.Phase != "stopping" {
				a.sequence.Phase, a.sequence.Message = "capturing", "Capturing"
			}
			a.mu.Unlock()
			payload := map[string]any{"raw": raw, "jpeg": jpeg, "target": target, "mode": mode, "frame_type": ft, "capture_id": id}
			if single {
				for _, field := range []string{"maximum_jpeg", "validation_jpeg", "remote_review", "burst_count"} {
					if value, exists := req[field]; exists {
						payload[field] = value
					}
				}
			}
			if err := a.captureAcrossLinks(payload, cancel, dir); err != nil {
				select {
				case <-cancel:
					return
				default:
				}
				a.setError(err)
				a.mu.Lock()
				a.sequence.Message = "Capture failed"
				a.mu.Unlock()
				return
			}
			a.mu.Lock()
			a.captureID = ""
			a.sequence.Completed = i + 1
			if ft == "Light" {
				a.sequence.IntegrationUS += int64(exposure)
			}
			progress := a.sequence
			a.mu.Unlock()
			if !single {
				appendSessionLog(dir, map[string]any{"event": "sequence_progress", "sequence": progress})
			}
			if interval > 0 && i < count-1 {
				select {
				case <-time.After(time.Duration(interval) * time.Millisecond):
				case <-cancel:
					return
				}
			}
		}
	}()
	return nil
}
func (a *App) captureAcrossLinks(payload map[string]any, cancel <-chan struct{}, dir string) error {
	a.mu.RLock()
	exposure, _ := asInt(a.currentControls["exposure_us"])
	a.mu.RUnlock()
	count, _ := asInt(payload["burst_count"])
	captureTime := time.Duration(exposure) * time.Microsecond * time.Duration(max(1, min(count, 16)))
	deadline := time.NewTimer(max(3*time.Minute, captureTime+3*time.Minute))
	defer deadline.Stop()
	for {
		if err := a.waitForSequenceLink(cancel, dir); err != nil {
			return err
		}
		a.mu.RLock()
		changed := a.linkChanged
		a.mu.RUnlock()
		select {
		case <-cancel:
			return fmt.Errorf("sequence stopped")
		default:
		}
		if response, err := a.command("capture", payload, 5*time.Second); err != nil {
			if response != nil {
				return err
			}
			select {
			case <-changed:
				continue
			case <-cancel:
				return fmt.Errorf("sequence stopped")
			case <-time.After(3 * time.Second):
				return err
			case <-deadline.C:
				return fmt.Errorf("capture recovery timed out")
			}
		}
		select {
		case result := <-a.captureDone:
			if result.err != nil {
				select {
				case <-changed:
					continue
				default:
				}
			}
			return result.err
		case <-changed:
			// Retain the same capture ID so the phone can replay its receipt.
			select {
			case result := <-a.captureDone:
				if result.err == nil {
					return nil
				}
			default:
			}
		case <-deadline.C:
			return fmt.Errorf("capture or transfer timed out")
		case <-cancel:
			return fmt.Errorf("sequence stopped")
		}
	}
}

func (a *App) fetchAcrossLink(first endpoint, path, dir, sessionDir string) (string, error) {
	a.mu.RLock()
	changed, current := a.linkChanged, a.active
	a.mu.RUnlock()
	if current.control != 0 {
		first = current
	}
	local, err := a.fetchFile(first, path, dir, sessionDir)
	if err == nil {
		return local, nil
	}
	// Allow a file that was already being written to be requested on the new link.
	timer := time.NewTimer(12 * time.Second)
	defer timer.Stop()
	for {
		a.mu.RLock()
		next, connected := a.active, a.device.Connected
		a.mu.RUnlock()
		if connected {
			select {
			case <-changed:
				return a.fetchFile(next, path, dir, sessionDir)
			default:
			}
		}
		select {
		case <-timer.C:
			return "", err
		case <-a.stop:
			return "", err
		case <-time.After(200 * time.Millisecond):
		}
	}
}

func (a *App) waitForSequenceLink(cancel <-chan struct{}, dir string) error {
	a.mu.Lock()
	if a.sequence.Phase == "stopping" {
		a.mu.Unlock()
		return fmt.Errorf("sequence stopped")
	}
	if a.device.Connected {
		a.mu.Unlock()
		return nil
	}
	a.sequence.Message = "Waiting for phone"
	a.mu.Unlock()

	appendSessionLog(dir, map[string]any{"event": "waiting_for_phone"})
	deadline := time.NewTimer(2 * time.Minute)
	defer deadline.Stop()
	retry := time.NewTicker(250 * time.Millisecond)
	defer retry.Stop()
	for {
		select {
		case <-cancel:
			return fmt.Errorf("sequence stopped")
		case <-deadline.C:
			return fmt.Errorf("phone did not reconnect within two minutes")
		case <-retry.C:
			a.mu.Lock()
			connected := a.device.Connected
			if connected && a.sequence.Message == "Waiting for phone" {
				a.sequence.Message = "Capturing"
			}
			a.mu.Unlock()
			if connected {
				appendSessionLog(dir, map[string]any{"event": "reconnected"})
				return nil
			}
		}
	}
}

func (a *App) videoBusyLocked() bool {
	return a.video.Recording || a.video.Message == "Starting" || a.video.Message == "Stopping"
}

func (a *App) captureActivityLocked() string {
	if a.sequence.Running {
		return a.sequence.Message
	}
	return "video " + strings.ToLower(a.video.Message)
}

func (a *App) stopSequence() {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.sequence.Running && a.sequenceCancel != nil {
		close(a.sequenceCancel)
		a.sequenceCancel = nil
		a.sequence.Phase, a.sequence.Message = "stopping", "Stopping"
	}
}

func (a *App) routes() http.Handler {
	mux := http.NewServeMux()
	sub, _ := fs.Sub(webFS, "web")
	fileServer := http.FileServer(http.FS(sub))
	mux.Handle("/assets/", http.StripPrefix("/assets/", fileServer))
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		b, _ := fs.ReadFile(sub, "index.html")
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(b)
	})
	mux.HandleFunc("/api/state", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, a.snapshot()) })
	mux.HandleFunc("GET /api/diagnostics", func(w http.ResponseWriter, r *http.Request) {
		a.mu.RLock()
		events := append([]diagnosticEvent(nil), a.diagnostics...)
		heartbeat := a.lastHeartbeat
		a.mu.RUnlock()
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Disposition", "attachment; filename=TOD_Astro_Diagnostics.json")
		writeJSON(w, map[string]any{"generated_at": time.Now(), "last_heartbeat": heartbeat, "state": a.snapshot(), "events": events})
	})
	mux.HandleFunc("POST /api/exit", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, map[string]any{"ok": true})
		_ = http.NewResponseController(w).Flush()
		select {
		case a.quitApp <- struct{}{}:
		default:
		}
	})
	mux.HandleFunc("GET /api/last-jpeg", func(w http.ResponseWriter, r *http.Request) {
		a.mu.RLock()
		saved := a.lastJPEG
		a.mu.RUnlock()
		if saved == nil {
			http.NotFound(w, r)
			return
		}
		f, err := os.Open(saved.path)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		defer f.Close()
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "image/jpeg")
		http.ServeContent(w, r, saved.Name, saved.Received, f)
	})
	mux.HandleFunc("/api/preview.jpg", func(w http.ResponseWriter, r *http.Request) {
		a.mu.RLock()
		if !a.previewLiveLocked(time.Now()) {
			a.mu.RUnlock()
			w.Header().Set("Cache-Control", "no-store")
			w.WriteHeader(http.StatusNoContent)
			return
		}
		etag := strconv.Quote(a.frameETag)
		if len(a.latestFrame) != 0 && r.Header.Get("If-None-Match") == etag {
			a.mu.RUnlock()
			w.Header().Set("ETag", etag)
			w.Header().Set("Cache-Control", "no-store")
			w.WriteHeader(http.StatusNotModified)
			return
		}
		b := append([]byte(nil), a.latestFrame...)
		a.mu.RUnlock()
		if len(b) == 0 {
			http.Error(w, "no frame", http.StatusNoContent)
			return
		}
		w.Header().Set("Content-Type", "image/jpeg")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("ETag", etag)
		_, _ = w.Write(b)
	})
	mux.HandleFunc("/api/stack.png", func(w http.ResponseWriter, r *http.Request) {
		a.mu.RLock()
		stack := a.stack
		a.mu.RUnlock()
		if stack == nil {
			http.NotFound(w, r)
			return
		}
		img := stack.image()
		if img == nil {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "image/png")
		w.Header().Set("Cache-Control", "no-store")
		if r.URL.Query().Has("download") {
			w.Header().Set("Content-Disposition", "attachment; filename=TOD_Live_Stack.png")
		}
		_ = writeStackPNG(w, img)
	})
	mux.HandleFunc("/api/reconnect", func(w http.ResponseWriter, r *http.Request) {
		a.disconnect("")
		select {
		case a.reconnect <- struct{}{}:
		default:
		}
		writeJSON(w, map[string]any{"ok": true})
	})
	mux.HandleFunc("/api/manual-connect", func(w http.ResponseWriter, r *http.Request) {
		var q struct {
			Host string `json:"host"`
		}
		_ = json.NewDecoder(r.Body).Decode(&q)
		if net.ParseIP(strings.TrimSpace(q.Host)) == nil {
			writeErr(w, "invalid IP")
			return
		}
		a.mu.Lock()
		if a.sequence.Running || a.video.Recording {
			a.mu.Unlock()
			writeErr(w, "Finish the active capture before pairing another phone.")
			return
		}
		a.manualLAN = strings.TrimSpace(q.Host)
		a.mu.Unlock()
		select {
		case a.reconnect <- struct{}{}:
		default:
		}
		writeJSON(w, map[string]any{"ok": true})
	})
	mux.HandleFunc("POST /api/focus-roi", func(w http.ResponseWriter, r *http.Request) {
		var point *FocusPoint
		if json.NewDecoder(r.Body).Decode(&point) != nil || (point != nil && (point.X < 0 || point.X > 1 || point.Y < 0 || point.Y > 1)) {
			writeErr(w, "Invalid focus location")
			return
		}
		a.mu.Lock()
		a.focusPoint = point
		a.metrics.FocusHistory = nil
		a.metrics.Star = nil
		a.mu.Unlock()
		writeJSON(w, map[string]any{"ok": true})
	})
	mux.HandleFunc("POST /api/display-zoom", func(w http.ResponseWriter, r *http.Request) {
		var request struct {
			Ratio float64 `json:"ratio"`
		}
		if json.NewDecoder(r.Body).Decode(&request) != nil || request.Ratio < 1 || request.Ratio > 8 {
			writeErr(w, "Invalid display zoom")
			return
		}
		response, err := a.command("set_controls", map[string]any{"display_zoom": request.Ratio}, 4*time.Second)
		if err != nil {
			writeErr(w, err.Error())
			return
		}
		if ratio, ok := response["display_zoom"].(float64); ok {
			a.mu.Lock()
			a.device.Telemetry = cloneMap(a.device.Telemetry)
			a.device.Telemetry["display_zoom"] = ratio
			a.mu.Unlock()
		}
		writeJSON(w, response)
	})
	mux.HandleFunc("/api/controls", func(w http.ResponseWriter, r *http.Request) {
		var p map[string]any
		_ = json.NewDecoder(r.Body).Decode(&p)
		resp, err := a.command("set_controls", p, 4*time.Second)
		if err != nil {
			writeErr(w, err.Error())
			return
		}
		if _, ok := resp["manual"].(bool); ok {
			a.mu.Lock()
			a.currentControls = cloneMap(resp)
			a.mu.Unlock()
		}
		writeJSON(w, resp)
	})
	mux.HandleFunc("/api/capture", func(w http.ResponseWriter, r *http.Request) {
		var p map[string]any
		_ = json.NewDecoder(r.Body).Decode(&p)
		if err := a.runSequence(p, nil, true); err != nil {
			writeErr(w, err.Error())
			return
		}
		writeJSON(w, map[string]any{"ok": true, "status": "starting"})
	})
	mux.HandleFunc("/api/video/start", func(w http.ResponseWriter, r *http.Request) {
		var p map[string]any
		_ = json.NewDecoder(r.Body).Decode(&p)
		a.mu.Lock()
		if a.sequence.Running || a.videoBusyLocked() {
			activity := a.captureActivityLocked()
			a.mu.Unlock()
			writeErr(w, "A capture is "+activity)
			return
		}
		a.video.Message, a.videoTransition = "Starting", time.Now()
		a.mu.Unlock()
		resp, err := a.command("start_video", p, 8*time.Second)
		if err != nil {
			a.mu.Lock()
			if a.video.Message == "Starting" {
				a.video.Message = "Error"
			}
			a.mu.Unlock()
			writeErr(w, err.Error())
			return
		}
		writeJSON(w, resp)
	})
	mux.HandleFunc("/api/video/stop", func(w http.ResponseWriter, r *http.Request) {
		resp, err := a.command("stop_video", map[string]any{}, 8*time.Second)
		if err != nil {
			writeErr(w, err.Error())
			return
		}
		a.mu.Lock()
		if a.video.Recording {
			a.video.Message = "Stopping"
		}
		a.mu.Unlock()
		writeJSON(w, resp)
	})
	mux.HandleFunc("/api/sequence/start", func(w http.ResponseWriter, r *http.Request) {
		var p map[string]any
		_ = json.NewDecoder(r.Body).Decode(&p)
		if err := a.startSequence(p); err != nil {
			writeErr(w, err.Error())
			return
		}
		writeJSON(w, map[string]any{"ok": true})
	})
	mux.HandleFunc("/api/sequence/stop", func(w http.ResponseWriter, r *http.Request) {
		a.stopSequence()
		writeJSON(w, map[string]any{"ok": true})
	})
	mux.HandleFunc("GET /api/sessions", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, sessionIndex(a.session.Root))
	})
	mux.HandleFunc("POST /api/sessions/resume", func(w http.ResponseWriter, r *http.Request) {
		var request struct {
			ID string `json:"id"`
		}
		if json.NewDecoder(r.Body).Decode(&request) != nil {
			writeErr(w, "Missing session")
			return
		}
		dir, ok := sessionPath(a.session.Root, request.ID)
		if !ok {
			writeErr(w, "Invalid session")
			return
		}
		record := readSession(filepath.Join(dir, "session.jsonl"), request.ID)
		from := record.State
		if from.Requested <= from.Completed {
			writeErr(w, "No unfinished exposures for this session")
			return
		}
		payload := map[string]any{"target": from.Target, "mode": from.Mode, "frame_type": from.FrameType,
			"count": from.Requested, "interval_ms": from.IntervalMs, "raw": record.Raw, "jpeg": record.JPEG,
			"calibration": record.Calibration}
		if err := a.runSequence(payload, &record, false); err != nil {
			writeErr(w, err.Error())
			return
		}
		writeJSON(w, map[string]any{"ok": true})
	})
	mux.HandleFunc("/api/session", func(w http.ResponseWriter, r *http.Request) {
		var p map[string]any
		_ = json.NewDecoder(r.Body).Decode(&p)
		a.mu.Lock()
		if a.sequence.Running || a.videoBusyLocked() {
			target, _ := p["target"].(string)
			mode, _ := p["mode"].(string)
			activity := a.captureActivityLocked()
			unchanged := (strings.TrimSpace(target) == "" || target == a.session.Target) &&
				(strings.TrimSpace(mode) == "" || mode == a.session.Mode)
			a.mu.Unlock()
			if unchanged {
				writeJSON(w, map[string]any{"ok": true})
			} else {
				writeErr(w, "Cannot change sessions while "+strings.ToLower(activity))
			}
			return
		}
		if t, ok := p["target"].(string); ok && strings.TrimSpace(t) != "" && t != a.session.Target {
			a.session.Target = t
			a.sessionID = ""
		}
		if m, ok := p["mode"].(string); ok && strings.TrimSpace(m) != "" && m != a.session.Mode {
			a.session.Mode = m
			a.sessionID = ""
		}
		a.ensureSessionDirLocked()
		a.mu.Unlock()
		writeJSON(w, map[string]any{"ok": true})
	})
	mux.HandleFunc("POST /api/open-folder", func(w http.ResponseWriter, r *http.Request) {
		var request struct {
			ID string `json:"id"`
		}
		_ = json.NewDecoder(r.Body).Decode(&request)
		a.mu.RLock()
		dir, root := a.session.CurrentDir, a.session.Root
		a.mu.RUnlock()
		if request.ID != "" {
			var ok bool
			dir, ok = sessionPath(root, request.ID)
			if !ok {
				writeErr(w, "Invalid session")
				return
			}
		}
		if stat, err := os.Stat(dir); err != nil || !stat.IsDir() {
			writeErr(w, "Session folder is unavailable")
			return
		}
		cmd := exec.Command("explorer.exe", dir)
		if err := cmd.Start(); err != nil {
			writeErr(w, err.Error())
			return
		}
		go func() { _ = cmd.Wait() }()
		writeJSON(w, map[string]any{"ok": true})
	})
	mux.HandleFunc("POST /api/siril", func(w http.ResponseWriter, r *http.Request) {
		var request struct {
			Calibration map[string]string `json:"calibration"`
		}
		_ = json.NewDecoder(r.Body).Decode(&request)
		a.mu.Lock()
		executable, dir, root := a.session.SirilPath, a.session.CurrentDir, a.session.Root
		if executable == "" || a.session.SirilStatus == "Processing" {
			a.mu.Unlock()
			writeErr(w, "Siril is missing or already processing")
			return
		}
		a.session.SirilStatus = "Processing"
		a.mu.Unlock()
		go func() {
			status := "Complete"
			if err := runSiril(executable, root, dir, request.Calibration); err != nil {
				status = "Failed"
				a.setError(err)
			}
			a.mu.Lock()
			a.session.SirilStatus = status
			a.mu.Unlock()
		}()
		writeJSON(w, map[string]any{"ok": true})
	})
	return withHeaders(mux)
}

func withHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		next.ServeHTTP(w, r)
	})
}
func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}
func writeErr(w http.ResponseWriter, s string) {
	w.WriteHeader(http.StatusBadRequest)
	writeJSON(w, map[string]any{"ok": false, "error": s})
}

func openAppWindow(address string) *exec.Cmd {
	url := "http://" + address + "/"
	candidates := [][]string{
		{filepath.Join(os.Getenv("ProgramFiles(x86)"), "Microsoft", "Edge", "Application", "msedge.exe"), "--app=" + url, "--start-maximized"},
		{filepath.Join(os.Getenv("ProgramFiles"), "Microsoft", "Edge", "Application", "msedge.exe"), "--app=" + url, "--start-maximized"},
		{"msedge.exe", "--app=" + url, "--start-maximized"},
	}
	for _, c := range candidates {
		if c[0] == "" {
			continue
		}
		if _, err := exec.LookPath(c[0]); err == nil || fileExists(c[0]) {
			cmd := exec.Command(c[0], c[1:]...)
			if cmd.Start() == nil {
				return cmd
			}
		}
	}
	cmd := exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	if cmd.Start() == nil {
		return cmd
	}
	return nil
}
func fileExists(p string) bool { st, e := os.Stat(p); return e == nil && !st.IsDir() }

func runningVersionMatches(address string) bool {
	client := &http.Client{Timeout: 750 * time.Millisecond}
	response, err := client.Get("http://" + address + "/api/state")
	if err != nil {
		return false
	}
	defer response.Body.Close()
	var identity struct {
		App     string `json:"app"`
		Version string `json:"version"`
	}
	return response.StatusCode == http.StatusOK &&
		json.NewDecoder(io.LimitReader(response.Body, 4<<20)).Decode(&identity) == nil &&
		identity.App == appName && identity.Version == version
}

func main() {
	runtime.LockOSThread()
	listener, err := net.Listen("tcp", httpAddr)
	if err != nil {
		if runningVersionMatches(httpAddr) {
			_ = openAppWindow(httpAddr)
			return
		}
		// A legacy instance can still own the fixed port. Its API and dashboard
		// must not be served as if they belonged to the executable just opened.
		listener, err = net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			return
		}
	}
	a := newApp()
	go a.discoveryLoop()
	srv := &http.Server{Handler: a.routes(), ReadHeaderTimeout: 5 * time.Second}
	go func() { _ = srv.Serve(listener) }()
	_ = openAppWindow(listener.Addr().String())
	<-a.quitApp
	close(a.stop)
	_ = srv.Close()
	a.stopSequence()
	a.mu.RLock()
	finished := a.sequenceFinished
	videoBusy := a.videoBusyLocked()
	a.mu.RUnlock()
	if finished != nil {
		select {
		case <-finished:
		case <-time.After(5 * time.Second):
		}
	}
	if videoBusy {
		_, _ = a.command("stop_video", nil, 2*time.Second)
	}
	a.disconnect("")
	select {
	case <-a.discoveryDone:
	case <-time.After(6 * time.Second):
	}
}

func init() {
	_ = mime.AddExtensionType(".js", "application/javascript")
	_ = mime.AddExtensionType(".css", "text/css")
}
