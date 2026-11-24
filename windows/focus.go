package main

import (
	"image"
	"math"
	"slices"
)

type StellarFocus struct {
	HFR  float64 `json:"hfr"`
	FWHM float64 `json:"fwhm"`
}

func stellarFocus(img image.Image) *StellarFocus {
	b := img.Bounds()
	center := image.Pt((b.Min.X+b.Max.X)/2, (b.Min.Y+b.Max.Y)/2)
	roi := image.Rect(center.X-48, center.Y-48, center.X+48, center.Y+48).Intersect(b)
	const radius = 9
	gray := func(x, y int) float64 {
		r, g, blue, _ := img.At(x, y).RGBA()
		return (.299*float64(r) + .587*float64(g) + .114*float64(blue)) / 257
	}
	peak := 0.0
	var star image.Point
	for y := roi.Min.Y; y < roi.Max.Y; y++ {
		for x := roi.Min.X; x < roi.Max.X; x++ {
			if v := gray(x, y); v > peak {
				star, peak = image.Pt(x, y), v
			}
		}
	}
	if peak > 248 || !image.Rect(star.X-radius, star.Y-radius, star.X+radius+1, star.Y+radius+1).In(roi) {
		return nil
	}
	var sky []float64
	for y := -radius; y <= radius; y++ {
		for x := -radius; x <= radius; x++ {
			d := x*x + y*y
			if d >= 49 && d <= radius*radius {
				sky = append(sky, gray(star.X+x, star.Y+y))
			}
		}
	}
	slices.Sort(sky)
	background := sky[len(sky)/2]
	noise := max(1, (sky[len(sky)*84/100]-sky[len(sky)*16/100])/2)
	if peak-background < max(14, noise*8) {
		return nil
	}
	type sample struct{ x, y, flux, distance float64 }
	var samples []sample
	var total, sx, sy float64
	for y := -7; y <= 7; y++ {
		for x := -7; x <= 7; x++ {
			if x*x+y*y >= 49 {
				continue
			}
			flux := gray(star.X+x, star.Y+y) - background
			if flux <= 2*noise {
				continue
			}
			s := sample{x: float64(x), y: float64(y), flux: flux}
			samples = append(samples, s)
			sx += s.x * flux
			sy += s.y * flux
			total += flux
		}
	}
	if len(samples) < 5 || total <= 0 {
		return nil
	}
	cx, cy := sx/total, sy/total
	if math.Hypot(cx, cy) > 2.5 {
		return nil
	}
	var xx, yy, xy float64
	for i := range samples {
		s := &samples[i]
		dx, dy := s.x-cx, s.y-cy
		s.distance = math.Hypot(dx, dy)
		xx += s.flux * dx * dx / total
		yy += s.flux * dy * dy / total
		xy += s.flux * dx * dy / total
	}
	trace := xx + yy
	if trace < 0.5 || math.Hypot(xx-yy, 2*xy) > trace*0.72 {
		return nil
	}
	slices.SortFunc(samples, func(a, b sample) int {
		if a.distance < b.distance {
			return -1
		}
		if a.distance > b.distance {
			return 1
		}
		return 0
	})
	var cumulative float64
	for _, s := range samples {
		cumulative += s.flux
		if cumulative >= total/2 {
			return &StellarFocus{HFR: s.distance, FWHM: 2.35482 * math.Sqrt(trace/2)}
		}
	}
	return nil
}
