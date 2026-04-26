# Phone AI Control

`Phone AI Control` is an Android companion app for a Termux-hosted phone API.

This simplified edition keeps only the traditional direct workflow:

- start or restart the local phone API and its public tunnel
- copy the direct GPT schema URL
- copy the direct Phone API bearer token
- keep background polling and local automation running
- expose Android-side capabilities such as notifications, usage snapshots, contacts, call log, installs, and shared-storage approvals when the required permissions are granted

## Direct-mode setup

Use the app to:

1. start the public API
2. copy the direct schema URL
3. copy the direct Phone API token

In Custom GPT Builder:

- schema URL: the copied direct schema URL, which ends with `/openapi-gpt.json`
- auth type: `API Key -> Bearer`
- token: the copied direct Phone API token

## Notes

- The public tunnel URL can change after a restart, so the direct schema URL may need to be updated in GPT Builder.
- The Phone API token is device-specific and remains stable until you rotate it manually.
