# The Wear OS app (Phase 19)

The watch app for the owner's Galaxy Watch 8 (One UI 8 Watch, Wear OS 6). First version:

- **voice capture**: tap a tile, the complication, the app, or double-press the Home key; speak; tap Send. The recording goes to Life OS Voice and comes back as an Inbox item;
- **a "Today" tile and a next-event complication**;
- **notifications with Done and Snooze**, bridged from the phone app (P18-05). The watch app adds nothing of its own there;
- **install and updates through the phone** (P19-06): no Play Store, no computer, and ADB only once ([Install and updates](#install-and-updates)).

No AI runs on the watch: it records and uploads, Life OS does the rest.

## Architecture

| Where | What |
|---|---|
| `core/…/wearlink/` | The phone–watch protocol, shared by both apps: paths, frames, the relay policy, the phone's handler, and the interfaces the phone implements. |
| `phone/…/wearlink/PhoneWearLinkService.kt` | The phone's Data Layer listener: token handoff and relay. |
| `wear/…/net/` | How the watch calls Life OS: through the phone first, directly second. |
| `wear/…/link/` | The watch's end of the Data Layer, and the token store. |
| `wear/…/capture/` | Recording and the upload queue. |
| `wear/…/today/` | Today's events and tasks: fetch, cache, layout rules. |
| `wear/…/tile/`, `wear/…/complication/` | The Capture and Today tiles, the next-event complication. |
| `wear/…/ui/` | The app's screens (Compose for Wear OS Material 3). |
| `wear/…/update/` | The watch's end of updates through the phone: version answer, offer, transfer, checks, install, result. |
| `core/…/update/WatchUpdateRules.kt`, `ApkRules.kt` | When the phone pushes, what the watch accepts, and the APK checks both sides run. |
| `core/…/watchsetup/` | The one-time bootstrap's state machine and input parsing, without Android. |
| `phone/…/watch/`, `phone/…/watchsetup/` | The phone's update push, and the bootstrap over ADB (libadb-android). |

Both apps are `com.gamch1k.lifeos` and signed with the same key, so the Data Layer connects only these two apps. The phone declares the capability `lifeos_phone_relay` (`phone/src/main/res/values/wear.xml`), the watch `lifeos_watch`.

### Token handoff

The watch never asks the owner for a token. It asks the phone:

1. The watch finds the phone by its capability (a nearby, Bluetooth-connected node first) and sends a `MessageClient` request on `/lifeos/credentials`: `{"v":1,"id":"<uuid>"}`.
2. The phone's listener asks `WearTokenSource.load()` and answers `{"v":1,"id":…,"credentials":{"baseUrl":"https://lifeos.gamch1k.com","clientId":…,"clientSecret":…}}`, or an error `no_credentials`.
3. The watch keeps them encrypted with an AES-256-GCM key in its own Android Keystore (`CredentialStore`); only ciphertext reaches SharedPreferences, and backups are off.

The watch asks when it starts without a token, when the phone app comes into reach without one, when Life OS refuses the token (401, 403 or a redirect to the Access login), when the owner taps **Get token from phone**, and when the phone pings `/lifeos/credentials-changed`.

Google lists handing a session token from the phone to the watch as a Data Layer use. Messages are encrypted in transit and delivered only to the app with the same package and signature. A request/response message, unlike a DataItem, leaves no copy in the Data Layer's storage.

### Two ways to Life OS: the phone first

Every API call from the watch goes through `RoutingTransport`:

1. **Through the phone** (`RelayTransport`) when a phone with the Life OS app is reachable. Small requests go as a `MessageClient` request on `/lifeos/relay`; a request whose frame doesn't fit a message (90 KB), such as audio, goes through a `ChannelClient` channel on `/lifeos/relay-channel`. The phone runs it with its own client (`WearRelay`) and returns the HTTP response, whatever its status.
2. **Directly** (`DirectTransport`) when the phone gives no HTTP answer: it is away, its app doesn't relay, or it couldn't reach Life OS. The watch uses embedded Cronet with Chromium's own DNS resolver, as the phone's network check does, so ECH hides the hostname ([network-check.md](network-check.md)). It runs on the watch's default network, which is Wi-Fi or LTE once the phone is out of Bluetooth range.

Why the phone first:

- The owner's work Wi-Fi resets every TLS handshake that carries `lifeos.gamch1k.com` in clear text. Only an ECH-capable stack gets through, and the phone has the better one: its own client, kept up to date with the phone app, plus a bigger battery and warm connections.
- When the watch is on Bluetooth, its own internet traffic goes through the phone anyway, only slower. The relay uses the Bluetooth link directly, and the watch's Wi-Fi radio can stay off.
- An HTTP answer through the phone is final, even an error: the direct path would reach the same server with the same token.

The direct path keeps the watch useful without the phone, on Wi-Fi or LTE, as long as it holds a token.

### The relay protocol

One frame format for messages and channels (`RelayCodec`):

```
u32 big-endian   header length
header           UTF-8 JSON: RelayRequest or RelayResponse
body             header.bodyLength bytes, raw (audio stays binary)
```

- `RelayRequest`: `v`, `id` (a fresh UUID), `method`, `path` (path and query, already encoded), `headers` (only `Accept`, `Content-Type` and `Idempotency-Key`), `bodyLength`.
- `RelayResponse`: the same `v` and `id`, then either `status`, `contentType` and the body, or `error` with a `code` and a `message`.
- Unknown JSON keys are ignored; another `v` is answered with `unsupported_version`. Headers are at most 16 KB, request bodies at most 26 MiB (Voice's upload limit plus framing), reply bodies at most 1 MiB. A reply to another request id is refused.

`RelayPolicy` lets through only the watch app's own calls: `POST /api/voice/v1/audio-jobs`, `GET /api/voice/v1/audio-jobs/{uuid}`, `GET /api/tasks/v1/tasks`, `POST /api/tasks/v1/tasks/{uuid}/complete` and `GET /api/calendar/v1/events`. The watch can't set the token or the actor; the phone adds `X-Life-OS-Actor: user` to writes.

| Error code | Meaning | The watch |
|---|---|---|
| `bad_request` | Unreadable frame | Gives up (a bug) |
| `forbidden` | Not allowed by `RelayPolicy` | Gives up (a bug) |
| `unsupported_version` | The apps speak different protocol versions | Goes direct |
| `no_credentials` | The phone has no token | Goes direct, if it holds one |
| `relay_unavailable` | The phone app doesn't relay (no `WearRelay` yet) | Goes direct |
| `upstream_unreachable` | The phone got no HTTP response | Goes direct |
| `too_large` | The reply doesn't fit a message | Sends the same request through a channel |
| `internal` | Anything else on the phone | Goes direct |

### Idempotency

Voice honours `Idempotency-Key` on `POST /v1/audio-jobs` since BUG-40: the same key with the same audio, `source` and `sourceMetadata` (compared as JSON) returns the job the first upload created (202), in whatever state it is now, failed included; the same key with anything else is 409 `idempotency_conflict`.

- A capture asks Voice for one job at a time, its **job attempt**. Every upload of an attempt carries the attempt's key: the capture's id (made when recording starts) for the first attempt, then `<capture id>-r1`, `<capture id>-r2`… `sourceMetadata.captureId` is always the capture's id; later attempts add `"attempt": n`.
- The metadata JSON is written once when the attempt starts (`Capture.sourceMetadataJson`) and sent byte for byte on every upload of it, also after a restart (`CaptureUploaderTest`). It holds only persisted fields (id, `createdAt`, time zone, duration), never anything from the upload. Captures queued by 0.3.0 have nothing stored; their first attempt builds the very JSON 0.3.0 sent.
- A capture with a Voice job is never uploaded again; each upload is claimed under the queue's lock; an outcome for a capture that moved on is ignored.
- The phone keeps the successful (2xx) reply of every relayed write with a key for 15 minutes (`RelayReplayCache`), so a retry through the phone is answered without even reaching Voice; a retry while the first try still runs waits for it. Other replies (5xx, 429, a refused token, a refusal) are not kept, so a retry really runs again.
- The owner's **Retry** on a failed capture:

| Why it failed | Retry |
|---|---|
| Voice made a job and it failed: "No speech was heard…", a transcription that failed after Voice's own retries, expired, or a job Voice no longer knows | A **new job attempt**: the next key and its own metadata. The old key would only bring back the failed job. |
| Voice answered 409: another upload holds the key | A new job attempt too: the old key can never be used for this recording. |
| Voice never accepted it: no way to Life OS, 5xx, 429, a refused token, out of retries, a refusal (400, 413), an unreadable 202 | The **same key and metadata**: if an earlier upload did reach Voice and only its reply was lost, the retry gets that job, not a second one. |

The phone app's Captures screen follows the same rules (`Capture.voiceIdempotencyKey`, `CaptureSender.reset`): **Retry** on a voice note whose job failed ("Transcription failed…") or that got a 409 starts a new job attempt; any other Retry keeps the key.

Completing a task sends one key per tap (`complete:<uuid>`), so a lost reply is replayed while a later tap on a reopened task still completes it.

## Voice capture

- **Recording** (`RecordingService`): Ogg/Opus, mono, 16 kHz, 24 kbit/s, which Voice accepts as is; MP4/AAC if the watch can't encode Opus. At most five minutes, then it sends by itself; under 0.8 s nothing is sent. A foreground service of type microphone, so the recording goes on when the screen dims; the screen stays on while the recording screen shows. Its "Recording" notice appears only if notifications are allowed for Life OS (the app doesn't ask); recording works either way.
- **Upload**: `POST /api/voice/v1/audio-jobs`, multipart with `audio` (`<capture id>.ogg`, `audio/ogg`), `source=watch` and `sourceMetadata={"via":"wear-app","timeZone":"Europe/Berlin","captureId":…,"recordedAt":…,"durationMs":…}` (plus `"attempt":n` from the second job attempt on), with the attempt's `Idempotency-Key`.
- **The queue** (`CaptureMachine`, persisted in `files/captures/queue.json` with the recordings beside it):

| State | Shown as | Next |
|---|---|---|
| `queued` | Queued | Uploaded when due |
| `sending` | Sending… | An upload "running" over 5 minutes was cut off and starts again |
| `waiting` | Waiting for phone | Neither the phone nor a direct connection reached Life OS. Tried again after 30 s, 1, 2, 4, 8, then every 10 minutes, and at once when the phone app comes back or hands over a token. Never gives up. |
| `sent` | Sent · transcribing | Voice accepted it. The job is polled for about 2.5 minutes after the upload, then once with every Today refresh, for a day. |
| `in_inbox` | In Inbox | Done; the audio is deleted. Kept in the list for three days. |
| `failed` | Failed · tap to retry | Refused (400 unsupported audio, 413 too large, 409 key taken), out of retries (6 tries over about 15 minutes for 5xx, 429 and a refused token), or the transcription failed ("No speech was heard…"). **Retry** queues it again with fresh retries: as a new job attempt when Voice's job failed or the key was taken, else under the same key ([Idempotency](#idempotency)). |

Long-press a capture on Home to delete it.

## Today

- **Data**: two calls, through the phone or directly:
  - `GET /api/calendar/v1/events?from=<today 00:00>&to=<the day after tomorrow 00:00>&timeZone=<the watch's zone>&limit=30`: recurring events expanded, cancelled ones left out;
  - `GET /api/tasks/v1/tasks?completed=false&dueBefore=<tomorrow 00:00>&includeUndated=false&limit=20`: the open tasks due today, overdue ones included.
  
  A future Life OS "Today" API (in the plan) could replace both.
- **Cache**: `files/today.json`, so the tile and complication always have something to show.
- **Refresh**: WorkManager every 30 minutes (10 minutes flex, batched with other wake-ups), when the tile shows data older than 10 minutes or the complication older than 30, when the app opens with data older than 5 minutes, after a task is completed, and when the phone pings `/lifeos/today-changed`.
- **Rules** (`TodayLayout`, `TodayRules`): events still ahead first (`now`, `14:30`, `All day`, `Tue 09:00`), then tasks, overdue first. A task is date-only by the Tasks service's rule (owner-local midnight, or a UTC midnight on the same calendar day), and overdue from the day after. Three rows on the 44 mm screen and two on smaller ones; a list gives its unused rows to the other; `+N more` when some don't fit; `As of 08:30` when the data is older than two hours.
- **The Today tile** has one timeline entry per event start and end in the next 12 hours, so a past event leaves the tile without a refresh. Tapping a task opens the app's **Mark done?** confirmation; tapping an event opens Today; the edge button records.
- **The next-event complication** answers with a timeline from the cache, so the watch face moves to the following event at each start:
  - short text: the time, with the event's title as title (a microphone and "Free" when nothing is ahead);
  - long text: `14:30 Dentist` under "Next" or "Tomorrow";
  - monochromatic image: a microphone.
  
  Every type records when tapped.

## Notifications (P19-04)

The phone app's notifications reach the watch by Android's own bridging. The watch app posts nothing for Life OS items, so nothing shows twice; its manifest keeps bridging on explicitly (`com.google.android.wearable.notificationBridgeMode` = `BRIDGING`). The only notification the watch app posts is its own "Recording" notice while it records, which stays on the watch.

What the phone app (P18-05) should set on each Life OS notification:

- **Don't** call `setLocalOnly(true)`: that stops bridging. Use it only for the phone's own technical notifications (update downloads, foreground services).
- **Dismissal id**: `NotificationCompat.WearableExtender().setDismissalId("lifeos:<notification id>")`, with the Life OS notification id, so dismissing it on either device dismisses it on both.
- **Bridge tag**: `WearableExtender().setBridgeTag("lifeos.<category>")`, for example `lifeos.tasks.due` or `lifeos.calendar.reminder`. Nothing is excluded today; if the watch app ever posts its own notification for a category, it can exclude that tag with `BridgingManager` and `BridgingConfig.addExcludedTags` instead of showing it twice.
- **Done and Snooze** as normal actions (`addAction`) whose `PendingIntent`s run on the phone, such as a broadcast receiver. Bridged actions run on the phone, which has the token and the ECH-capable client. The content intent (open the app) works only on the phone.
- After Done or Snooze, call `WearLinkPhone.notifyTodayChanged(context)`, so the Today tile drops the task at once.
- Group related notifications (`setGroup` plus a summary), so the watch shows one stack.

## What the phone app implements

All in `core/…/wearlink/WearRelay.kt`; since 0.3.0 the phone app implements every piece: `LifeOsApp` is the `WearLinkHost` (`core/…/wearlink/PhoneWearLinkHost.kt`), `TokenStore` is the `WearTokenSource`, the relay is `LifeOsClient.relay()`, and the app pings the watch when the token is saved or removed and when Today's data changes (a refresh that found other Tasks or events, a Done, a push about Tasks or events). `PhoneWearLinkHostTest` runs it end to end through the real handler.

1. **`WearLinkHost`**: the phone app's `Application` implements it, so the listener finds the two pieces below. Without it, the listener falls back as described under "The listener's fallback" below.
2. **`WearTokenSource.load(): WearCredentials?`**, implemented by the phone's token store: the base URL (`https://lifeos.gamch1k.com`) and the service token the watch should use, or null when there is none. With one Access service token per device (the plan), this is the watch's own token; until then, the phone's.
3. **`WearRelay.execute(RelayHttpRequest): RelayHttpResponse`**, implemented with the phone's ECH-capable client:
   - send `method` to the base URL plus `path`, with `headers` (the actor is already among them on writes), the body, and the `CF-Access-Client-Id` and `CF-Access-Client-Secret` headers;
   - don't follow redirects, and return every HTTP response, whatever its status;
   - throw `WearRelayException(RelayErrorCode.NO_CREDENTIALS)` without a token and `WearRelayException(RelayErrorCode.UPSTREAM_UNREACHABLE)` when no response came (any other exception counts as unreachable too);
   - it runs on a background thread; the watch waits about 30 seconds for small requests and 2 minutes for uploads.
4. **Pings**: call `WearLinkPhone.notifyCredentialsChanged(context)` after the token changes, and `WearLinkPhone.notifyTodayChanged(context)` when tasks or events change (a push arrived, Done or Snooze). Both are no-ops without a watch.

**The listener's fallback.** Without a `WearLinkHost` (never the case since 0.3.0) the listener would hand over the phone's token through `ServiceTokenStore` (a read-only view of the token store, which holds the token entered at the phone app's first run or under Settings → **Change token**) and relay nothing (`WearRelay.UNAVAILABLE`).

## Install and updates

The watch app is not in the Play Store, and the owner has no computer at hand. So the phone app installs it, once, as an ADB client over the watch's Wireless debugging, and then sends it every new version over the Data Layer; the watch installs those itself, without a prompt.

### The one-time setup (the owner)

On the phone: **Settings › Watch › Set up the watch app**, then **Start**. The phone first downloads the newest wear APK from Life OS (`apps.wear` of `/android/updates`) and checks it like its own update: size, SHA-256, package, version and the release key. Only then does it ask for the pairing code, which expires when the watch's pairing screen closes.

The steps, with One UI Watch's menu names (from Samsung's documentation; not yet checked on the Galaxy Watch 8 itself):

1. **Same Wi-Fi.** On the watch: Settings › Connections › Wi-Fi, join the network the phone is on. Keep the watch's screen on during the setup: Wear OS turns Wireless debugging off whenever the watch's Wi-Fi drops.
2. **Developer options.** Settings › About watch › Software information: tap **Software version** about 5 times, until the watch says Developer mode is on.
3. **Debugging.** Settings › Developer options: turn on **ADB debugging**, then **Wireless debugging**, and allow it on this network (**Always allow on this network**).
4. **Pair.** Wireless debugging › **Pair new device**. The watch shows a **Wi-Fi pairing code** and an **IP address & port**. Type both into the phone and tap **Pair and install**.
5. The phone pairs, finds the connection port by itself (mDNS, `_adb-tls-connect._tcp`) or asks for it (the port under **IP address & port** on the main Wireless debugging screen, not the pairing port), and then, in about a minute:
   - reads `ro.product.cpu.abilist` (the watch's ABIs, shown under Settings › Watch);
   - installs the APK (`cmd package install -S <size>`, streamed);
   - runs `appops set com.gamch1k.lifeos REQUEST_INSTALL_PACKAGES allow`: "Install unknown apps" for Life OS, which One UI Watch has no screen for, and which silent self-updates need;
   - grants the watch app's notifications (`pm grant … POST_NOTIFICATIONS`), for the update's one-tap confirmation and the recording notice;
   - starts the watch app once (it fetches the token from the phone);
   - disconnects, and waits until the watch app answers over the Data Layer with the new version.
6. **Turn Wireless debugging off** again: Settings › Developer options › Wireless debugging. ADB debugging can go off too. Nothing later needs them.

**Paired before?** The phone keeps its ADB key (RSA-2048; the private key encrypted with an AES-256-GCM key in the phone's Android Keystore, in the app's private storage, never backed up). The watch lists it under Wireless debugging › Paired devices as **Life OS phone**. As long as it is there, a rescue (a reset watch app, a lost permission) needs no new code: turn Wireless debugging on, and use **Connect and install** with the IP address from the Wireless debugging screen (the port is optional).

What the setup says when something is off:

| Problem | What the phone says, and what to do |
|---|---|
| The phone is not on Wi-Fi | Connect it to the watch's Wi-Fi. |
| Wrong code | The watch refused the code: check the six digits, or tap Pair new device again for a new code. |
| Pairing expired | Nothing answers on the pairing port: the pairing screen closed (and its code with it), or the port is another. Tap Pair new device again. |
| Wrong network | No answer, and the watch's address is outside the phone's Wi-Fi subnet: connect both to the same Wi-Fi. |
| No answer, same subnet | The watch's Wi-Fi is asleep, or Wireless debugging went off: wake the watch and check. |
| Port changed | Nothing answers on the connection port: Wireless debugging picks a new one each time it is turned on. Type the one it shows now. |
| Not paired | The watch doesn't trust the phone's key (any more): pair again. |
| Install failed | The installer's code and a plain reason, such as `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (a Life OS signed with another key is installed: uninstall it on the watch first). **Try again** reconnects and goes on. A watch that already has a newer version keeps it. |
| Not allowed | `appops get` doesn't say `allow` after the grant. |
| No answer over the Data Layer | Installed, but the watch app hasn't answered over Bluetooth within a minute: open Life OS on the watch, keep the phone close, **Try again** (only the check runs again). |

### Updates through the phone

```mermaid
sequenceDiagram
  participant P as Phone app
  participant L as Life OS /android/updates
  participant W as Watch app
  P->>W: /lifeos/watch-info (request)
  W-->>P: version, API level, ABIs, capabilities, install permission, battery, last result
  P->>L: manifest; apps.wear newer?
  P->>L: download the wear APK (resumable), check size, SHA-256, package, version, release key
  P->>W: /lifeos/apk-offer (version, size, SHA-256)
  W-->>P: accepted, or why not
  P->>W: /lifeos/apk-transfer channel: header, chunks, end
  W-->>P: received (or refused, and why)
  W->>W: PackageInstaller, USER_ACTION_NOT_REQUIRED
  W->>P: /lifeos/watch-report on its next start (installed, failed, waiting)
```

- **When the phone checks**: once a day on an unmetered network (`UpdateWorker`, before the phone's own update, which may end the process), when the phone app opens (at most every 6 hours), when the watch app reports in (after an install, at most once a minute), and on **Settings › Watch › Update now**. Automatic checks download the 15 MB APK only on an unmetered network; Update now downloads on any.
- **The version question** (`/lifeos/watch-info`, `MessageClient` request): `WatchInfoRequest {v, id}` → `WatchInfo {v, id, versionCode, versionName, sdkInt, abis, capabilities, canInstallPackages, battery {percent, charging}, lastUpdate}`. The capability `apk_transfer_v1` says the watch takes updates; a watch app older than 0.4.0 doesn't answer at all, and the phone says the setup is needed.
- **The phone's rules** (`WatchUpdateRules.plan`): push only a published `apps.wear` with a higher versionCode, a usable hash and URL, and a `minSdk` the watch meets; never while the watch reports less than 20 % battery without charging; after a push, wait 10 minutes for the watch's report; after a failure, back off 30 minutes, then 2, 8 and 24 hours (a new version starts over; an install that failed on the watch counts too); a confirmation the watch waits for is not pushed again. **Update now** skips the backoff and the wait, but never the battery rule or 30 seconds between two installs.
- **The offer** (`/lifeos/apk-offer`): `ApkOffer {v, id, versionCode, versionName, size, sha256}` → `ApkOfferReply {v, id, accepted, error {code, message}, retryAfterSeconds}`. The watch refuses (in this order) a broken offer (`bad_request`), one over 64 MiB (`too_large`), a version not higher than its own (`not_newer`), no "Install unknown apps" (`no_install_permission`), a battery under 20 % without charging (`low_battery`), too little free space (`storage`), another update running (`busy`), and a second install within Android's 30 seconds (35 s with margin; `throttled`, with `retryAfterSeconds`). Throttled, busy and low battery aren't counted as failures.
- **The transfer** (`/lifeos/apk-transfer`, `ChannelClient`, `ApkTransferCodec`): a header frame (`u32` length, then `ApkTransferHeader {v, id, versionCode, size, sha256}` as JSON, which must match the accepted offer, valid for 10 minutes), then chunks (`i32` length and that many bytes, at most 256 KiB each; the phone sends 64 KiB), then `0` for the end, or `-1` when the phone gives up. The watch writes the bytes to its private storage and hashes them as they arrive: more bytes than announced stop the transfer at once, fewer or another SHA-256 are refused at the end, and the partial file is deleted. Its answer is one frame, `ApkTransferResult {v, id, received, error}`.
- **The watch's checks** before installing (`ApkRules`, the same on the phone): the archive must be `com.gamch1k.lifeos`, signed by exactly the certificates the installed app is signed with, at the announced versionCode, and higher than its own (no downgrade, no reinstall). Android checks the signature again when it installs.
- **The install**: a `PackageInstaller` session with `setRequireUserAction(USER_ACTION_NOT_REQUIRED)`. It is silent because the app updates itself, declares `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, holds `REQUEST_INSTALL_PACKAGES` with "Install unknown apps" allowed, and targets API 36 (Android 16 wants 34 or higher). The watch remembers when it committed, so it never starts a second install within 30 seconds.
- **When Android asks anyway** (`STATUS_PENDING_USER_ACTION`): the confirmation opens at once if Life OS is on the watch's screen; otherwise a notification **Life OS x.y is ready · Tap to install** opens it, and Home shows **Install update**, which commits the stored APK again with the app on screen.
- **After the update** Android restarts the app (`MY_PACKAGE_REPLACED`). On its next start the new version records "installed", deletes the APK and sends a `WatchInfo` to the phone on `/lifeos/watch-report` (one-way message); a failure, a refusal or a waiting confirmation is reported at once. Until the phone has it, the watch sends it again whenever the phone comes into reach.
- **What the owner sees**: on the phone, Settings › Watch shows the watch app's version and when it was last seen, the last update's result, what the phone is doing (downloading, sending, waiting and why), **Update now**, and the watch's ABIs; on the watch, Connection shows the last update or that the setup's permission is missing.

### The watch's ABIs

The wear APK carries embedded Cronet for `arm64-v8a` and `armeabi-v7a` (11 MB of its 15 MB). The setup records `ro.product.cpu.abilist`, and every `WatchInfo` carries `Build.SUPPORTED_ABIS`. Settings › Watch then says which single ABI a watch-only APK would need (`WatchAbi.smallestApkAbi`): `arm64-v8a` when the list has it, else `armeabi-v7a`, else none (keep both). Nothing changes on its own: dropping an ABI is a change to `wear/build.gradle.kts` (`ndk.abiFilters`), made once the owner has seen the list.

## Setting up the watch (the owner)

1. **Install** the watch app from the phone: Settings › Watch › **Set up the watch app** ([Install and updates](#install-and-updates)). Updates then come through the phone by themselves. (By hand, with a computer, `adb install -r lifeos-wear-<version>.apk` over Wireless debugging still works, but then "Install unknown apps" must be allowed with `adb shell appops set com.gamch1k.lifeos REQUEST_INSTALL_PACKAGES allow` for silent updates.)
2. **Token**: the phone app must hold its service token, entered at its first run or under Settings → **Change token**; the watch needs none of its own. Open **Life OS** on the watch once; it asks the phone by itself. **Connection** shows "Token: From the phone, <time>"; if not, tap **Get token from phone**, and **Test connection** to see which way Life OS answers.
3. **Microphone**: the first recording asks for it. After "Don't allow", the recording screen offers **Open settings**.
4. **Home key**: Settings › Advanced features › Customize buttons (Customize keys on older One UI Watch versions) › Home key › Double press › choose **Capture**. "Capture" is the app's second entry in the app list and opens straight into recording; choosing "Life OS" opens Home instead.
5. **Tiles**: from the watch face, swipe left to the end of the tiles, tap **+**, and add **Life OS Capture** and/or **Life OS Today**. The Galaxy Wearable app on the phone (Tiles) does the same.
6. **Complication**: touch and hold the watch face › Customize › Complications, pick a slot, then **Life OS next event**. Short-text slots show the time and title, long ones `14:30 Dentist`, small round ones a microphone. Some Samsung watch faces offer no slots for other apps.
7. The watch's ABIs: the setup reads them; Settings › Watch shows them and which single ABI a smaller watch APK would need ([The watch's ABIs](#the-watchs-abis)). The APK carries both until the owner decides.

## Tests and build

- `tools/build test` runs every module's unit tests, including:
  - `core/…/wearlink/`: the frame codec (round trips, limits, truncation, versions, wire names), the relay policy, the phone's handler (credentials, relayed reads and writes, error codes, the channel, too-large replies, the replay cache, which replays successes only);
  - `core/…/capture/`: the phone's queue, including Retry: a new job attempt after a failed Voice job or a 409, the same key otherwise;
  - `wear/…/net/`: the watch's relay against the phone's real handler, end to end, and the routing rules;
  - `wear/…/capture/`: the queue's state machine and idempotency (job attempts, their keys and metadata, Retry), and the uploader against a fake Life OS (the multipart body, polling to In Inbox, waiting, backoff, refusals, a refused token, a restart, Retry after a failed job, a 409 or a lost reply);
  - `wear/…/today/`: parsing, the date-only rule, the tile's rows, the timeline change times, the complication's timeline, and the repository's queries and cache;
  - `core/…/update/`: the wear entry of the manifest, the phone's push rules (version, battery, the wait for a report, backoff, Update now, a waiting confirmation), the watch's offer rules (newer only, permission, battery, space, busy, 30 seconds), the APK checks (package, certificate, announced version, downgrade), and the ABI advice;
  - `core/…/wearlink/WatchUpdateProtocolTest`: the update messages (round trips, wire names, a 0.3 watch's answer, unknown codes, versions), and the transfer (chunks, progress, SHA-256, too many and too few bytes, the sender's abort, a stream cut mid-chunk, the header's limits);
  - `core/…/watchsetup/`: the bootstrap's state machine with ADB faked (the whole run, a failed download, typos, no Wi-Fi, wrong code, an expired pairing, the wrong network, no mDNS, a changed port, a lost pairing, a rescue without pairing, a refused install and Try again, a newer version on the watch, a refused permission, optional steps, a broken connection, a watch app that answers late), and the parsing of addresses, codes, the subnet and the shell's answers.
- `tools/build` builds the signed `lifeos-wear-<version>.apk` (about 15 MB: embedded Cronet's native code for two ABIs is 11 MB of it).

Not tested automatically: the Data Layer between the real phone and watch, recording on the watch's microphone, the tile and complication rendering, and everything of P19-06 that needs the devices: pairing and installing over the watch's Wireless debugging (libadb-android against One UI Watch's adbd), mDNS discovery, `appops` on One UI Watch, a channel transfer of 15 MB over Bluetooth, and whether the silent self-update really stays silent on the Galaxy Watch 8. P19-05 is the acceptance on the watch.
