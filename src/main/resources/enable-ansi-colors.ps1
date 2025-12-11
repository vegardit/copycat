# SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
# SPDX-FileContributor: Sebastian Thomschke (Vegard IT GmbH)
# SPDX-License-Identifier: Apache-2.0
# SPDX-ArtifactOfProjectHomePage: https://github.com/vegardit/copycat

# enable-ansi.ps1
$signature = @"
using System;
using System.Runtime.InteropServices;

public static class VTConsole {
  [DllImport("kernel32.dll", SetLastError = true)]
  public static extern IntPtr GetStdHandle(int nStdHandle);

  [DllImport("kernel32.dll", SetLastError = true)]
  public static extern bool GetConsoleMode(IntPtr hConsoleHandle, out int lpMode);

  [DllImport("kernel32.dll", SetLastError = true)]
  public static extern bool SetConsoleMode(IntPtr hConsoleHandle, int dwMode);
}
"@

Add-Type -TypeDefinition $signature -PassThru | Out-Null

$STD_OUTPUT_HANDLE = -11
$ENABLE_PROCESSED_OUTPUT = 0x0001
$ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004

$hOut = [VTConsole]::GetStdHandle($STD_OUTPUT_HANDLE)
if ($hOut -eq [IntPtr]::Zero) {
  Write-Host "GetStdHandle failed"
  exit 1
}

[int]$mode = 0
if (-not [VTConsole]::GetConsoleMode($hOut, [ref]$mode)) {
  $err = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
  Write-Host "GetConsoleMode failed with Win32 error $err"
  exit 1
}

# Write-Host ("Mode before: 0x{0:X}" -f $mode)/

$mode = $mode -bor $ENABLE_PROCESSED_OUTPUT -bor $ENABLE_VIRTUAL_TERMINAL_PROCESSING
if (-not [VTConsole]::SetConsoleMode($hOut, $mode)) {
  $err = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
  Write-Host "SetConsoleMode failed with Win32 error $err"
  exit 1
}

# Write-Host ("Mode after:  0x{0:X}" -f $mode)

# $esc = [char]27
# Write-Host "$esc[32mIf this text is green, VT works.$esc[0m"

exit 0
