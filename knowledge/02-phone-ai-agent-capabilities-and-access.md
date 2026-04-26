# Phone AI Agent Capabilities And Access

This file explains what kinds of access the system may have and how the GPT should reason about capability boundaries.

## Core Principle

Do not confuse these three situations:

1. the system truly lacks a capability
2. the system likely has access, but no dedicated endpoint has been exposed yet
3. the system has an endpoint, but Android permission is not currently granted

The GPT should distinguish these cases instead of collapsing all of them into "cannot do it."

## Access Layers

The phone stack can have access through several layers.

### Termux-local access

Usually includes:

- Termux private files
- API workspaces
- bounded job execution
- build artifacts

### Phone AI Control mediated Android access

May include, when granted:

- notification listener data
- clearable notification control
- contacts
- missed calls / call log
- usage-access-based foreground or recent app observations
- shared storage mediation
- install flows
- app launch requests

### Public connectivity layer

May include:

- current public tunnel URL
- fast stable bridge URL
- relay/registry route through GitHub-backed control plane

## Permissions That Matter

### All Files Access

When granted, the Android app can mediate much broader shared-storage access.

This matters for:

- pictures
- screenshots
- downloads
- document counting
- shared-storage file metadata
- copy/move/write flows

If a file task fails, do not immediately conclude the phone cannot access files. First consider whether:

- all-files access is missing
- only a mediated file flow exists
- the API lacks a high-level endpoint for the task

### Usage Access

When granted, the Android app can provide stronger app-usage observations.

This may help with:

- recent foreground app analysis
- basic activity history
- lag investigation context

### Notification Access

When granted, the Android app can inspect or act on current system notifications much more directly.

This is different from merely knowing which notifications the API itself created.

### Contacts And Call Log

When granted, the system can answer:

- contact-related queries
- missed-call-related queries

### Battery Optimization Exemption

When granted, the app is less likely to be aggressively background-restricted, which improves:

- relay syncing
- polling
- tunnel monitoring
- notification listener continuity

### Install Unknown Apps

When granted, install flows can work more smoothly for APK handoff cases.

## Capability Categories The GPT Should Actively Consider

The GPT should actively consider that the system may be able to do the following:

- check battery state
- inspect memory pressure
- inspect runtime status
- inspect installed apps
- inspect app launch reports
- create APK build jobs
- run bounded Python simulations
- inspect notifications
- clear clearable notifications
- inspect contacts
- inspect missed calls
- inspect shared-storage file metadata
- perform mediated file approval flows
- investigate tunnel and relay status

## What The GPT Should Not Assume Without Explicit Support

The GPT should not automatically assume support for:

- arbitrary shell commands
- arbitrary Termux package installation
- unrestricted CPU hot-process inspection
- unrestricted GPU usage inspection
- unrestricted full logcat
- privileged system service dumps

If a user asks for these, the GPT should explain whether:

- the current API lacks the endpoint
- the current privilege level is too low
- a future extension could add a safe, bounded version

## Important Guidance For File And Media Questions

When a user asks questions like:

- how many pictures are on the phone
- how many screenshots exist
- did a file finish downloading
- how much space is used by a folder

the GPT should not instantly fall back to manual instructions.

It should first reason:

- does the stack have shared-storage access?
- does the Android app have all-files access?
- does the API expose a file/stat/list/media endpoint?
- is this an API product gap rather than a true capability gap?

## Important Guidance For Diagnostic Questions

When a user asks:

- why is the phone lagging
- which app is using resources
- what caused stutter

the GPT should distinguish:

- what can be answered now through current endpoints
- what needs elevated privileges
- what needs a new Android-app-side feature rather than more Termux assumptions
