# Privacy Policy

Effective date: 2026-04-25

This repository provides **Phone AI Control**, an Android app that works with a user-controlled Termux environment and a user-controlled phone API.

## Summary

- This project does **not** include developer-run ads or analytics by default.
- This project is designed to be **self-hosted by the user** on the user's own Android device and Termux environment.
- Data may still be processed by services the user chooses to connect, such as:
  - OpenAI / ChatGPT
  - tunnel providers such as Cloudflare Tunnel or localtunnel
  - Android system components such as the package installer, alarm, timer, and notification services

## What Data The Software Can Handle

Depending on how the user configures the app and API, the software may handle:

- API bearer tokens stored in Termux private storage
- device status information such as battery data
- local API and public tunnel status
- shared-storage file paths, metadata, and file contents
- APK files and installation requests
- device action requests such as notifications, alarms, timers, and app-launch requests
- logs and operational status data generated on the user's own device

## How Data Is Used

Data is used only to operate the user-controlled phone workflow, including:

- checking whether the local API is online
- exposing the API through a tunnel chosen by the user
- reading or writing approved files
- building APKs or running bounded compute jobs in Termux
- triggering Android-side actions through Phone AI Control
- showing status information in the app UI

## Data Storage

By design, most operational data is stored locally on the user's own device, including:

- Termux private app storage
- Phone AI Control app storage
- Android shared storage, if the user explicitly grants access

This project does not require a developer account, remote developer database, or developer-operated cloud backend.

## Data Sharing

This project does not intentionally send user data to a developer-operated server by default.

However, data may be transmitted to third parties when the user chooses to use or enable them, including:

- OpenAI / ChatGPT, if the user connects a Custom GPT or other AI workflow
- public tunnel providers, if the user enables public API exposure
- Android and OEM system components needed for installation, notifications, alarms, timers, or app launches

Users are responsible for understanding the privacy and security implications of any third-party service they enable.

## Permissions

Depending on version and configuration, the app may request permissions related to:

- running Termux commands through the official Termux interface
- reading or writing shared storage
- launching the Android package installer
- posting notifications

These permissions are used to provide the app's phone-control features and are not intended to bypass Android's security model.

## Security

The project is not root by default and does not claim unrestricted access to other apps' private data.

Users should keep their bearer token private and should disable public exposure when it is not needed. If a public tunnel is enabled, requests may traverse third-party infrastructure chosen by the user.

## Retention

Retention depends on the user's own device, app state, Termux files, logs, and any services the user enables. This project does not provide a developer-run retention system.

## Your Choices

Users can generally:

- disable or stop the public tunnel
- revoke Android permissions
- remove files stored on their own device
- uninstall the app
- delete their local Termux environment and local API data

## Open-Source Notice

This project is open source. Anyone who forks, modifies, deploys, or republishes it may operate it differently. If someone else runs a modified or hosted version, their privacy practices may differ from this document.

## Contact

For project questions, use the repository issue tracker or the distribution channel provided by the publisher of your copy of this app.

## Changes

This policy may be updated by future repository commits. The version published in the repository at the referenced URL is the current version for that repository state.
