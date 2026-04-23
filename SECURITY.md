# Security

Phone AI Control does not embed any API token, public tunnel URL, device serial number, or phone-specific identifier.

The app controls Termux scripts through Termux's `RunCommandService`. Only install and grant the command permission if you trust the APK and the scripts in `~/ai-phone-api`.

If you expose a phone API to the public internet, protect it with a strong token and rotate the token if it is ever shared accidentally.

Do not publish:

- API tokens
- Tunnel URLs that should stay private
- ADB serial numbers
- Device-specific documents
- Personal files from internal storage
