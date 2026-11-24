package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
)

const (
	transportUSB = "USB"
	transportLAN = "LAN"
)

type endpoint struct {
	kind, host             string
	control, preview, file int
}

func lanEndpoint(host string) endpoint {
	return endpoint{transportLAN, host, controlPort, previewPort, filePort}
}

func (e endpoint) dial(port int, timeout time.Duration) (net.Conn, error) {
	return net.DialTimeout("tcp", net.JoinHostPort(e.host, strconv.Itoa(port)), timeout)
}

type usbStatus struct {
	State  string `json:"state"`
	Detail string `json:"detail"`
}

type usbLink struct {
	serial   string
	endpoint endpoint
}

type adbTunnel struct {
	run        func(...string) (string, error)
	usbPresent func() bool
	link       usbLink
}

func bundledADB() *adbTunnel {
	if runtime.GOOS != "windows" {
		return nil
	}
	exe, err := os.Executable()
	if err != nil {
		return nil
	}
	path := filepath.Join(filepath.Dir(exe), "platform-tools", "adb.exe")
	if _, err := os.Stat(path); err != nil {
		return nil
	}
	return &adbTunnel{usbPresent: androidUSBPresent, run: func(args ...string) (string, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
		defer cancel()
		cmd := exec.CommandContext(ctx, path, args...)
		cmd.Dir = filepath.Dir(path)
		hideConsole(cmd)
		output, err := cmd.CombinedOutput()
		return strings.TrimSpace(string(output)), err
	}}
}

func (a *adbTunnel) detect() (string, usbStatus) {
	output, err := a.run("-d", "get-state")
	lower := strings.ToLower(output)
	switch {
	case strings.Contains(lower, "unauthorized"):
		return "", usbStatus{"unauthorized", "Unlock the phone and approve “Allow USB debugging?” on its RSA prompt."}
	case strings.Contains(lower, "offline"):
		return "", usbStatus{"reconnecting", "The phone is restarting its USB connection."}
	case strings.Contains(lower, "more than one"):
		return "", usbStatus{"multiple", "Connect one USB phone at a time."}
	case err == nil && output == "device":
		serial, err := a.run("-d", "get-serialno")
		if err == nil && serial != "" && !strings.ContainsAny(serial, " \t\r\n") {
			return serial, usbStatus{"authorized", "USB debugging authorized. Open TOD Astro Photography on the phone."}
		}
		return "", usbStatus{"reconnecting", "Confirming the authorized phone."}
	case strings.Contains(lower, "no devices") || strings.Contains(lower, "no usb") || strings.Contains(lower, "device not found"):
		if a.usbPresent != nil && a.usbPresent() {
			return "", usbStatus{"debugging_unavailable", "Android USB data detected. Enable USB debugging and check the phone’s USB driver if it is already enabled."}
		}
		return "", usbStatus{"no_data", "No Android USB data connection detected. Plug in the phone or try a USB data cable instead of a charging-only cable."}
	default:
		return "", usbStatus{"unavailable", "USB detection is temporarily unavailable. Reconnect the cable or restart TOD Astro Suite."}
	}
}

func (a *adbTunnel) forwards(serial string) (endpoint, error) {
	if a.link.serial == serial {
		return a.link.endpoint, nil
	}
	a.close()
	e := endpoint{kind: transportUSB, host: "127.0.0.1"}
	local := []*int{&e.control, &e.preview, &e.file}
	for i, remote := range []int{controlPort, previewPort, filePort} {
		out, err := a.run("-s", serial, "forward", "--no-rebind", "tcp:0", "tcp:"+strconv.Itoa(remote))
		port, parseErr := strconv.Atoi(out)
		if err != nil || parseErr != nil || port < 1 || port > 65535 {
			a.link = usbLink{serial, e}
			a.close()
			return endpoint{}, fmt.Errorf("could not open the USB camera tunnel")
		}
		*local[i] = port
	}
	a.link = usbLink{serial, e}
	return e, nil
}

func (a *adbTunnel) close() {
	link := a.link
	a.link = usbLink{}
	for _, port := range []int{link.endpoint.control, link.endpoint.preview, link.endpoint.file} {
		if port != 0 {
			_, _ = a.run("-s", link.serial, "forward", "--remove", "tcp:"+strconv.Itoa(port))
		}
	}
}

func transportOrder(usb endpoint, discovered, known, manual string) []endpoint {
	var result []endpoint
	if usb.control != 0 {
		result = append(result, usb)
	}
	seen := map[string]bool{}
	for _, host := range []string{discovered, known, manual} {
		if host != "" && !seen[host] {
			result = append(result, lanEndpoint(host))
			seen[host] = true
		}
	}
	return result
}

func lanPreferencePath() string {
	root, err := os.UserConfigDir()
	if err != nil {
		return ""
	}
	return filepath.Join(root, "TOD Astro Suite", "lan-endpoint")
}

func loadLANPreference(path string) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	if ip := net.ParseIP(strings.TrimSpace(string(data))); ip != nil {
		return ip.String()
	}
	return ""
}

func saveLANPreference(path, host string) {
	if path == "" {
		return
	}
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return
	}
	_ = os.WriteFile(path, []byte(host+"\n"), 0600)
}
