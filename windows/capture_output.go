package main

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"image"
	"image/jpeg"
	"os"
	"path/filepath"
	"strings"
	"time"
)

type savedCapture struct {
	Files   []string  `json:"files"`
	Folder  string    `json:"folder"`
	SavedAt time.Time `json:"saved_at"`
}

func captureRootPreference() string {
	if path := lanPreferencePath(); path != "" {
		return filepath.Join(filepath.Dir(path), "capture-folder")
	}
	return ""
}

func loadCaptureRoot() string {
	data, _ := os.ReadFile(captureRootPreference())
	if path := string(data); filepath.IsAbs(path) {
		if info, err := os.Stat(path); err == nil && info.IsDir() {
			return path
		}
	}
	return filepath.Join(userHome(), "Pictures", "TOD Astro Photography")
}

func (a *App) setCaptureRoot(path string) error {
	path = filepath.Clean(path)
	if !filepath.IsAbs(path) {
		return fmt.Errorf("Choose an absolute capture folder")
	}
	info, err := os.Stat(path)
	if err != nil || !info.IsDir() {
		return fmt.Errorf("Capture folder is unavailable")
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.sequence.Running || a.videoBusyLocked() || len(a.captureTransfers) > 0 {
		return fmt.Errorf("Finish the current capture before changing folders")
	}
	probe, err := os.CreateTemp(path, ".tod-write-check-")
	if err != nil {
		return fmt.Errorf("Capture folder is not writable: %w", err)
	}
	_ = probe.Close()
	_ = os.Remove(probe.Name())
	pref := captureRootPreference()
	if pref == "" {
		return fmt.Errorf("Cannot locate the capture preferences folder")
	}
	if err := os.MkdirAll(filepath.Dir(pref), 0700); err != nil {
		return err
	}
	f, err := os.CreateTemp(filepath.Dir(pref), ".capture-folder-")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	_, writeErr := f.WriteString(path)
	closeErr := f.Close()
	if writeErr != nil {
		return writeErr
	}
	if closeErr != nil {
		return closeErr
	}
	if err := os.Rename(f.Name(), pref); err != nil {
		return err
	}
	if a.session.Root != path {
		a.session.Root, a.session.CurrentDir = path, path
		a.sessionID = ""
		a.session.CapturedFiles = nil
	}
	return nil
}

func saveZoomJPEG(original, session string, zoom float64) (string, error) {
	data, err := os.ReadFile(original)
	if err != nil {
		return "", err
	}
	img, err := jpeg.Decode(bytes.NewReader(data))
	if err != nil {
		return "", err
	}
	b := img.Bounds()
	w, h := max(1, int(float64(b.Dx())/zoom)), max(1, int(float64(b.Dy())/zoom))
	x, y := b.Min.X+(b.Dx()-w)/2, b.Min.Y+(b.Dy()-h)/2
	crop := img.(interface {
		SubImage(image.Rectangle) image.Image
	}).SubImage(image.Rect(x, y, x+w, y+h))
	var encoded bytes.Buffer
	if err := jpeg.Encode(&encoded, crop, &jpeg.Options{Quality: 95}); err != nil {
		return "", err
	}
	dir := filepath.Join(session, "Zoom JPEG")
	if err := os.MkdirAll(dir, 0755); err != nil {
		return "", err
	}
	base := strings.TrimSuffix(filepath.Base(original), filepath.Ext(original))
	final := filepath.Join(dir, fmt.Sprintf("%s_%.2fx.jpg", base, zoom))
	f, err := os.CreateTemp(dir, ".zoom-*.part")
	if err != nil {
		return "", err
	}
	defer os.Remove(f.Name())
	out := encoded.Bytes()
	orientation := jpegOrientation(data)
	_, err = f.Write(out[:2])
	if err == nil && orientation > 1 {
		// Minimal EXIF orientation record. Original dimensions and other
		// capture metadata remain with the original JPEG.
		segment := []byte{0xff, 0xe1, 0, 34, 'E', 'x', 'i', 'f', 0, 0,
			'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 1, 3, 0, 1, 0, 0, 0, orientation, 0, 0, 0, 0, 0, 0, 0}
		_, err = f.Write(segment)
	}
	if err == nil {
		_, err = f.Write(out[2:])
	}
	closeErr := f.Close()
	if err != nil {
		return "", err
	}
	if closeErr != nil {
		return "", closeErr
	}
	return final, os.Rename(f.Name(), final)
}

func jpegOrientation(data []byte) byte {
	for i := 2; i+4 <= len(data) && data[i] == 0xff; {
		marker := data[i+1]
		if marker == 0xda || marker == 0xd9 {
			break
		}
		size := int(binary.BigEndian.Uint16(data[i+2:]))
		if size < 2 || size > len(data)-i-2 {
			break
		}
		part := data[i+4 : i+2+size]
		i += 2 + size
		if marker != 0xe1 || len(part) < 14 || string(part[:6]) != "Exif\x00\x00" {
			continue
		}
		tiff := part[6:]
		var order binary.ByteOrder
		switch string(tiff[:2]) {
		case "II":
			order = binary.LittleEndian
		case "MM":
			order = binary.BigEndian
		default:
			continue
		}
		offset := int(order.Uint32(tiff[4:]))
		if offset < 8 || offset > len(tiff)-2 {
			continue
		}
		count := int(order.Uint16(tiff[offset:]))
		for j := offset + 2; count > 0 && j+12 <= len(tiff); j, count = j+12, count-1 {
			if order.Uint16(tiff[j:]) == 274 && order.Uint16(tiff[j+2:]) == 3 && order.Uint32(tiff[j+4:]) == 1 {
				n := order.Uint16(tiff[j+8:])
				if n >= 1 && n <= 8 {
					return byte(n)
				}
			}
		}
	}
	return 1
}
