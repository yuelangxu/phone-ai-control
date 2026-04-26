# Phone AI Agent System Overview

This knowledge file explains the overall phone automation system that the Custom GPT is connected to.

## What This System Is

The system is a self-hosted phone execution stack composed of:

- a Custom GPT with one Action
- a phone-local API running inside Termux
- the `Phone AI Control` Android app
- optional public tunnel exposure
- optional GitHub relay and optional stable bridge

The GPT is not a root shell. It is a bounded controller for a phone-side API.

## The Two Connection Modes

The system can be used in two action modes.

### 1. Direct mode

Direct mode usually means:

- GPT Action authentication uses `API key` / `Bearer`
- requests go directly to the phone API endpoint
- the endpoint may be a current public tunnel URL or a stable bridge that already fronts the phone

Direct mode is the low-latency path.

### 2. Relay mode

Relay mode usually means:

- GPT Action authentication uses `OAuth`
- requests go through a relay or bridge path that is designed for user-specific routing
- GitHub acts as a control plane or registry, not necessarily as the per-request data path

Relay mode is the more durable, user-specific path.

## Important Architectural Reality

GitHub is best treated as a control plane:

- it can store current device registry data
- it can store relay metadata
- it can help route to the current tunnel

The best low-latency production pattern is:

- phone updates GitHub only when state or address changes
- bridge reads GitHub registry state
- GPT talks to the stable bridge URL

Do not assume every request should go through GitHub Issues and Actions. That path is valid, but slower.

## What Phone AI Control Adds Beyond Plain Termux

Phone AI Control is the Android-side bridge that makes this system more capable than Termux alone.

It can provide mediated Android access for things such as:

- notifications
- contacts
- call log
- shared storage
- install flows
- app launch or app-state related actions
- usage-access-based observations

This means:

- "plain Termux alone cannot do X" does not automatically mean the whole system cannot do X
- some capabilities exist only because the Android app mediates them

## Phone API Token Behavior

The phone API bearer token is designed to be:

- generated once on first setup
- stable across tunnel changes
- stable across network changes
- different for different devices/users
- changed only when the user explicitly rotates it

Therefore:

- a tunnel restart should not require a new phone API token
- a different user should not see the same first token as another user

## Tunnel Reality

The public tunnel URL may change.

When the tunnel changes:

- the phone API token normally stays the same
- direct clients may need an updated schema URL unless a stable bridge is used
- GitHub registry or relay state may need refresh

## Dynamic Port Reality

The phone-local API port may be discovered dynamically.

Do not treat the absence of a hard-coded local port in docs as proof that the API is unavailable.

Correct behavior:

- trust runtime status and health checks
- trust the configured action server
- validate discovered ports by calling `/healthz`

## Trust Model

This system is intended to be used by a trusted GPT through a bounded API.

It should not be treated as:

- arbitrary shell access
- root access
- unrestricted Android system administration

## High-Level Limits

This system does not inherently imply:

- root
- full system logcat
- unrestricted per-process CPU/GPU inspection
- unrestricted `dumpsys`
- arbitrary package manager commands

Those stronger diagnostics need additional privileges such as ADB shell, Shizuku, or root, or they need explicit new Android-app-side features.
