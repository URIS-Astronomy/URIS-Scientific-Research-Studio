package main

import (
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// stageSirilInputs keeps each sequence homogeneous and gives the script only
// fixed directory names, even when a target or source session contains spaces.
func stageSirilInputs(src, dst, ext string) (int, error) {
	entries, err := os.ReadDir(src)
	if err != nil {
		return 0, err
	}
	if err := os.Mkdir(dst, 0755); err != nil {
		return 0, err
	}
	count := 0
	for _, entry := range entries {
		if entry.IsDir() || !strings.EqualFold(filepath.Ext(entry.Name()), ext) {
			continue
		}
		from := filepath.Join(src, entry.Name())
		to := filepath.Join(dst, fmt.Sprintf("%06d%s", count+1, ext))
		if err := os.Link(from, to); err != nil {
			file, err := os.Open(from)
			if err != nil {
				return count, err
			}
			dest, err := os.Create(to)
			if err != nil {
				file.Close()
				return count, err
			}
			_, copyErr := io.Copy(dest, file)
			file.Close()
			closeErr := dest.Close()
			if copyErr != nil {
				return count, copyErr
			}
			if closeErr != nil {
				return count, closeErr
			}
		}
		count++
	}
	return count, nil
}

func sirilScript(calibrators map[string]bool, raw bool) string {
	var script strings.Builder
	script.WriteString("requires 1.4.4\n")
	convert := "convert"
	if raw {
		convert = "convertraw"
	}
	importSequence := func(directory, kind string) {
		fmt.Fprintf(&script, "cd %s\n%s %s -out=../work\ncd ../work\n", directory, convert, kind)
	}
	for _, kind := range []string{"bias", "dark", "flat"} {
		if !calibrators[kind] {
			continue
		}
		directory := "../" + kind
		if script.Len() == len("requires 1.4.4\n") {
			directory = kind
		}
		importSequence(directory, kind)
		if kind == "flat" {
			sequence := kind
			if calibrators["bias"] {
				script.WriteString("calibrate flat -bias=master_bias.fit\n")
				sequence = "pp_flat"
			}
			fmt.Fprintf(&script, "stack %s rej 3 3 -norm=mul -out=master_flat\n", sequence)
		} else {
			fmt.Fprintf(&script, "stack %s rej 3 3 -nonorm -out=master_%s\n", kind, kind)
		}
	}
	directory := "../light"
	if script.Len() == len("requires 1.4.4\n") {
		directory = "light"
	}
	importSequence(directory, "light")
	options := []string{}
	if calibrators["dark"] {
		options = append(options, "-dark=master_dark.fit")
	} else if calibrators["bias"] {
		options = append(options, "-bias=master_bias.fit")
	}
	if calibrators["flat"] {
		options = append(options, "-flat=master_flat.fit")
	}
	if raw {
		options = append(options, "-debayer")
	}
	sequence := "light"
	if len(options) > 0 {
		fmt.Fprintf(&script, "calibrate light %s\n", strings.Join(options, " "))
		sequence = "pp_light"
	}
	fmt.Fprintf(&script, "register %s -2pass\nseqapplyreg %s -prefix=r_ -framing=min\n", sequence, sequence)
	fmt.Fprintf(&script, "stack r_%s rej 3 3 -norm=addscale -out=TOD_Stacked\n", sequence)
	return script.String()
}

func runSiril(executable, root, session string, calibrators map[string]string) error {
	lights := filepath.Join(session, "Light")
	entries, err := os.ReadDir(lights)
	if err != nil {
		return fmt.Errorf("capture light frames before processing: %w", err)
	}
	extension := ".jpg"
	for _, entry := range entries {
		if strings.EqualFold(filepath.Ext(entry.Name()), ".dng") {
			extension = ".dng"
			break
		}
	}
	job, err := os.MkdirTemp(session, "Siril-")
	if err != nil {
		return err
	}
	if err = os.Mkdir(filepath.Join(job, "work"), 0755); err != nil {
		return err
	}
	count, err := stageSirilInputs(lights, filepath.Join(job, "light"), extension)
	if err != nil {
		return err
	}
	if count < 2 {
		return fmt.Errorf("Siril stacking requires two matching lights")
	}
	present := map[string]bool{}
	for _, kind := range []string{"Bias", "Dark", "Flat"} {
		id := calibrators[kind]
		if id == "" {
			continue
		}
		dir, ok := sessionPath(root, id)
		if !ok {
			return fmt.Errorf("invalid %s session", kind)
		}
		key := strings.ToLower(kind)
		count, err := stageSirilInputs(filepath.Join(dir, kind), filepath.Join(job, key), extension)
		if err != nil {
			return err
		}
		if count < 2 {
			return fmt.Errorf("Siril master %s requires two %s files", kind, extension)
		}
		present[key] = true
	}
	script := filepath.Join(job, "TOD_Process.ssf")
	if err = os.WriteFile(script, []byte(sirilScript(present, extension == ".dng")), 0644); err != nil {
		return err
	}
	logName := filepath.Join(job, "Siril.log")
	log, err := os.Create(logName)
	if err != nil {
		return err
	}
	defer log.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Hour)
	defer cancel()
	command := exec.CommandContext(ctx, executable, "-d", job, "-s", script)
	command.Stdout, command.Stderr = log, log
	if err = command.Run(); err != nil {
		return fmt.Errorf("Siril: %w. Log: %s", err, logName)
	}
	files, _ := filepath.Glob(filepath.Join(job, "work", "TOD_Stacked.*"))
	if len(files) == 0 {
		return fmt.Errorf("Siril did not create a stack. Log: %s", logName)
	}
	return nil
}
