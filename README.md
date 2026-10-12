# Life OS app

The Android phone app and the Wear OS app for [Life OS](https://github.com/GAMch1k/life-os), in one repository: both share one package name and one signing key, which the Wear OS Data Layer requires.

The plan lives in Notion ("Android & Wear OS apps — plan", Phase 18 and Phase 19 in Implementation Tickets).

## What is here

| Path | What |
|---|---|
| `core/` | Android library shared by the apps: the Life OS API client over embedded Cronet, the service-token store, the capture queue's logic, Today, the update rules, and the network check. Every unit test lives here. |
| `phone/` | The phone app ([below](#the-phone-app)): onboarding, Today, capture with an offline queue, self-update, and the **Network check** ([docs/network-check.md](docs/network-check.md)) under Settings. |
| `wear/` | The Wear OS app. For now one screen: "Life OS" and the version. |
| `tools/build` | Builds everything on the server, in a container. |
| `tools/builder/` | The builder image (`Dockerfile`) and the script that runs inside it (`in-container`). |
| `version.properties` | The one version of both apps. |
| `gradle/libs.versions.toml` | Every library, plugin and SDK level, in one version catalog. |

Both apps use the `applicationId` **`com.gamch1k.lifeos`**. Phone: minSdk 34, targetSdk 36. Wear: minSdk 33, targetSdk 36. Both compile against SDK 37, because current AndroidX and Compose releases require it.

Main versions: AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21, Gradle 9.7.1, Compose BOM 2026.09.00, Compose for Wear OS Material 3 1.7.1, Cronet 500.1.0 (`org.chromium.net:cronet-bundled`, Chromium 155.0.8059.16), JDK 21.

The phone APK is about 10 MB. Embedded Cronet's native library for arm64 accounts for 7.3 MB of it, and the APK carries only arm64 code. The wear APK is about 1.4 MB.

## Building

Builds run on the Life OS server, never on a laptop. The host needs only Docker and `flock`:

```sh
tools/build              # release: unit tests, signed APKs of both apps, checks, dist/
tools/build test         # unit tests only (no signing material needed)
tools/build gradle ARGS  # any Gradle command in the builder, e.g. tools/build gradle :phone:dependencies
tools/build image        # only build the builder image
```

What `tools/build` does:

- It holds `/tmp/lifeos-heavy.lock` for the whole run. Every heavy job on this host takes that lock. If another job holds it, the build waits; it never bypasses the lock.
- It builds the builder image from `tools/builder/Dockerfile`, or reuses it. The image tag is the Dockerfile's hash, and older builder images are removed. The image holds Temurin JDK 21, Android command-line tools 23.0, `platforms;android-37.0`, `build-tools;36.1.0` and platform-tools 37.0.1. All of them are pinned, and the image build fails if the SDK manager delivers other revisions. The image is about 1 GB.
- It runs Gradle in a container limited to 3 GiB of memory (no swap) and 2 CPUs, as uid 1000, with the repository mounted at `/workspace`.
- It keeps the Gradle cache in the named Docker volume `lifeos-android-gradle`. To start clean, run `docker volume rm lifeos-android-gradle`.
- `gradle.properties` keeps the one Gradle JVM small: `-Xmx1536m`, metaspace capped at 512 MiB, no daemon, Kotlin compiled in-process, and two workers.
- At the end it prints the container's peak memory, sampled from its cgroup:
  - **process memory**: what the processes really use;
  - **working set**: what `docker stats` shows;
  - **cgroup peak**: this also counts page cache, so it can reach the limit harmlessly.

## Signing

Every APK that is ever installed is signed with the **release key**, including dev builds. That way updates never break, and the phone and the watch always pair.

- The key lives only on the server, in `~/life-os.secrets/android/`:
  - `lifeos-release.p12`: the PKCS#12 keystore, alias `lifeos`;
  - `keystore.properties`: the keys `storeFile` (relative to the properties file), `storeType`, `storePassword`, `keyAlias` and `keyPassword`.

  The directory is 0700 and the files are 0600.
- `tools/build` mounts that directory read-only, at the same path, and sets `LIFEOS_KEYSTORE_PROPERTIES=<dir>/keystore.properties`. To use another directory, set `LIFEOS_SIGNING_DIR`.
- The key, its passwords and the properties are never printed, logged, copied into the repository, or baked into an image. `.gitignore` also blocks `*.p12`, `*.jks`, `*.keystore` and `keystore.properties`.
- Without `LIFEOS_KEYSTORE_PROPERTIES`, compiling and unit tests still work. Building any APK, debug or release, then fails before any task runs, with a message that says why. `tools/build` refuses to start a release when the signing directory is missing.
- After the build, each APK must have exactly one signer, with this certificate SHA-256:
  `AB:A9:79:EC:4A:64:CC:64:60:36:BA:F6:C9:76:4A:54:63:55:47:BC:C2:58:3C:59:BB:6A:E3:3A:8D:1E:EF:9F`

## Release output (`dist/`)

A release build empties `dist/` (gitignored), then writes:

- `lifeos-phone-<versionName>-<versionCode>.apk`
- `lifeos-wear-<versionName>-<versionCode>.apk`
- `release.json`

Before anything is copied there, the script checks each APK:

- `apksigner verify`: the signature, and that the release certificate signed it;
- `aapt2 dump badging`: the package name and the version.

### `release.json`

```json
{
  "schema": 1,
  "apps": {
    "phone": {
      "file": "lifeos-phone-0.1.0-1.apk",
      "applicationId": "com.gamch1k.lifeos",
      "versionCode": 1,
      "versionName": "0.1.0",
      "minSdk": 34,
      "sha256": "<64 lowercase hex characters>",
      "size": 9653243,
      "builtAt": "2026-10-12T15:04:05Z",
      "gitCommit": "<40 hex characters>"
    },
    "wear": { "...": "the same fields, for the wear APK" }
  }
}
```

| Field | Meaning |
|---|---|
| `schema` | The format version of this file. It goes up only on incompatible changes. |
| `apps.phone`, `apps.wear` | One entry per app. Today both are always present. |
| `file` | The APK's file name, next to `release.json`. |
| `applicationId`, `versionCode`, `versionName`, `minSdk` | Read back from the built APK (`aapt2 dump badging`), not from the build configuration. |
| `sha256`, `size` | The SHA-256 (lowercase hex) and the size in bytes of the APK file. |
| `builtAt` | When the build finished, in UTC (ISO 8601). |
| `gitCommit` | The commit it was built from. A `-dirty` suffix means the tree had uncommitted changes; don't publish such a build. |

## Versioning

`version.properties` holds the one version that both apps share:

- `versionName` is a semantic version (`MAJOR.MINOR.PATCH`). This is the version people read.
- `versionCode` is the number Android compares when it installs an update. **Add 1 for every release that is published, and never reuse or lower it.** Android refuses to install a lower code over a higher one, and the update channel (`/android/updates` on Life OS) uses it to recognise a newer build.

To release:

1. Raise `versionCode`, and `versionName` too if the change deserves a new name.
2. Commit.
3. Run `tools/build`.
4. Publish `dist/`.

## The phone app

### First run

The app asks for the server (default `https://lifeos.gamch1k.com`) and a Cloudflare Access **service token** made for this phone, with "How to get a token" steps built in:

1. Cloudflare dashboard → Zero Trust → **Access controls → Service credentials → Service Tokens → Create Service Token**. Name it `life-os-phone`, pick a duration, **Generate token**, and copy the Client ID (it ends in `.access`) and the Client Secret, which Cloudflare shows only once.
2. **Access controls → Policies → Add a policy**: Action **Service Auth**, Include → **Service Token** → `life-os-phone`, **Save**.
3. **Access controls → Applications → Life OS → Configure → Policies → Select existing policies**, tick the new policy, **Confirm**, **Save**. Service Auth policies are evaluated before the owner's Allow policy, which keeps working.
4. In the app: paste both values, **Test connection** (`GET /api/tasks/v1/tasks?limit=1`), **Save and continue**.

To revoke the phone, delete its token in Cloudflare. **Sign out** (Settings) removes the token, the cached Today and the queue from the phone.

### How it talks to Life OS

- `core/…/api/LifeOsClient.kt` over `CronetTransport`: the embedded Cronet with the built-in DNS resolver (ECH), no QUIC, redirects never followed.
- Every request sends `CF-Access-Client-Id`, `CF-Access-Client-Secret`, `X-Life-OS-Actor: user` and `User-Agent: LifeOS-Android/<version>`.
- Errors are typed (`LifeOsException`): offline or reset, timeout, **401/403** (Access refused the token), a **redirect to `*.cloudflareaccess.com`** (no Service Auth policy accepts the token), a 4xx with the Life OS error envelope's `code` and `message`, a 5xx. Idempotent calls (reads, completing or reopening a Task, Inbox captures with a dedupe key, content-addressed image uploads) are retried twice on errors that may pass.
- The token is encrypted with an AES-256-GCM key in the Android Keystore; only ciphertext is in the `settings` DataStore. A token saved by 0.1.0's network check is moved over once.

### Today

Home shows the overdue and due-today Tasks (`GET /api/tasks/v1/tasks?completed=false&dueBefore=<end of today>&includeUndated=false`, split by the Dashboard's rules in the phone's time zone) and today's and tomorrow's events (`GET /api/calendar/v1/events?from&to&timeZone`, recurring events as Calendar expands them). Tap a Task to complete it (`POST …/complete`, with Undo, which reopens it); long-press opens `/tasks` in the Dashboard; an event opens `/calendar?date=…`. Pull to refresh. The last result is cached, so Home opens at once and offline, with "Updated hh:mm".

### Capture and the offline queue

Voice, Photo and Text go into a queue (`core/…/capture`) that a WorkManager job per capture sends when there is a network, with exponential backoff; the queue survives restarts and reboots. **Captures** (the list icon on Home) shows each one: waiting, sent, failed with Retry, and then its Inbox state ("In Inbox", "Processed: created a Task") from the item's `state` and its last processed attempt's actions.

| Capture | Sent as | Idempotency |
| --- | --- | --- |
| Text | `POST /api/inbox/v1/inbox-items`, `source: "web"`, `sourceMetadata: {"via": "android-app", "timeZone", "captureId"}` | `dedupeKey: "android-app:<capture id>"` |
| Photo (camera or picker, up to 5) | each image converted on the phone to JPEG (HEIC too), at most 2560 px, then `POST /api/inbox/v1/media` one by one, then the Inbox capture with `mediaReferences` | references are content-addressed and stored as they arrive; the same dedupe key |
| Voice (Ogg/Opus, mono, 16 kHz, 32 kbit/s, at most 20 minutes) | `POST /api/voice/v1/audio-jobs` (`audio`, `source=web`, `sourceMetadata`); Voice transcribes it into the Inbox, and the app follows the job | the job id is stored at once; Voice has no idempotency key, so only an upload whose answer was lost can be sent twice |

### Updates (P18-09)

The app reads `/android/updates` (the contract is `docs/operations/android-releases.md` in the life-os repository) when it starts (at most every 6 hours; it only shows a banner), once a day on an unmetered network (`UpdateWorker`: downloads, and installs at once when the app is not on screen), and on **Settings → Check for updates** (downloads and installs). It takes `apps.phone` only when its `versionCode` is higher than its own and the phone's API level reaches `minSdk`; it downloads through the authenticated client (resuming a partial file), refuses a file whose size or SHA-256 differs, checks that the archive is this package at that version, and installs it with a `PackageInstaller` session and `USER_ACTION_NOT_REQUIRED`. When Android still asks (the first update after a browser install, when Chrome is the installer of record), the app opens the confirmation, or offers it on Home. Without **Install unknown apps** for Life OS it leads to that setting. After a restart on a new version, Home says "Updated to x.y".

## Installing

The phone app is a normal APK. Open the published `lifeos-phone-….apk` on the phone, let Android install apps from that source when it asks, and install. Later builds install over it as long as `versionCode` went up, because the signing key never changes.

Until the Data Layer flow exists, the wear APK goes onto the watch with `adb` over Wi-Fi debugging: `adb install lifeos-wear-….apk`.
