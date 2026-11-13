package com.harshith.taskwidget

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.TimeZone

data class Snapshot(
    val hasPermission: Boolean,
    val today: LocalDate,
    val active: List<Item>,
    val done: Int,
) {
    val total: Int get() = active.size + done
}

object Repo {
    private const val MIN_OWNER_ACCESS = 700
    private const val LOOKBACK_DAYS = 90L

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private val INSTANCE_PROJECTION = arrayOf(
        CalendarContract.Instances.EVENT_ID,        // 0
        CalendarContract.Instances.TITLE,           // 1
        CalendarContract.Instances.BEGIN,           // 2
        CalendarContract.Instances.END,             // 3
        CalendarContract.Instances.ALL_DAY,         // 4
        CalendarContract.Instances.EVENT_COLOR,     // 5
        CalendarContract.Instances.CALENDAR_COLOR,  // 6
        CalendarContract.Instances.RRULE,           // 7
        CalendarContract.Instances.RDATE,           // 8
        CalendarContract.Instances.DESCRIPTION,     // 9
        CalendarContract.Instances.VISIBLE,         // 10
        CalendarContract.Instances.CALENDAR_ACCESS_LEVEL, // 11
        CalendarContract.Instances.SELF_ATTENDEE_STATUS,  // 12
    )

    private fun argb(color: Int): Int = color or (0xFF shl 24)

    private fun readInstance(c: Cursor): Item? {
        if (c.getInt(10) == 0) return null
        if (c.getInt(11) < MIN_OWNER_ACCESS) return null
        if (c.getInt(12) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) return null
        val eventColor = if (c.isNull(5)) 0 else c.getInt(5)
        val color = if (eventColor != 0) eventColor else c.getInt(6)
        return Item(
            id = c.getLong(0),
            title = c.getString(1) ?: "(no title)",
            begin = c.getLong(2),
            end = c.getLong(3),
            allDay = c.getInt(4) == 1,
            color = argb(color),
            recurring = !c.getString(7).isNullOrEmpty() || !c.getString(8).isNullOrEmpty(),
            description = c.getString(9),
        )
    }

    private fun queryRange(ctx: Context, today: LocalDate): List<Item> {
        val from = today.minusDays(LOOKBACK_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val out = ArrayList<Item>()
        ctx.contentResolver.query(uri, INSTANCE_PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) readInstance(c)?.let { out.add(it) }
        }
        return out
    }

    private fun update(ctx: Context, id: Long, values: ContentValues): Boolean {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        return ctx.contentResolver.update(uri, values, null, null) > 0
    }

    fun getItem(ctx: Context, id: Long): Item? {
        val proj = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_COLOR,
            CalendarContract.Events.CALENDAR_COLOR,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.RDATE,
            CalendarContract.Events.DESCRIPTION,
        )
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        ctx.contentResolver.query(uri, proj, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val begin = c.getLong(1)
            val end = if (c.isNull(2)) begin + 30 * 60 * 1000L else c.getLong(2)
            val eventColor = if (c.isNull(4)) 0 else c.getInt(4)
            val color = if (eventColor != 0) eventColor else c.getInt(5)
            return Item(
                id = id,
                title = c.getString(0) ?: "(no title)",
                begin = begin,
                end = end,
                allDay = c.getInt(3) == 1,
                color = argb(color),
                recurring = !c.getString(6).isNullOrEmpty() || !c.getString(7).isNullOrEmpty(),
                description = c.getString(8),
            )
        }
        return null
    }

    /** Stretches every undone, past-due task through today and marks it delayed. */
    fun sync(ctx: Context, today: LocalDate = LocalDate.now(zone)) {
        if (!hasPermission(ctx)) return
        for (item in queryRange(ctx, today)) {
            val change = Rules.stretch(item, today) ?: continue
            val v = ContentValues().apply {
                put(CalendarContract.Events.TITLE, change.first)
                put(CalendarContract.Events.DTEND, change.second)
            }
            update(ctx, item.id, v)
        }
    }

    fun load(ctx: Context, today: LocalDate = LocalDate.now(zone)): Snapshot {
        if (!hasPermission(ctx)) return Snapshot(false, today, emptyList(), 0)
        val all = queryRange(ctx, today)
        val active = Rules.order(all.filter { Rules.isActiveToday(it, today) })
        val done = all.count { Rules.isDoneToday(it, today) }
        return Snapshot(true, today, active, done)
    }

    // ----- actions -----

    fun markDone(ctx: Context, id: Long, today: LocalDate = LocalDate.now(zone)): Boolean {
        val item = getItem(ctx, id) ?: return false
        if (item.recurring) return false
        val p = item.parsed
        val v = ContentValues().apply {
            put(CalendarContract.Events.TITLE, Titles.build(Kind.DONE, 0, p.base))
            if (item.endDay.isBefore(today)) put(CalendarContract.Events.DTEND, Rules.endThrough(item, today))
        }
        Reminders.cancel(ctx, id)
        return update(ctx, id, v)
    }

    /** Moves the block to a new date, and to a new time when [time] is given. Clears any status tag. */
    fun reschedule(ctx: Context, id: Long, date: LocalDate, time: LocalTime?): Boolean {
        val item = getItem(ctx, id) ?: return false
        if (item.recurring) return false
        val p = item.parsed
        val v = ContentValues().apply {
            put(CalendarContract.Events.TITLE, p.base)
        }
        val singleDay = item.startDay == item.endDay
        if (item.allDay && time == null) {
            val start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            v.put(CalendarContract.Events.DTSTART, start)
            v.put(CalendarContract.Events.DTEND, date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        } else {
            val startTime = time ?: if (item.allDay) LocalTime.of(9, 0)
            else java.time.Instant.ofEpochMilli(item.begin).atZone(zone).toLocalTime()
            val begin = LocalDateTime.of(date, startTime).atZone(zone).toInstant().toEpochMilli()
            val length = if (!item.allDay && singleDay) item.end - item.begin else 30 * 60 * 1000L
            v.put(CalendarContract.Events.ALL_DAY, 0)
            v.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            v.put(CalendarContract.Events.DTSTART, begin)
            v.put(CalendarContract.Events.DTEND, begin + maxOf(length, 15 * 60 * 1000L))
            addAlertAtStart(ctx, id)
        }
        Reminders.cancel(ctx, id)
        return update(ctx, id, v)
    }

    /** Marks the task as started but waiting, with an optional note and phone reminder. */
    fun partlyDone(ctx: Context, id: Long, note: String, remindAt: Long?): Boolean {
        val item = getItem(ctx, id) ?: return false
        if (item.recurring) return false
        val p = item.parsed
        val cleaned = (item.description ?: "")
            .replace(Regex("^Follow-up note: .*(\\r?\\n)*", RegexOption.MULTILINE), "")
            .trim()
        val desc = if (note.isBlank()) cleaned else "Follow-up note: ${note.trim()}\n\n$cleaned".trim()
        val v = ContentValues().apply {
            put(CalendarContract.Events.TITLE, Titles.build(Kind.FOLLOWUP, 0, p.base))
            put(CalendarContract.Events.DESCRIPTION, desc)
        }
        val ok = update(ctx, id, v)
        if (ok && remindAt != null) Reminders.schedule(ctx, id, p.base, remindAt) else Reminders.cancel(ctx, id)
        return ok
    }

    private fun addAlertAtStart(ctx: Context, id: Long) {
        val v = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, id)
            put(CalendarContract.Reminders.MINUTES, 0)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        try {
            ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, v)
        } catch (_: Exception) {
        }
    }
}
