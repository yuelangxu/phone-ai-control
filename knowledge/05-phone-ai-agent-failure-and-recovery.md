# Phone AI Agent Failure And Recovery

This file teaches the GPT how to interpret common failures without giving misleading answers.

## Failure Type 1: Public Endpoint Returns 503

Typical sign:

- `503 Tunnel Unavailable`

Meaning:

- the phone-local API may still be alive
- the public tunnel session is stale or broken
- the public URL may need refresh or reconnection

Correct GPT response:

- explain that the public tunnel path failed
- do not immediately claim the whole phone API is down
- suggest refreshing tunnel or bridge status

## Failure Type 2: Current Public Address Is None

Meaning can include:

- public exposure is disabled
- tunnel is not running
- runtime metadata is stale
- relay mode expects bridge/registry rather than direct tunnel

Correct GPT response:

- distinguish "no current direct public URL" from "system completely unusable"
- if a stable bridge exists, prefer that route
- if direct mode is expected, explain that public exposure needs refresh

## Failure Type 3: Local API Offline

Meaning:

- local phone API is not reachable right now
- Termux service may be stopped
- local port may have changed

Correct GPT response:

- describe it as a runtime availability problem
- do not assume the bearer token is invalid just because the local API is offline

## Failure Type 4: Permission Not Granted

Examples:

- usage access not granted
- notification access not granted
- contacts/call log access not granted
- all-files access not granted

Correct GPT response:

- say the system likely supports the feature in principle
- explain that Android permission is missing
- avoid wording like "the phone cannot do this"

## Failure Type 5: No Dedicated Endpoint

Meaning:

- the stack may have raw capability, but the API product surface is incomplete

Examples:

- user asks for image counting
- system may have shared-storage access
- but no high-level media stats endpoint exists yet

Correct GPT response:

- call this an API product gap
- not an inherent phone impossibility

## Failure Type 6: Relay Not Configured

Meaning:

- GitHub relay mode was chosen
- but no relay owner/repo/token/bootstrap state is ready

Correct GPT response:

- explain that relay configuration is missing or incomplete
- do not describe it as a direct tunnel failure

## Failure Type 7: OAuth Flow Not Ready

Meaning:

- the app or GPT expects OAuth-based relay/bridge mode
- but the sign-in or action auth is incomplete

Correct GPT response:

- explain whether the issue is at GPT Action auth, app-side GitHub login, or relay repo bootstrap
- do not confuse GitHub login problems with phone bearer-token problems

## Failure Type 8: Direct And Relay Modes Are Mixed Up

Common bad pattern:

- GPT treats a direct bearer-token setup like a relay OAuth setup
- or treats a relay setup like a direct tunnel setup

Correct GPT response:

- infer the active mode from the action configuration and observed behavior
- keep mode-specific troubleshooting separate

## Failure Type 9: Installed Does Not Mean Running

Meaning:

- APK install request exists
- or package is installed
- but successful launch has not been confirmed

Correct GPT response:

- separate install status from launch status
- avoid claiming the app is running unless launch evidence exists

## Failure Type 10: Notification Data Ambiguity

Meaning:

- the system may only have its own action-history notifications
- or, with notification access granted, it may have real system notification snapshots

Correct GPT response:

- distinguish "API-created notification history" from "live third-party system notifications"

## Recovery Mindset

When something fails, the GPT should explain the smallest truthful diagnosis:

- transport path problem
- missing permission
- missing endpoint
- stale tunnel or registry data
- unsupported capability under current privilege level

The GPT should avoid dramatic overstatements such as:

- "the phone cannot do this"
- "the API is broken"
- "there is no access at all"

unless the available evidence really supports that conclusion.
