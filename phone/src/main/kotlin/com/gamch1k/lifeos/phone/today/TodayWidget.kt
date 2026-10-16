package com.gamch1k.lifeos.phone.today

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.gamch1k.lifeos.core.today.TodayBuilder
import com.gamch1k.lifeos.core.today.TodayView
import com.gamch1k.lifeos.phone.MainActivity
import com.gamch1k.lifeos.phone.R
import com.gamch1k.lifeos.phone.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Today widget: the Tasks due (overdue first) and the next events, from
 * the cached Today; it re-renders when Today changes (Home's refresh, a Done,
 * the hourly refresh) and every 30 minutes. A tap opens the app.
 */
class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = context.graph
        val initiallySignedIn = graph.tokens.current() != null
        graph.today.load()
        provideContent {
            // Collected here, so an update during a live session shows the new data, not the old.
            val snapshot by graph.today.snapshot.collectAsState()
            val signedIn by remember { graph.tokens.connection.map { it != null } }.collectAsState(initiallySignedIn)
            val now = Instant.now()
            val view = snapshot?.let { TodayBuilder.build(it, now, ZoneId.systemDefault()) }
            val fetched = snapshot?.fetchedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
            GlanceTheme { TodayContent(context, signedIn, view, fetched, now) }
        }
    }

    companion object {
        /** Re-renders every Today widget from the cache, and no other widget (BUG-41). */
        suspend fun refreshAll(context: Context) = renderAll(context, TodayWidgetReceiver::class.java, TodayWidget())
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

/**
 * Renders [widget] into the widgets placed from [receiver], and only into
 * those (BUG-41). The ids come from the system's list for the receiver, not
 * from Glance's `updateAll`, which finds them by the [GlanceAppWidget]'s class
 * name: R8 had merged [QuickCaptureWidget] into [TodayWidget] (one class, told
 * apart by a synthetic field), that one name covered both receivers, and every
 * Today refresh drew Today into the Capture widgets too. The classes are kept
 * now (proguard-rules.pro), and `tools/build` checks R8's mapping for it.
 */
private suspend fun renderAll(context: Context, receiver: Class<out GlanceAppWidgetReceiver>, widget: GlanceAppWidget) {
    val glance = GlanceAppWidgetManager(context)
    val ids = runCatching { AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, receiver)) }
        .getOrElse { IntArray(0) }
    for (id in ids) {
        try {
            widget.update(context, glance.getGlanceIdBy(id))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Removed meanwhile, or Glance failed; the next refresh tries again.
            Log.w("LifeOsWidgets", "could not render widget $id of ${receiver.simpleName}", failure)
        }
    }
}

private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

private data class WidgetRow(val title: String, val detail: String, val warn: Boolean)

@Composable
private fun TodayContent(context: Context, signedIn: Boolean, view: TodayView?, fetched: Instant?, now: Instant) {
    val open = actionStartActivity(CaptureLinks.home(context))
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(12.dp)
            .clickable(open),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Today", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.defaultWeight())
            fetched?.let {
                val stale = now.epochSecond - it.epochSecond > 2 * 3600
                Text(
                    (if (stale) "as of " else "") + time.format(it.atZone(ZoneId.systemDefault())),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                )
            }
        }
        Spacer(GlanceModifier.height(6.dp))
        when {
            !signedIn -> Hint("Open Life OS to connect it.")
            view == null -> Hint("Open Life OS once to load Today.")
            else -> {
                val rows = rows(view, now)
                if (rows.isEmpty()) {
                    Hint("Nothing due, no events ahead.")
                } else {
                    LazyColumn {
                        items(rows.size) { index ->
                            val row = rows[index]
                            Column(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp).clickable(open)) {
                                Text(
                                    row.title,
                                    maxLines = 1,
                                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                                )
                                Text(
                                    row.detail,
                                    maxLines = 1,
                                    style = TextStyle(
                                        color = if (row.warn) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
                                        fontSize = 12.sp,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Events still ahead today and tomorrow first, then the Tasks due, overdue first. */
private fun rows(view: TodayView, now: Instant): List<WidgetRow> {
    val zone = ZoneId.systemDefault()
    val nowTime = now.atZone(zone).toLocalTime()
    val today = view.eventsToday.filter { event ->
        // "09:00–10:00": still listed until it ends; all-day and "until" ones all day.
        val end = Regex("""–(\d{2}:\d{2})$""").find(event.time)?.groupValues?.get(1)
        end == null || runCatching { java.time.LocalTime.parse(end) }.getOrNull()?.isAfter(nowTime) != false
    }.map { WidgetRow(it.title, listOfNotNull(it.time, it.location).joinToString(" · "), warn = false) }
    val tomorrow = view.eventsTomorrow.map { WidgetRow(it.title, "Tomorrow · " + it.time, warn = false) }
    val overdue = view.overdue.map { WidgetRow(it.title, it.detail, warn = true) }
    val due = view.dueToday.map { WidgetRow(it.title, if (it.detail == "Today") "Due today" else "Due " + it.detail, warn = false) }
    return today + overdue + due + tomorrow
}

@Composable
private fun Hint(text: String) {
    Text(text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
}

/** The capture buttons' intents: MainActivity opens the matching capture screen. */
object CaptureLinks {
    const val EXTRA_CAPTURE = "capture"

    /** CLEAR_TOP with SINGLE_TOP: the running MainActivity gets onNewIntent and keeps its screens. */
    private const val FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

    fun intent(context: Context, kind: String): Intent = Intent(context, MainActivity::class.java)
        .setData(Uri.parse("lifeos://capture/$kind"))
        .putExtra(EXTRA_CAPTURE, kind)
        .addFlags(FLAGS)

    fun home(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setData(Uri.parse("lifeos://home"))
        .addFlags(FLAGS)
}

/**
 * The quick-capture widget: Voice, Photo and Text, each straight into its
 * screen. Its content never changes, so it is drawn only when the system
 * asks (placing it, a reboot, an app update) and when the app opens, which
 * also heals an instance that shows something else (BUG-41).
 */
class QuickCaptureWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                Row(
                    GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(20.dp)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CaptureButton(context, "voice", "Voice", R.drawable.ic_mic, primary = true)
                    Spacer(GlanceModifier.width(8.dp))
                    CaptureButton(context, "photo", "Photo", R.drawable.ic_photo_camera)
                    Spacer(GlanceModifier.width(8.dp))
                    CaptureButton(context, "text", "Text", R.drawable.ic_edit)
                }
            }
        }
    }

    companion object {
        /** Draws every Capture widget again, and no other widget. */
        suspend fun refreshAll(context: Context) = renderAll(context, QuickCaptureWidgetReceiver::class.java, QuickCaptureWidget())
    }
}

class QuickCaptureWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickCaptureWidget()
}

@Composable
private fun androidx.glance.layout.RowScope.CaptureButton(context: Context, kind: String, label: String, icon: Int, primary: Boolean = false) {
    val background = if (primary) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.secondaryContainer
    val foreground = if (primary) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSecondaryContainer
    Column(
        GlanceModifier
            .defaultWeight()
            .background(background)
            .cornerRadius(16.dp)
            .padding(vertical = 8.dp)
            .clickable(actionStartActivity(CaptureLinks.intent(context, kind))),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(ImageProvider(icon), contentDescription = label, modifier = GlanceModifier.size(28.dp), colorFilter = androidx.glance.ColorFilter.tint(foreground))
        Text(label, style = TextStyle(color = foreground, fontSize = 13.sp, fontWeight = FontWeight.Medium))
    }
}
