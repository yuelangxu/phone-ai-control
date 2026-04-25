# Phone AI Agent Overview

This knowledge file describes a self-hosted phone automation stack built from:

- A Custom GPT with an Action
- A public HTTPS tunnel
- A local API running inside Termux on the phone
- The Phone AI Control Android app

## Purpose

The system lets a trusted Custom GPT inspect phone state, submit bounded jobs, queue Android-side actions, and coordinate file approvals without exposing arbitrary shell access.

## Current Capabilities

The phone-side API can currently support these kinds of work:

- Check service status and connectivity
- Read cached battery status
- Read cached usage and memory-pressure information
- Take a single diagnostics snapshot
- Take a bounded diagnostics poll for a limited number of samples
- List or create APK install requests for Phone AI Control to handle
- Queue Android-side actions such as notifications, alarms, timers, and open-app requests
- Create bounded `apk_build` and `python_sim` jobs
- Create and track file approval flows for shared-storage changes
- Start or stop the public tunnel

## Important Limits

This system is not root and is not a full device-admin tool.

It does **not** currently provide:

- Arbitrary shell command execution
- Arbitrary package manager access
- Full-system per-process CPU usage
- Full-system GPU usage
- Full Android logcat access
- Full privileged `dumpsys` inspection

Those stronger diagnostics need elevated privileges such as ADB shell, Shizuku, or root.

## Trust Model

The API is intended for a trusted private Custom GPT.

Do not put the bearer token into:

- GPT Knowledge files
- GPT Instructions
- Screenshots
- README examples committed to GitHub

The bearer token should be stored only in the GPT Action authentication settings.

## Tunnel Reality

The public tunnel URL may change when the tunnel restarts.

If the tunnel URL changes:

1. Update the GPT Action schema URL
2. Keep using the same bearer token unless you intentionally rotate it

## Practical Recommendation

Use this Custom GPT as a phone operator with guardrails, not as a raw remote shell.

That means:

- Read current state through the API
- Use bounded jobs for build and simulation work
- Use approval workflows for file changes
- Add new capabilities by extending the API deliberately, not by exposing generic command execution
