package main

import (
	"fmt"
	"image"
	"math"
	"os"
	"path/filepath"
	"strings"
)

type calibration struct {
	dark, flat, bias []float64
	width, height    int
	flatMean         [3]float64
}

func loadCalibration(root string, selection map[string]string, extension string) (*calibration, error) {
	c := &calibration{}
	for _, kind := range []string{"Dark", "Flat", "Bias"} {
		id := selection[kind]
		if id == "" {
			continue
		}
		dir, ok := sessionPath(root, id)
		if !ok {
			return nil, fmt.Errorf("invalid %s session", kind)
		}
		dir = filepath.Join(dir, kind)
		entries, err := os.ReadDir(dir)
		if err != nil {
			return nil, err
		}
		var sum []float64
		count := 0
		for _, entry := range entries {
			if entry.IsDir() || !strings.EqualFold(filepath.Ext(entry.Name()), extension) {
				continue
			}
			img, err := loadStackFrame(filepath.Join(dir, entry.Name()))
			if err != nil {
				return nil, err
			}
			preview := stackPreview(img)
			if c.width == 0 {
				c.width, c.height = preview.Bounds().Dx(), preview.Bounds().Dy()
			}
			if c.width != preview.Bounds().Dx() || c.height != preview.Bounds().Dy() {
				return nil, fmt.Errorf("calibration images have different dimensions")
			}
			if sum == nil {
				sum = make([]float64, c.width*c.height*3)
			}
			for i := 0; i < len(sum); i += 3 {
				for channel := 0; channel < 3; channel++ {
					sum[i+channel] += linearSample(preview.Pix[i/3*4+channel])
				}
			}
			count++
		}
		if count == 0 {
			return nil, fmt.Errorf("%s session contains no %s calibration frames", kind, extension)
		}
		for i := range sum {
			sum[i] /= float64(count)
		}
		switch kind {
		case "Dark":
			c.dark = sum
		case "Flat":
			c.flat = sum
		case "Bias":
			c.bias = sum
		}
	}
	if c.flat != nil {
		for i, v := range c.flat {
			if c.bias != nil {
				v -= c.bias[i]
			}
			c.flat[i] = max(0, v)
			c.flatMean[i%3] += c.flat[i]
		}
		for i := range c.flatMean {
			c.flatMean[i] /= float64(c.width * c.height)
			if c.flatMean[i] < .01 {
				return nil, fmt.Errorf("flat master is underexposed")
			}
		}
	}
	return c, nil
}

func linearSample(v byte) float64 {
	x := float64(v) / 255
	if x <= .04045 {
		return x / 12.92
	}
	return math.Pow((x+.055)/1.055, 2.4)
}

func (c *calibration) apply(img *image.NRGBA) error {
	if c == nil || c.width == 0 {
		return nil
	}
	if img.Bounds().Dx() != c.width || img.Bounds().Dy() != c.height {
		return fmt.Errorf("light frame dimensions differ from calibration masters")
	}
	for i := 0; i < c.width*c.height; i++ {
		for channel := 0; channel < 3; channel++ {
			p := i*3 + channel
			value := linearSample(img.Pix[4*i+channel])
			if c.dark != nil {
				value -= c.dark[p]
			} else if c.bias != nil {
				value -= c.bias[p]
			}
			if c.flat != nil {
				value *= c.flatMean[channel] / max(c.flat[p], c.flatMean[channel]*.1)
			}
			img.Pix[4*i+channel] = displaySample(value)
		}
	}
	return nil
}
