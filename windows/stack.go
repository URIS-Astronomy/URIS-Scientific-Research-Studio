package main

import (
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"io"
	"math"
	"os"
	"sync"
)

// LiveStack combines saved JPEG light frames in a bounded preview buffer.
// Registration estimates translation against the first accepted image.
type LiveStack struct {
	mu        sync.Mutex
	reference *image.NRGBA
	sums      [][3]uint64
	counts    []uint64
	stats     StackStats
}

type StackStats struct {
	Accepted      int   `json:"accepted"`
	Rejected      int   `json:"rejected"`
	IntegrationUS int64 `json:"integration_us"`
}

func (s *LiveStack) snapshot() StackStats {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.stats
}

func (s *LiveStack) addJPEG(path string, exposureUS int64) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()
	config, err := jpeg.DecodeConfig(file)
	if err != nil {
		return err
	}
	if int64(config.Width)*int64(config.Height) > 100_000_000 {
		return fmt.Errorf("image exceeds the live-stack decoder limit")
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return err
	}
	img, err := jpeg.Decode(file)
	if err != nil {
		return err
	}
	frame := stackPreview(img)
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.reference == nil {
		_, _, score := translation(frame, frame)
		if score < 0.65 {
			s.stats.Rejected++
			return nil
		}
		s.reference = frame
		pixels := frame.Bounds().Dx() * frame.Bounds().Dy()
		s.sums = make([][3]uint64, pixels)
		s.counts = make([]uint64, pixels)
	}
	if frame.Bounds() != s.reference.Bounds() {
		s.stats.Rejected++
		return nil
	}
	dx, dy, score := translation(s.reference, frame)
	if score < 0.65 {
		s.stats.Rejected++
		return nil
	}
	width, height := frame.Bounds().Dx(), frame.Bounds().Dy()
	for y := max(0, -dy); y < min(height, height-dy); y++ {
		for x := max(0, -dx); x < min(width, width-dx); x++ {
			i := y*width + x
			pixel := frame.NRGBAAt(x+dx, y+dy)
			s.sums[i][0] += uint64(pixel.R)
			s.sums[i][1] += uint64(pixel.G)
			s.sums[i][2] += uint64(pixel.B)
			s.counts[i]++
		}
	}
	s.stats.Accepted++
	s.stats.IntegrationUS += exposureUS
	return nil
}

func stackPreview(img image.Image) *image.NRGBA {
	b := img.Bounds()
	stride := max(1, (max(b.Dx(), b.Dy())+479)/480)
	out := image.NewNRGBA(image.Rect(0, 0, max(1, b.Dx()/stride), max(1, b.Dy()/stride)))
	for y := 0; y < out.Bounds().Dy(); y++ {
		for x := 0; x < out.Bounds().Dx(); x++ {
			out.Set(x, y, img.At(b.Min.X+x*stride, b.Min.Y+y*stride))
		}
	}
	return out
}

func luminance(img *image.NRGBA) []float64 {
	values := make([]float64, len(img.Pix)/4)
	for i := range values {
		p := img.Pix[i*4:]
		values[i] = (0.299*float64(p[0]) + 0.587*float64(p[1]) + 0.114*float64(p[2]))
	}
	return values
}

func translation(reference, frame *image.NRGBA) (int, int, float64) {
	const radius = 8
	width, height := frame.Bounds().Dx(), frame.Bounds().Dy()
	if width < 32 || height < 32 {
		return 0, 0, -1
	}
	a, b := luminance(reference), luminance(frame)
	bestX, bestY, bestScore := 0, 0, -1.0
	for dy := -radius; dy <= radius; dy++ {
		for dx := -radius; dx <= radius; dx++ {
			var sumA, sumB, squareA, squareB, products, samples float64
			for y := radius; y < height-radius; y += 3 {
				for x := radius; x < width-radius; x += 3 {
					v, w := a[y*width+x], b[(y+dy)*width+x+dx]
					sumA += v
					sumB += w
					squareA += v * v
					squareB += w * w
					products += v * w
					samples++
				}
			}
			varianceA := squareA - sumA*sumA/samples
			varianceB := squareB - sumB*sumB/samples
			if min(varianceA, varianceB) < samples {
				continue
			}
			score := (products - sumA*sumB/samples) / math.Sqrt(varianceA*varianceB)
			if score > bestScore || (score == bestScore && dx*dx+dy*dy < bestX*bestX+bestY*bestY) {
				bestX, bestY, bestScore = dx, dy, score
			}
		}
	}
	return bestX, bestY, bestScore
}

func (s *LiveStack) image() *image.NRGBA {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.stats.Accepted == 0 {
		return nil
	}
	out := image.NewNRGBA(s.reference.Bounds())
	for i, sum := range s.sums {
		count := s.counts[i]
		if count > 0 {
			out.SetNRGBA(i%out.Bounds().Dx(), i/out.Bounds().Dx(), color.NRGBA{
				R: uint8(sum[0] / count), G: uint8(sum[1] / count), B: uint8(sum[2] / count), A: 255,
			})
		}
	}
	return out
}

func writeStackPNG(w io.Writer, img image.Image) error {
	return png.Encode(w, img)
}
