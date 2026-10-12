# The Wear OS app (Phase 19)

The watch app for the owner's Galaxy Watch 8 (One UI 8 Watch, Wear OS 6). First version:

- **voice capture**: tap a tile, the complication, the app, or double-press the Home key; speak; tap Send. The recording goes to Life OS Voice and comes back as an Inbox item;
- **a "Today" tile and a next-event complication**;
- **notifications with Done and Snooze**, bridged from the phone app (P18-05). The watch app adds nothing of its own there.

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

Voice has no `Idempotency-Key` today (the Life OS repository's `services/voice/internal/api/handler.go` doesn't read one), so the watch does what it can:

- every upload carries the capture's id, made when recording starts, as `Idempotency-Key` and as `sourceMetadata.captureId`;
- a capture with a Voice job is never uploaded again; each upload is claimed under the queue's lock; an outcome for a capture that moved on is ignored;
- the phone keeps the reply of every write with a key for 15 minutes (`RelayReplayCache`). If the reply to the watch got lost, the watch's retry gets the stored reply and no second job is created. A retry while the first try still runs waits for it.

What's left: a reply lost on the **direct** path, or across a phone restart, can still create a second job. The fix belongs in Voice: honour `Idempotency-Key` as Tasks does (store the key with the job; the same key returns the same job). The watch already sends it. Completing a task sends one key per tap (`complete:<uuid>`), so a lost reply is replayed while a later tap on a reopened task still completes it.

## Voice capture

- **Recording** (`RecordingService`): Ogg/Opus, mono, 16 kHz, 24 kbit/s, which Voice accepts as is; MP4/AAC if the watch can't encode Opus. At most five minutes, then it sends by itself; under 0.8 s nothing is sent. A foreground service of type microphone, so the recording goes on when the screen dims; the screen stays on while the recording screen shows. Its "Recording" notice appears only if notifications are allowed for Life OS (the app doesn't ask); recording works either way.
- **Upload**: `POST /api/voice/v1/audio-jobs`, multipart with `audio` (`<capture id>.ogg`, `audio/ogg`), `source=watch` and `sourceMetadata={"via":"wear-app","timeZone":"Europe/Berlin","captureId":…,"recordedAt":…,"durationMs":…}`.
- **The queue** (`CaptureMachine`, persisted in `files/captures/queue.json` with the recordings beside it):

| State | Shown as | Next |
|---|---|---|
| `queued` | Queued | Uploaded when due |
| `sending` | Sending… | An upload "running" over 5 minutes was cut off and starts again |
| `waiting` | Waiting for phone | Neither the phone nor a direct connection reached Life OS. Tried again after 30 s, 1, 2, 4, 8, then every 10 minutes, and at once when the phone app comes back or hands over a token. Never gives up. |
| `sent` | Sent · transcribing | Voice accepted it. The job is polled for about 2.5 minutes after the upload, then once with every Today refresh, for a day. |
| `in_inbox` | In Inbox | Done; the audio is deleted. Kept in the list for three days. |
| `failed` | Failed · tap to retry | Refused (400 unsupported audio, 413 too large), out of retries (6 tries over about 15 minutes for 5xx, 429 and a refused token), or the transcription failed ("No speech was heard…"). **Retry** starts it again as a new upload. |

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

All in `core/…/wearlink/WearRelay.kt`. The listener (`PhoneWearLinkService`) and its manifest entry exist already.

