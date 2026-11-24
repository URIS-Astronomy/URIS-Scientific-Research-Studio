package main

import (
	"bufio"
	"encoding/json"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
)

type SessionRecord struct {
	Paths        []string          `json:"-"`
	PendingID    string            `json:"-"`
	PendingIndex int               `json:"-"`
	ID           string            `json:"id"`
	State        SequenceState     `json:"state"`
	Controls     map[string]any    `json:"-"`
	Raw          bool              `json:"-"`
	JPEG         bool              `json:"-"`
	Calibration  map[string]string `json:"-"`
	Files        int               `json:"files"`
}

func readSession(path, id string) SessionRecord {
	out := SessionRecord{ID: id, JPEG: true}
	f, err := os.Open(path)
	if err != nil {
		return out
	}
	defer f.Close()
	seen := map[string]bool{}
	scan := bufio.NewScanner(f)
	scan.Buffer(make([]byte, 4096), 2<<20)
	for scan.Scan() {
		var event struct {
			Path          string            `json:"path"`
			CaptureID     string            `json:"capture_id"`
			ExposureIndex int               `json:"exposure_index"`
			Kind          string            `json:"event"`
			Time          string            `json:"time"`
			Target        string            `json:"target"`
			Mode          string            `json:"mode"`
			FrameType     string            `json:"frame_type"`
			Requested     int               `json:"requested"`
			Interval      int               `json:"interval_ms"`
			State         SequenceState     `json:"sequence"`
			Controls      map[string]any    `json:"controls"`
			Raw           bool              `json:"raw"`
			JPEG          *bool             `json:"jpeg"`
			Calibration   map[string]string `json:"calibration"`
		}
		if json.Unmarshal(scan.Bytes(), &event) != nil {
			continue
		}
		switch event.Kind {
		case "sequence_started":
			out.State.Target, out.State.Mode, out.State.FrameType = event.Target, event.Mode, event.FrameType
			out.State.Requested, out.State.IntervalMs, out.State.StartedAt = event.Requested, event.Interval, event.Time
			out.Controls, out.Raw, out.Calibration = event.Controls, event.Raw, event.Calibration
			if event.JPEG != nil {
				out.JPEG = *event.JPEG
			}
		case "capture_requested":
			out.PendingID, out.PendingIndex = event.CaptureID, event.ExposureIndex
		case "sequence_progress", "sequence_finished":
			out.State = event.State
			if out.State.Completed > out.PendingIndex {
				out.PendingID = ""
			}
		case "file_received":
			if !seen[event.Path] {
				seen[event.Path] = true
				out.Files++
				relative, err := filepath.Rel(filepath.Dir(path), event.Path)
				if err == nil && filepath.IsLocal(relative) {
					if info, err := os.Stat(event.Path); err == nil && info.Mode().IsRegular() {
						out.Paths = append(out.Paths, event.Path)
					}
				}
			}
		}
	}
	if out.State.Running || (out.State.Requested > out.State.Completed && out.State.Message != "Stopped") {
		out.State.Message = "Interrupted"
	}
	out.State.Running = false
	if out.State.Requested > 0 && out.State.Completed == out.State.Requested {
		out.State.Message = "Complete"
	}
	return out
}

func sessionIndex(root string) []SessionRecord {
	out := []SessionRecord{}
	_ = filepath.WalkDir(root, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if entry.IsDir() {
			if relative, err := filepath.Rel(root, path); err == nil && len(splitPath(relative)) > 3 {
				return filepath.SkipDir
			}
			return nil
		}
		if entry.Name() == "session.jsonl" {
			id, err := filepath.Rel(root, filepath.Dir(path))
			if err == nil {
				out = append(out, readSession(path, filepath.ToSlash(id)))
			}
		}
		return nil
	})
	sort.Slice(out, func(i, j int) bool { return out[i].State.StartedAt > out[j].State.StartedAt })
	return out
}

func splitPath(s string) []string {
	var parts []string
	for s != "." && s != "" {
		parts = append(parts, filepath.Base(s))
		next := filepath.Dir(s)
		if next == s {
			break
		}
		s = next
	}
	return parts
}

func sessionPath(root, id string) (string, bool) {
	path, err := filepath.Localize(id)
	if err != nil || len(splitPath(path)) != 3 {
		return "", false
	}
	return filepath.Join(root, path), true
}
