package com.gamch1k.lifeos.wear.complication

import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.text.format.DateFormat
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingTimelineComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.TimeInterval
import androidx.wear.watchface.complications.datasource.TimelineEntry
import com.gamch1k.lifeos.wear.LifeOsWear
import com.gamch1k.lifeos.wear.R
import com.gamch1k.lifeos.wear.today.NextFace
import com.gamch1k.lifeos.wear.today.NextIcon
import com.gamch1k.lifeos.wear.today.NextItem
import com.gamch1k.lifeos.wear.today.NextKind
import com.gamch1k.lifeos.wear.today.NextUp
import com.gamch1k.lifeos.wear.today.TodayRefreshWorker
import com.gamch1k.lifeos.wear.ui.MainActivity
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * "Life OS next": the next event, else the next task due, else a microphone
 * ([NextUp] has the rules). Short and long text, and monochromatic and small
 * images (a calendar, a check, or the microphone); no ranged value or goal
 * progress, since Samsung's edge slots take those first and draw an arc or a
 * line around the text (P19-07). Tapping an event or a task opens Today with
 * it highlighted; the microphone records.
 *
 * The class name stays from 0.4 (`NextEventComplicationService`), so watch
 * faces that already use it keep it. It answers with a timeline from the
 * cached day, so the face moves on at each event start without asking again;
 * [TodayRefreshWorker] refreshes the cache and then asks for an update, and
 * the system asks every 30 minutes as a safety net.
 */
class NextEventComplicationService : SuspendingTimelineComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline? {
        val kind = kindOf(request.complicationType) ?: return null
        val taps = Taps(this)
        return try {
            val repository = LifeOsWear.graph(this).today
            try {
                if (repository.isStale(Duration.ofMinutes(30))) TodayRefreshWorker.refreshNow(this)
            } catch (failure: Exception) {
                Log.w(TAG, "could not plan the Today refresh", failure)
            }
            val plan = NextUp.plan(repository.snapshot.value, Instant.now(), ZoneId.systemDefault(), DateFormat.is24HourFormat(this))
            ComplicationDataTimeline(
                defaultComplicationData = data(kind, NextUp.face(kind, plan.default), taps),
                timelineEntries = plan.slices.map { slice ->
                    TimelineEntry(TimeInterval(slice.from, slice.until), data(kind, NextUp.face(kind, slice.item), taps))
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "the next-item complication could not be built; showing the microphone", failure)
            try {
                ComplicationDataTimeline(data(kind, NextUp.face(kind, NextItem.Free), taps), emptyList())
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val kind = kindOf(type) ?: return null
        val sample = NextItem.Event("preview", "Dentist", Instant.EPOCH, "14:30", "14:30", day = null)
        return data(kind, NextUp.face(kind, sample), Taps(this))
    }

    private fun data(kind: NextKind, face: NextFace, taps: Taps): ComplicationData {
        val description = plain(face.description)
        val tap = taps.forFace(face)
        val image = MonochromaticImage.Builder(icon(face.icon)).build()
        return when (kind) {
            NextKind.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(face.text ?: ""), description)
                .apply { face.title?.let { setTitle(plain(it)) } }
                .setMonochromaticImage(image)
                .setTapAction(tap)
                .build()
            NextKind.LONG_TEXT -> LongTextComplicationData.Builder(plain(face.text ?: ""), description)
                .apply { face.title?.let { setTitle(plain(it)) } }
                .setMonochromaticImage(image)
                .setTapAction(tap)
                .build()
            NextKind.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(image, description)
                .setTapAction(tap)
                .build()
            NextKind.SMALL_IMAGE -> SmallImageComplicationData.Builder(SmallImage.Builder(icon(face.icon), SmallImageType.ICON).build(), description)
                .setTapAction(tap)
                .build()
        }
    }

    private fun icon(icon: NextIcon): Icon = Icon.createWithResource(
        this,
        when (icon) {
            NextIcon.EVENT -> R.drawable.ic_event
            NextIcon.TASK -> R.drawable.ic_check
            NextIcon.MIC -> R.drawable.ic_mic
        },
    )

    private fun plain(text: String) = PlainComplicationText.Builder(text).build()

    /** The taps of one answer: one PendingIntent per highlighted item, and the recording one. */
    private class Taps(private val context: Context) {
        private val today = mutableMapOf<String, PendingIntent>()

        fun forFace(face: NextFace): PendingIntent {
            val highlight = face.highlight ?: return recordIntent(context)
            return today.getOrPut(highlight) {
                PendingIntent.getActivity(
                    context,
                    REQUEST_TODAY,
                    MainActivity.todayIntent(context, highlight),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
        }
    }

    companion object {
        private const val TAG = "NextComplication"
        private const val REQUEST_RECORD = 1
        private const val REQUEST_TODAY = 2

        fun kindOf(type: ComplicationType): NextKind? = when (type) {
            ComplicationType.SHORT_TEXT -> NextKind.SHORT_TEXT
            ComplicationType.LONG_TEXT -> NextKind.LONG_TEXT
            ComplicationType.MONOCHROMATIC_IMAGE -> NextKind.MONOCHROMATIC_IMAGE
            ComplicationType.SMALL_IMAGE -> NextKind.SMALL_IMAGE
            else -> null
        }

        /** Opens the app straight into recording; shared with the capture complication. */
        fun recordIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_RECORD,
            MainActivity.recordIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
