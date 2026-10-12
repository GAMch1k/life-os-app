package com.gamch1k.lifeos.wear.complication

import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.text.format.DateFormat
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingTimelineComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.TimeInterval
import androidx.wear.watchface.complications.datasource.TimelineEntry
import com.gamch1k.lifeos.wear.LifeOsWear
import com.gamch1k.lifeos.wear.R
import com.gamch1k.lifeos.wear.today.NextEvent
import com.gamch1k.lifeos.wear.today.TodayRefreshWorker
import com.gamch1k.lifeos.wear.ui.MainActivity
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The next-event complication: the next event's time and title (short and
 * long text), or a microphone (icon). Tapping it starts a recording.
 *
 * It answers with a timeline from the cached day, so the watch face moves to
 * the following event at each start time without asking again; the cache is
 * refreshed by [TodayRefreshWorker], which then asks for an update.
 */
class NextEventComplicationService : SuspendingTimelineComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline? {
        val type = request.complicationType
        val repository = LifeOsWear.graph(this).today
        if (repository.isStale(Duration.ofMinutes(30))) TodayRefreshWorker.refreshNow(this)
        if (type == ComplicationType.MONOCHROMATIC_IMAGE) return ComplicationDataTimeline(micData(), emptyList())

        val entries = NextEvent.timeline(repository.snapshot.value, Instant.now(), ZoneId.systemDefault(), DateFormat.is24HourFormat(this))
        val fallback = data(type, null) ?: return null
        return ComplicationDataTimeline(
            defaultComplicationData = fallback,
            timelineEntries = entries.mapNotNull { entry ->
                data(type, entry)?.let { TimelineEntry(TimeInterval(entry.validFrom, entry.validUntil), it) }
            },
        )
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = when (type) {
        ComplicationType.MONOCHROMATIC_IMAGE -> micData()
        else -> data(
            type,
            NextEvent.Entry(Instant.EPOCH, Instant.EPOCH, "Dentist", Instant.EPOCH, "14:30", "14:30", tomorrow = false),
        )
    }

    /** One event as complication data; [entry] null means "nothing ahead". */
    private fun data(type: ComplicationType, entry: NextEvent.Entry?): ComplicationData? {
        val tap = recordIntent()
        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                val text = entry?.shortTime ?: "—"
                val title = entry?.title ?: getString(R.string.no_events_short)
                ShortTextComplicationData.Builder(plain(text), plain(description(entry)))
                    .setTitle(plain(title))
                    .setMonochromaticImage(if (entry == null) mic() else null)
                    .setTapAction(tap)
                    .build()
            }
            ComplicationType.LONG_TEXT -> {
                val text = entry?.let { "${it.time} ${it.title}" } ?: getString(R.string.no_more_events)
                LongTextComplicationData.Builder(plain(text), plain(description(entry)))
                    .setTitle(plain(getString(if (entry?.tomorrow == true) R.string.tomorrow else R.string.next_event)))
                    .setMonochromaticImage(mic())
                    .setTapAction(tap)
                    .build()
            }
            ComplicationType.MONOCHROMATIC_IMAGE -> micData()
            else -> NoDataComplicationData()
        }
    }

    private fun micData() = MonochromaticImageComplicationData.Builder(mic(), plain(getString(R.string.record_voice_note)))
        .setTapAction(recordIntent())
        .build()

    private fun mic() = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_mic)).build()

    private fun description(entry: NextEvent.Entry?) =
        entry?.let { getString(R.string.next_event_description, it.title, it.time) } ?: getString(R.string.no_more_events)

    private fun plain(text: String) = PlainComplicationText.Builder(text).build()

    private fun recordIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_RECORD,
        MainActivity.recordIntent(this),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val REQUEST_RECORD = 1
    }
}
