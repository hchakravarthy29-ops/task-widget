package com.harshith.taskwidget

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.LocalTime
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
        CalendarContract.Instances.CALENDAR_ID,     // 13
        CalendarContract.Instances.ORGANIZER,       // 14
    )

    private fun argb(color: Int): Int = color or (0xFF shl 24)

    private class Row(val item: Item, val calendarId: Long, val organizer: String?)

    /** Addresses that count as "you" for each calendar: its own address and the Google account. */
    private fun selfAddresses(ctx: Context): Map<Long, Set<String>> {
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.NAME, // a secondary Google calendar's own address
        )
        val out = HashMap<Long, Set<String>>()
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = setOfNotNull(c.getString(1), c.getString(2), c.getString(3))
        }
        return out
    }

    private fun guestsByEvent(ctx: Context, eventIds: Collection<Long>): Map<Long, List<Guest>> {
        if (eventIds.isEmpty()) return emptyMap()
        val proj = arrayOf(
            CalendarContract.Attendees.EVENT_ID,
            CalendarContract.Attendees.ATTENDEE_EMAIL,
            CalendarContract.Attendees.ATTENDEE_TYPE,
        )
        val out = HashMap<Long, MutableList<Guest>>()
        for (chunk in eventIds.distinct().chunked(500)) {
            val where = "${CalendarContract.Attendees.EVENT_ID} IN (${chunk.joinToString(",")})"
            ctx.contentResolver.query(CalendarContract.Attendees.CONTENT_URI, proj, where, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val isRoom = !c.isNull(2) && c.getInt(2) == CalendarContract.Attendees.TYPE_RESOURCE
                    out.getOrPut(c.getLong(0)) { ArrayList() }.add(Guest(c.getString(1), isRoom))
                }
            }
        }
        return out
    }

    /** Drops meetings with other people: the app never shows, renames or stretches them. */
    private fun withoutMeetings(ctx: Context, rows: List<Row>): List<Item> {
        val self = selfAddresses(ctx)
        val guests = guestsByEvent(ctx, rows.map { it.item.id })
        return rows.filterNot {
            Rules.isMeeting(it.organizer, guests[it.item.id].orEmpty(), self[it.calendarId].orEmpty())
        }.map { it.item }
    }

    private fun readInstance(c: Cursor): Row? {
        if (c.getInt(10) == 0) return null
        if (c.getInt(11) < MIN_OWNER_ACCESS) return null
        if (c.getInt(12) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) return null
        val eventColor = if (c.isNull(5)) 0 else c.getInt(5)
        val color = if (eventColor != 0) eventColor else c.getInt(6)
        val item = Item(
            id = c.getLong(0),
            title = c.getString(1) ?: "(no title)",
            begin = c.getLong(2),
            end = c.getLong(3),
            allDay = c.getInt(4) == 1,
            color = argb(color),
            recurring = !c.getString(7).isNullOrEmpty() || !c.getString(8).isNullOrEmpty(),
            description = c.getString(9),
        )
        return Row(item, c.getLong(13), c.getString(14))
    }

    private fun queryRange(ctx: Context, today: LocalDate): List<Item> {
        val from = today.minusDays(LOOKBACK_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val rows = ArrayList<Row>()
        ctx.contentResolver.query(uri, INSTANCE_PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) readInstance(c)?.let { rows.add(it) }
        }
        return withoutMeetings(ctx, withEventTimes(ctx, rows))
    }

    /**
     * Replaces each one-off event's title and times with the Events table's own values.
     * The Instances table can lag behind our writes; reading a stale end there made the
     * stretch run again on every refresh (one event reached DELAYED +33 in minutes).
     */
    private fun withEventTimes(ctx: Context, rows: List<Row>): List<Row> {
        val ids = rows.filter { !it.item.recurring }.map { it.item.id }.distinct()
        if (ids.isEmpty()) return rows
        val proj = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
        )
        class Times(val title: String?, val begin: Long, val end: Long?, val allDay: Boolean)
        val fresh = HashMap<Long, Times>()
        for (chunk in ids.chunked(500)) {
            val where = "${CalendarContract.Events._ID} IN (${chunk.joinToString(",")})"
            ctx.contentResolver.query(CalendarContract.Events.CONTENT_URI, proj, where, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.isNull(2)) continue
                    fresh[c.getLong(0)] = Times(
                        c.getString(1), c.getLong(2), if (c.isNull(3)) null else c.getLong(3), c.getInt(4) == 1,
                    )
                }
            }
        }
        return rows.map { row ->
            val t = fresh[row.item.id] ?: return@map row
            if (row.item.recurring) return@map row
            val item = row.item.copy(
                title = t.title ?: row.item.title,
                begin = t.begin,
                end = t.end ?: row.item.end,
                allDay = t.allDay,
            )
            Row(item, row.calendarId, row.organizer)
        }
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

    private const val PREFS = "task_widget"
    private const val KEY_SINCE = "trackingSinceEpochDay"

    /** The first day the app synced with calendar access. Only tasks from then on can be delayed. */
    fun trackingSince(ctx: Context, today: LocalDate = LocalDate.now(zone)): LocalDate {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SINCE)) prefs.edit().putLong(KEY_SINCE, today.toEpochDay()).apply()
        return LocalDate.ofEpochDay(prefs.getLong(KEY_SINCE, today.toEpochDay()))
    }

    private const val STRETCHED = "stretched_on"

    /**
     * Stretches every undone, past-due task through today and marks it delayed.
     * Each event is stretched at most once per day (remembered here), so even if the calendar
     * reports an out-of-date end time, the delay can only grow by one step a day.
     */
    @Synchronized // the app screen and the widget can refresh at the same moment
    fun sync(ctx: Context, today: LocalDate = LocalDate.now(zone)) {
        if (!hasPermission(ctx)) return
        val since = trackingSince(ctx, today)
        val done =ctx.getSharedPreferences(STRETCHED, Context.MODE_PRIVATE)
        val day = today.toEpochDay()
        // Forget days before today so the store stays small.
        done.all.filter { (it.value as? Long ?: 0L) < day }.keys.let { old ->
            if (old.isNotEmpty()) done.edit().apply { old.forEach { remove(it) } }.apply()
        }
        for (listed in queryRange(ctx, today)) {
            if (done.getLong("e${listed.id}", -1L) == day) continue
            if (Rules.stretch(listed, today, since) == null) continue
            // Decide from the event as it is right now, not from the list we read a moment ago.
            val item = getItem(ctx, listed.id) ?: continue
            val change = Rules.stretch(item, today, since) ?: continue
            val v = ContentValues().apply {
                put(CalendarContract.Events.TITLE, change.first)
                put(CalendarContract.Events.DTEND, change.second)
            }
            if (update(ctx, item.id, v)) done.edit().putLong("e${item.id}", day).commit()
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

    /**
     * Marks the task done. Returns the task as it was before, for [undoDone], or null when nothing
     * changed. The follow-up reminder is left alone so an undo keeps it; the caller cancels it.
     */
    fun markDone(ctx: Context, id: Long, today: LocalDate = LocalDate.now(zone)): Item? {
        val item = getItem(ctx, id) ?: return null
        // Already done: changing nothing keeps any Undo pointing at the real earlier state.
        if (item.recurring || item.parsed.kind == Kind.DONE) return null
        val (title, end) = Rules.done(item, today)
        val v = ContentValues().apply {
            put(CalendarContract.Events.TITLE, title)
            if (end != null) put(CalendarContract.Events.DTEND, end)
        }
        return if (update(ctx, id, v)) item else null
    }

    /** Puts back the title, and the end time only if [markDone] changed it. */
    fun undoDone(ctx: Context, before: Item): Boolean {
        val now = getItem(ctx, before.id)
        val v = ContentValues().apply {
            put(CalendarContract.Events.TITLE, before.title)
            if (now != null && now.end != before.end) put(CalendarContract.Events.DTEND, before.end)
        }
        return update(ctx, before.id, v)
    }

    /**
     * Moves the block to a new date, and to a new time when [time] is given. Reschedule clears any
     * status tag and follow-up reminder; Later today ([keepStatus]) keeps both.
     */
    fun reschedule(ctx: Context, id: Long, date: LocalDate, time: LocalTime?, keepStatus: Boolean = false): Boolean {
        val item = getItem(ctx, id) ?: return false
        if (item.recurring) return false
        val v = ContentValues().apply {
            put(CalendarContract.Events.TITLE, Rules.movedTitle(item, keepStatus))
        }
        val slot = Rules.rescheduleSlot(item, date, time)
        val toTimed = item.allDay && !slot.allDay
        if (toTimed) {
            // Only when an all-day event becomes timed; a timed event keeps its own time zone.
            v.put(CalendarContract.Events.ALL_DAY, 0)
            v.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        v.put(CalendarContract.Events.DTSTART, slot.begin)
        v.put(CalendarContract.Events.DTEND, slot.end)
        val ok = update(ctx, id, v)
        if (ok && !keepStatus) Reminders.cancel(ctx, id)
        // A timed event already has the user's own alerts; only a converted all-day one gets one,
        // and only once the provider has accepted the update.
        if (ok && toTimed) addAlertAtStart(ctx, id)
        return ok
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
