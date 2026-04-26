# Phone AI Agent Endpoints Reference

This file is a task-oriented endpoint map for the current phone stack.

It intentionally does not contain a live token or a live tunnel URL.

## Core Health And Status

- `GET /healthz`
  - basic reachability check
  - public form may be intentionally minimal

- `GET /v1/status`
  - runtime summary
  - capability summary
  - useful as a first orientation call

## Device State

- `GET /v1/device/battery`
  - latest battery snapshot

- `GET /v1/device/usage`
  - usage-access-derived state, memory pressure, or related cached observations

- `GET /v1/device/diagnostics/snapshot`
  - one-shot diagnostics sample

- `POST /v1/device/diagnostics/poll`
  - bounded multi-sample diagnostics run
  - expected fields often include:
    - `interval_sec`
    - `duration_sec`
    - `max_samples`

## Notifications

- `GET /v1/device/notifications`
  - current notification listener snapshot if permission is granted

- `POST /v1/device/notifications/clear-all`
  - clear currently clearable notifications
  - system-pinned or non-clearable notifications may remain

- `POST /v1/device/notify`
  - create a local notification through Phone AI Control

## Contacts And Call Log

- `GET /v1/device/contacts`
  - contacts snapshot if permission is granted

- `GET /v1/device/missed-calls`
  - missed calls snapshot if permission is granted

## Apps And Launch State

- `GET /v1/apps/installed`
  - check whether a package is installed
  - may also include APK path or run-state related details

- `POST /v1/apps/report-launch`
  - launch-report or heartbeat-style reporting endpoint for apps that cooperate with the system

- `POST /v1/device/open-app`
  - request opening an installed app by package name

## Files And Shared Storage

- `GET /v1/files/stat`
  - file existence
  - size
  - modified time
  - optional hash

- `POST /v1/file-approvals`
  - create a pending file write/delete approval

- `GET /v1/file-approvals/pending`
  - list pending approvals

- `GET /v1/file-approvals/{approval_id}`
  - inspect one approval

- `POST /v1/file-approvals/{approval_id}/approve`
  - approve a pending change and mint execution authority

- `POST /v1/file-approvals/{approval_id}/execute`
  - execute approved change

- `POST /v1/file-approvals/{approval_id}/complete-local`
  - mark local mediated execution complete

## APK Install Flow

- `GET /v1/device/install-apk`
  - list install requests

- `POST /v1/device/install-apk`
  - create an install request for Phone AI Control

- `GET /v1/device/install-apk/{request_id}`
  - inspect install request state

## Jobs

- `GET /v1/jobs`
  - list recent jobs

- `POST /v1/jobs`
  - create a bounded job
  - common job types include:
    - `apk_build`
    - `python_sim`

- `GET /v1/jobs/{job_id}`
  - inspect job status and result

- `GET /v1/jobs/{job_id}/artifacts/{artifact_path}`
  - download artifacts from completed jobs

## Android-Side Actions

- `GET /v1/device/actions`
  - list Android-side queued or recent actions

- `GET /v1/device/actions/{action_id}`
  - inspect one action

- `POST /v1/device/alarm`
  - create alarm request

- `POST /v1/device/timer`
  - create timer request

## Public Exposure Control

- `POST /v1/control/start-public`
  - start or refresh public exposure

- `POST /v1/control/stop-public`
  - stop only public exposure

- `POST /v1/control/stop-all`
  - stop tunnel and local API

## Routing Notes

Depending on deployment, the GPT may be talking to:

- a direct tunnel URL
- a stable bridge URL
- a relay/registry-backed route

The endpoint names stay conceptually similar, but the transport path may differ by mode.

## Non-Goals

Current public API design should not be assumed to provide:

- arbitrary shell execution
- unrestricted package-manager access
- unrestricted root-only inspection
- unrestricted full logcat
