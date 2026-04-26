$ErrorActionPreference = "SilentlyContinue"

$BridgeDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BridgePidFile = Join-Path $BridgeDir "bridge.pid"
$TunnelPidFile = Join-Path $BridgeDir "tunnel.pid"
$RuntimeFile = Join-Path $BridgeDir "bridge.runtime.json"

function Stop-IfRunning([string]$PidFile) {
    if (Test-Path $PidFile) {
        try {
            $pid = [int](Get-Content $PidFile | Select-Object -First 1)
            $proc = Get-Process -Id $pid -ErrorAction SilentlyContinue
            if ($proc) {
                Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue
            }
        } catch {
        }
        Remove-Item $PidFile -ErrorAction SilentlyContinue
    }
}

Stop-IfRunning $TunnelPidFile
Stop-IfRunning $BridgePidFile
Remove-Item $RuntimeFile -ErrorAction SilentlyContinue
Write-Host "Fast bridge stopped."
