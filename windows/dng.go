package main

import (
	"encoding/binary"
	"fmt"
	"image"
	"math"
	"os"
)

type dngReader struct {
	data  []byte
	order binary.ByteOrder
	tags  map[uint16][]float64
}

// loadDNG reads classic TIFF-based, uncompressed 16-bit Bayer DNGs. It scans
// SubIFDs because Android may write a separate thumbnail before the RAW plane.
func loadDNG(path string) (image.Image, error) {
	stat, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	if stat.Size() > 256<<20 {
		return nil, fmt.Errorf("DNG exceeds the 256 MiB decoder limit")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if len(data) < 8 {
		return nil, fmt.Errorf("truncated DNG")
	}
	r := dngReader{data: data, order: binary.LittleEndian}
	switch string(data[:2]) {
	case "II":
	case "MM":
		r.order = binary.BigEndian
	default:
		return nil, fmt.Errorf("invalid DNG byte order")
	}
	if r.order.Uint16(data[2:4]) != 42 {
		return nil, fmt.Errorf("DNG requires classic TIFF")
	}
	queue := []uint32{r.order.Uint32(data[4:8])}
	seen := make(map[uint32]bool)
	for len(queue) > 0 && len(seen) < 32 {
		offset := queue[0]
		queue = queue[1:]
		if offset == 0 || seen[offset] {
			continue
		}
		seen[offset] = true
		next := r.readIFD(int(offset))
		if next != 0 {
			queue = append(queue, next)
		}
		for _, child := range r.tags[330] {
			queue = append(queue, uint32(child))
		}
		if r.first(262) == 32803 {
			return r.bayerPreview()
		}
	}
	return nil, fmt.Errorf("DNG has no supported Bayer image")
}

func (r *dngReader) first(tag uint16) int {
	if values := r.tags[tag]; len(values) > 0 {
		return int(values[0])
	}
	return 0
}

func (r *dngReader) readIFD(offset int) uint32 {
	r.tags = make(map[uint16][]float64)
	if offset < 0 || offset > len(r.data)-2 {
		return 0
	}
	count := int(r.order.Uint16(r.data[offset:]))
	start := offset + 2
	end := start + count*12
	if end > len(r.data)-4 {
		return 0
	}
	for i := start; i < end; i += 12 {
		field := r.data[i : i+12]
		tag, typ := r.order.Uint16(field), r.order.Uint16(field[2:])
		switch tag {
		case 256, 257, 258, 259, 262, 273, 277, 278, 279, 330, 33421, 33422, 50713, 50714, 50717:
		default:
			continue
		}
		n := int(r.order.Uint32(field[4:]))
		unit := 0
		switch typ {
		case 1:
			unit = 1
		case 3:
			unit = 2
		case 4, 13:
			unit = 4
		case 5:
			unit = 8
		}
		if unit == 0 || n < 1 || n > 1<<16 {
			continue
		}
		var raw []byte
		if n*unit <= 4 {
			raw = field[8 : 8+n*unit]
		} else {
			ptr := int(r.order.Uint32(field[8:]))
			if ptr < 0 || ptr > len(r.data)-n*unit {
				continue
			}
			raw = r.data[ptr : ptr+n*unit]
		}
		values := make([]float64, n)
		for j := range values {
			b := raw[j*unit:]
			switch typ {
			case 1:
				values[j] = float64(b[0])
			case 3:
				values[j] = float64(r.order.Uint16(b))
			case 4, 13:
				values[j] = float64(r.order.Uint32(b))
			case 5:
				if d := r.order.Uint32(b[4:]); d != 0 {
					values[j] = float64(r.order.Uint32(b)) / float64(d)
				}
			}
		}
		r.tags[tag] = values
	}
	return r.order.Uint32(r.data[end:])
}

func (r *dngReader) bayerPreview() (image.Image, error) {
	w, h := r.first(256), r.first(257)
	rows := r.first(278)
	offsets, sizes := r.tags[273], r.tags[279]
	pattern, dims := r.tags[33422], r.tags[33421]
	if w < 4 || h < 4 || w > 12000 || h > 12000 || r.first(258) != 16 || r.first(259) != 1 ||
		r.first(277) > 1 || len(pattern) != 4 || len(dims) != 2 || dims[0] != 2 || dims[1] != 2 ||
		rows < 1 || len(offsets) != (h+rows-1)/rows || len(sizes) != len(offsets) {
		return nil, fmt.Errorf("unsupported DNG layout; expected uncompressed 16-bit 2×2 Bayer strips")
	}
	for _, channel := range pattern {
		if channel < 0 || channel > 2 {
			return nil, fmt.Errorf("unsupported CFA color")
		}
	}
	for i, start := range offsets {
		length := min(rows, h-i*rows) * w * 2
		if start < 0 || int(start) > len(r.data)-length || sizes[i] < float64(length) {
			return nil, fmt.Errorf("truncated DNG strip")
		}
	}
	white := float64(r.first(50717))
	if white <= 0 {
		white = 65535
	}
	black := r.tags[50714]
	stride := max(1, (max(w/2, h/2)+479)/480)
	out := image.NewNRGBA(image.Rect(0, 0, max(1, w/2/stride), max(1, h/2/stride)))
	for y := 0; y < out.Bounds().Dy(); y++ {
		for x := 0; x < out.Bounds().Dx(); x++ {
			var sum [3]float64
			var n [3]int
			for j := 0; j < 4; j++ {
				sx, sy := x*2*stride+j%2, y*2*stride+j/2
				ptr := int(offsets[sy/rows]) + ((sy%rows)*w+sx)*2
				v := float64(r.order.Uint16(r.data[ptr:]))
				base := 0.0
				if len(black) == 1 {
					base = black[0]
				} else if len(black) >= 4 {
					base = black[j]
				}
				c := int(pattern[j])
				sum[c] += max(0, min(1, (v-base)/max(1, white-base)))
				n[c]++
			}
			p := out.PixOffset(x, y)
			for c := 0; c < 3; c++ {
				out.Pix[p+c] = displaySample(sum[c] / float64(max(1, n[c])))
			}
			out.Pix[p+3] = 255
		}
	}
	return out, nil
}

func displaySample(value float64) byte {
	value = max(0, min(1, value))
	if value <= .0031308 {
		value *= 12.92
	} else {
		value = 1.055*math.Pow(value, 1/2.4) - .055
	}
	return byte(math.Round(value * 255))
}
