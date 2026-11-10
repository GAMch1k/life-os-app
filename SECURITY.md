# Security policy

## Supported versions

Security fixes go into `main` and into the next release of the apps; older versions get no fixes.

| Version | Supported |
| --- | --- |
| The latest released version | Yes |
| `main` | Yes |
| Any older version | No: update the app |

The Life OS server, the Dashboard, and the published images have their own policy in [GAMch1k/life-os](https://github.com/GAMch1k/life-os/security/policy).

## Reporting a vulnerability

Report it privately. Never open a public issue, pull request, or discussion about a vulnerability.

1. Open the repository's **Security** tab and choose **Report a vulnerability** ([direct link](https://github.com/GAMch1k/life-os-app/security/advisories/new)). This is GitHub's private vulnerability reporting: only you and the maintainer see the report.
2. Include what you can of:
   - the app's version (Android Settings › Apps › Life OS), and the phone or watch model with its Android or Wear OS version;
   - the part that is affected, such as the token storage, the capture queue, updates, the watch setup, or push;
   - what an attacker can do, and what they need first (for example another app on the device, or a position on the network);
   - the steps to reproduce it, or a proof of concept;
   - logs (`adb logcat`), with service tokens and personal data removed.

## What happens next

Life OS has one maintainer, who handles reports on a best-effort basis:

- You get an answer, usually within a week.
- The maintainer confirms the problem or explains why it isn't one, and keeps you informed in the report.
- A confirmed problem is fixed in `main` and in the next release, and published as a GitHub security advisory. You are credited, unless you prefer not to be.

Please don't disclose the problem publicly until a fix is released, or until you have agreed on a date in the report.

## Good to know

- **The server is an early preview.** Life OS has no login of its own yet; the apps reach it through Cloudflare Access with a service token, which the phone keeps encrypted with an Android Keystore key. The [server's policy](https://github.com/GAMch1k/life-os/security/policy) explains the preview's limits.
- **Every APK is signed with one release key**, which never leaves the maintainer's server. Its certificate's SHA-256 is under [Signing](README.md#signing); `apksigner verify --print-certs <file>.apk` shows the certificate an APK carries. Android installs an update only when the same key signed it.
