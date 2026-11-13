package com.harshith.taskwidget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.util.Locale

private val BG = Color(0xFF16171A)
private val FG = Color(0xFFECECEC)
private val MUTED = Color(0xFF9A9CA3)
private val TRACK = Color(0xFF2A2C31)
private val BLUE = Color(0xFF7FB0FF)
private val RED = Color(0xFFE57373)
private val AMBER = Color(0xFFFFB74D)

private data class TData(
    val id: Long,
    val title: String,
    val sub: String,
    val subColor: Color,
    val dot: Color,
)

class TaskWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = withContext(Dispatchers.IO) {
            Repo.sync(context)
            Repo.load(context)
        }
        val rows = snap.active.map { toRow(it) }
        val dateText = snap.today.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        provideContent {
            Content(context, snap, rows, dateText)
        }
    }
}

private fun toRow(i: Item): TData {
    val p = i.parsed
    val dot = Color(i.color)
    return when (p.kind) {
        Kind.DELAYED -> TData(i.id, p.base, "Delayed +${p.delayDays}", RED, dot)
        Kind.FOLLOWUP -> TData(i.id, p.base, "Follow up" + (i.followNote?.let { " · $it" } ?: ""), AMBER, dot)
        else -> {
            val time = if (i.allDay) "All day" else
                Instant.ofEpochMilli(i.begin).atZone(zone)
                    .format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
            TData(i.id, p.base, time, MUTED, dot)
        }
    }
}

@Composable
private fun Content(context: Context, snap: Snapshot, rows: List<TData>, dateText: String) {
    Column(
        modifier = GlanceModifier.fillMaxSize().background(ColorProvider(BG)).padding(14.dp)
    ) {
        androidx.glance.layout.Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = dateText,
                style = TextStyle(color = ColorProvider(FG), fontSize = 16.sp, fontWeight = FontWeight.Medium),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = "${snap.done} of ${snap.total} done",
                style = TextStyle(color = ColorProvider(MUTED), fontSize = 13.sp)
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        LinearProgressIndicator(
            progress = if (snap.total == 0) 0f else snap.done.toFloat() / snap.total,
            modifier = GlanceModifier.fillMaxWidth(),
            color = ColorProvider(BLUE),
            backgroundColor = ColorProvider(TRACK)
        )
        Spacer(GlanceModifier.height(8.dp))
        when {
            !snap.hasPermission -> Text(
                text = "Open Task Widget once to allow calendar access.",
                style = TextStyle(color = ColorProvider(MUTED), fontSize = 13.sp)
            )
            rows.isEmpty() -> Text(
                text = "Nothing left today.",
                style = TextStyle(color = ColorProvider(MUTED), fontSize = 13.sp)
            )
            else -> LazyColumn {
                items(rows, itemId = { it.id }) { r -> TaskRow(context, r) }
            }
        }
    }
}

@Composable
private fun TaskRow(context: Context, r: TData) {
    val open = Intent(context, ActionActivity::class.java).apply {
        putExtra(ActionActivity.EXTRA_EVENT_ID, r.id)
        data = Uri.parse("taskwidget://task/${r.id}")
    }
    // A separate data URI keeps this PendingIntent distinct from the text tap's.
    val done = Intent(context, ActionActivity::class.java).apply {
        putExtra(ActionActivity.EXTRA_EVENT_ID, r.id)
        putExtra(ActionActivity.EXTRA_DONE_NOW, true)
        data = Uri.parse("taskwidget://done/${r.id}")
    }
    val view = Intent(Intent.ACTION_VIEW).apply {
        data = Uri.parse("content://com.android.calendar/events/${r.id}")
    }
    androidx.glance.layout.Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = GlanceModifier
                .size(24.dp)
                .background(ColorProvider(r.dot))
                .cornerRadius(12.dp)
                .clickable(actionStartActivity(done)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_check),
                contentDescription = "Mark done",
                modifier = GlanceModifier.size(14.dp),
                colorFilter = ColorFilter.tint(ColorProvider(BG))
            )
        }
        Spacer(GlanceModifier.size(10.dp))
        Column(
            modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(open))
        ) {
            Text(
                text = r.title,
                maxLines = 2,
                style = TextStyle(color = ColorProvider(FG), fontSize = 14.sp)
            )
            Text(
                text = r.sub,
                maxLines = 1,
                style = TextStyle(color = ColorProvider(r.subColor), fontSize = 12.sp)
            )
        }
        Image(
            provider = ImageProvider(R.drawable.ic_calendar),
            contentDescription = "Open in Google Calendar",
            modifier = GlanceModifier.size(20.dp).clickable(actionStartActivity(view)),
            colorFilter = ColorFilter.tint(ColorProvider(MUTED))
        )
    }
}

class TaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TaskWidget()
}
