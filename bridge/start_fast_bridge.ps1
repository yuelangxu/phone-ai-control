$ErrorActionPreference = "Stop"

$BridgeDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$EnvFile = Join-Path $BridgeDir ".env.local"
$RuntimeFile = Join-Path $BridgeDir "bridge.runtime.json"
$BridgePidFile = Join-Path $BridgeDir "bridge.pid"
$TunnelPidFile = Join-Path $BridgeDir "tunnel.pid"
$BridgeOutLog = Join-Path $BridgeDir "bridge.out.log"
$BridgeErrLog = Join-Path $BridgeDir "bridge.err.log"
$TunnelLog = Join-Path $BridgeDir "bridge.tunnel.log"

function Import-DotEnv([string]$Path) {
    if (!(Test-Path $Path)) {
        throw "Missing $Path. Create bridge/.env.local first."
    }
    Get-Content $Path | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) { return }
        $parts = $line.Split("=", 2)
        if ($parts.Length -ne 2) { return }
        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim())
    }
}

function Stop-IfRunning([string]$PidFile) {
    if (Test-Path $PidFile) {
        try {
            $pid = [int](Get-Content $PidFile | Select-Object -First 1)
            $proc = Get-Process -Id $pid -ErrorAction SilentlyContinue
            if ($proc) {
                Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue
                Start-Sleep -Milliseconds 500
            }
        } catch {
        }
        Remove-Item $PidFile -ErrorAction SilentlyContinue
    }
}

Import-DotEnv $EnvFile

$Python = if ($env:PYTHON_EXE) { $env:PYTHON_EXE } else { "python" }
$Port = if ($env:PORT) { $env:PORT } else { "8788" }
$Subdomain = if ($env:BRIDGE_SUBDOMAIN) { $env:BRIDGE_SUBDOMAIN } else { "phone-ai-bridge-" + (Get-Random -Minimum 100000 -Maximum 999999) }

Stop-IfRunning $BridgePidFile
Stop-IfRunning $TunnelPidFile

if (!(Test-Path $Python)) {
    throw "Python executable not found: $Python"
}

$bridgeCommand = @"
Set-Location '$BridgeDir'
`$env:GITHUB_TOKEN = '$($env:GITHUB_TOKEN)'
`$env:RELAY_OWNER = '$($env:RELAY_OWNER)'
`$env:RELAY_REPO = '$($env:RELAY_REPO)'
`$env:RELAY_BRANCH = '$($env:RELAY_BRANCH)'
`$env:REGISTRY_PATH = '$($env:REGISTRY_PATH)'
`$env:SECRET_PATH = '$($env:SECRET_PATH)'
`$env:BRIDGE_BEARER_TOKEN = '$($env:BRIDGE_BEARER_TOKEN)'
`$env:CACHE_TTL_SECONDS = '$($env:CACHE_TTL_SECONDS)'
`$env:REQUEST_TIMEOUT_SECONDS = '$($env:REQUEST_TIMEOUT_SECONDS)'
`$env:PUBLIC_BASE_URL = '$($env:PUBLIC_BASE_URL)'
`$env:PORT = '$Port'
& '$Python' '$BridgeDir\phone_fast_bridge.py' 1>> '$BridgeOutLog' 2>> '$BridgeErrLog'
"@

$bridgeProcess = Start-Process powershell -ArgumentList @("-NoProfile", "-WindowStyle", "Hidden", "-Command", $bridgeCommand) -PassThru
$bridgeProcess.Id | Set-Content $BridgePidFile

Start-Sleep -Seconds 3

$tunnelCommand = @"
Set-Location '$BridgeDir'
npx --yes localtunnel --port $Port --local-host 127.0.0.1 --subdomain $Subdomain 1>> '$TunnelLog' 2>&1
"@

$tunnelProcess = Start-Process powershell -ArgumentList @("-NoProfile", "-WindowStyle", "Hidden", "-Command", $tunnelCommand) -PassThru
$tunnelProcess.Id | Set-Content $TunnelPidFile

$runtime = [ordered]@{
    bridge_port = [int]$Port
    bridge_subdomain = $Subdomain
    bridge_base_url = "https://$Subdomain.loca.lt"
    bridge_schema_url = "https://$Subdomain.loca.lt/openapi-gpt.json"
    bridge_pid = $bridgeProcess.Id
    tunnel_pid = $tunnelProcess.Id
    started_at = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
}
$runtime | ConvertTo-Json | Set-Content $RuntimeFile -Encoding UTF8

Write-Host "Bridge starting at https://$Subdomain.loca.lt"
