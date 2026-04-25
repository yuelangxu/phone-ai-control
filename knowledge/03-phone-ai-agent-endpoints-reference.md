# Phone AI Agent Endpoints Reference

This file gives the Custom GPT a stable conceptual map of the API.

It intentionally does not contain the live bearer token or the live public URL.

## Core Status

- `GET /v1/status`
  Purpose: return overall capability and runtime summary.

- `GET /healthz`
  Purpose: basic reachability check.
  Note: the public version is intentionally minimal.

## Device State

- `GET /v1/device/battery`
  Purpose: return the latest cached battery snapshot.

- `GET /v1/device/usage`
  Purpose: return cached usage and memory-pressure information from Phone AI Control.

- `GET /v1/device/diagnostics/snapshot`
  Purpose: collect a current one-shot diagnostics snapshot.

- `POST /v1/device/diagnostics/poll`
  Purpose: collect a bounded sequence of diagnostics samples.
  Typical body fields:
  - `interval_sec`
  - `duration_sec`
  - `max_samples`

## Jobs

- `GET /v1/jobs`
  Purpose: list recent jobs.

- `POST /v1/jobs`
  Purpose: create a bounded job.
  Supported job types:
  - `apk_build`
  - `python_sim`

- `GET /v1/jobs/{job_id}`
  Purpose: inspect one job and its result.

- `GET /v1/jobs/{job_id}/artifacts/{artifact_path}`
  Purpose: download an artifact from a finished job.

## Device Actions

- `GET /v1/device/actions`
  Purpose: list recent Android-side actions.

- `GET /v1/device/actions/{action_id}`
  Purpose: inspect one action.

- `POST /v1/device/notify`
  Purpose: queue a local notification through Phone AI Control.

- `POST /v1/device/alarm`
  Purpose: queue an alarm request.

- `POST /v1/device/timer`
  Purpose: queue a timer request.

- `POST /v1/device/open-app`
  Purpose: request opening an installed app by package name.

## APK Install Flow

- `GET /v1/device/install-apk`
  Purpose: list recent install requests.

- `POST /v1/device/install-apk`
  Purpose: create an APK install request for Phone AI Control.

- `GET /v1/device/install-apk/{request_id}`
  Purpose: inspect one install request.

## File Approval Flow

- `POST /v1/file-approvals`
  Purpose: create a pending write or delete approval.

- `GET /v1/file-approvals/pending`
  Purpose: list pending and approved-but-not-executed approvals.

- `GET /v1/file-approvals/{approval_id}`
  Purpose: inspect one approval.

- `POST /v1/file-approvals/{approval_id}/approve`
  Purpose: approve a pending file change and mint a short-lived execution token.

- `POST /v1/file-approvals/{approval_id}/execute`
  Purpose: execute an approved file change.

- `POST /v1/file-approvals/{approval_id}/complete-local`
  Purpose: mark a local shared-storage execution as completed.

## Public Exposure Control

- `POST /v1/control/start-public`
  Purpose: start or refresh public exposure.

- `POST /v1/control/stop-public`
  Purpose: stop only the public tunnel.

- `POST /v1/control/stop-all`
  Purpose: stop the public tunnel and the local API.

## Current Non-Goals

The API does not currently expose:

- arbitrary shell execution
- generic package-manager commands
- unrestricted system log access
- unrestricted per-process CPU/GPU inspection
