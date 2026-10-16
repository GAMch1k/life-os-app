package com.gamch1k.lifeos.wear.update

import com.gamch1k.lifeos.core.update.TransferProgress
import com.gamch1k.lifeos.core.wearlink.ApkStatus
import com.gamch1k.lifeos.core.wearlink.ApkStatusState
import com.gamch1k.lifeos.core.wearlink.UpdateError

/**
 * Where the watch's update of the accepted offer [offerId] stands (transfer
 * v2, docs/wear.md "Install and updates"): what the phone's status requests
 * get ([status]), what Connection and the update's notification show.
 */
data class UpdateProgress(
    val offerId: String,
    val versionCode: Long,
    val versionName: String,
    /** The APK's size, as offered. */
    val size: Long,
    /** One of [ApkStatusState]. */
    val state: String,
    /** One of [com.gamch1k.lifeos.core.wearlink.ApkRoute]; null before a route is chosen. */
    val route: String? = null,
    /** Bytes held of the APK and the speed, while bytes move (and after, for the record). */
    val progress: TransferProgress? = null,
    /** With [ApkStatusState.NEEDS_STREAM]: the bytes the watch holds, where the phone's stream starts. */
    val offset: Long = 0,
    /** With [ApkStatusState.NEEDS_STREAM]: why Wi-Fi was given up; with [ApkStatusState.FAILED]: why the update failed. */
    val error: UpdateError? = null,
) {
    /** The answer to the phone's [com.gamch1k.lifeos.core.wearlink.ApkStatusRequest] for this offer. */
    fun status(): ApkStatus = ApkStatus(
        id = offerId,
        state = state,
        route = route,
        received = progress?.done ?: offset,
        size = progress?.total ?: size,
        bytesPerSecond = progress?.bytesPerSecond,
        offset = offset,
        error = error,
    )

    /** Bytes are moving: Wi-Fi download or the phone's stream. */
    val moving: Boolean get() = state == ApkStatusState.DOWNLOADING || state == ApkStatusState.RECEIVING

    companion object {
        /** The answer about an offer the watch doesn't know (it restarted, or the offer was replaced or expired). */
        fun unknown(id: String) = ApkStatus(id = id, state = ApkStatusState.UNKNOWN)
    }
}
