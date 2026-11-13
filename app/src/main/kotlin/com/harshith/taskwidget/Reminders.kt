package com.harshith.taskwidget

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

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

    fun schedule(ctx: Context, id: Long, title: String, atMillis: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending(ctx, id, title))
    }

    fun cancel(ctx: Context, id: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx, id, null))
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
        Reminders.notify(context, id, Reminders.readTitle(intent))
    }
}
