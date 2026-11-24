package main

import (
	"encoding/binary"
	"image"
	"image/color"
	"math"
	"os"
	"path/filepath"
	"testing"
)

func TestFocusIsolatedGaussianAndSaturation(t *testing.T) {
	frame := image.NewGray(image.Rect(0, 0, 128, 128))
	const sigma = 1.7
	paint := func(amplitude float64) {
		for y := 0; y < 128; y++ {
			for x := 0; x < 128; x++ {
				d := math.Hypot(float64(x-64)-.25, float64(y-64)+.15)
				l := 12 + amplitude*math.Exp(-d*d/(2*sigma*sigma))
				frame.SetGray(x, y, color.Gray{Y: byte(min(255, l))})
			}
		}
	}
	paint(200)
	focus := stellarFocus(frame)
	if focus == nil || math.Abs(focus.FWHM-2.355*sigma) > .5 || math.Abs(focus.HFR-1.177*sigma) > .5 {
		t.Fatalf("unexpected Gaussian measurements: %+v", focus)
	}
	paint(300)
	if got := stellarFocus(frame); got != nil {
		t.Fatalf("accepted a clipped star: %+v", got)
	}
	paint(0)
	if got := stellarFocus(frame); got != nil {
		t.Fatalf("accepted a flat frame: %+v", got)
	}
}

func TestCalibrationSubtractsDarkAndNormalizesFlat(t *testing.T) {
	frame := image.NewNRGBA(image.Rect(0, 0, 2, 1))
	for i := 0; i < 2; i++ {
		frame.SetNRGBA(i, 0, color.NRGBA{R: displaySample(.3), G: displaySample(.3), B: displaySample(.3), A: 255})
	}
	master := &calibration{width: 2, height: 1, dark: []float64{.1, .1, .1, .1, .1, .1},
		flat: []float64{.5, .5, .5, 1, 1, 1}, flatMean: [3]float64{.75, .75, .75}}
	if err := master.apply(frame); err != nil {
		t.Fatal(err)
	}
	for x, expected := range []float64{.3, .15} {
		if actual := linearSample(frame.NRGBAAt(x, 0).R); math.Abs(actual-expected) > .01 {
			t.Errorf("pixel %d = %f, expected %f", x, actual, expected)
		}
	}
}

func TestUncompressedBayerDNG(t *testing.T) {
	type field struct {
		tag, typ     uint16
		count, value uint32
	}
	fields := []field{{256, 4, 1, 8}, {257, 4, 1, 8}, {258, 3, 1, 16}, {259, 3, 1, 1}, {262, 3, 1, 32803},
		{273, 4, 1, 0}, {277, 3, 1, 1}, {278, 4, 1, 8}, {279, 4, 1, 128},
		{33421, 3, 2, 2 | 2<<16}, {33422, 1, 4, 0 | 1<<8 | 1<<16 | 2<<24}, {50714, 3, 1, 64}, {50717, 4, 1, 1024}}
	start := 8 + 2 + len(fields)*12 + 4
	fields[5].value = uint32(start)
	b := make([]byte, start+128)
	copy(b, "II")
	binary.LittleEndian.PutUint16(b[2:], 42)
	binary.LittleEndian.PutUint32(b[4:], 8)
	binary.LittleEndian.PutUint16(b[8:], uint16(len(fields)))
	for i, v := range fields {
		p := b[10+i*12:]
		binary.LittleEndian.PutUint16(p, v.tag)
		binary.LittleEndian.PutUint16(p[2:], v.typ)
		binary.LittleEndian.PutUint32(p[4:], v.count)
		binary.LittleEndian.PutUint32(p[8:], v.value)
	}
	for y := 0; y < 8; y++ {
		for x := 0; x < 8; x++ {
			value := []uint16{544, 304, 304, 124}[(y%2)*2+x%2]
			binary.LittleEndian.PutUint16(b[start+2*(y*8+x):], value)
		}
	}
	file := filepath.Join(t.TempDir(), "synthetic.dng")
	if err := os.WriteFile(file, b, 0600); err != nil {
		t.Fatal(err)
	}
	img, err := loadDNG(file)
	if err != nil {
		t.Fatal(err)
	}
	got := img.(*image.NRGBA).NRGBAAt(1, 1)
	expected := color.NRGBA{R: displaySample(.5), G: displaySample(.25), B: displaySample(.0625), A: 255}
	if got != expected {
		t.Fatalf("unexpected Bayer values: %+v wanted %+v", got, expected)
	}
	if err := os.WriteFile(file, b[:start+5], 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := loadDNG(file); err == nil {
		t.Fatal("accepted a truncated DNG")
	}
}

func TestInterruptedJournalRestoresProgress(t *testing.T) {
	root := t.TempDir()
	const id = "2026-11-24/M42/123456-Deep Sky"
	dir, _ := sessionPath(root, id)
	appendSessionLog(dir, map[string]any{"event": "sequence_started", "target": "M42", "mode": "Deep Sky", "frame_type": "Light", "requested": 3, "jpeg": true, "controls": map[string]any{"iso": 400}})
	appendSessionLog(dir, map[string]any{"event": "sequence_progress", "sequence": SequenceState{
		Target: "M42", Mode: "Deep Sky", FrameType: "Light", Requested: 3, Completed: 1, Running: true, IntegrationUS: 500000,
	}})
	appendSessionLog(dir, map[string]any{"event": "file_received", "path": "one.jpg"})
	f, err := os.OpenFile(filepath.Join(dir, "session.jsonl"), os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = f.WriteString("{truncated")
	_ = f.Close()
	list := sessionIndex(root)
	if len(list) != 1 || list[0].State.Running || list[0].State.Message != "Interrupted" || list[0].State.Completed != 1 ||
		list[0].State.IntegrationUS != 500000 || list[0].Controls["iso"] != 400.0 || list[0].Files != 1 {
		t.Fatalf("incorrect recovery: %+v", list)
	}
	if _, ok := sessionPath(root, "../Pictures/file"); ok {
		t.Fatal("accepted traversal")
	}
}
