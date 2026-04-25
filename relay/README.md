# GitHub Relay For Phone AI API

This relay makes GitHub the control-plane entrypoint for your phone API:

- Custom GPT talks to `api.github.com`
- GitHub Issues store each request
- GitHub Actions forwards the request to the phone
- GitHub Actions writes the result back to the issue comments

This is intentionally an asynchronous relay. It is not a low-latency reverse proxy.

## Phone-side GitHub sync

`Phone AI Control` can now publish the current device address and health directly to GitHub, so the relay repo stays aligned even when the tunnel changes.

Place a config file on the phone at:

`/storage/emulated/0/Android/media/com.example.phoneaicontrol/github-relay.json`

You can start from:

- `relay/github-relay-config.example.json`

The safest pattern is:

- keep repo metadata in `github-relay.json`
- keep the GitHub PAT itself in a separate phone file such as `github-relay-token.txt`
- point `github_token_file` at that token file

When configured, the app/service will update `relay/current_device.json` automatically.

## What GitHub stores

- The phone bearer token should be stored as a **GitHub Actions secret** named `PHONE_API_BEARER_TOKEN`
- The phone public URL can be stored either as:
  - a **GitHub Actions secret** named `PHONE_API_PUBLIC_URL`, or
  - a committed private file at `relay/current_device.json`

Do not commit the real bearer token to the repository.

## Required repo setup

1. Use a private repository.
2. Add these repo secrets:
   - `PHONE_API_BEARER_TOKEN`
   - `PHONE_API_PUBLIC_URL` (optional if you use `relay/current_device.json`)
3. If you prefer file-based device state, copy `relay/current_device.example.json` to `relay/current_device.json` and replace the placeholder URL.

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

Use `relay/openapi-github-relay.json` as the GPT action schema.

Authentication should be configured against the GitHub API, not the phone API:

- auth type: API key or bearer
- value: a GitHub token that can create and read issues in this repo

Your GPT should:

1. create a relay issue
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
