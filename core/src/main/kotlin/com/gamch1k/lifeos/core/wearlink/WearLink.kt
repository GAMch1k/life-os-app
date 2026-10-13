package com.gamch1k.lifeos.core.wearlink

/**
 * The phone–watch link ("wearlink"): what the phone app and the watch app say
 * to each other over the Wear OS Data Layer. Both apps share the package name
 * and the signing key, so the Data Layer only connects these two apps.
 *
 * - The watch asks the phone for the server URL and the Cloudflare Access
 *   service token ([PATH_CREDENTIALS], a `MessageClient` request).
 * - The watch sends Life OS API requests through the phone
 *   ([PATH_RELAY], a `MessageClient` request, for small JSON;
 *   [PATH_RELAY_CHANNEL], a `ChannelClient` channel, for audio). The phone
 *   runs them with its own client ([WearRelay]).
 * - The phone tells the watch when the token or the day's data changed
 *   ([PATH_CREDENTIALS_CHANGED], [PATH_TODAY_CHANGED], plain messages).
 * - Updates of the watch app go through the phone (P19-06): the phone asks
 *   the watch's version ([PATH_WATCH_INFO]), offers a newer APK
 *   ([PATH_APK_OFFER]) and streams it ([PATH_APK_TRANSFER]); the watch
 *   reports the result ([PATH_WATCH_REPORT]). Since 0.4.3 (transfer v2) a
 *   watch may download the APK itself over Wi-Fi while the phone follows it
 *   ([PATH_APK_STATUS]), and a broken stream resumes where it stopped.
 *
 * See docs/wear.md.
 */
object WearLink {
    /** The protocol version. A side that receives another version answers `unsupported_version`. */
    const val PROTOCOL_VERSION = 1

    /** Declared by the phone app (phone/src/main/res/values/wear.xml): it relays requests and hands over the token. */
    const val CAPABILITY_PHONE = "lifeos_phone_relay"

    /** Declared by the watch app (wear/src/main/res/values/wear.xml). */
    const val CAPABILITY_WATCH = "lifeos_watch"

    /** Every path of the link starts with this; the listeners' intent filters match it. */
    const val PATH_PREFIX = "/lifeos/"

    /** Watch → phone, `MessageClient.sendRequest`: a [CredentialsRequest], answered with a [CredentialsReply]. */
    const val PATH_CREDENTIALS = "/lifeos/credentials"

    /** Watch → phone, `MessageClient.sendRequest`: one framed [RelayRequest], answered with a framed [RelayResponse]. */
    const val PATH_RELAY = "/lifeos/relay"

    /** Watch → phone, `ChannelClient`: the same frames as [PATH_RELAY], for bodies too large for a message. */
    const val PATH_RELAY_CHANNEL = "/lifeos/relay-channel"

    /** Phone → watch, a message without payload: the token changed; the watch asks for it again. */
    const val PATH_CREDENTIALS_CHANGED = "/lifeos/credentials-changed"

    /** Phone → watch, a message without payload: tasks or events changed; the watch refreshes Today. */
    const val PATH_TODAY_CHANGED = "/lifeos/today-changed"

    /** Phone → watch, `MessageClient.sendRequest`: a [WatchInfoRequest], answered with a [WatchInfo]. */
    const val PATH_WATCH_INFO = "/lifeos/watch-info"

    /**
     * Watch → phone, a message: a [WatchInfo] the watch sends by itself, on its
     * first start after an update and after every install result.
     */
    const val PATH_WATCH_REPORT = "/lifeos/watch-report"

    /** Phone → watch, `MessageClient.sendRequest`: an [ApkOffer], answered with an [ApkOfferReply]. */
    const val PATH_APK_OFFER = "/lifeos/apk-offer"

    /** Phone → watch, `ChannelClient`: the accepted APK ([ApkTransferCodec]); the watch answers an [ApkTransferResult]. */
    const val PATH_APK_TRANSFER = "/lifeos/apk-transfer"

    /**
     * Phone → watch, `MessageClient.sendRequest`: an [ApkStatusRequest], answered with an
     * [ApkStatus]: how the watch's own download of an accepted offer goes (transfer v2).
     */
    const val PATH_APK_STATUS = "/lifeos/apk-status"

    /** In [WatchInfo.capabilities]: the watch app takes APKs over [PATH_APK_OFFER] and [PATH_APK_TRANSFER] (0.4.0 and later). */
    const val WATCH_CAN_UPDATE = "apk_transfer_v1"

    /**
     * In [WatchInfo.capabilities]: transfer v2 (0.4.3 and later). The watch answers an offer
     * with the bytes it already holds of that APK, so a broken stream resumes; it may download
     * the APK itself over Wi-Fi first, and answers [PATH_APK_STATUS]. The messages keep
     * [PROTOCOL_VERSION] 1: v2 only adds fields, which a v1 side ignores.
     */
    const val WATCH_CAN_RESUME = "apk_transfer_v2"

    /** The largest APK the link carries; the wear APK is about 6 MB since 0.4.3. */
    const val MAX_APK_BYTES = 64L * 1024 * 1024

    /**
     * The largest message, request or reply, the link sends. Data Layer
     * messages are meant to stay small (about 100 KB at most); anything larger
     * goes through [PATH_RELAY_CHANNEL].
     */
    const val MAX_MESSAGE_BYTES = 90 * 1024

    /** The largest body of a channel request: Voice's 25 MiB upload limit plus multipart framing. */
    const val MAX_CHANNEL_BODY_BYTES = 26 * 1024 * 1024

    /** The largest body of a reply. Life OS answers the watch's requests with small JSON. */
    const val MAX_REPLY_BODY_BYTES = 1024 * 1024

    /** The largest frame header (the JSON before the body). */
    const val MAX_HEADER_BYTES = 16 * 1024
}
