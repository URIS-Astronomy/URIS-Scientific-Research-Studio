package main

import (
	"os/exec"
	"strings"
	"syscall"
	"unicode/utf16"
	"unsafe"
)

func hideConsole(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
}

func androidUSBPresent() bool {
	cm := syscall.NewLazyDLL("cfgmgr32.dll")
	sizeProc := cm.NewProc("CM_Get_Device_ID_List_SizeW")
	listProc := cm.NewProc("CM_Get_Device_ID_ListW")
	filter, _ := syscall.UTF16PtrFromString("USB")
	const presentUSB = 0x100 | 0x1 // PRESENT | FILTER_ENUMERATOR
	for attempt := 0; attempt < 2; attempt++ {
		var size uint32
		result, _, _ := sizeProc.Call(uintptr(unsafe.Pointer(&size)), uintptr(unsafe.Pointer(filter)), presentUSB)
		if result != 0 || size < 2 || size > 1<<20 {
			return false
		}
		buf := make([]uint16, size)
		result, _, _ = listProc.Call(uintptr(unsafe.Pointer(filter)), uintptr(unsafe.Pointer(&buf[0])), uintptr(size), presentUSB)
		if result != 0 {
			continue
		}
		for _, id := range strings.Split(strings.ToUpper(string(utf16.Decode(buf))), "\x00") {
			for _, vid := range []string{"2D95", "18D1", "04E8", "2717", "22B8", "2A70", "22D9", "12D1", "0BB4", "0FCE"} {
				if strings.HasPrefix(id, "USB\\VID_"+vid+"&") {
					return true
				}
			}
		}
		return false
	}
	return false
}
