package com.gamch1k.lifeos.core.share

import com.gamch1k.lifeos.core.capture.CaptureKind
import java.net.URI

/** What another app shared with Life OS, read off the intent (no Android types, so it is testable). */
data class ShareInput(
    val action: String?,
    val type: String?,
    val text: String?,
    val subject: String?,
    val streams: List<SharedStream> = emptyList(),
)

data class SharedStream(val uri: String, val mimeType: String?)

/** A link found in the shared text; recipe and video links go in as text for Life OS's import. */
data class SharedLink(val url: String, val platform: Platform) {
    enum class Platform(val label: String) {
        INSTAGRAM("Instagram link"),
        TIKTOK("TikTok link"),
        YOUTUBE("YouTube link"),
        OTHER("Link"),
    }
}

/** What the share becomes: a text capture, or a photo capture with up to five images. */
sealed interface SharePlan {
    data class Capture(
        val kind: CaptureKind,
        val text: String,
        val imageUris: List<String>,
        /** Images beyond the five Inbox takes per capture. */
        val droppedImages: Int,
        val link: SharedLink?,
    ) : SharePlan {
        /** The Inbox content: the owner's note first, then what was shared. */
        fun content(note: String): String = listOf(note.trim(), text).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    data class Unsupported(val reason: String) : SharePlan
}

/**
 * Maps `ACTION_SEND` and `ACTION_SEND_MULTIPLE` to a capture: text and URLs
 * as a text capture (Instagram, TikTok and YouTube links stay text with their
 * URL, so Life OS's recipe import and processor handle them), images (at most
 * five, JPEG-converted on the phone later) as a photo capture whose caption is
 * the shared text.
 */
object ShareMapper {
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
    const val MAX_IMAGES = 5

    private val URL = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    fun map(input: ShareInput): SharePlan {
        if (input.action != ACTION_SEND && input.action != ACTION_SEND_MULTIPLE) return SharePlan.Unsupported("Nothing was shared.")
        val images = input.streams.filter { stream -> isImage(stream.mimeType ?: input.type) }
        val others = input.streams.size - images.size
        val text = combine(input.subject, input.text)
        if (images.isEmpty() && text.isEmpty()) {
            return SharePlan.Unsupported(
                if (others > 0) "Life OS takes text, links and images; this share has neither (${typeName(input.type)})." else "Nothing to capture.",
            )
        }
        return SharePlan.Capture(
            kind = if (images.isEmpty()) CaptureKind.TEXT else CaptureKind.PHOTO,
            text = text,
            imageUris = images.take(MAX_IMAGES).map { it.uri },
            droppedImages = (images.size - MAX_IMAGES).coerceAtLeast(0),
            link = firstLink(text),
        )
    }

    /** The subject (a page title, a reel's caption) above the text, unless the text already has it. */
    private fun combine(subject: String?, text: String?): String {
        val body = text?.trim().orEmpty()
        val title = subject?.trim().orEmpty()
        return when {
            title.isEmpty() -> body
            body.isEmpty() -> title
            body.contains(title) -> body
            else -> "$title\n\n$body"
        }
    }

    fun firstLink(text: String): SharedLink? {
        val url = URL.find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?') ?: return null
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull().orEmpty().removePrefix("www.").removePrefix("m.")
        val platform = when {
            host == "instagram.com" || host == "instagr.am" || host.endsWith(".instagram.com") -> SharedLink.Platform.INSTAGRAM
            host == "tiktok.com" || host.endsWith(".tiktok.com") -> SharedLink.Platform.TIKTOK
            host == "youtube.com" || host == "youtu.be" || host.endsWith(".youtube.com") -> SharedLink.Platform.YOUTUBE
            else -> SharedLink.Platform.OTHER
        }
        return SharedLink(url, platform)
    }

    private fun isImage(mimeType: String?) = mimeType?.lowercase()?.startsWith("image/") == true

    private fun typeName(type: String?) = when {
        type == null -> "unknown type"
        type.startsWith("video/") -> "a video"
        else -> type
    }
}
