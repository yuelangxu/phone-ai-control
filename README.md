# Phone AI Control

Phone AI Control is an Android control panel and mediator app for a Termux-hosted phone API.

It is no longer just a tunnel toggle. The current app can:

- start and stop the Termux local API and public tunnel helpers
- follow a dynamic random 4-digit local port instead of assuming `8787`
- copy the current public URL
- copy the API bearer token on demand and clear it from clipboard after 20 seconds
- show battery information through the local API bridge
- execute device actions requested by the API:
  - notifications
  - alarms with remarks/messages and repeat days
  - timers with remarks/messages
- broker shared-storage file operations on behalf of the Termux API:
  - `list_dir`
  - `read_file`
  - `write_file`
  - `delete_file`
- handle large shared-storage reads with automatic chunk upload instead of trying to inline huge files into JSON
- trigger Android's APK installer through a dedicated app-side install flow

## Current Architecture

The design is deliberately split:

- `Termux` hosts the authenticated local API and the public tunnel processes.
- `Phone AI Control` is the Android-side mediator for things Termux is weak at:
  - Android UI actions
  - shared storage
  - alarms/timers/notifications
  - battery broadcasts
  - install intents
- the app and the API talk over phone-local loopback HTTP

This matters because it explains a lot of the real-world behavior:

- closing the app UI does not necessarily stop the tunnel or Termux API
- force-stopping the app does stop the app's polling service
- force-stopping the app does **not** automatically kill the Termux API or tunnel processes

## Features

### Control And Status

- Shows whether the local API is online.
- Shows the current public address.
- Shows whether public exposure is enabled and reachable.
- Uses dedicated Termux wrapper scripts for start/stop flows instead of a long fragile inline shell chain.
- Follows the current Termux-side port automatically by reading Termux-owned runtime state instead of assuming a fixed port forever.

### Background Polling

- Includes a foreground background-worker service so API-triggered alarms, notifications, timers, installs, and shared-storage tasks can still be consumed when the app is not on screen.
- Polling interval is adjustable in-app.
- Enter `0` in the polling field to disable polling completely.
- Default polling interval is `5` seconds.
- When polling is disabled, queued device actions remain `pending` until the app is reopened or polling is re-enabled.

### Shared Storage Bridge

- Supports all-files access on shared storage when Android permission is granted.
- Shared-storage operations are auto-approved in the current convenience-first setup.
- Small files are returned inline.
- Larger files are uploaded back in chunks, so the app does not need to hold the whole payload in one giant JSON response.

### Device Actions

- Notification endpoint
- Alarm endpoint
- Timer endpoint
- Battery endpoint
- APK install endpoint

### Token Handling

- The API token stays in Termux private storage.
- The app does not display the token in the UI.
- The token is copied only on demand.
- Copied token text is cleared from clipboard after 20 seconds.

## Security Model

This app is powerful, but it is not root and it is not a generic Android exploit layer.

- It cannot read arbitrary private app data such as `/data/data/<other-app>/...`.
- It does not bypass Android lockscreen or package-install security.
- It delegates sensitive script execution to Termux through the official `RunCommandService`.
- It needs explicit Termux cooperation:

```text
allow-external-apps = true
```

- It needs this Android permission:

```text
com.termux.permission.RUN_COMMAND
```

The token still belongs to the Termux-side API because the API process validates incoming bearer tokens. That means the strongest boundary is still: only trust your own GPT/automation and do not leak the token.

## What Still Is Not Implemented

- There is no true Android stopwatch control API in this project yet.
- The app does not silently install APKs; it launches Android's installer flow and the user still confirms the install.
- Force-stopping the app does not automatically shut down Termux's own API/tunnel processes.

## Known Friction And How To Avoid It

This project works, but Android + Termux + tunnels is a curved path. The main traps are:

### 1. Tunnel up does not always mean URL reachable

Cloudflare Quick Tunnel or localtunnel may be "running" while DNS for the published URL is still broken or stale.

To reduce confusion:

- prefer `Refresh Status` after start/stop actions
- expect temporary tunnel URLs to change after restart
- keep a fallback between Cloudflare and localtunnel

### 2. Termux may need to be warm after backend restarts

On this phone, after some backend restarts, opening Termux once helps the local API recover more reliably.

### 3. Shared storage is not the same as app-private storage

Termux itself is still heavily sandboxed. The reason this project can read/write shared files more broadly is that `Phone AI Control` mediates those operations through Android app permissions.

### 4. Force stop semantics are easy to misunderstand

- `Force stop Phone AI Control`: stops polling/service
- `Stop API And Tunnel` in the app: asks Termux to stop its API and tunnel scripts

Those are different operations.

### 5. Giant files must be chunked

Trying to base64 a 1GB+ file into one JSON body is a good way to crash the phone. This is why the current bridge uses:

- inline return for small files
- chunk upload for larger files

### 6. Unknown-sources install still belongs to Android

Even with the dedicated install endpoint, Android may still require:

- enabling unknown-sources for this app
- tapping the final install confirmation

## Build In Termux

This repository is intentionally a plain Java Android project that can be built directly in a prepared Termux toolchain:

```sh
bash build-termux.sh
```

Expected output:

```text
build/PhoneAIControl.apk
```

`build-termux.sh` now changes into the repository directory first, which makes Termux-side builds less fragile when launched from ADB or another working directory.

## Install

```sh
adb install -r build/PhoneAIControl.apk
adb shell pm grant com.example.phoneaicontrol com.termux.permission.RUN_COMMAND
```

If shared storage mediation is needed, also grant Android-side all-files access from settings.

## Recommended Operating Rules

- Keep polling enabled only when you need background device actions.
- Set polling to `0` when you want the app quiet.
- Do not assume the public URL is stable across restarts.
- Treat `force stop` as "pause the mediator", not "destroy the whole phone API stack".
- Prefer the app's own stop buttons when you want the Termux API/tunnel to actually stop.
