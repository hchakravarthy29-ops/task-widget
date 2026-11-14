package com.harshith.taskwidget

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object Reminders {
    const val CHANNEL_ID = "followups"
    private const val EXTRA_ID = "eventId"
    private const val EXTRA_TITLE = "title"

    private fun pending(ctx: Context, id: Long, title: String?): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_ID, id)
            if (title != null) putExtra(EXTRA_TITLE, title)
        }
        return PendingIntent.getBroadcast(
            ctx, id.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // Android drops all alarms on restart (and on app update), so each reminder is also saved
    // here and set again by BootReceiver. Key "r<eventId>", value "<atMillis>\n<title>".
    private const val STORE = "reminders"
    private fun store(ctx: Context) = ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun schedule(ctx: Context, id: Long, title: String, atMillis: Long) {
        store(ctx).edit().putString("r$id", "$atMillis\n$title").apply()
        setAlarm(ctx, id, title, atMillis)
    }

    private fun setAlarm(ctx: Context, id: Long, title: String, atMillis: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending(ctx, id, title))
    }

    fun cancel(ctx: Context, id: Long) {
        forget(ctx, id)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx, id, null))
    }

    fun forget(ctx: Context, id: Long) {
        store(ctx).edit().remove("r$id").apply()
    }

    /** Sets every saved reminder again. Ones that came due while the phone was off fire now. */
    fun restoreAll(ctx: Context) {
        val now = System.currentTimeMillis()
        for ((key, value) in store(ctx).all) {
            val id = key.removePrefix("r").toLongOrNull() ?: continue
            val parts = (value as? String)?.split("\n", limit = 2) ?: continue
            val at = parts[0].toLongOrNull() ?: continue
            val title = parts.getOrNull(1) ?: "A task needs a follow-up"
            setAlarm(ctx, id, title, maxOf(at, now + 5_000L))
        }
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Follow-up reminders", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    fun notify(ctx: Context, id: Long, title: String) {
        ensureChannel(ctx)
        val open = Intent(ctx, ActionActivity::class.java).apply {
            putExtra(ActionActivity.EXTRA_EVENT_ID, id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val tap = PendingIntent.getActivity(
            ctx, id.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle("Follow up")
            .setContentText(title)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(id.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }

    fun readId(i: Intent): Long = i.getLongExtra(EXTRA_ID, -1L)
    fun readTitle(i: Intent): String = i.getStringExtra(EXTRA_TITLE) ?: "A task needs a follow-up"
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = Reminders.readId(intent)
        if (id < 0) return
        Reminders.forget(context, id)
        val title = Reminders.readTitle(intent)
        val pending = goAsync()
        // The calendar lookup runs off the main thread so a cold provider can't stall the receiver.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Skip it if the task was finished or deleted elsewhere (Google Calendar, Claude in chat).
                // If the lookup itself fails, remind anyway: a spare reminder beats a lost one.
                val skip = try {
                    if (Repo.hasPermission(context)) {
                        val item = Repo.getItem(context, id)
                        item == null || item.parsed.kind == Kind.DONE
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    false
                }
                if (!skip) Reminders.notify(context, id, title)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Puts saved follow-up reminders back after a restart or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Reminders.restoreAll(context)
        }
    }
}
