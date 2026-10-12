package com.gamch1k.lifeos.wear.tile

import android.content.Context
import android.text.format.DateFormat
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.layout.androidImageResource
import androidx.wear.protolayout.layout.column
import androidx.wear.protolayout.layout.imageResource
import androidx.wear.protolayout.material3.ButtonDefaults.filledTonalButtonColors
import androidx.wear.protolayout.material3.ButtonDefaults.filledVariantButtonColors
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.compactButton
import androidx.wear.protolayout.material3.icon
import androidx.wear.protolayout.material3.iconEdgeButton
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.contentDescription
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.tile
import androidx.wear.tiles.timeline
import androidx.wear.tiles.timelineEntry
import com.gamch1k.lifeos.wear.LifeOsWear
import com.gamch1k.lifeos.wear.R
import com.gamch1k.lifeos.wear.today.TodayLayout
import com.gamch1k.lifeos.wear.today.TodayRefreshWorker
import com.gamch1k.lifeos.wear.today.TodayRow
import com.gamch1k.lifeos.wear.today.TodayTileModel
import com.gamch1k.lifeos.wear.ui.Launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

/**
 * The "Today" tile: the next events and the tasks due today, from the cache
 * ([com.gamch1k.lifeos.wear.today.TodayRepository]), with one timeline entry
 * per change so past events drop off by themselves. Tapping a task opens the
 * app's "Mark done?" confirmation; the edge button records.
 */
class TodayTileService : Material3TileService() {
    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val context = this@TodayTileService
        val repository = LifeOsWear.graph(context).today
        if (repository.isStale(Duration.ofMinutes(10))) TodayRefreshWorker.refreshNow(context)

        val snapshot = repository.snapshot.value
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val rows = TodayLayout.maxRows(requestParams.deviceConfiguration.screenHeightDp)
        val use24Hour = DateFormat.is24HourFormat(context)
        val moments = listOf(now) + TodayLayout.changeTimes(snapshot, now)
        val entries = moments.mapIndexed { index, start ->
            val model = TodayLayout.model(snapshot, start, zone, rows, use24Hour)
            val validity = TimelineBuilders.TimeInterval.Builder()
                .setStartMillis(if (index == 0) 0 else start.toEpochMilli())
                .apply { moments.getOrNull(index + 1)?.let { setEndMillis(it.toEpochMilli()) } }
                .build()
            timelineEntry(todayLayout(context, model), validity)
        }
        return tile(timeline = timeline(*entries.toTypedArray()), freshness = 30.minutes)
    }

    private fun MaterialScope.todayLayout(context: Context, model: TodayTileModel): LayoutElement = primaryLayout(
        titleSlot = { text(context.getString(R.string.today).layoutString) },
        mainSlot = {
            val lines = model.rows.map { row -> rowButton(context, row) } +
                listOfNotNull(model.emptyText, model.footer).map { line ->
                    text(line.layoutString, typography = Typography.BODY_SMALL, color = colorScheme.onSurfaceVariant)
                }
            column(*lines.toTypedArray(), width = expand(), horizontalAlignment = LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        },
        bottomSlot = {
            iconEdgeButton(
                onClick = context.launchApp(Launch.RECORD),
                modifier = LayoutModifier.contentDescription(context.getString(R.string.record_voice_note)),
            ) { icon(imageResource(androidImageResource(R.drawable.ic_mic))) }
        },
    )

    private fun MaterialScope.rowButton(context: Context, row: TodayRow): LayoutElement = when (row) {
        is TodayRow.Event -> compactButton(
            onClick = context.launchApp(Launch.TODAY),
            labelContent = { text(row.label.layoutString) },
            width = expand(),
            colors = filledTonalButtonColors(),
        )
        is TodayRow.Task -> compactButton(
            onClick = context.launchApp(Launch.COMPLETE_TASK, mapOf(Launch.EXTRA_TASK_ID to row.id, Launch.EXTRA_TASK_TITLE to row.title)),
            labelContent = { text(row.label.layoutString) },
            iconContent = { icon(imageResource(androidImageResource(R.drawable.ic_check))) },
            width = expand(),
            colors = filledVariantButtonColors(),
            modifier = LayoutModifier.contentDescription(context.getString(R.string.task_due, row.title, row.due)),
        )
    }
}
