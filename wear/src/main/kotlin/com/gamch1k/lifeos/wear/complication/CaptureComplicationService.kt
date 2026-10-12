package com.gamch1k.lifeos.wear.complication

import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.gamch1k.lifeos.wear.R

/**
 * "Life OS capture": a microphone that starts a recording, for a small slot
 * next to "Life OS next" (which shows a microphone only when nothing is
 * ahead). Small and monochromatic image, or short text ("Rec" under the
 * microphone). It never changes, so the system never has to ask again.
 */
class CaptureComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? = data(request.complicationType)

    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(type)

    private fun data(type: ComplicationType): ComplicationData? {
        val icon = Icon.createWithResource(this, R.drawable.ic_mic)
        val description = PlainComplicationText.Builder(getString(R.string.record_voice_note)).build()
        val tap = NextEventComplicationService.recordIntent(this)
        return when (type) {
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(SmallImage.Builder(icon, SmallImageType.ICON).build(), description)
                .setTapAction(tap)
                .build()
            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(MonochromaticImage.Builder(icon).build(), description)
                .setTapAction(tap)
                .build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(PlainComplicationText.Builder(getString(R.string.record_short)).build(), description)
                .setMonochromaticImage(MonochromaticImage.Builder(icon).build())
                .setTapAction(tap)
                .build()
            else -> null
        }
    }
}
