package com.gamch1k.lifeos.wear.tile

import android.content.Context
import android.text.format.DateFormat
import android.util.Log
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ModifiersBuilders.Clickable
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
import androidx.wear.protolayout.material3.textEdgeButton
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
import com.gamch1k.lifeos.wear.today.TodaySnapshot
import com.gamch1k.lifeos.wear.today.TodayTileModel
import com.gamch1k.lifeos.wear.today.TodayTilePlan
import com.gamch1k.lifeos.wear.ui.Launch
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

/**
 * The tile's timeline from [plan]: each timed entry with its start and end,
 * the default without validity (an end is never left unset: it would be 0).
 */
internal fun todayTimeline(plan: List<TodayTilePlan.Entry>, layout: (TodayTileModel) -> LayoutElement): TimelineBuilders.Timeline {
    val entries = plan.map { entry ->
        val validity = if (entry.from != null && entry.until != null) {
            TimelineBuilders.TimeInterval.Builder()
                .setStartMillis(entry.from.toEpochMilli())
                .setEndMillis(entry.until.toEpochMilli())
                .build()
        } else {
            null
        }
        timelineEntry(layout(entry.model), validity)
    }
    return timeline(*entries.toTypedArray())
}

/**
 * The "Today" tile: the next events and the tasks due today, from the cache
 * ([com.gamch1k.lifeos.wear.today.TodayRepository]), never from the network:
 * old data starts a refresh in the background ([TodayRefreshWorker]), which
 * asks for a new tile when it is done.
 *
 * The timeline comes from [TodayTilePlan]: one closed entry per stretch
 * between event starts and ends, and always a default entry, so the renderer
 * has something valid to show at every instant (0.4.1's black tile). Building
 * never throws: a failure gives the "couldn't be shown" tile, and renderers
 * older than 1.[MATERIAL_MIN_MINOR] get plain text instead of Material 3.
 *
 * Tapping a task opens the app's "Mark done?" confirmation, an event opens
 * Today with it highlighted; the edge button records.
 */
