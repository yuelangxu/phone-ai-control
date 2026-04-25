# Phone AI Fast Bridge

This bridge gives your Custom GPT a stable endpoint while your phone keeps using temporary tunnel URLs.

## What it does

- reads `relay/current_device.json` from your private GitHub relay repo
- reads `relay/phone_api_secret.json` from the same repo
- forwards requests directly to the current phone tunnel
- retries once after a forced registry refresh if the tunnel just changed
- serves `/healthz`
- serves `/openapi.json` by fetching the phone schema and rewriting `servers` to the stable bridge URL

## Why this is the preferred architecture

It keeps the low-latency path:

`GPT -> stable bridge -> current phone tunnel`

while GitHub is used only for:

- device discovery
- address updates
- health metadata

That is much faster than the older issue-based GitHub relay for normal interactive tasks.

## Required environment variables

- `GITHUB_TOKEN`
- `RELAY_OWNER`
- `RELAY_REPO`

Optional:

- `RELAY_BRANCH`
- `REGISTRY_PATH`
- `SECRET_PATH`
- `BRIDGE_BEARER_TOKEN`
- `CACHE_TTL_SECONDS`
- `REQUEST_TIMEOUT_SECONDS`
- `PORT`

## Install

```sh
pip install -r bridge/requirements-fast-bridge.txt
```

## Run

```sh
export GITHUB_TOKEN=...
export RELAY_OWNER=your-account
export RELAY_REPO=your-private-phone-relay-repo
export BRIDGE_BEARER_TOKEN=your-stable-bridge-token
python bridge/phone_fast_bridge.py
```

Then bind your Custom GPT action to:

- `https://your-bridge-domain/openapi.json`

and authenticate that action with:

- `Authorization: Bearer <BRIDGE_BEARER_TOKEN>`

The GPT never needs to know the phone tunnel URL or the phone's own bearer token.
