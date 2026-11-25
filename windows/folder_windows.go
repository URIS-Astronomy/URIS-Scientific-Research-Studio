//go:build windows

package main

import (
	"context"
	"os/exec"
	"strings"
)

func chooseCaptureFolder(ctx context.Context, current string) (string, error) {
	cmd := exec.CommandContext(ctx, "powershell.exe", "-NoProfile", "-STA", "-Command", `
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
Add-Type -AssemblyName System.Windows.Forms
$owner = New-Object System.Windows.Forms.Form
$owner.TopMost = $true
$dialog = New-Object System.Windows.Forms.FolderBrowserDialog
try {
  $dialog.Description = 'Choose where TOD Astro Suite saves captures'
  $dialog.SelectedPath = $env:TOD_FOLDER_PICKER_START
  if ($dialog.ShowDialog($owner) -eq 'OK') { [Console]::Write($dialog.SelectedPath) }
} finally { $dialog.Dispose(); $owner.Dispose() }
`)
	cmd.Env = append(cmd.Environ(), "TOD_FOLDER_PICKER_START="+current)
	hideConsole(cmd)
	data, err := cmd.Output()
	return strings.TrimRight(string(data), "\r\n"), err
}