class TodayTileService : Material3TileService() {
    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val context = this@TodayTileService
        val material = usesMaterial(requestParams)
        return try {
            val (snapshot, error) = cachedDay(context)
            val rows = TodayLayout.maxRows(requestParams.deviceConfiguration.screenHeightDp)
            val plan = TodayTilePlan.plan(snapshot, Instant.now(), ZoneId.systemDefault(), rows, DateFormat.is24HourFormat(context), error)
            val timeline = todayTimeline(plan) { model -> if (material) todayLayout(context, model) else basicLayout(context, model) }
            tile(timeline = timeline, freshness = 30.minutes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "the Today tile could not be built; showing the fallback", failure)
            brokenTile(context, material)
        }
    }

    /** The cached day and the last refresh's problem; starts a refresh when the day is old, without waiting for it. */
    private fun cachedDay(context: Context): Pair<TodaySnapshot?, String?> {
        val repository = LifeOsWear.graph(context).today
        try {
            if (repository.isStale(Duration.ofMinutes(10))) TodayRefreshWorker.refreshNow(context)
        } catch (failure: Exception) {
            Log.w(TAG, "could not plan the Today refresh", failure)
        }
        return repository.snapshot.value to repository.lastError.value
    }

    /** A single entry without validity, for when the real tile can't be built; asked for again in 5 minutes. */
    private fun MaterialScope.brokenTile(context: Context, material: Boolean): Tile {
        val plain = { basicLayout(context, TodayTileModel(emptyList(), TodayLayout.BROKEN, null)) }
        val layout = if (!material) {
            plain()
        } else {
            try {
                primaryLayout(
                    titleSlot = { text(context.getString(R.string.today).layoutString) },
                    mainSlot = { text(TodayLayout.BROKEN.layoutString, typography = Typography.BODY_SMALL, maxLines = 3) },
                    bottomSlot = { textEdgeButton(onClick = context.launchApp(Launch.TODAY)) { text(context.getString(R.string.open).layoutString) } },
                )
            } catch (failure: Exception) {
                Log.w(TAG, "the Material 3 fallback failed too", failure)
                plain()
            }
        }
        return tile(timeline = timeline(timelineEntry(layout)), freshness = 5.minutes)
    }

    private fun MaterialScope.todayLayout(context: Context, model: TodayTileModel): LayoutElement = primaryLayout(
        titleSlot = { text(context.getString(R.string.today).layoutString) },
        mainSlot = {
            val lines = model.rows.map { row -> rowButton(context, row) } +
                listOfNotNull(model.emptyText, model.footer).map { line ->
                    text(line.layoutString, typography = Typography.BODY_SMALL, color = colorScheme.onSurfaceVariant, maxLines = 2)
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
            onClick = rowClick(context, row),
            labelContent = { text(TodayLayout.elide(row.label, LABEL_MAX).layoutString) },
            width = expand(),
            colors = filledTonalButtonColors(),
        )
        is TodayRow.Task -> compactButton(
            onClick = rowClick(context, row),
            labelContent = { text(TodayLayout.elide(row.label, LABEL_MAX).layoutString) },
            iconContent = { icon(imageResource(androidImageResource(R.drawable.ic_check))) },
            width = expand(),
            colors = filledVariantButtonColors(),
            modifier = LayoutModifier.contentDescription(context.getString(R.string.task_due, row.title, row.due)),
        )
    }

    private fun rowClick(context: Context, row: TodayRow): Clickable = when (row) {
        is TodayRow.Event -> context.launchApp(Launch.TODAY, mapOf(Launch.EXTRA_HIGHLIGHT to TodayLayout.eventHighlight(row.key)))
        is TodayRow.Task -> context.launchApp(
            Launch.COMPLETE_TASK,
            mapOf(Launch.EXTRA_TASK_ID to row.id, Launch.EXTRA_TASK_TITLE to TodayLayout.elide(row.title, TITLE_EXTRA_MAX)),
        )
    }

    /** Plain ProtoLayout text, for renderers too old for Material 3 and as the last fallback: no images, no resources. */
    private fun basicLayout(context: Context, model: TodayTileModel): LayoutElement {
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(basicText(context.getString(R.string.today), 15f, WHITE, bold = true))
        model.rows.forEach { row ->
            val color = if (row is TodayRow.Task && row.overdue) ACCENT else WHITE
            column.addContent(basicText(TodayLayout.elide(row.label, LABEL_MAX), 14f, color, click = rowClick(context, row)))
        }
        listOfNotNull(model.emptyText, model.footer).forEach { line -> column.addContent(basicText(line, 12f, GREY, maxLines = 3)) }
        column.addContent(basicText(context.getString(R.string.record), 14f, ACCENT, bold = true, click = context.launchApp(Launch.RECORD)))
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(20f)).setEnd(dp(20f)).build())
                    .setClickable(context.launchApp(Launch.TODAY))
                    .build(),
            )
            .addContent(column.build())
            .build()
    }

    @Suppress("DEPRECATION") // TEXT_OVERFLOW_ELLIPSIZE_END is the one old renderers know.
    private fun basicText(text: String, sizeSp: Float, color: Int, maxLines: Int = 1, bold: Boolean = false, click: Clickable? = null): LayoutElement {
        val style = LayoutElementBuilders.FontStyle.Builder().setSize(sp(sizeSp)).setColor(argb(color))
        if (bold) style.setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
        val modifiers = ModifiersBuilders.Modifiers.Builder()
            .setPadding(ModifiersBuilders.Padding.Builder().setTop(dp(2f)).build())
        click?.let(modifiers::setClickable)
        return LayoutElementBuilders.Text.Builder()
            .setText(text)
            .setMaxLines(maxLines)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
            .setFontStyle(style.build())
            .setModifiers(modifiers.build())
            .build()
    }

    private companion object {
        const val TAG = "TodayTile"

        /**
         * The lowest renderer schema (1.x) that gets the Material 3 layout.
         * Material 3 adapts by itself from 1.200 on (Wear OS 4; it checks
         * 1.300 for some features); a renderer reporting less, or nothing
         * (read as 1.0), gets plain text.
         */
        const val MATERIAL_MIN_MINOR = 200

        /** A row's label on the tile: one line shows about 20 characters, the renderer cuts the rest. */
        const val LABEL_MAX = 40
        const val TITLE_EXTRA_MAX = 120

        const val WHITE = 0xFFFFFFFF.toInt()
        const val GREY = 0xFFBDBDBD.toInt()
        const val ACCENT = 0xFFA8C7FA.toInt()

        fun usesMaterial(request: TileRequest): Boolean = try {
            val version = request.deviceConfiguration.rendererSchemaVersion
            version.major > 1 || (version.major == 1 && version.minor >= MATERIAL_MIN_MINOR)
        } catch (_: Exception) {
            true
        }
    }
}
