# Phone AI Agent Task Playbooks

This file gives the GPT concrete playbooks for common user requests.

## Playbook: Check Battery

When the user asks:

- "what is my battery"
- "is the phone charging"

Prefer:

- `GET /v1/device/battery`

Do:

- return the current battery snapshot
- say if the result is cached or current if the payload indicates that

## Playbook: Check General Health

When the user asks:

- "is the phone online"
- "is the API reachable"

Prefer:

- `GET /healthz`
- `GET /v1/status`

Do:

- separate API reachability from tunnel reachability
- mention direct-vs-relay context if relevant

## Playbook: Investigate Lag

When the user asks:

- "why is the phone lagging"
- "check what is going on"

Prefer:

- `GET /v1/device/diagnostics/snapshot`
- if user wants monitoring over time, `POST /v1/device/diagnostics/poll`

Do:

- summarize current diagnostics truthfully
- mention when the current privilege level cannot expose full system CPU/GPU truth
- do not invent process-level conclusions without evidence

## Playbook: Check Notifications

When the user asks:

- "what notifications do I have"
- "clear notifications"

Prefer:

- `GET /v1/device/notifications`
- `POST /v1/device/notifications/clear-all`

Do:

- distinguish live notification-listener data from API-created notification history if needed
- mention if non-clearable notifications may remain

## Playbook: Check Missed Calls Or Contacts

When the user asks:

- "who called me"
- "show missed calls"
- "find a contact"

Prefer:

- `GET /v1/device/missed-calls`
- `GET /v1/device/contacts`

Do:

- mention permission requirement if data is unavailable because Android permission is missing

## Playbook: Check Whether An App Is Installed

When the user asks:

- "is this package installed"
- "did the APK install"

Prefer:

- `GET /v1/apps/installed`
- if install flow is involved, `GET /v1/device/install-apk/{request_id}`

Do:

- distinguish request-created, installed, and actually launched states

## Playbook: Open An App

When the user asks:

- "open this app"

Prefer:

- `POST /v1/device/open-app`

Do:

- briefly say you are about to request an app launch
- avoid claiming launch succeeded unless follow-up evidence exists

## Playbook: Build An APK

When the user asks:

- "make an APK"
- "build an Android app"

Prefer:

- `POST /v1/jobs` with `apk_build`
- then `GET /v1/jobs/{job_id}`
- then artifact download if needed

Do:

- treat it as a bounded job
- wait for confirmation before claiming success

## Playbook: Run Numerical Simulation

When the user asks:

- "run a simulation"
- "do some Python math on the phone"

Prefer:

- `POST /v1/jobs` with `python_sim`
- then poll job status

Do:

- describe it as bounded remote compute, not arbitrary shell

## Playbook: Inspect A File

When the user asks:

- "does this file exist"
- "what size is this file"
- "did the download finish"

Prefer:

- `GET /v1/files/stat`

Do:

- use the stat result before making claims
- explain permission boundaries if shared-storage mediation is required

## Playbook: Change A Shared-Storage File

When the user asks:

- "write this file"
- "delete that file"

Prefer:

- file approval flow endpoints

Do:

- describe the action briefly first
- use approval flow rather than assuming unrestricted direct file write access

## Playbook: Count Pictures Or Inspect Media Volume

When the user asks:

- "how many pictures are on my phone"
- "how many screenshots do I have"

Do not immediately say the phone cannot answer.

Instead reason in this order:

1. does the stack have mediated storage access?
2. does the API expose a file/media counting endpoint?
3. if no high-level endpoint exists, explain this as an API surface gap

Correct fallback wording:

- "The phone stack may have the underlying access, but the current public API does not yet expose a dedicated image-count endpoint."

Incorrect fallback wording:

- "The phone cannot access your images."

## Playbook: Tunnel Or Bridge Trouble

When the user asks:

- "why is the URL gone"
- "why is the action failing"

Prefer:

- `GET /healthz`
- `GET /v1/status`

Reason through:

- stale tunnel
- no public address
- relay mode vs direct mode mismatch
- missing bridge or stale registry

Do:

- diagnose the routing problem specifically
- avoid blaming the bearer token unless the evidence points there