1. **`WearLinkHost`**: the phone app's `Application` implements it, so the listener finds the two pieces below. Without it, the listener falls back to the stubs described under "Today's stubs".
2. **`WearTokenSource.load(): WearCredentials?`**, implemented by the phone's token store: the base URL (`https://lifeos.gamch1k.com`) and the service token the watch should use, or null when there is none. With one Access service token per device (the plan), this is the watch's own token; until then, the phone's.
3. **`WearRelay.execute(RelayHttpRequest): RelayHttpResponse`**, implemented with the phone's ECH-capable client:
   - send `method` to the base URL plus `path`, with `headers` (the actor is already among them on writes), the body, and the `CF-Access-Client-Id` and `CF-Access-Client-Secret` headers;
   - don't follow redirects, and return every HTTP response, whatever its status;
   - throw `WearRelayException(RelayErrorCode.NO_CREDENTIALS)` without a token and `WearRelayException(RelayErrorCode.UPSTREAM_UNREACHABLE)` when no response came (any other exception counts as unreachable too);
   - it runs on a background thread; the watch waits about 30 seconds for small requests and 2 minutes for uploads.
4. **Pings**: call `WearLinkPhone.notifyCredentialsChanged(context)` after the token changes, and `WearLinkPhone.notifyTodayChanged(context)` when tasks or events change (a push arrived, Done or Snooze). Both are no-ops without a watch.

**Today's stubs.** Until the `Application` implements `WearLinkHost`, the listener hands over the token saved on the network check screen (`ServiceTokenStore`, with `LifeOsEndpoints.HOST`), and relays nothing (`WearRelay.UNAVAILABLE`, error `relay_unavailable`). The watch then calls Life OS directly with that token: everything works except the relay.

## Setting up the watch (the owner)

1. **Install** the wear APK. Until P19-06 brings installs through the phone, this is ADB over Wi-Fi: on the watch, Settings › Developer options › Wireless debugging, pair, then `adb install -r lifeos-wear-<version>.apk`. Turn Wireless debugging off again afterwards.
2. **Token**: the phone app must hold a token (today: saved on its network check screen). Open **Life OS** on the watch once; it asks the phone by itself. **Connection** shows "Token: From the phone, <time>"; if not, tap **Get token from phone**, and **Test connection** to see which way Life OS answers.
3. **Microphone**: the first recording asks for it. After "Don't allow", the recording screen offers **Open settings**.
4. **Home key**: Settings › Advanced features › Customize buttons (Customize keys on older One UI Watch versions) › Home key › Double press › choose **Capture**. "Capture" is the app's second entry in the app list and opens straight into recording; choosing "Life OS" opens Home instead.
5. **Tiles**: from the watch face, swipe left to the end of the tiles, tap **+**, and add **Life OS Capture** and/or **Life OS Today**. The Galaxy Wearable app on the phone (Tiles) does the same.
6. **Complication**: touch and hold the watch face › Customize › Complications, pick a slot, then **Life OS next event**. Short-text slots show the time and title, long ones `14:30 Dentist`, small round ones a microphone. Some Samsung watch faces offer no slots for other apps.
7. Optional, to save 4–7 MB: `adb shell getprop ro.product.cpu.abilist` tells whether the watch runs 64-bit (`arm64-v8a`) or 32-bit ARM apps. The APK carries both until we know.

## Tests and build

- `tools/build test` runs every module's unit tests, including:
  - `core/…/wearlink/`: the frame codec (round trips, limits, truncation, versions, wire names), the relay policy, the phone's handler (credentials, relayed reads and writes, error codes, the channel, too-large replies, the replay cache);
  - `wear/…/net/`: the watch's relay against the phone's real handler, end to end, and the routing rules;
  - `wear/…/capture/`: the queue's state machine and idempotency, and the uploader against a fake Life OS (the multipart body, polling to In Inbox, waiting, backoff, refusals, a refused token, a restart);
  - `wear/…/today/`: parsing, the date-only rule, the tile's rows, the timeline change times, the complication's timeline, and the repository's queries and cache.
- `tools/build` builds the signed `lifeos-wear-<version>.apk` (about 15 MB: embedded Cronet's native code for two ABIs is 11 MB of it).

Not tested automatically: the Data Layer between the real phone and watch, recording on the watch's microphone, the tile and complication rendering. P19-05 is the acceptance on the Galaxy Watch 8.
