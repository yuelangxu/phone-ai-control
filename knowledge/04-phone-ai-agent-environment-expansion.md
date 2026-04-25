# Phone AI Agent Environment Expansion

## Short Answer

Can the current Custom GPT install more environments on the phone, such as a C language toolchain?

**Not yet through the currently exposed public API.**

The current API can:

- build APKs through a bounded build job
- run bounded Python simulations
- trigger approved phone-side actions

But it does **not** currently expose a safe endpoint for:

- `pkg install ...`
- `apt install ...`
- arbitrary shell provisioning

## Why This Matters

Installing language environments is much more powerful than reading status.

If done naively, a generic "run shell command" endpoint would create a dangerous remote-control surface.

So the better design is not:

- "let the GPT run any Termux command"

The better design is:

- "let the GPT request installation of packages from a strict allowlist"

## Safe Next Step

If this project is extended, the recommended design is a dedicated Termux package-management capability with:

- a strict allowlist
- clear install status
- readable error messages
- optional approval before execution

Recommended allowlist for a C build environment:

- `clang`
- `make`
- `cmake`
- `pkg-config`
- `binutils`
- `git`

Optional related packages:

- `lld`
- `gdb`
- `python`
- `nodejs`

## Example Future Endpoints

These are proposed future endpoints, not current guaranteed endpoints:

- `GET /v1/termux/packages`
  Return installed allowed packages and versions.

- `POST /v1/termux/packages/install`
  Install one or more allowed packages.

- `GET /v1/termux/packages/install/{request_id}`
  Track progress and result of one install request.

- `POST /v1/termux/packages/remove`
  Remove one or more allowed packages, if removal is considered safe enough.

## What "C Environment" Means Here

For this project, a "C environment" would usually mean:

- compile and run native C programs inside Termux
- manage source files and build directories in Termux storage
- optionally produce CLI binaries for the phone itself

This is different from:

- Android NDK app development
- building native libraries for APK integration

Those Android-native flows are possible too, but they are a separate layer and should be designed separately.

## Recommended GPT Behavior Today

Until package-install endpoints exist, the Custom GPT should say:

- it can **describe** what packages are needed
- it can **prepare** source files, build scripts, and project layouts
- it cannot truthfully claim package installation succeeded through the public API unless such an endpoint is added

## Recommended GPT Behavior After Extension

After safe package-install endpoints are added, the GPT should:

1. Check whether required packages are already installed
2. Install only allowlisted packages
3. Verify installation result
4. Then create code, build files, and run bounded compile/test tasks
