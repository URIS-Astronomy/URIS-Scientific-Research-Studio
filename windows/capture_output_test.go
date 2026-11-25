package main

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"image"
	"image/jpeg"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestZoomCopyPreservesOriginalAndOrientation(t *testing.T) {
	dir := t.TempDir()
	original := filepath.Join(dir, "still.jpg")
	var encoded bytes.Buffer
	if err := jpeg.Encode(&encoded, image.NewRGBA(image.Rect(0, 0, 120, 80)), nil); err != nil {
		t.Fatal(err)
	}
	exif := []byte{0xff, 0xe1, 0, 34, 'E', 'x', 'i', 'f', 0, 0,
		'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 1, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0}
	data := append(append(append([]byte{}, encoded.Bytes()[:2]...), exif...), encoded.Bytes()[2:]...)
	if err := os.WriteFile(original, data, 0600); err != nil {
		t.Fatal(err)
	}
	path, err := saveZoomJPEG(original, dir, 2)
	if err != nil {
		t.Fatal(err)
	}
	crop, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	shape, err := jpeg.DecodeConfig(bytes.NewReader(crop))
	if err != nil || shape.Width != 60 || shape.Height != 40 || jpegOrientation(crop) != 6 {
		t.Fatalf("crop dimensions/orientation: %+v, orientation %d, error %v", shape, jpegOrientation(crop), err)
	}
	if got, err := os.ReadFile(original); err != nil || !bytes.Equal(got, data) {
		t.Fatal("original JPEG changed")
	}
}

func TestSingleCapturePublishesReceiptAfterTransfer(t *testing.T) {
	var encoded bytes.Buffer
	if err := jpeg.Encode(&encoded, image.NewRGBA(image.Rect(0, 0, 80, 60)), nil); err != nil {
		t.Fatal(err)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		if _, err := bufio.NewReader(conn).ReadString('\n'); err == nil {
			_ = binary.Write(conn, binary.BigEndian, int64(encoded.Len()))
			_, _ = conn.Write(encoded.Bytes())
		}
	}()

	root := t.TempDir()
	a := &App{
		session:  SessionState{Root: root, Target: "Moon", Mode: "Capture"},
		active:   endpoint{host: "127.0.0.1", file: listener.Addr().(*net.TCPAddr).Port},
		sequence: SequenceState{FrameType: "Light"}, captureID: "capture-1", captureZoom: 2,
		linkChanged: make(chan struct{}), captureDone: make(chan captureResult, 1),
		captureTransfers: make(map[string]bool),
	}
	a.handleEvent(map[string]any{"event": "capture_saved", "capture_id": "capture-1", "files": []any{"/camera/shot.jpg"}})
	select {
	case result := <-a.captureDone:
		if result.err != nil {
			t.Fatal(result.err)
		}
	case <-time.After(4 * time.Second):
		t.Fatal("capture transfer did not complete")
	}
	snapshot := a.snapshot()
	saved := snapshot.LastSaved
	if saved == nil || len(saved.Files) != 2 || snapshot.LastJPEG.Width != 40 || snapshot.LastJPEG.Height != 30 {
		t.Fatalf("expected a saved original and reviewable zoom copy, got %+v", saved)
	}
	for _, path := range saved.Files {
		if info, err := os.Stat(path); err != nil || info.Size() == 0 {
			t.Fatalf("receipt points to missing or empty file: %s", path)
		}
	}
}
