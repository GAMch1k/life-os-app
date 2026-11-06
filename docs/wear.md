# The Wear OS app (Phase 19)

The watch app for the owner's Galaxy Watch 8 (One UI 8 Watch, Wear OS 6). First version:

- **voice capture**: tap a tile, a complication, the app, or double-press the Home key; speak; tap Send. The recording goes to Life OS Voice and comes back as an Inbox item;
- **a "Today" tile, and two complications**: "Life OS next" (the next event or task) and "Life OS capture" (a microphone) ([Tiles and complications](#tiles-and-complications));
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
| `wear/…/tile/`, `wear/…/complication/` | The Capture and Today tiles; the "Life OS next" and "Life OS capture" complications. |
| `wear/…/ui/` | The app's screens (Compose for Wear OS Material 3). |
| `wear/…/update/` | The watch's end of updates through the phone: version answer, offer, its own download over Wi-Fi, status, transfer, checks, install, result; the foreground service and wake lock while an update arrives. |
| `core/…/update/WatchUpdateRules.kt`, `ApkRules.kt`, `PartialApk.kt`, `TransferProgress.kt` | When the phone pushes, what the watch accepts, the APK checks both sides run, the watch's partly received APK, and the progress numbers (speed, ETA) both sides show. |
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
- **The Today tile** and **"Life OS next"** read only the cache, never the network ([Tiles and complications](#tiles-and-complications)).

## Tiles and complications

### The Today tile

- **Content**: the rows of the rules above. Tapping a task opens the app's **Mark done?** confirmation; tapping an event opens Today with that event outlined; the edge button records. With nothing cached: "Open Life OS to load Today" (and the last refresh's problem under it); with an empty day: "Nothing left today".
- **Fast**: the tile is built from `files/today.json` at once. Data older than 10 minutes starts a refresh in the background (WorkManager); when it is done, the tile and the complication are asked to redraw (`WearGraph.updateSurfaces`).
- **Timeline** (`TodayTilePlan`): one entry per stretch between the event starts and ends of the next 12 hours, each with a start and an end, the first reaching back to the epoch; then a **default entry without validity**, with the state after the last change. So the renderer always has an entry for "now", and a past event leaves the tile without a refresh. The tile is asked for again every 30 minutes while in use.
- **Never black, never throws**: any failure while building gives a single-entry tile, "Today couldn't be shown. Tap to open Life OS.", asked for again after 5 minutes; if Material 3 fails there too, plain text. Renderers older than schema 1.200 (or not reporting one) get a plain-text layout without images; Material 3 adapts itself above that.
- **Why 0.4.1 was black**: its last timeline entry (the only one when no event started or ended in the next 12 hours: no cache yet, an empty day, a day of tasks only, the evening) had a start and no end. On the wire an unset end is 0, so the entry is never valid, and the renderer's "closest entry" fallback skips invalid intervals too: nothing to show, a black tile. With events ahead it rendered until the last event start or end in its 12-hour window, then went black until the next request. The Capture tile has a single entry without validity, so it always worked.

### "Life OS next" (complication)

`NextEventComplicationService` (the 0.4 class name, so watch faces that use it keep it), rules in `NextUp`:

- **What**: the next timed event that hasn't started (today or tomorrow), with a calendar icon; else the first task due (overdue first, then by due time), showing its due time, "Today" or "Overdue", with a check icon; else a microphone ("Free").

| Type | Event | Task | Nothing ahead |
|---|---|---|---|
| `SHORT_TEXT` | `14:30`, title `Dentist` (`Tue Flight` tomorrow) | `Today`, title `Pay rent` | `Free`, microphone |
| `LONG_TEXT` | `14:30 Dentist` under "Next" or "Tomorrow" | `Pay rent` under `Today` | `Nothing ahead`, microphone |
| `MONOCHROMATIC_IMAGE`, `SMALL_IMAGE` | calendar | check | microphone |

- **Text only, no arc** (P19-07, 0.4.3): it offers `SHORT_TEXT,LONG_TEXT,MONOCHROMATIC_IMAGE,SMALL_IMAGE`, short text first. 0.4.2 also offered `RANGED_VALUE` and `GOAL_PROGRESS` (an arc filling over the hour before an event); Samsung's edge slots take those first when offered and drew an arc or a line around the text, so they are gone. A watch face that picked one of them for a slot under 0.4.2 may show the slot empty after the update: **select "Life OS next" for that slot again** (touch and hold the face › Customize › Complications) and it comes back as text.
- **Text**: titles are cut to 10 characters in the small slots and 40 in the long one; the content description has the whole title and time ("Next: Dentist at 14:30", "Pay rent, overdue").
- **Tap**: an event or a task opens Today with it outlined and scrolled to; the microphone records.
- **Timeline**: from the cache, one entry per event (at most four) until it starts; then the default, the state after the last of them. The watch face switches by itself, without waking the app. Every Today refresh asks for an update; the system asks every 30 minutes as a safety net.

### "Life OS capture" (complication)

`CaptureComplicationService`: a microphone that starts a recording, as monochromatic or small image, or short text ("Rec" under the microphone). Added because "Life OS next" now shows a calendar or a check in image slots too (0.4 always showed a microphone there): put "Life OS next" on an edge slot and "Life OS capture" on a small one to have both. It never changes, so the system never asks again.

## Notifications (P19-04)

The phone app's notifications reach the watch by Android's own bridging. The watch app posts nothing for Life OS items, so nothing shows twice; its manifest keeps bridging on explicitly (`com.google.android.wearable.notificationBridgeMode` = `BRIDGING`). The only notification the watch app posts is its own "Recording" notice while it records, which stays on the watch.

What the phone app (P18-05) sets on each Life OS notification (`phone/…/push/LifeOsNotifications.kt`):

- **Don't** call `setLocalOnly(true)`: that stops bridging. Use it only for the phone's own technical notifications (update downloads, foreground services).
- **Dismissal id**: `NotificationCompat.WearableExtender().setDismissalId("lifeos:<notification id>")`, with the Life OS notification id, so dismissing it on either device dismisses it on both.
- **Bridge tag**: `WearableExtender().setBridgeTag("lifeos.<category>")`, for example `lifeos.tasks.due` or `lifeos.calendar.reminder`. Nothing is excluded today; if the watch app ever posts its own notification for a category, it can exclude that tag with `BridgingManager` and `BridgingConfig.addExcludedTags` instead of showing it twice.
- **Done and Snooze** as normal actions (`addAction`) whose `PendingIntent`s are broadcasts to the phone's `NotificationActionReceiver`, marked as opening nothing (`setShowsUserInterface(false)`). Bridged actions run on the phone, which has the token and the ECH-capable client. Done is on notifications about a Task; Snooze (0.4.4) on every one but the summaries ([README](../README.md#share-push-widgets-030)). The content intent (open the app) works only on the phone.
- **Done** cancels the phone's notification at once, which removes the watch's copy, and the Done job calls `WearLinkPhone.notifyTodayChanged(context)` once the Task is completed, so the Today tile drops it.
- **Snooze** cancels the phone's notification the same way, so the watch's copy goes with it; the notification that comes back later (same payload, tag, dismissal id and actions, "Snoozed" as its sub-text) is bridged again like a new one. Snooze changes nothing in Life OS, so it pings nothing.
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
4. **Pings**: call `WearLinkPhone.notifyCredentialsChanged(context)` after the token changes, and `WearLinkPhone.notifyTodayChanged(context)` when tasks or events change (a push arrived, a Done). Both are no-ops without a watch.

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
   - takes the watch app off the battery optimisations (`cmd deviceidle whitelist +com.gamch1k.lifeos`, since 0.4.3), so it may start its update's foreground service from the background ([Updates through the phone](#updates-through-the-phone)); optional, like the notifications;
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
  participant L as Life OS /android/
  participant W as Watch app
  P->>W: /lifeos/watch-info (request)
  W-->>P: version, API level, ABIs, capabilities, install permission, battery, last result
  P->>L: manifest; apps.wear newer?
  P->>L: download the wear APK (resumable), check size, SHA-256, package, version, release key
  P->>W: /lifeos/apk-offer (version, size, SHA-256; v2: transfer 2, the APK's path)
  W-->>P: accepted (v2: bytes already held, own download or not), or why not
  alt v2, the watch downloads it itself
    W->>L: Wi-Fi network requested; GET the APK with the watch's token (Range: what it holds)
    P->>W: /lifeos/apk-status every 2 s
    W-->>P: waiting for Wi-Fi, downloading (bytes, speed), verifying, installing, or needs the stream from offset N
  end
  P->>W: /lifeos/apk-transfer channel: header (v2: offset), chunks, end
  W-->>P: received (or refused, and why)
  W->>W: PackageInstaller, USER_ACTION_NOT_REQUIRED
  W->>P: /lifeos/watch-report on its next start (installed, failed, waiting)
```

- **When the phone checks**: once a day on an unmetered network (`UpdateWorker`, before the phone's own update, which may end the process), when the phone app opens (at most every 6 hours), when the watch app reports in (after an install, at most once a minute), and on **Settings › Watch › Update now**. Automatic checks download the APK (5.9 MB since 0.4.5) only on an unmetered network; Update now downloads on any.
- **The version question** (`/lifeos/watch-info`, `MessageClient` request): `WatchInfoRequest {v, id}` → `WatchInfo {v, id, versionCode, versionName, sdkInt, abis, capabilities, canInstallPackages, battery {percent, charging}, lastUpdate}`. The capability `apk_transfer_v1` says the watch takes updates, `apk_transfer_v2` (0.4.3) that it speaks transfer v2; a watch app older than 0.4.0 doesn't answer at all, and the phone says the setup is needed.
- **The phone's rules** (`WatchUpdateRules.plan`): push only a published `apps.wear` with a higher versionCode, a usable hash and URL, and a `minSdk` the watch meets; never to a watch whose ABIs don't include `armeabi-v7a` (`arm64-v8a` in 0.4.3 and 0.4.4; [The watch's ABIs](#the-watchs-abis)); never while the watch reports less than 20 % battery without charging; after a push, wait 10 minutes for the watch's report; after a failure, back off 30 minutes, then 2, 8 and 24 hours (a new version starts over; an install that failed on the watch counts too); a confirmation the watch waits for is not pushed again. **Update now** skips the backoff and the wait, but never the battery rule or 30 seconds between two installs.
- **The offer** (`/lifeos/apk-offer`): `ApkOffer {v, id, versionCode, versionName, size, sha256, transfer, url}` → `ApkOfferReply {v, id, accepted, error {code, message}, retryAfterSeconds, transfer, offset, download}`. The watch refuses (in this order) a broken offer (`bad_request`), one over 64 MiB (`too_large`), a version not higher than its own (`not_newer`), no "Install unknown apps" (`no_install_permission`), a battery under 20 % without charging (`low_battery`), too little free space (`storage`), another update running (`busy`, retry after 10 s; the phone asks up to three more times), and a second install within Android's 30 seconds (35 s with margin; `throttled`, with `retryAfterSeconds`). Throttled, busy and low battery aren't counted as failures.
- **Transfer v1 and v2** (`TransferVersion`). `v` stays 1: v2 only adds fields, whose defaults are v1's meaning, and both sides ignore keys they don't know. The phone offers `transfer: 2` and the watch answers the lower of that and its own: a 0.4.0–0.4.2 watch answers without the field (v1), and a 0.4.3 watch answers a 0.4.0–0.4.2 phone with v1. In v1 the phone streams the whole APK from byte 0, once per push; in v2 (`PushPath.after`):
  - the reply's `offset` is how many bytes of this APK (same SHA-256 and size) the watch holds from an earlier, broken attempt, written and synced (`PartialApk` in `filesDir/updates-partial`: the bytes plus a small record of whose they are and how many are durable, synced every 256 KiB). The stream starts there, and the SHA-256 of the whole file is checked at the end; a wrong one drops everything, and the next offer says 0;
  - with `download: true` the watch first downloads the APK itself over Wi-Fi (below), and the phone follows it on `/lifeos/apk-status`;
  - a broken stream, a lost download, or a watch that holds fewer bytes than the stream assumed (`aborted`) gets a new offer, which resumes; up to 4 breaks per push. A watch that forgot the offer (it restarted) answers `unknown`.
- **The watch's own download** (v2, `DirectDownload`, `WifiDownload.kt`). The offer carries the APK's path (`apps.wear.url`, such as `/android/lifeos-wear-0.4.3-10.apk`); with it, a complete token and bytes missing, the watch asks Android for a Wi-Fi network with internet (`ConnectivityManager.requestNetwork`, `TRANSPORT_WIFI`), as Google's Wear OS network guidance describes: while the phone is connected over Bluetooth, the watch's default network is the phone's proxy, and Wear OS brings Wi-Fi up only for an app that asks. If no network comes within 45 s, or the download fails (no byte for 30 s, a refused token, a 404, more than 10 minutes), the watch answers `needs_stream` with the bytes it holds and why (`no_wifi`, `download_failed`), and the phone streams from there; it tries Wi-Fi once per push. The download uses embedded Cronet bound to that network (`UrlRequest.Builder.bindToNetwork`, not the whole process, so the relay keeps its route), the watch's own token, `Range: bytes=<held>-` to resume, and writes the same partial file as the stream, so either continues where the other stopped. A network-bound request may resolve the hostname through that network's resolver without the HTTPS record, so without ECH: on the work Wi-Fi it fails and the phone's stream takes over. The network request is released at once afterwards.
- **The status** (`/lifeos/apk-status`, v2): `ApkStatusRequest {v, id}` → `ApkStatus {v, id, state, route, received, size, bytesPerSecond, offset, error}`, with `state` one of `waiting_for_wifi`, `downloading`, `needs_stream`, `receiving`, `verifying`, `installing`, `failed`, `unknown`. The phone asks every 2 s while the watch downloads; it gives the download up when the state stops changing for 3 minutes or the watch doesn't answer five times. On the Wi-Fi route the watch waits up to 5 s for the phone to hear `installing` before it commits (the commit ends its process).
- **The transfer** (`/lifeos/apk-transfer`, `ChannelClient`, `ApkTransferCodec`): a header frame (`u32` length, then `ApkTransferHeader {v, id, versionCode, size, sha256, offset}` as JSON, which must match the accepted offer, valid for 10 minutes), then chunks (`i32` length and that many bytes, at most 256 KiB each; the phone sends 64 KiB, length and bytes in one write), then `0` for the end, or `-1` when the phone gives up. The chunks carry the bytes from `offset` (0 in v1) to the end. The watch writes them to its private storage and hashes them as they arrive: more bytes than announced stop the transfer at once, fewer or another SHA-256 are refused at the end and the partial file is deleted; a stream that breaks off keeps what arrived (v2 resumes it, v1's next push starts over). Its answer is one frame, `ApkTransferResult {v, id, received, error}`.
- **No fixed time limit** (0.4.3). 0.4.2's phone closed the channel after a fixed 15 minutes: at about 17 KB/s over Bluetooth that was 91–95 % of its 16.6 MB APK ("channel closed unexpectedly" on the watch). Now the phone cuts a stream only when no byte moved for 2 minutes (`STREAM_IDLE`), when the watch's verdict doesn't come within 5 minutes of the last byte, or after `streamCap` (20 minutes, or the time at 4 KB/s, whichever is longer: about 24.5 minutes for 0.4.5's 5.9 MB). The 5.9 MB APK takes about 6 minutes at 17 KB/s.
- **Foreground and awake** (0.4.3). On the phone, the push (download, checks, offer, the watch's download or the stream) runs in `WatchUpdateService`, a foreground service of type `connectedDevice` with a progress notification ("Updating the watch: 43% · 2.3 of 5.4 MB · about 2 min left", **Cancel**, local only, so it isn't bridged to the watch) and a partial wake lock capped at 60 minutes; neither leaving the app nor WorkManager's 10 minutes ends it. Where Android refuses a background start (the daily job, a report from the watch while the app is closed), the push runs where the check runs, as before. On the watch, every part that must not be cut off (waiting up to 2 minutes for the stream, the download, the stream, the checks, the install) holds `UpdateKeepAlive`: a partial wake lock (capped at 60 minutes) and `WatchUpdateService`, a foreground service of type `dataSync` with an ongoing notification ("Over Wi-Fi: 43% · 2.3 of 5.4 MB · 2.1 MB/s · less than 10 s left"). It starts inside the Data Layer callbacks (the offer, the channel). Android 12+ refuses foreground services started from the background except in listed cases; one is an app off the battery optimisations, which the setup does (`cmd deviceidle whitelist`); where it is refused anyway, the wake lock still holds and a cut stream resumes.
- **The watch's checks** before installing (`ApkRules`, the same on the phone): the archive must be `com.gamch1k.lifeos`, signed by exactly the certificates the installed app is signed with, at the announced versionCode, and higher than its own (no downgrade, no reinstall). Android checks the signature again when it installs.
- **The install**: a `PackageInstaller` session with `setRequireUserAction(USER_ACTION_NOT_REQUIRED)`. It is silent because the app updates itself, declares `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, holds `REQUEST_INSTALL_PACKAGES` with "Install unknown apps" allowed, and targets API 36 (Android 16 wants 34 or higher). The watch remembers when it committed, so it never starts a second install within 30 seconds.
- **When Android asks anyway** (`STATUS_PENDING_USER_ACTION`): the confirmation opens at once if Life OS is on the watch's screen; otherwise a notification **Life OS x.y is ready · Tap to install** opens it, and Home shows **Install update**, which commits the stored APK again with the app on screen.
- **After the update** Android restarts the app (`MY_PACKAGE_REPLACED`). On its next start the new version records "installed", deletes the APK and any partial file, and sends a `WatchInfo` to the phone on `/lifeos/watch-report` (one-way message); a failure, a refusal or a waiting confirmation is reported at once. Until the phone has it, the watch sends it again whenever the phone comes into reach.
- **What the owner sees**: on the phone, Settings › Watch shows the watch app's version and when it was last seen, the last update's result, what the phone is doing and how (over Wi-Fi, or through the phone over Bluetooth), the percentage, megabytes, speed and time left, another try and why, **Cancel** while it runs (the watch keeps what it received), **Update now**, and the watch's ABIs; on the watch, Connection shows the update arriving (the route and the same numbers), the last update, or that the setup's permission is missing, and the ongoing notification shows the progress.

**From 0.4.1 or 0.4.2 to 0.4.3 and later.** Those watches have the v1 receiver and no partial file. A 0.4.3 or later phone offers `transfer: 2` with the APK's path; the old watch ignores both and accepts as before, its reply reads as v1, and the phone streams the whole APK (5.9 MB since 0.4.5) from byte 0 in its foreground service, cut only by the timeouts above, about 6 minutes at Bluetooth's 17 KB/s. A break means another try on the next check (v1 has no resume). Once 0.4.3 or later runs on the watch, later updates use v2: Wi-Fi first, then the resumable stream. The Galaxy Watch 8 stayed on 0.4.2 through 0.4.3 and 0.4.4, which it can't run ([The watch's ABIs](#the-watchs-abis), BUG-45); 0.4.5 takes this path once the phone itself runs 0.4.5.

### The watch's ABIs

The wear APK carries embedded Cronet's native code for one ABI only (`ndk.abiFilters` in `wear/build.gradle.kts`; `WatchAbi.APK_ABIS` in core says the same, and `WearApkAbiTest` holds the two equal through `BuildConfig.APK_ABIS`), and stores its native libraries and dex compressed (`packaging.jniLibs` and `packaging.dex`, `useLegacyPackaging`; extracted at install). Since 0.4.5 that ABI is **`armeabi-v7a`**: 5.9 MB (Cronet 2.8 MB, 4.4 MB once extracted; classes.dex 1.8 MB; the uncompressed resource table 0.8 MB). 0.4.2 carried `arm64-v8a` and `armeabi-v7a`, both stored uncompressed (16.6 MB); 0.4.3 and 0.4.4 `arm64-v8a` only (6.6 MB).

**The Galaxy Watch 8 runs 32-bit apps only** (BUG-45). Wear OS 6 on it reports `armeabi-v7a, armeabi` (`ro.product.cpu.abilist`, `Build.SUPPORTED_ABIS`), so Android there refuses an APK whose native code is 64-bit only (`INSTALL_FAILED_NO_MATCHING_ABIS`). 0.4.3 and 0.4.4 were built for `arm64-v8a` only, on the wrong belief that it runs a 64-bit user space: the phone's check below refused to send them, Settings › Watch said why ("…built for arm64-v8a only and the watch reports armeabi-v7a, armeabi; Android would refuse it…"), and the watch stayed on 0.4.2. 0.4.5 carries `armeabi-v7a`, which that watch runs, as does a 64-bit watch that runs 32-bit apps too (`arm64-v8a, armeabi-v7a, armeabi`). A watch without 32-bit support (`arm64-v8a` only) would need `arm64-v8a` added in both places.

- The setup records `ro.product.cpu.abilist`, and every `WatchInfo` carries `Build.SUPPORTED_ABIS`; Settings › Watch and the setup's last screen show them and whether the watch runs the APK.
- The phone never pushes to a watch whose list lacks `armeabi-v7a` (`WatchUpdateRules.plan`: "the watch app is built for armeabi-v7a only and the watch reports …; Android would refuse it (INSTALL_FAILED_NO_MATCHING_ABIS)"); an empty list (not known yet) gets the benefit of the doubt.
- The phone decides with the `WatchAbi.APK_ABIS` it was built with, not by looking into the published APK. So a 0.4.4 phone still refuses the 0.4.5 wear APK for the Galaxy Watch 8: the phone updates itself to 0.4.5 first, and its next check (or **Update now**) sends the watch app.
- The release build fails before anything goes into `dist/` when the wear APK has no `lib/armeabi-v7a/` or the phone APK no `lib/arm64-v8a/` (`tools/builder/check-apk-abi`, run by `tools/builder/in-container`).

## Setting up the watch (the owner)

1. **Install** the watch app from the phone: Settings › Watch › **Set up the watch app** ([Install and updates](#install-and-updates)). Updates then come through the phone by themselves. (By hand, with a computer, `adb install -r lifeos-wear-<version>.apk` over Wireless debugging still works, but then "Install unknown apps" must be allowed with `adb shell appops set com.gamch1k.lifeos REQUEST_INSTALL_PACKAGES allow` for silent updates.)
2. **Token**: the phone app must hold its service token, entered at its first run or under Settings → **Change token**; the watch needs none of its own. Open **Life OS** on the watch once; it asks the phone by itself. **Connection** shows "Token: From the phone, <time>"; if not, tap **Get token from phone**, and **Test connection** to see which way Life OS answers.
3. **Microphone**: the first recording asks for it. After "Don't allow", the recording screen offers **Open settings**.
4. **Home key**: Settings › Advanced features › Customize buttons (Customize keys on older One UI Watch versions) › Home key › Double press › choose **Capture**. "Capture" is the app's second entry in the app list and opens straight into recording; choosing "Life OS" opens Home instead.
5. **Tiles**: from the watch face, swipe left to the end of the tiles, tap **+**, and add **Life OS Capture** and/or **Life OS Today**. The Galaxy Wearable app on the phone (Tiles) does the same. After an update a tile may keep its old look until it is shown again; if Today stays empty, swipe away and back.
6. **Complications** (Galaxy Watch): touch and hold the watch face › **Customize** › swipe to **Complications** › tap the slot (an edge slot for the next event) › **Life OS next**; for a capture button, another slot › **Life OS capture**. Press the Home key to save. The Galaxy Wearable app on the phone (Watch faces › Customize) does the same. Edge slots and round ones show the time and title as text (or an icon, where a slot takes only images), long ones `14:30 Dentist` ([Tiles and complications](#tiles-and-complications)). After the 0.4.3 update, a slot that showed an arc under 0.4.2 may need **Life OS next** selected again. Faces that used "Life OS next event" from 0.4 keep the slot, now named "Life OS next". Some Samsung watch faces offer no slots for other apps.
7. The watch's ABIs: the setup reads them, and Settings › Watch shows them and whether the watch runs the APK, which carries `armeabi-v7a` code only since 0.4.5 ([The watch's ABIs](#the-watchs-abis)). The Galaxy Watch 8 shows `armeabi-v7a, armeabi`: it runs 32-bit apps only.
8. **Wi-Fi on Auto** (for fast updates, 0.4.3): on the watch, Settings › Connections › Wi-Fi: set Wi-Fi to **Auto** (not Off) and save your home network on the watch. Then the watch downloads an update itself over Wi-Fi, much faster; otherwise it comes through the phone over Bluetooth, about 6 minutes for 5.9 MB. The watch turns Wi-Fi on only for the download and lets it go afterwards.

## Tests and build

- `tools/build test` runs every module's unit tests, including:
  - `core/…/push/`: the push payload (fields, Task links, which notifications get Snooze) and Snooze (`SnoozeTest`: the alarm at the earliest snooze and moving on, the lengths, nothing early, several due together, snoozing again, Done by tag and by Task, Home's completion, shown before forgotten, the store outliving the process and a reboot, late ones after a force stop, sign-out, a damaged store; "tomorrow at 09:00" across both DST changes in Berlin, in New York, Sydney, Kolkata, Kiritimati and UTC, after midnight, in a DST gap and overlap, and every 47 minutes of 2026 in six zones);
  - `core/…/wearlink/`: the frame codec (round trips, limits, truncation, versions, wire names), the relay policy, the phone's handler (credentials, relayed reads and writes, error codes, the channel, too-large replies, the replay cache, which replays successes only);
  - `core/…/capture/`: the phone's queue, including Retry: a new job attempt after a failed Voice job or a 409, the same key otherwise;
  - `wear/…/net/`: the watch's relay against the phone's real handler, end to end, and the routing rules;
  - `wear/…/capture/`: the queue's state machine and idempotency (job attempts, their keys and metadata, Retry), and the uploader against a fake Life OS (the multipart body, polling to In Inbox, waiting, backoff, refusals, a refused token, a restart, Retry after a failed job, a 409 or a lost reply);
  - `wear/…/today/`: parsing, the date-only rule, the tile's rows, the timeline change times, the repository's queries and cache; the Today tile's plan from no cache, an empty day, a day whose events are over, yesterday's data and a full day (one default entry, closed timed entries, something shown at every minute for 36 hours, and the content the rules give for each minute), and 0.4.1's open-ended entry that was never shown; "Life OS next": what comes next (event, task, nothing), every type's fields for each, the timeline switching at each start, and the cap at four events; in `wear/…/complication/`, that the manifest offers it as text and images only, short text first;
  - `wear/…/tile/`: the real ProtoLayout timeline of the Today tile, read back with the tile renderer's rule;
  - `core/…/update/`: the wear entry of the manifest, the phone's push rules (version, battery, the wait for a report, backoff, Update now, a waiting confirmation, no push to a watch without `armeabi-v7a`, and a push to the Galaxy Watch 8's `armeabi-v7a, armeabi`), the stream's time limits, the watch's offer rules (newer only, permission, battery, space, busy, 30 seconds), the APK checks (package, certificate, announced version, downgrade), and the ABI list; the partial APK (`PartialApkTest`: held bytes, checkpoints after a crash, resume to the right SHA-256, another APK's bytes, a damaged complete file and a damaged prefix back to byte 0, a missing or unreadable record); progress and ETA (`TransferProgressTest`: percent, rounding, the words, the sliding-window speed, a resumed start, a stall);
  - `core/…/wearlink/WatchUpdateProtocolTest`: the update messages (round trips, wire names of v1 and v2, a 0.3 watch's answer, unknown codes and states, versions), the v1/v2 negotiation (a 0.4.2 watch reading a 0.4.3 phone's offer and header and its reply meaning a whole stream, a 0.4.3 watch reading a 0.4.2 phone's, the lower version, the phone's path after each reply), and the transfer (chunks, progress, SHA-256, too many and too few bytes, the sender's abort, a stream cut mid-chunk, the header's limits, a resumed stream from an offset, a wrong prefix, an offset at the end or outside);
  - `wear/…/update/WearApkAbiTest`: the ABI filter the wear APK is built with (`BuildConfig.APK_ABIS`) is the one the phone checks the watch against (`WatchAbi.APK_ABIS`), and the Galaxy Watch 8 runs it (BUG-45);
  - `wear/…/update/DirectDownloadTest`: the watch's own download with a fake Wi-Fi and a fake Life OS: when it is tried at all, no Wi-Fi in time, a download on the requested network with the watch's token, the network given back every time, a broken download resumed with a range, the stream continuing what the download left, a server ignoring the range, refused tokens and missing files, a wrong SHA-256 and too many or too few bytes;
  - `core/…/watchsetup/`: the bootstrap's state machine with ADB faked (the whole run, a failed download, typos, no Wi-Fi, wrong code, an expired pairing, the wrong network, no mDNS, a changed port, a lost pairing, a rescue without pairing, a refused install and Try again, the ABI named when the install is refused for it, a newer version on the watch, a refused permission, optional steps, a broken connection, a watch app that answers late), and the parsing of addresses, codes, the subnet and the shell's answers.
- `tools/build` builds the signed `lifeos-wear-<version>.apk` (about 5.9 MB since 0.4.5: embedded Cronet for `armeabi-v7a` only, native code and dex compressed; 0.4.3's and 0.4.4's, `arm64-v8a` only, were 6.6 MB, 0.4.2's 16.6 MB), and fails before anything goes into `dist/` when the wear APK has no `lib/armeabi-v7a/` or the phone APK no `lib/arm64-v8a/` (`tools/builder/check-apk-abi`, BUG-45).

Not tested automatically: the Data Layer between the real phone and watch, Done and Snooze tapped on the watch's bridged notification (P18-05), the snooze alarm's timing in Doze, recording on the watch's microphone, the tile and complication rendering, and everything of P19-06 that needs the devices: pairing and installing over the watch's Wireless debugging (libadb-android against One UI Watch's adbd), mDNS discovery, `appops` on One UI Watch, a channel transfer over Bluetooth (0.4.2's 16.6 MB broke at 91 % and 95 %; 0.4.3 and 0.4.4 never reached the Galaxy Watch 8 (BUG-45); 0.4.5's 5.9 MB v1 stream and v2's resume are untested on the devices), the watch's Wi-Fi request and the network-bound download, whether One UI Watch lets the update's foreground services start from the Data Layer's callbacks, and whether the silent self-update really stays silent on the Galaxy Watch 8. P19-05 is the acceptance on the watch.
