package main

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"embed"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"image"
	_ "image/jpeg"
	"io"
	"io/fs"
	"log"
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
	appName       = "TOD Astro Suite"
	version       = "1.0 Engineering RC"
	controlPort   = 47000
	previewPort   = 47001
	filePort      = 47002
	discoveryPort = 47003
	httpAddr      = "127.0.0.1:47871"
)

//go:embed web/*
var webFS embed.FS

type Metrics struct {
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
	StartedAt      string         `json:"started_at"`
	Message        string         `json:"message"`
}

type SequenceState struct {
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

type StateSnapshot struct {
	App      string        `json:"app"`
	Version  string        `json:"version"`
	Device   DeviceState   `json:"device"`
	Metrics  Metrics       `json:"metrics"`
	Video    VideoState    `json:"video"`
	Sequence SequenceState `json:"sequence"`
	Session  SessionState  `json:"session"`
	Stack    StackStats    `json:"stack"`
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
	mu              sync.RWMutex
	device          DeviceState
	metrics         Metrics
	video           VideoState
	sequence        SequenceState
	session         SessionState
	sessionID       string
	currentControls map[string]any
	videoSession    string
	videoSessions   map[string]string
	stack           *LiveStack

	latestFrame   []byte
	frameETag     string
	lastFrameTime time.Time
	frameBytes    uint64
	frameCounter  uint64

	connMu         sync.Mutex
	control        net.Conn
	writer         *bufio.Writer
	pending        map[string]chan map[string]any
	seqID          atomic.Uint64
	stop           chan struct{}
	reconnect      chan struct{}
	captureDone    chan captureResult
	captureID      string
	sequenceCancel chan struct{}
	connecting     atomic.Bool
	uiSeen         atomic.Bool
	lastUI         atomic.Int64
	quitApp        chan struct{}
}

func newApp() *App {
	pictures := filepath.Join(userHome(), "Pictures", "TOD Astro Photography")
	_ = os.MkdirAll(pictures, 0755)
	a := &App{
		device:   DeviceState{Status: "Waiting for TOD Astro Photography", Capabilities: map[string]any{}, Telemetry: map[string]any{}},
		video:    VideoState{Message: "Idle"},
		sequence: SequenceState{Mode: "Planetary", FrameType: "Light", Message: "Idle"},
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
	return StateSnapshot{Stack: stack, App: appName, Version: version, Device: d, Metrics: a.metrics, Video: a.video, Sequence: a.sequence, Session: s}
}
func cloneMap(src map[string]any) map[string]any {
	out := map[string]any{}
	for k, v := range src {
		out[k] = v
	}
	return out
}

func (a *App) setError(err error) {
	if err == nil {
		return
	}
	a.mu.Lock()
	a.device.LastError = err.Error()
	a.mu.Unlock()
}

func (a *App) discoveryLoop() {
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-a.stop:
			return
		case <-ticker.C:
		case <-a.reconnect:
		}
		a.mu.RLock()
		connected := a.device.Connected
		needsCaps := len(a.device.Capabilities) == 0
		a.mu.RUnlock()
		if connected {
			if _, err := a.command("ping", nil, 3*time.Second); err != nil {
				a.disconnect("Phone connection timed out")
			} else if needsCaps {
				a.queryCapabilities()
			}
			continue
		}
		if host := discoverPhone(); host != "" {
			go a.connect(host)
		}
	}
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

func (a *App) connect(host string) {
	if !a.connecting.CompareAndSwap(false, true) {
		return
	}
	defer a.connecting.Store(false)
	a.mu.RLock()
	already := a.device.Connected
	a.mu.RUnlock()
	if already {
		return
	}
	c, err := net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(controlPort)), 2*time.Second)
	if err != nil {
		a.setError(err)
		return
	}
	a.connMu.Lock()
	if a.control != nil {
		a.connMu.Unlock()
		_ = c.Close()
		return
	}
	a.control = c
	a.writer = bufio.NewWriter(c)
	a.connMu.Unlock()
	a.mu.Lock()
	a.device.Connected = true
	a.device.Host = host
	a.device.Status = "Connected"
	a.device.LastError = ""
	a.mu.Unlock()
	go a.controlReadLoop(c)
	go a.previewLoop(host, c)
	if r, err := a.command("hello", map[string]any{"client": appName, "version": version}, 3*time.Second); err == nil {
		a.applyHello(r)
	} else {
		a.setError(err)
	}
	a.queryCapabilities()
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
	a.connMu.Lock()
	if a.control != nil {
		_ = a.control.Close()
		a.control = nil
		a.writer = nil
	}
	a.connMu.Unlock()
	a.mu.Lock()
	a.device.Connected = false
	a.device.Status = "Waiting for phone"
	if a.device.Host != "" {
		a.device.Status = "Reconnecting"
	}
	a.device.Capabilities = map[string]any{}
	a.device.Telemetry = map[string]any{}
	a.latestFrame = nil
	a.metrics = Metrics{}
	a.lastFrameTime = time.Time{}
	a.video.Recording = false
	a.video.Message = "Idle"
	if reason != "" {
		a.device.LastError = reason
	}
	id := a.captureID
	dir := a.session.CurrentDir
	a.mu.Unlock()
	if reason != "" {
		a.completeCapture(captureResult{id: id, err: fmt.Errorf("%s", reason)})
		if id != "" {
			appendSessionLog(dir, map[string]any{"event": "disconnected", "error": reason})
		}
	}
}

