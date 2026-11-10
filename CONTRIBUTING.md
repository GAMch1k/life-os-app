# Contributing to the Life OS app

Contributions are welcome. This repository holds the Android phone app and the Wear OS app; the server, the Dashboard, and the APIs they call are in [GAMch1k/life-os](https://github.com/GAMch1k/life-os), which has its own [contributing guide](https://github.com/GAMch1k/life-os/blob/master/CONTRIBUTING.md).

Everyone who takes part follows the [Code of Conduct](CODE_OF_CONDUCT.md). Security problems are never reported in public: see [SECURITY.md](SECURITY.md).

## Before you start

- **Bugs and ideas**: [open an issue](https://github.com/GAMch1k/life-os-app/issues/new/choose) with the matching form. Problems with the server, the Dashboard, or the setup belong in [life-os](https://github.com/GAMch1k/life-os/issues/new/choose).
- **Bigger changes** (a new screen or feature, a change to how the apps talk to Life OS, update, or sign, or to the build): open an issue first and describe what you want to change and how. Agreeing on the approach before you write the code saves you work.
- **Small fixes** (a typo, a broken link, an obvious bug) can go straight to a pull request.

## Pull requests

1. Fork the repository, branch from `main`, and keep a pull request to one coherent change. When it changes what the apps do or how they are built, update [README.md](README.md) or [`docs/`](docs) in the same pull request.
2. Run the unit tests, and build the apps if you changed them: `tools/build ci` runs what CI runs (the unit tests, both release APKs unsigned, the widget and ABI checks) in the builder container, so it needs only Docker and `flock`; see [Building](README.md#building). Every check must pass on the pull request before it can be merged.
3. Sign the [Contributor License Agreement](CLA.md), once: comment on your pull request with exactly `I have read the CLA Document and I hereby sign the CLA`. You keep your copyright, your work stays available under the AGPL, and the maintainer may also offer it under other licenses. The `CLA` check comments on your pull request with what is missing and turns green once everyone who wrote a commit in it has signed. Commit with an email address that is linked to your GitHub account, so the check can tell who wrote each commit. A signature in life-os doesn't count here: each repository keeps its own record.
4. CI on a pull request from a fork may wait until the maintainer approves the run (GitHub asks for this for first-time contributors), so the checks may take a while to start. The `CLA` check runs at once.
5. The maintainer reviews the pull request and may ask for changes. Push them to the same branch; there is no need to squash or rebase. Once the checks pass, the maintainer merges it.

## Checks

These checks must pass before a pull request can be merged into `main`:

- **Tests, unsigned APKs, ABI check**: the unit tests of every module, both apps' release APKs built unsigned, and the release's checks that need no signature (the widget classes in R8's mapping, the native code's ABIs).
- **CLA**: everyone who wrote a commit has signed the [CLA](CLA.md).

CI also runs the CLA check's own unit tests, and skips a change that touches only Markdown (`**.md`, `docs/**`). To run the checks locally:

| Check | Command |
| --- | --- |
| Everything CI runs | `tools/build ci` (Docker and `flock` only) |
| Unit tests only | `tools/build test` |
| The CLA check's own tests | `node --test .github/cla/cla.test.js` (Node 22) |

## Guidelines

- **Small, focused commits** with messages that say what changed and why.
- **No secrets or real data.** Never commit a keystore, `keystore.properties`, a Cloudflare Access service token, or anyone's personal data. Tests and docs use placeholders such as `lifeos.example.com`. Remove tokens and personal data from logs before you paste them anywhere.
- **Leave `version.properties` alone.** The maintainer raises the version when releasing.

## How changes ship

1. A pull request is checked by CI, reviewed, and merged into `main` by the maintainer.
2. Releases are built and signed on the maintainer's server with `tools/build`. The release key never leaves that server and never goes to GitHub; CI's APKs are unsigned and are not published ([Signing](README.md#signing)).
3. The signed APKs are published on the official Life OS server, from which the installed apps update themselves. GitHub Releases with the APKs, for people who run their own Life OS, are planned (P20-06).
