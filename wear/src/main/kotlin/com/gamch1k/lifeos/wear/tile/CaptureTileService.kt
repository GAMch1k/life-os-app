package com.gamch1k.lifeos.wear.tile

import android.content.ComponentName
import android.content.Context
import androidx.wear.protolayout.ActionBuilders.AndroidExtra
import androidx.wear.protolayout.ActionBuilders.launchAction
import androidx.wear.protolayout.ActionBuilders.stringExtra
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.layout.androidImageResource
import androidx.wear.protolayout.layout.imageResource
import androidx.wear.protolayout.material3.IconButtonStyle.Companion.largeIconButtonStyle
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.icon
import androidx.wear.protolayout.material3.iconButton
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.contentDescription
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.tile
import com.gamch1k.lifeos.wear.R
import com.gamch1k.lifeos.wear.ui.Launch
import com.gamch1k.lifeos.wear.ui.MainActivity

/** A tap that opens the app with a [Launch] action. */
internal fun Context.launchApp(action: String, extras: Map<String, String> = emptyMap()): Clickable {
    val all: Map<String, AndroidExtra> = (mapOf(Launch.EXTRA_ACTION to action) + extras).mapValues { stringExtra(it.value) }
    val id = (listOf(action) + extras.values).joinToString(":")
    return clickable(launchAction(ComponentName(this, MainActivity::class.java), all), id = id)
}

/**
 * The "Capture" tile: one big button that opens the app straight into
 * recording. Swipe to it from the watch face, tap, speak, tap Send.
 */
class CaptureTileService : Material3TileService() {
    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val record = this@CaptureTileService.launchApp(Launch.RECORD)
        val layout = primaryLayout(
            titleSlot = { text(getString(R.string.app_name).layoutString) },
            mainSlot = {
                iconButton(
                    onClick = record,
                    iconContent = { icon(imageResource(androidImageResource(R.drawable.ic_mic))) },
                    modifier = LayoutModifier.contentDescription(getString(R.string.record_voice_note)),
                    width = dp(88f),
                    height = dp(88f),
                    style = largeIconButtonStyle(),
                )
            },
            bottomSlot = { textEdgeButton(onClick = record) { text(getString(R.string.record).layoutString) } },
        )
        return tile(timeline = TimelineBuilders.Timeline.fromLayoutElement(layout))
    }
}