func (a *App) controlReadLoop(c net.Conn) {
	scan := bufio.NewScanner(c)
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
		a.handleEvent(m)
	}
	a.disconnect("Phone link closed")
}

func (a *App) applyHello(r map[string]any) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if p, ok := asInt(r["protocol"]); ok {
		a.device.Protocol = p
	}
}

func (a *App) handleEvent(m map[string]any) {
	ev := fmt.Sprint(m["event"])
	switch ev {
	case "telemetry":
		a.mu.Lock()
		a.device.Telemetry = m
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
		host := a.device.Host
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
				if _, err := a.fetchFile(host, fmt.Sprint(frame["path"]), filepath.Join(dir, "Best frames"), dir); err != nil {
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
		host := a.device.Host
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
		if id != "" && id == a.captureID && a.sequence.Mode == "Live Stack" && frameType == "Light" {
			stack = a.stack
		}
		a.mu.Unlock()
		go func() {
			var err error
			if len(files) == 0 {
				err = fmt.Errorf("phone saved no files")
			}
			for _, file := range files {
				path, transferErr := a.fetchFile(host, fmt.Sprint(file), dir, sessionDir)
				if transferErr != nil {
					err = transferErr
					break
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
			a.completeCapture(captureResult{id: id, err: err})
		}()

	case "warning":
		a.mu.Lock()
		a.device.LastError = fmt.Sprint(m["message"])
		a.mu.Unlock()
	case "capture_error":
		a.mu.Lock()
		a.device.LastError = fmt.Sprint(m["message"])
		a.video.Recording = false
		a.video.Message = "Error"
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

func (a *App) previewLoop(host string, control net.Conn) {
	for {
		a.connMu.Lock()
		active := a.control == control
		a.connMu.Unlock()
		if !active {
			return
		}
		a.readPreview(host)
		select {
		case <-a.stop:
			return
		case <-time.After(time.Second):
		}
	}
}

func (a *App) readPreview(host string) {
	c, err := net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(previewPort)), 2*time.Second)
	if err != nil {
		return
	}
	defer c.Close()
	for {
		_ = c.SetReadDeadline(time.Now().Add(5 * time.Second))
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
		a.ingestFrame(b)
	}
}

func (a *App) ingestFrame(b []byte) {
	now := time.Now()
	sum := sha256.Sum256(b)
	a.mu.Lock()
	prev := a.lastFrameTime
	a.latestFrame = append(a.latestFrame[:0], b...)
	a.frameETag = hex.EncodeToString(sum[:8])
	a.frameCounter++
	if !prev.IsZero() {
		inst := 1 / now.Sub(prev).Seconds()
		if a.metrics.FPS == 0 {
			a.metrics.FPS = inst
		} else {
			a.metrics.FPS = a.metrics.FPS*0.85 + inst*0.15
		}
	}
	a.lastFrameTime = now
	a.frameBytes += uint64(len(b))
	a.metrics.Frames = a.frameCounter
	analyze := a.frameCounter%3 == 0
	a.mu.Unlock()
	if analyze {
		a.analyzeFrame(b)
	}
}

func (a *App) analyzeFrame(b []byte) {
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
	for y := step; y < h-step; y += step * 2 {
		for x := step; x < w-step; x += step * 2 {
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
	m := Metrics{Histogram: hist, Star: stellarFocus(img)}
	if total > 0 {
		m.Mean = float64(sum) / float64(total)
		m.Median = median
		m.ShadowClip = float64(low) * 100 / float64(total)
		m.HighlightClip = float64(high) * 100 / float64(total)
	}
	if focusN > 0 {
		m.FocusScore = focus / float64(focusN)
	}
	a.mu.Lock()
	m.FocusHistory = append(append([]float64(nil), a.metrics.FocusHistory...), m.FocusScore)
	if len(m.FocusHistory) > 90 {
		m.FocusHistory = m.FocusHistory[1:]
	}
	m.Frames = a.frameCounter
	m.FPS = a.metrics.FPS
	m.BitrateMbps = a.metrics.FPS * float64(len(b)) * 8 / 1e6
	m.Dropped = a.metrics.Dropped
	a.metrics = m
	a.mu.Unlock()
}

func (a *App) fetchFile(host, path, dir, sessionDir string) (string, error) {
	c, err := net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(filePort)), 3*time.Second)
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
	return a.runSequence(req, nil)
}

func (a *App) runSequence(req map[string]any, resume *SessionRecord) error {
	a.mu.Lock()
	if a.sequence.Running {
		a.mu.Unlock()
		return fmt.Errorf("sequence already running")
	}
	count, _ := asInt(req["count"])
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
	if mode == "Live Stack" {
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
	a.session.Target = target
	a.session.Mode = mode
	a.sessionID = ""
	dir := a.ensureSessionDirLocked()
	controls := cloneMap(a.currentControls)
	completed := 0
	if resume != nil {
		dir, _ = sessionPath(a.session.Root, resume.ID)
		a.session.CurrentDir, a.sessionID = dir, filepath.Base(dir)
		controls = cloneMap(resume.Controls)
		completed = resume.State.Completed
	}
	capabilities := cloneMap(a.device.Capabilities)
	telemetry := cloneMap(a.device.Telemetry)
	exposure, _ := asInt(controls["exposure_us"])
	if controls["manual"] != true {
		exposure = 0
	}
	a.sequence = SequenceState{Running: true, Target: target, Mode: mode, FrameType: ft, Requested: count, Completed: completed, IntervalMs: interval, StartedAt: time.Now().Format(time.RFC3339), Message: "Capturing"}
	if resume != nil {
		a.sequence.IntegrationUS = resume.State.IntegrationUS
		a.sequence.StartedAt = resume.State.StartedAt
	}
	cancel := make(chan struct{})
	a.sequenceCancel = cancel
	a.mu.Unlock()
	raw, _ := req["raw"].(bool)
	jpeg := true
	if v, ok := req["jpeg"].(bool); ok {
		jpeg = v
	}
	go func() {
		if resume == nil {
			appendSessionLog(dir, map[string]any{
				"event": "sequence_started", "target": target, "mode": mode, "frame_type": ft,
				"requested": count, "interval_ms": interval, "controls": controls,
				"capabilities": capabilities, "telemetry": telemetry, "raw": raw, "jpeg": jpeg,
				"calibration": req["calibration"],
			})
		} else {
			appendSessionLog(dir, map[string]any{"event": "sequence_resumed", "completed": completed})
		}

		defer func() {
			a.mu.Lock()
			a.sequence.Running = false
			a.captureID = ""
			if a.sequence.Message == "Capturing" {
				a.sequence.Message = "Complete"
			}
			sequence, metrics, telemetry := a.sequence, a.metrics, cloneMap(a.device.Telemetry)
			a.mu.Unlock()
			appendSessionLog(dir, map[string]any{"event": "sequence_finished", "sequence": sequence,
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
				a.mu.Lock()
				a.sequence.Message = "Stopped"
				a.mu.Unlock()
				return
			default:
			}
			if err := a.waitForSequenceLink(cancel, dir); err != nil {
				a.mu.Lock()
				if a.sequence.Message != "Stopped" {
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
			appendSessionLog(dir, map[string]any{"event": "capture_requested", "capture_id": id, "exposure_index": i})
			a.mu.Lock()
			select {
			case <-a.captureDone:
			default:
			}
			a.captureID = id
			a.mu.Unlock()
			payload := map[string]any{"raw": raw, "jpeg": jpeg, "target": target, "mode": mode, "frame_type": ft, "capture_id": id}
			if _, err := a.command("capture", payload, 5*time.Second); err != nil {
				a.setError(err)
				a.mu.Lock()
				a.sequence.Message = "Capture error"
				a.mu.Unlock()
				return
			}
			select {
			case result := <-a.captureDone:
				if result.err != nil {
					a.setError(result.err)
					a.mu.Lock()
					a.sequence.Message = "Capture failed"
					a.mu.Unlock()
					return
				}
			case <-time.After(3 * time.Minute):
				a.setError(fmt.Errorf("capture or transfer timed out"))
				a.mu.Lock()
				a.sequence.Message = "Capture timed out"
				a.mu.Unlock()
				return
			case <-cancel:
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
			appendSessionLog(dir, map[string]any{"event": "sequence_progress", "sequence": progress})
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
func (a *App) waitForSequenceLink(cancel <-chan struct{}, dir string) error {
	a.mu.Lock()
	if a.device.Connected {
		a.mu.Unlock()
		return nil
	}
	if a.sequence.Message == "Stopped" {
		a.mu.Unlock()
		return fmt.Errorf("sequence stopped")
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

func (a *App) stopSequence() {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.sequence.Running && a.sequenceCancel != nil {
		close(a.sequenceCancel)
		a.sequenceCancel = nil
		a.sequence.Message = "Stopped"
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
	mux.HandleFunc("/api/ui-heartbeat", func(w http.ResponseWriter, r *http.Request) {
		a.uiSeen.Store(true)
		a.lastUI.Store(time.Now().UnixNano())
		writeJSON(w, map[string]any{"ok": true})
	})
	mux.HandleFunc("/api/exit", func(w http.ResponseWriter, r *http.Request) {
		select {
		case a.quitApp <- struct{}{}:
		default:
		}
		writeJSON(w, map[string]any{"ok": true})
	})
	mux.HandleFunc("/api/preview.jpg", func(w http.ResponseWriter, r *http.Request) {
		a.mu.RLock()
		b := append([]byte(nil), a.latestFrame...)
		etag := a.frameETag
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
		go a.connect(strings.TrimSpace(q.Host))
		writeJSON(w, map[string]any{"ok": true})
	})
	mux.HandleFunc("/api/controls", func(w http.ResponseWriter, r *http.Request) {
		var p map[string]any
		_ = json.NewDecoder(r.Body).Decode(&p)
		resp, err := a.command("set_controls", p, 4*time.Second)
		if err != nil {
			writeErr(w, err.Error())
			return
		}
		a.mu.Lock()
		a.currentControls = cloneMap(resp)
		a.mu.Unlock()
		writeJSON(w, resp)
	})
	mux.HandleFunc("/api/capture", func(w http.ResponseWriter, r *http.Request) {
		var p map[string]any
		_ = json.NewDecoder(r.Body).Decode(&p)
		resp, err := a.command("capture", p, 5*time.Second)
		if err != nil {
			writeErr(w, err.Error())
			return
		}
		writeJSON(w, resp)
	})
	mux.HandleFunc("/api/video/start", func(w http.ResponseWriter, r *http.Request) {
		var p map[string]any
		_ = json.NewDecoder(r.Body).Decode(&p)
		a.mu.RLock()
		recording := a.video.Recording
		a.mu.RUnlock()
		if recording {
			writeErr(w, "video is already recording")
			return
		}
		resp, err := a.command("start_video", p, 8*time.Second)
		if err != nil {
			writeErr(w, err.Error())
			return
		}
		a.mu.Lock()
		if !a.video.Recording && a.video.Message != "Error" {
			a.video.Message = "Starting"
		}
		a.mu.Unlock()

		writeJSON(w, resp)
	})
	mux.HandleFunc("/api/video/stop", func(w http.ResponseWriter, r *http.Request) {
		resp, err := a.command("stop_video", map[string]any{}, 8*time.Second)
		if err != nil {
			writeErr(w, err.Error())
			return
		}
		a.mu.Lock()
		a.video.Message = "Stopping"
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
		if err := a.runSequence(payload, &record); err != nil {
			writeErr(w, err.Error())
			return
		}
		writeJSON(w, map[string]any{"ok": true})
	})
	mux.HandleFunc("/api/session", func(w http.ResponseWriter, r *http.Request) {
		var p map[string]any
		_ = json.NewDecoder(r.Body).Decode(&p)
		a.mu.Lock()
		if a.sequence.Running || a.video.Recording {
			a.mu.Unlock()
			writeErr(w, "A capture run is active")
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

func openAppWindow() *exec.Cmd {
	url := "http://" + httpAddr + "/"
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

func main() {
	runtime.LockOSThread()
	log.SetOutput(io.Discard)
	a := newApp()
	go a.discoveryLoop()
	srv := &http.Server{Addr: httpAddr, Handler: a.routes(), ReadHeaderTimeout: 5 * time.Second}
	go func() { _ = srv.ListenAndServe() }()
	time.Sleep(250 * time.Millisecond)
	_ = openAppWindow()
	go func() {
		ticker := time.NewTicker(3 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-a.stop:
				return
			case <-ticker.C:
				if !a.uiSeen.Load() {
					continue
				}
				last := a.lastUI.Load()
				if last > 0 && time.Since(time.Unix(0, last)) > 15*time.Second {
					select {
					case a.quitApp <- struct{}{}:
					default:
					}
					return
				}
			}
		}
	}()
	<-a.quitApp
	close(a.stop)
	a.stopSequence()
	a.disconnect("")
	_ = srv.Close()
}

func init() {
	_ = mime.AddExtensionType(".js", "application/javascript")
	_ = mime.AddExtensionType(".css", "text/css")
}
