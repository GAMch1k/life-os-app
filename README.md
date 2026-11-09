# Life OS app

The Android phone app and the Wear OS app for [Life OS](https://github.com/GAMch1k/life-os), in one repository: both share one package name and one signing key, which the Wear OS Data Layer requires.

The plan lives in Notion ("Android & Wear OS apps — plan", Phase 18 and Phase 19 in Implementation Tickets).

## What is here

| Path | What |
|---|---|
| `core/` | Android library shared by the apps: the Life OS API client over embedded Cronet, the service-token store, the capture queue's logic, Today, the update rules (the phone's and the watch's), the phone–watch link, the watch setup's state machine, the network check, and Snooze's rules. Most unit tests live here; the wear app's are in `wear/src/test`. |
| `phone/` | The phone app ([below](#the-phone-app)): onboarding, Today, capture with an offline queue, self-update, the watch app's setup and updates, and the **Network check** ([docs/network-check.md](docs/network-check.md)) under Settings. |
| `wear/` | The Wear OS app: voice capture, the Capture and Today tiles, the "Life OS next" and "Life OS capture" complications, updates through the phone ([docs/wear.md](docs/wear.md)). |
| `tools/build` | Builds everything on the server, in a container. |
| `tools/builder/` | The builder image (`Dockerfile`) and the script that runs inside it (`in-container`). |
| `.github/` | CI on GitHub Actions (`workflows/ci.yml`, [below](#ci-github-actions)) and Dependabot's configuration. |
| `version.properties` | The one version of both apps. |
| `gradle/libs.versions.toml` | Every library, plugin and SDK level, in one version catalog. |

Both apps use the `applicationId` **`com.gamch1k.lifeos`**. Phone: minSdk 34, targetSdk 36. Wear: minSdk 33, targetSdk 36. Both compile against SDK 37, because current AndroidX and Compose releases require it.

Main versions: AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21, Gradle 9.7.1, Compose BOM 2026.09.00, Compose for Wear OS Material 3 1.7.1, Cronet 500.1.0 (`org.chromium.net:cronet-bundled`, Chromium 155.0.8059.16), JDK 21.

The watch setup (P19-06, phone only) uses [libadb-android](https://github.com/MuntashirAkon/libadb-android) 3.1.1 (`com.github.MuntashirAkon:libadb-android`, from JitPack). It is dual-licensed GPL-3.0-or-later or Apache-2.0; **we use it under Apache-2.0**. It brings SPAKE2 (`com.github.MuntashirAkon.spake2-java:spake2-android` 2.2.1, LGPL-3.0, a small JNI library linked dynamically) and BouncyCastle 1.81 (MIT); the phone adds Conscrypt 2.7.0 (`org.conscrypt:conscrypt-android`, Apache-2.0), which pairing needs for TLS 1.3's keying-material export, and BouncyCastle's `bcpkix` for the ADB key's certificate. JitPack is a repository for exactly these two groups (`exclusiveContent` in `settings.gradle.kts`); everything else comes from Google and Maven Central.

The phone APK is about 16.1 MB. Embedded Cronet's native library for arm64 accounts for 7.3 MB of it, and since 0.4.0 Conscrypt's for 2.7 MB (the watch setup; BouncyCastle's unused resources are left out); the APK carries only arm64 code. The wear APK is about 5.9 MB since 0.4.5 (6.6 MB in 0.4.3 and 0.4.4, 16.6 MB before): it embeds Cronet too (for calls without the phone and its own update download), for `armeabi-v7a` only, and stores its native code and dex compressed, which the watch extracts at install. The Galaxy Watch 8 runs 32-bit apps only (it reports `armeabi-v7a, armeabi`), so the watch app is a 32-bit ARM app ([BUG-45](#the-watch-app-setup-and-updates-p19-06-040), [docs/wear.md](docs/wear.md#the-watchs-abis)).

## Building

Builds run on the Life OS server, never on a laptop. The host needs only Docker and `flock`:

```sh
tools/build              # release: unit tests, signed APKs of both apps, checks, dist/
tools/build test         # unit tests only, of the release variant (no signing material needed)
tools/build ci           # what CI runs: unit tests, unsigned release APKs, the widget and ABI checks
tools/build gradle ARGS  # any Gradle command in the builder, e.g. tools/build gradle :phone:dependencies
tools/build image        # only build the builder image
```

What `tools/build` does:

- It holds `/tmp/lifeos-heavy.lock` for the whole run. Every heavy job on this host takes that lock. If another job holds it, the build waits; it never bypasses the lock.
- It builds the builder image from `tools/builder/Dockerfile`, or reuses it. The image tag is the Dockerfile's hash, and older builder images are removed. The image holds Temurin JDK 21, Android command-line tools 23.0, `platforms;android-37.0`, `build-tools;36.1.0` and platform-tools 37.0.1. All of them are pinned, and the image build fails if the SDK manager delivers other revisions. The image is about 1 GB.
- It runs Gradle in a container limited to 3 GiB of memory (no swap) and 2 CPUs, as uid 1000, with the repository mounted at `/workspace`.
- It keeps the Gradle cache in the named Docker volume `lifeos-android-gradle`. To start clean, run `docker volume rm lifeos-android-gradle`.
- A release runs three Gradle invocations, each in a fresh JVM, so the peak is that of the largest step rather than of everything one JVM ever loaded: the unit tests (`testReleaseUnitTest`), then `:phone:assembleRelease`, then `:wear:assembleRelease`, both with one worker so R8 and lint never run side by side. The unit tests run against the release variant (`android.onlyEnableUnitTestForTheTestedBuildType=false`), whose classes the APKs are built from, so nothing is compiled twice.
- `gradle.properties` keeps each Gradle JVM small: `-Xmx1536m` as a ceiling, the serial collector told to keep only 20–40 % of the heap free (so the heap stays near the live data, about 550 MiB at R8's peak), metaspace capped at 512 MiB, no daemon, Kotlin compiled in-process. `in-container` also sets `MALLOC_ARENA_MAX=2` and a small JVM for the Gradle client, which only relays output.
- After each step and at the end it prints the container's peak memory and the step's time, sampled from its cgroup:
  - **process memory**: what the processes really use;
  - **working set**: what `docker stats` shows;
  - **cgroup peak** (end only): this also counts page cache, so it can reach the limit harmlessly.

  Measured on a cold release build without the build cache: 473 s and a peak process memory of 1564 MiB (cgroup peak 2076 MiB) with these settings, against 562 s and 2668 MiB (cgroup peak 3036 MiB) with 0.3.0's (one invocation, two workers, the parallel collector). R8 is most of the time (about 150 s for the phone, 135 s for the wear app). The configuration cache stays off: it would store the signing configuration, passwords included, in `.gradle/configuration-cache` (encrypted, with the key in the Gradle volume), and a release's changed `version.properties` would invalidate it anyway.

## Servers: official default, your own, and updates

The apps talk to one Life OS server and get their updates from one place.

- **The server.** At the first run the phone offers the official server, `https://lifeos.gamch1k.com`. It is not forced: type your own Life OS's address instead, or change it later under **Settings › Connection › Edit connection**. A new server needs its own service token, because the token belongs to that server's Cloudflare Access. The watch has no server setting: it takes the server and the token from the phone, and follows a change at once. The network check and the Dashboard links use the server set in the app.
- **Updates** always come from the official domain, whatever server is set in the app. The phone's manifest (`/android/updates`) and APKs and the watch's own Wi-Fi download all use the update origin. The service token goes along only when the server set in the app *is* the update origin; a token for another server is never sent there. With another server, the update requests carry no token, so they work only where the update origin lets `/android/` through without one.
- **Building your own app.** Two build settings decide what is compiled in. They live in core's `BuildConfig` and are read through `core/…/config/LifeOsEndpoints.kt`, so the phone and the watch app always agree:

  | Gradle property | Environment variable | Default | What |
  |---|---|---|---|
  | `lifeos.serverUrl` | `LIFE_OS_PUBLIC_URL` | `https://lifeos.gamch1k.com` | The server the phone offers at the first run. |
  | `lifeos.updatesUrl` | `LIFE_OS_UPDATES_URL` | `https://lifeos.gamch1k.com` | Where both apps get updates. If you sign with your own key, point it at your own server: Android installs an update only when the installed app's key signed it. |

  Each must be an https:// origin: a host and perhaps a port, no path. The Gradle property wins over the environment variable. Set them with `-P` (`./gradlew -Plifeos.serverUrl=https://lifeos.example.com …`, or `tools/build gradle -Plifeos.serverUrl=… :phone:assembleRelease`), in Gradle's environment, or in a `gradle.properties`. `tools/build` passes no environment variables into its container, so its own builds always use the defaults unless a `gradle.properties` says otherwise.

## Signing

Every APK that is ever installed is signed with the **release key**, including dev builds. That way updates never break, and the phone and the watch always pair.

- The key lives only on the server, in `~/life-os.secrets/android/`:
  - `lifeos-release.p12`: the PKCS#12 keystore, alias `lifeos`;
  - `keystore.properties`: the keys `storeFile` (relative to the properties file), `storeType`, `storePassword`, `keyAlias` and `keyPassword`.

  The directory is 0700 and the files are 0600.
- `tools/build` mounts that directory read-only, at the same path, and sets `LIFEOS_KEYSTORE_PROPERTIES=<dir>/keystore.properties`. To use another directory, set `LIFEOS_SIGNING_DIR`.
- The key, its passwords and the properties are never printed, logged, copied into the repository, or baked into an image. `.gitignore` also blocks `*.p12`, `*.jks`, `*.keystore` and `keystore.properties`.
- Without `LIFEOS_KEYSTORE_PROPERTIES`, compiling and unit tests still work. Building any APK, debug or release, then fails before any task runs, with a message that says why. `tools/build` refuses to start a release when the signing directory is missing.
- The one exception is `-Plifeos.unsignedApks=true`, which [CI](#ci-github-actions) and `tools/build ci` use: the APKs are then built without any signing configuration, as `<module>-release-unsigned.apk`. Android refuses to install an unsigned APK, so every APK that can be installed still carries the release key.
- After the build, each APK must have exactly one signer, with this certificate SHA-256:
  `AB:A9:79:EC:4A:64:CC:64:60:36:BA:F6:C9:76:4A:54:63:55:47:BC:C2:58:3C:59:BB:6A:E3:3A:8D:1E:EF:9F`

## Release output (`dist/`)

A release build empties `dist/` (gitignored), then writes:

- `lifeos-phone-<versionName>-<versionCode>.apk`
- `lifeos-wear-<versionName>-<versionCode>.apk`
- `release.json`

Before anything is copied there, the script checks each APK:

- `apksigner verify`: the signature, and that the release certificate signed it;
- `aapt2 dump badging`: the package name and the version;
- the phone's R8 mapping: `TodayWidget` and `QuickCaptureWidget` stay two classes under their own names (BUG-41, below);
- the native code (`tools/builder/check-apk-abi`): the phone APK must carry `lib/arm64-v8a/`, the wear APK `lib/armeabi-v7a/`, the ABI the Galaxy Watch 8 runs (BUG-45, below). The check prints the ABIs each APK carries.

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

## CI (GitHub Actions)

`.github/workflows/ci.yml` checks every push on GitHub, without the signing key. It runs `tools/builder/in-container ci`, the script that `tools/build ci` runs on the server:

- the unit tests of every module (`testReleaseUnitTest`, as in `tools/build test`);
- `:phone:assembleRelease` and `:wear:assembleRelease` with `-Plifeos.unsignedApks=true`: the release build type, so R8 and the same `ndk.abiFilters` as a release (they are set in `defaultConfig`), only unsigned;
- the release's checks that need no signature: the widget classes in the phone's R8 mapping (BUG-41), and `tools/builder/check-apk-abi` on both APKs. The phone APK must carry `lib/arm64-v8a/` and the wear APK `lib/armeabi-v7a/` (BUG-45), or the run fails.

When it runs:

- on every push to any branch, on a pull request from a fork, and by hand (Actions › CI › Run workflow). A pull request from a branch of this repository gets no run of its own, because the branch's push already tested it;
- not when a push changes only Markdown (`**.md`, `docs/**`);
- a newer push to the same branch cancels the older run.

How it runs:

- One job on `ubuntu-latest`, with Temurin JDK 21. The SDK platform and build-tools are the ones `tools/builder/Dockerfile` pins, read from that file and installed only when the runner image lacks them (today it has both). The workflow installs no NDK, since the apps compile no native code; as in the builder, AGP packages the dependencies' native libraries as they are, unstripped.
- `gradle/actions/setup-gradle` validates the wrapper JAR and restores the Gradle cache, with its open-source `basic` provider (a plain `actions/cache`). Only runs on `main` write that cache; other branches restore `main`'s.
- Every action is pinned by commit SHA, with its version in a comment, and Dependabot keeps them current.
- Nothing secret is involved. The workflow uses no secrets, and its token can only read the repository. The APKs stay unsigned and are not uploaded: Android won't install them, and the release APKs come from `tools/build`. Nothing is published.

Cost: the repository is private, so runs count against GitHub Free's 2,000 minutes a month. Runs without any Gradle cache (X-09's, before `main` had one) took 8 min 35 s to 9 min 52 s: about 3 minutes for the unit tests and 2½ to 3½ for each app. They are billed as 9 or 10 minutes, so 2,000 minutes last about 200 such runs. A run that restores `main`'s cache skips the downloads and reuses the outputs of unchanged tasks.

To reproduce a run on the server, use `tools/build ci`. It runs the same script in the builder container, under the heavy-job lock, without the signing directory. `tools/build test` runs only the unit tests, and a release (`tools/build`) runs everything plus the signature checks.

Dependabot (`.github/dependabot.yml`) looks for updates weekly, for the Gradle dependencies (the version catalog and the plugins) and for the actions. Minor and patch updates come grouped, one pull request per ecosystem, and a major update gets its own. It keeps at most 5 Gradle and 3 actions pull requests open, and CI tests each one. It doesn't raise the SDK levels, `buildTools`, or what `tools/builder/Dockerfile` pins; those are raised by hand.

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

The app asks for the server (the official `https://lifeos.gamch1k.com`, or your own Life OS: [Servers](#servers-official-default-your-own-and-updates)) and a Cloudflare Access **service token** made for this phone, with "How to get a token" steps built in:

1. Cloudflare dashboard → Zero Trust → **Access controls → Service credentials → Service Tokens → Create Service Token**. Name it `life-os-phone`, pick a duration, **Generate token**, and copy the Client ID (it ends in `.access`) and the Client Secret, which Cloudflare shows only once.
2. **Access controls → Policies → Add a policy**: Action **Service Auth**, Include → **Service Token** → `life-os-phone`, **Save**.
3. **Access controls → Applications → Life OS → Configure → Policies → Select existing policies**, tick the new policy, **Confirm**, **Save**. Service Auth policies are evaluated before the owner's Allow policy, which keeps working.
4. In the app: paste both values and tap **Connect**: it tests the token (`GET /api/tasks/v1/tasks?limit=1`) and, when Life OS answers, saves it and opens Today.

To revoke the phone, delete its token in Cloudflare. **Sign out** (Settings) removes the token, the cached Today and the queue from the phone. To switch servers or tokens later: **Settings › Connection › Edit connection**.

### How it talks to Life OS

- `core/…/api/LifeOsClient.kt` over `CronetTransport`: the embedded Cronet with the built-in DNS resolver (ECH), no QUIC, redirects never followed.
- Every request sends `CF-Access-Client-Id`, `CF-Access-Client-Secret`, `X-Life-OS-Actor: user` and `User-Agent: LifeOS-Android/<versionName> (<versionCode>)`, for example `LifeOS-Android/0.4.0 (7)`.
- Errors are typed (`LifeOsException`): offline or reset, timeout, **401/403** (Access refused the token), a **redirect to `*.cloudflareaccess.com`** (no Service Auth policy accepts the token), a 4xx with the Life OS error envelope's `code` and `message`, a 5xx. Idempotent calls (reads, completing or reopening a Task, Inbox captures with a dedupe key, content-addressed image uploads, voice uploads with an `Idempotency-Key`) are retried twice on errors that may pass.
- The token is encrypted with an AES-256-GCM key in the Android Keystore; only ciphertext is in the `settings` DataStore. A token saved by 0.1.0's network check is moved over once.
- For the watch link (`docs/wear.md`): `LifeOsClient.relay()` sends a request on the watch's behalf with the phone's token, follows no redirect, returns every HTTP answer as it is, and throws `NotSignedIn` without a token or `Offline`/`Timeout`/`Local` without an answer. `ServiceTokenStore(context).load()` is kept as a read-only view of the token store for code that still uses that name.

### Today

Home shows the overdue and due-today Tasks (`GET /api/tasks/v1/tasks?completed=false&dueBefore=<end of today>&includeUndated=false`, split by the Dashboard's rules in the phone's time zone) and today's and tomorrow's events (`GET /api/calendar/v1/events?from&to&timeZone`, recurring events as Calendar expands them). Tap a Task to complete it (`POST …/complete`, with Undo, which reopens it); long-press opens `/tasks` in the Dashboard; an event opens `/calendar?date=…`. Pull to refresh. The last result is cached, so Home opens at once and offline, with "Updated hh:mm".

### Capture and the offline queue

Voice, Photo and Text go into a queue (`core/…/capture`) that a WorkManager job per capture sends when there is a network, with exponential backoff; the queue survives restarts and reboots. **Captures** (the list icon on Home) shows each one: waiting, sent, failed with Retry, and then its Inbox state ("In Inbox", "Processed: created a Task") from the item's `state` and its last processed attempt's actions. A voice note whose transcription failed ("Transcription failed…", or expired) offers Retry too, and stays in the list until it is retried or deleted.

| Capture | Sent as | Idempotency |
| --- | --- | --- |
| Text | `POST /api/inbox/v1/inbox-items`, `source: "web"`, `sourceMetadata: {"via": "android-app", "timeZone", "captureId"}` | `dedupeKey: "android-app:<capture id>"` |
| Photo (camera or picker, up to 5) | each image converted on the phone to JPEG (HEIC too), at most 2560 px, then `POST /api/inbox/v1/media` one by one, then the Inbox capture with `mediaReferences` | references are content-addressed and stored as they arrive; the same dedupe key |
| Voice (Ogg/Opus, mono, 16 kHz, 32 kbit/s, at most 20 minutes) | `POST /api/voice/v1/audio-jobs` (`audio`, `source=web`, `sourceMetadata`); Voice transcribes it into the Inbox, and the app follows the job | Voice honours `Idempotency-Key` (BUG-40). Each job attempt has its key, `<capture id>` and then `<capture id>-r1`, `-r2`…, and its `sourceMetadata` JSON, stored when the attempt starts and byte-identical on every try of it; the job id is stored at once. Retry after a failed job or a 409 starts a new attempt; any other Retry keeps the key ([docs/wear.md](docs/wear.md#idempotency)) |

### Updates (P18-09)

The app reads `/android/updates` on the update origin, the official domain whatever server is set ([Servers](#servers-official-default-your-own-and-updates)); the contract is `docs/operations/android-releases.md` in the life-os repository. It checks when it starts (at most every 6 hours; it only shows a banner), once a day on an unmetered network (`UpdateWorker`: downloads, and installs at once when the app is not on screen), and on **Settings → Check for updates** (downloads and installs). It takes `apps.phone` only when its `versionCode` is higher than its own and the phone's API level reaches `minSdk`; it downloads through the client (resuming a partial file), refuses a file whose size or SHA-256 differs, checks that the archive is this package at that version, and installs it with a `PackageInstaller` session and `USER_ACTION_NOT_REQUIRED`. Android asks once, on the first self-update after a browser install (your browser is then the installer of record): the app opens the confirmation, or offers it on Home. Once Life OS installed the current version, later updates are silent. Without **Install unknown apps** for Life OS it leads to that setting. After a restart on a new version, Home says "Updated to x.y".

### The watch app: setup and updates (P19-06, 0.4.0)

**Settings › Watch** shows the watch app's version, the last update's result, **Update now**, the watch's ABIs, and **Set up the watch app**:

- **Set up the watch app** installs the watch app once, without Play and without a computer: the phone downloads and checks the newest `apps.wear`, pairs with the watch's Wireless debugging (the code and IP:port the watch shows), finds the connection port over mDNS or asks for it, installs the APK, allows "Install unknown apps" for Life OS on the watch (`appops set com.gamch1k.lifeos REQUEST_INSTALL_PACKAGES allow`), and checks that the watch app answers over the Data Layer. Then Wireless debugging goes off again. The phone's ADB key stays, encrypted with a Keystore key, for a later rescue without a new pairing.
- **Updates**: the phone asks the watch its version over the Data Layer; when Life OS publishes a newer `apps.wear`, it downloads and checks it (size, SHA-256, package, version, the release key), offers it, and streams it through a channel. The watch checks it again and installs it itself, silently (`USER_ACTION_NOT_REQUIRED`), and reports the result on its next start. Daily on Wi-Fi, when the app opens (at most every 6 hours), when the watch reports in, and on Update now; with backoff, a battery rule and Android's 30 seconds between installs.
- **Since 0.4.3** (transfer v2): a 0.4.3 watch first downloads the APK itself from the update origin over a Wi-Fi network it asks Android for, while the phone follows; without Wi-Fi within 45 s, or when that download fails, the phone streams it, from the bytes the watch already holds, and a broken stream resumes (up to 4 times per push). Both sides run the update in a foreground service with a wake lock and a progress notification; the phone has no fixed time limit any more (0.4.2's 15 minutes cut the 16.6 MB APK at 91–95 % over Bluetooth), only 2 minutes without a byte. Settings › Watch shows the route, percentage, speed and time left, with **Cancel**; the watch shows the same on Connection. A 0.4.1 or 0.4.2 watch still gets the whole APK in one stream (v1). Tip: set the watch's Wi-Fi to **Auto** and save your network on it.
- **BUG-45** (fixed in 0.4.5): 0.4.3 and 0.4.4 never reached the Galaxy Watch 8. Their wear APK carried native code for `arm64-v8a` only, on the wrong belief that the watch runs a 64-bit user space; it runs 32-bit apps only and reports `armeabi-v7a, armeabi`, so Android there would refuse the APK (`INSTALL_FAILED_NO_MATCHING_ABIS`), and the phone, which compares the watch's ABIs with the APK's before it sends, refused to send it (Settings › Watch said so). Since 0.4.5 the wear APK carries `armeabi-v7a` only (5.9 MB), and the release build fails when the wear APK lacks `lib/armeabi-v7a/` or the phone APK `lib/arm64-v8a/`. The phone judges by the ABIs it was built with, so a 0.4.4 phone still refuses the 0.4.5 watch app: the phone updates itself to 0.4.5 first, then sends it on its next check or on **Update now**. A 0.4.2 watch gets it in one v1 stream, about 6 minutes over Bluetooth.

The steps, the protocol and the failure modes are in [docs/wear.md](docs/wear.md#install-and-updates).

### Share, push, widgets (0.3.0)

- **Share → Life OS** (`phone/…/share/ShareActivity.kt`, mapping in `core/…/share/ShareMapper.kt`): `ACTION_SEND` with text or an image and `ACTION_SEND_MULTIPLE` with images, from any app. A sheet shows the text or link ("Instagram link", "TikTok link", "YouTube link") and the images, takes an optional note, and queues a text capture, or a photo capture with up to five images (converted to JPEG while the share's read permission lasts). Links stay text with their URL, so Life OS's recipe import and processor handle them.
- **Push** (`phone/…/push/`): UnifiedPush connector 3.3.5 with the embedded FCM distributor 3.1.0. The app is its own distributor (`saveDistributor(packageName)`), so it never picks another UnifiedPush app such as ntfy. The distributor registers with Play services using the server's VAPID public key (`GET /api/notifications/v1/push/config`), which needs no Firebase project of ours and no gateway; the endpoint is FCM's Web Push endpoint, `https://fcm.googleapis.com/fcm/send/…`. `POST /api/notifications/v1/push/subscriptions` stores it with the connector's `p256dh` and `auth` (again on every start and on a new endpoint; the old subscription is deleted); sign-out deletes it. The connector decrypts each push (RFC 8291); the app shows it on one of three channels (Urgent, Reminders, Information by importance; Urgent, channel id `lifeos.urgent` since 0.3.3, vibrates long and strong, about 5 seconds, like ntfy's max priority, and replaced the first `lifeos.high` channel, since Android fixes a channel's vibration when it is created), with the payload's `tag` as the notification tag, `WearableExtender` dismissal id `lifeos:<id>` and bridge tag `lifeos.<category>` for the watch, one group with a summary, a tap that opens `link` in the Dashboard, and **Done** when the link names a Task (`/tasks/<uuid>`, `/tasks?task=<uuid>`, …; `core/…/push/PushPayload.kt`). Done completes the Task through a WorkManager job (offline too) and tells the watch.
  - **Snooze** (P18-05, 0.4.4; `core/…/push/Snooze.kt`, `phone/…/push/Snoozes.kt`): on every Life OS notification but the summaries, which are read and done with: `summary.morning` (the daily summary) and `finance.budget.summary` (the month's), so any category below `summary.` or ending in `.summary`. Snooze removes the notification, on the phone and on the watch, and shows it again after the length chosen under **Settings › Notifications › Snooze for**: 15 minutes, 1 hour (the default), 3 hours, or tomorrow at 09:00 in the phone's time zone (on the wall clock, so a DST change overnight doesn't move it; from midnight to 05:00 it means the coming morning). It comes back with the same payload, channel, tag and actions (Done where there was Done, and Snooze again), alerts like a new notification, and says **Snoozed** as its sub-text, with the time it came back as its time; the title is unchanged.
    - **Done** on a notification drops a pending snooze of the same tag and every snoozed notification about the same Task; completing the Task on Home does too.
    - **How it comes back**: the snoozed notifications are stored in the settings DataStore, and one inexact `AlarmManager.setAndAllowWhileIdle` alarm is set at the earliest. It needs no permission (exact alarms would), fires in Doze, and may come a few minutes late, never early. When it goes off, everything due within the next minute comes back, and the alarm moves to the next one.
    - **The process dying** loses nothing: the alarm and the store outlive it. **A reboot** clears alarms, so `SnoozeRestoreReceiver` (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`) is enabled while something is snoozed: it shows what came due meanwhile and sets the alarm again. `RECEIVE_BOOT_COMPLETED` came with WorkManager already, so the APK asks for nothing new. Every app start does the same, which covers a force stop (Android deletes the app's alarms then).
    - Snooze is local: Life OS doesn't know about it. Sign-out forgets snoozed notifications; the length stays.
    - **On the watch**: Done and Snooze are broadcasts to the phone (`NotificationActionReceiver`), so the watch shows both on its bridged copy and runs them on the phone; both are marked as opening nothing (`setShowsUserInterface(false)`). After Snooze the phone cancels its notification, which removes the watch's copy too, and the notification that comes back is bridged again ([docs/wear.md](docs/wear.md#notifications-p19-04)).
- **Widgets** (Glance 1.2.0, `phone/…/today/TodayWidget.kt`): **Life OS Capture** (Voice, Photo, Text into their screens) and **Life OS Today** (the events still ahead today and tomorrow, then the Tasks due, overdue first; from the cached Today, re-rendered when it changes, every 30 minutes, and after the hourly background refresh). A tap opens the app.
  - **BUG-41** (fixed in 0.4.3): the Capture widget sometimes showed Today, and kept it. Glance finds a widget's ids by its `GlanceAppWidget`'s class name, and R8 had merged `QuickCaptureWidget` into `TodayWidget`, so every Today refresh drew Today into the Capture widgets as well; Capture is drawn only when the system asks (placing it, a reboot, an app update), so it stayed until then. Now `proguard-rules.pro` keeps every `GlanceAppWidget` class, the release build fails if R8's mapping shows them renamed or merged, each widget renders only into the ids the system lists for its own receiver, and the Capture widgets are drawn again whenever the app opens.
- **Voice idempotency** (BUG-40): every voice upload sends its job attempt's `Idempotency-Key` and the `sourceMetadata` JSON stored once for that attempt, so a retry is answered with the same job; 409 `idempotency_conflict` fails the capture with a clear message. Since 0.3.1, Retry after a failed transcription or a 409 starts a new job attempt with a new key, on the phone and the watch.

### Life OS Capture and the side key (P18-12, 0.4.3)

The phone app has a second entry in the app list, **Life OS Capture** (a microphone on the Life OS blue; `phone/…/ui/VoiceCaptureActivity.kt`). It opens straight into Home's Voice screen and starts recording at once; **Stop and send** queues the note, **Cancel** discards it.

- **Over the lock screen**: it shows over the lock screen and turns the screen on, and shows only the recording there. Send, Cancel and Back close it, back to the lock screen; it opens nothing else, so the rest of the app stays behind the unlock. It runs in a task of its own, out of Recents.
- **Sending** works with the phone locked: the note goes into the queue like any other (`Voice note (0:12) queued for the Inbox` as a toast) and is sent when there is a network.
- The screen stays on while it records. If it goes off anyway (the side key), the recording stops and is kept; the next launch shows it with Send and Cancel.
- **Microphone**: the first time, Android asks for it; over the lock screen, the unlock comes first, then the question.

**Double press of the side key** (Samsung One UI): Settings › Advanced features › **Side button** › turn on **Double press** › **Open app** › choose **Life OS Capture**. Pressing the side key twice then records, from the lock screen too. If One UI asks for the unlock before it opens the app, that is One UI's rule for the side key; Life OS Capture itself can show over the lock screen.

## Installing

The phone app is a normal APK. Open the published `lifeos-phone-….apk` on the phone, let Android install apps from that source when it asks, and install. Later builds install over it as long as `versionCode` went up, because the signing key never changes.

The watch app is installed from the phone: **Settings › Watch › Set up the watch app**, once (no computer, no Play account; [docs/wear.md](docs/wear.md#install-and-updates)). After that, every published wear APK reaches the watch through the phone and installs itself. The rest of the watch's setup (token, tiles, complications, Home key) is in [docs/wear.md](docs/wear.md#setting-up-the-watch-the-owner).
