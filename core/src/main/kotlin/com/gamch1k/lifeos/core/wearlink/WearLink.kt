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
