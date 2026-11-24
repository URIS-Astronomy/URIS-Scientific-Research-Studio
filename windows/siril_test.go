package main

import (
	"image"
	"image/color"
	"image/jpeg"
	"math"
	"math/rand"
	"os"
	"path/filepath"
	"strconv"
	"testing"
)

func TestSirilProcessesJPEGSequence(t *testing.T) {
	cli := os.Getenv("TOD_TEST_SIRIL_CLI")
	if cli == "" {
		t.Skip("portable Siril is required for this integration test")
	}
	root := t.TempDir()
	session := filepath.Join(root, "Synthetic field")
	lights := filepath.Join(session, "Light")
	if err := os.MkdirAll(lights, 0755); err != nil {
		t.Fatal(err)
	}
	type star struct {
		x, y int
		peak float64
	}
	random := rand.New(rand.NewSource(42))
	stars := make([]star, 120)
	for i := range stars {
		stars[i] = star{30 + random.Intn(580), 30 + random.Intn(420), 130 + random.Float64()*100}
	}

	for index := 0; index < 6; index++ {
		frame := image.NewRGBA(image.Rect(0, 0, 640, 480))
		for y := 0; y < 480; y++ {
			for x := 0; x < 640; x++ {
				level := uint8(14 + random.Intn(8))
				frame.SetRGBA(x, y, color.RGBA{level, level, level, 255})
			}
		}
		for _, point := range stars {
			for y := -7; y <= 7; y++ {
				for x := -7; x <= 7; x++ {
					px, py := point.x+x+index, point.y+y-index
					background := float64(frame.RGBAAt(px, py).R)
					signal := point.peak * math.Exp(-float64(x*x+y*y)/7)
					sample := uint8(math.Min(255, background+signal))
					frame.SetRGBA(px, py, color.RGBA{sample, sample, sample, 255})
				}
			}
		}
		output, err := os.Create(filepath.Join(lights, "field-"+strconv.Itoa(index)+".jpg"))
		if err != nil {
			t.Fatal(err)
		}
		encodeErr := jpeg.Encode(output, frame, &jpeg.Options{Quality: 98})
		closeErr := output.Close()
		if encodeErr != nil {
			t.Fatal(encodeErr)
		}
		if closeErr != nil {
			t.Fatal(closeErr)
		}
	}
	if err := runSiril(cli, root, session, nil); err != nil {
		logs, _ := filepath.Glob(filepath.Join(session, "Siril-*", "Siril.log"))
		for _, log := range logs {
			if body, readErr := os.ReadFile(log); readErr == nil {
				t.Log(string(body))
			}
		}
		t.Fatal(err)
	}
	products, _ := filepath.Glob(filepath.Join(session, "Siril-*", "work", "TOD_Stacked.*"))
	if len(products) != 1 {
		t.Fatalf("expected one stacked image, got %v", products)
	}
	data, err := os.ReadFile(products[0])
	if err != nil || len(data) < 2880 || string(data[:8]) != "SIMPLE  " {
		t.Fatalf("invalid FITS stack: %v, %d bytes", err, len(data))
	}
}
