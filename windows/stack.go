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
	"path/filepath"
	"strings"
	"sync"
)

// LiveStack combines saved JPEG light frames in a bounded preview buffer.
// Registration estimates translation against the first accepted image.
type LiveStack struct {
	mu          sync.Mutex
	reference   *image.NRGBA
	sums        [][3]uint64
	counts      []uint64
	stats       StackStats
	calibration *calibration
	extension   string
}

type StackStats struct {
	Accepted      int         `json:"accepted"`
	Rejected      int         `json:"rejected"`
	Histogram     [256]uint64 `json:"histogram"`
	Calibrated    bool        `json:"calibrated"`
	IntegrationUS int64       `json:"integration_us"`
}

func (s *LiveStack) snapshot() StackStats {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.stats
}

func loadJPEG(path string) (image.Image, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	config, err := jpeg.DecodeConfig(file)
	if err != nil {
		return nil, err
	}
	if int64(config.Width)*int64(config.Height) > 100_000_000 {
		return nil, fmt.Errorf("image exceeds the live-stack decoder limit")
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return nil, err
	}
	return jpeg.Decode(file)
}

func loadStackFrame(path string) (image.Image, error) {
	if strings.EqualFold(filepath.Ext(path), ".dng") {
		return loadDNG(path)
	}
	return loadJPEG(path)
}

func (s *LiveStack) addFrame(path string, exposureUS int64) error {
	img, err := loadStackFrame(path)
	if err != nil {
		return err
	}
	frame := stackPreview(img)
	if err := s.calibration.apply(frame); err != nil {
		return err
	}
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
	frame, dx, dy, score := registerStack(s.reference, frame)
	if score < 0.65 {
		s.stats.Rejected++
		return nil
	}
	width, height := frame.Bounds().Dx(), frame.Bounds().Dy()
	for y := max(0, -dy); y < min(height, height-dy); y++ {
		for x := max(0, -dx); x < min(width, width-dx); x++ {
			i := y*width + x
			pixel := frame.NRGBAAt(x+dx, y+dy)
			if pixel.A == 0 {
				continue
			}
			s.sums[i][0] += uint64(pixel.R)
			s.sums[i][1] += uint64(pixel.G)
			s.sums[i][2] += uint64(pixel.B)
			s.counts[i]++
		}
	}
	s.stats.Accepted++
	s.stats.Calibrated = s.calibration != nil && s.calibration.width > 0
	s.stats.Histogram = [256]uint64{}
	for i, sum := range s.sums {
		if count := s.counts[i]; count > 0 {
			bin := (77*sum[0] + 150*sum[1] + 29*sum[2]) / (256 * count)
			s.stats.Histogram[bin]++
		}
	}
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

// Refine small orientation changes when translation alone gives a weak match.
func registerStack(reference, frame *image.NRGBA) (*image.NRGBA, int, int, float64) {
	best := frame
	dx, dy, score := translation(reference, frame)
	if score > .93 {
		return best, dx, dy, score
	}
	for _, angle := range []float64{-2, -1, 1, 2} {
		candidate := rotateStack(frame, angle*math.Pi/180)
		x, y, quality := translation(reference, candidate)
		if quality > score+.015 {
			best, dx, dy, score = candidate, x, y, quality
		}
	}
	return best, dx, dy, score
}

func rotateStack(frame *image.NRGBA, angle float64) *image.NRGBA {
	out := image.NewNRGBA(frame.Bounds())
	width, height := frame.Bounds().Dx(), frame.Bounds().Dy()
	cx, cy := float64(width-1)/2, float64(height-1)/2
	sin, cos := math.Sincos(angle)
	for y := 0; y < height; y++ {
		for x := 0; x < width; x++ {
			dx, dy := float64(x)-cx, float64(y)-cy
			fx, fy := dx*cos-dy*sin+cx, dx*sin+dy*cos+cy
			if fx < 0 || fy < 0 || fx >= float64(width-1) || fy >= float64(height-1) {
				continue
			}
			ix, iy := int(fx), int(fy)
			u, v := fx-float64(ix), fy-float64(iy)
			a, b, c, d := frame.PixOffset(ix, iy), frame.PixOffset(ix+1, iy), frame.PixOffset(ix, iy+1), frame.PixOffset(ix+1, iy+1)
			dst := out.PixOffset(x, y)
			for ch := 0; ch < 3; ch++ {
				top := float64(frame.Pix[a+ch])*(1-u) + float64(frame.Pix[b+ch])*u
				bottom := float64(frame.Pix[c+ch])*(1-u) + float64(frame.Pix[d+ch])*u
				out.Pix[dst+ch] = byte(math.Round(top*(1-v) + bottom*v))
			}
			out.Pix[dst+3] = 255
		}
	}
	return out
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
