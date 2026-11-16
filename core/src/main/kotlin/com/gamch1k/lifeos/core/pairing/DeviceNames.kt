package com.gamch1k.lifeos.core.pairing

/**
 * The names a paired phone and its watch have in the Dashboard's Settings →
 * Devices: 1 to 64 characters, no control characters (life-os
 * `docs/api/devices.md`, `POST /api/auth/devices/pair`).
 */
object DeviceNames {
    const val MAX_LENGTH = 64
    const val PHONE_FALLBACK = "Android phone"
    const val WATCH_FALLBACK = "Wear OS watch"

    /** [raw] (the phone's device name, the watch's node name) made acceptable, or [fallback]. */
    fun clean(raw: String?, fallback: String): String {
        val visible = raw.orEmpty()
            // Tabs and new lines are control characters too: spaces first, so words stay apart.
            .map { if (it.isWhitespace()) ' ' else it }
            .joinToString("")
            .filterNot { char ->
                when (Character.getType(char).toByte()) {
                    Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
                    else -> false
                }
            }
            .replace(Regex("\\s+"), " ")
            .trim()
        if (visible.isEmpty()) return fallback
        var cut = visible.take(MAX_LENGTH)
        // Never half a surrogate pair at the end.
        if (cut.length == MAX_LENGTH && cut.last().isHighSurrogate()) cut = cut.dropLast(1)
        return cut.trim().ifEmpty { fallback }
    }
}
