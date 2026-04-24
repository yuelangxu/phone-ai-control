# Phone AI Control

Phone AI Control is a small Android control panel for a Termux-hosted local API.

It is designed for a phone that runs a local build/simulation service on:

```text
http://127.0.0.1:8787
```

The app shows whether the local API is online, shows the current public tunnel URL reported by the API, and can ask Termux to start or stop the local API and public tunnel scripts.

## Features

- Shows local API status.
- Shows current public address.
- Shows whether public exposure is enabled.
- Starts the Termux API and localtunnel helper.
- Stops the public tunnel.
- Stops both the public tunnel and API.
- Copies the current public address.
- Can copy the API token on demand and automatically clear it from clipboard after 20 seconds.
- Does not display the API token in the UI and does not store it in shared storage.
- Uses dedicated Termux wrapper scripts for `Start Public API` and `Stop API And Tunnel`, so the UI no longer depends on a long inline shell command chain.
- Schedules several follow-up refreshes after start/stop actions, which makes status transitions much less likely to get stuck in a stale intermediate state.

## Security Model

This app is only a control panel. It does not contain a backend server, API token, public URL, phone serial number, or device-specific identifier.

The sensitive work is delegated to Termux through Termux's official `RunCommandService`. Termux must explicitly allow external app commands before this app can control scripts.

The token is still owned by the Termux-side API because the Termux process validates incoming bearer tokens. This app requests the token only when the user presses `Copy Token (20s)`, copies it to clipboard, and then clears the clipboard after a short timeout.

Required Termux property:

```text
allow-external-apps = true
```

Required Android permission:

```text
com.termux.permission.RUN_COMMAND
```

The app also enables cleartext HTTP only so it can reach the phone's own loopback address, `127.0.0.1`. It is not meant to send secrets over public HTTP.

## Expected Termux Scripts

The app expects these scripts to exist in Termux:

```text
~/ai-phone-api/start-phone-ai-public.sh
~/ai-phone-api/stop-phone-ai-localtunnel.sh
~/ai-phone-api/stop-phone-ai-tunnel.sh
~/ai-phone-api/stop-phone-ai-all.sh
```

The local API should expose:

```text
GET /healthz
```

with fields such as:

```json
{
  "ok": true,
  "public_url": "https://example.loca.lt",
  "public_enabled": true,
  "localtunnel_running": true,
  "cloudflared_running": false
}
```

## Build In Termux

This repository is a minimal Android Java project. It can be built on a configured Termux Android SDK environment:

```sh
bash build-termux.sh
```

Expected output:

```text
build/PhoneAIControl.apk
```

## Install

```sh
adb install -r build/PhoneAIControl.apk
adb shell pm grant com.example.phoneaicontrol com.termux.permission.RUN_COMMAND
```

If installing directly on the phone, grant the Termux command permission from Android settings if needed.

## Notes

- The package name is `com.example.phoneaicontrol`.
- The app targets a conservative Android SDK level for broad compatibility.
- The public tunnel URL is not hardcoded; it is read from the local API.
- The API token is intentionally not shown in the UI.
