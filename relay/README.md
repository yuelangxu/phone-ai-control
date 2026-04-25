# GitHub Relay For Phone AI API

This relay makes GitHub the control-plane entrypoint for your phone API:

- Custom GPT talks to `api.github.com`
- GitHub Issues store each request
- GitHub Actions forwards the request to the phone
- GitHub Actions writes the result back to the issue comments

This is intentionally an asynchronous relay. It is not a low-latency reverse proxy.

## Fast bridge architecture

If you want the best overall architecture for a Custom GPT, do not send every request through GitHub Issues.

Instead, use:

- `Phone AI Control` to keep publishing `relay/current_device.json`
- GitHub as the registry / control plane
- a stable bridge endpoint that reads GitHub and forwards directly to the current phone tunnel

That bridge is implemented in:

- `bridge/phone_fast_bridge.py`
- `bridge/requirements-fast-bridge.txt`

The expected runtime model is:

1. the phone only updates GitHub when its public tunnel or health changes
2. the bridge reads the latest `relay/current_device.json`
3. the bridge forwards requests to the current `public_url`
4. your GPT always talks to the stable bridge URL

This keeps normal requests fast while still surviving tunnel changes.

The bridge can expose its own:

- `/healthz`
- `/openapi.json`

so your GPT action can be bound to the stable bridge instead of the changing tunnel URL.

## Phone-side GitHub sync

`Phone AI Control` can now publish the current device address and health directly to GitHub, so the relay repo stays aligned even when the tunnel changes.

Place a config file on the phone at:

`/storage/emulated/0/Android/media/com.example.phoneaicontrol/github-relay.json`

You can start from:

- `relay/github-relay-config.example.json`
- `relay/github-oauth.example.json`

The safest pattern is:

- keep repo metadata in `github-relay.json`
- keep the GitHub PAT itself in a separate phone file such as `github-relay-token.txt`
- point `github_token_file` at that token file

When configured, the app/service will update `relay/current_device.json` automatically.

Optional config fields:

- `oauth_client_id`
  - enables GitHub Device Flow in the app
- `oauth_scope`
  - default is `repo read:user`
- `bridge_base_url`
  - if you deploy the fast bridge, this becomes the preferred URL that the app copies for GPT setup

## What GitHub stores

The relay repository should stay private.

`Phone AI Control` can bootstrap a private relay repository in a file-based way:

- `relay/current_device.json`
  - current public URL
  - health and sync metadata
- `relay/phone_api_secret.json`
  - the phone API bearer token used by the relay workflow

This design lets the phone set up the relay repo without separately configuring GitHub Actions secrets.

## Required repo setup

1. Use a private repository.
2. Either let `Phone AI Control` bootstrap the repo automatically, or add these files yourself:
   - `relay/current_device.json`
   - `relay/phone_api_secret.json`
3. If you prefer a manual start, copy:
   - `relay/current_device.example.json` to `relay/current_device.json`
   - `relay/phone_api_secret.example.json` to `relay/phone_api_secret.json`

## Relay request format

Create a new GitHub issue with:

- label: `phone-relay`
- optionally label: `relay-pending`
- body containing this marker:

````md
<!-- phone-ai-relay-request -->
```json
{
  "request_id": "battery-check-001",
  "method": "GET",
  "path": "/v1/device/battery"
}
```
````

Another example:

````md
<!-- phone-ai-relay-request -->
```json
{
  "request_id": "clear-notifications-001",
  "method": "POST",
  "path": "/v1/device/notifications/clear-all",
  "body": {}
}
```
````

The workflow parses the JSON block, calls the phone API, then posts a result comment and closes the issue.

Optional payload fields:

- `query`: object of query-string parameters
- `timeout_sec`: bounded to 5-120 seconds
- `expected_status`: integer or list of acceptable HTTP status codes
- `close_issue`: whether the workflow should close the issue after posting the result

## Custom GPT setup

### Option A: stable fast bridge

Best for day-to-day GPT use.

1. Deploy `bridge/phone_fast_bridge.py` somewhere stable.
2. Point it at your private relay repo with environment variables.
3. Set a separate `BRIDGE_BEARER_TOKEN`.
4. Use the bridge's own `/openapi.json` as the GPT action schema URL.

In this mode:

- GPT talks to the bridge
- the bridge talks to the current phone tunnel
- GitHub only stores registry state

### Option B: GitHub issue relay

Use `relay/openapi-github-relay.json` as the GPT action schema.

Authentication should be configured against the GitHub API, not the phone API:

- auth type: API key or bearer
- value: a GitHub token that can create and read issues in the private relay repo

Your GPT should:

1. create a relay issue in the configured private relay repo
2. remember the returned issue number
3. poll the issue comments until a `<!-- phone-ai-relay-response -->` comment appears

If you need to retry an existing request, add the comment:

```text
/phone-relay-run
```

## Important tradeoff

This relay is usually slower than talking to the phone directly:

- GitHub issue creation latency
- GitHub Actions startup latency
- phone API execution time
- comment write-back latency

It is best treated as a durable control-plane relay, not as a streaming RPC channel.
