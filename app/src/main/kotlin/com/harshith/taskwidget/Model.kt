package com.harshith.taskwidget

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

enum class Kind { NORMAL, DONE, DELAYED, FOLLOWUP }

data class Parsed(val kind: Kind, val delayDays: Int, val base: String)

object Titles {
    const val DONE_PREFIX = "✅ DONE – "
    const val FOLLOWUP_PREFIX = "🔁 FOLLOW-UP – "
    fun delayedPrefix(n: Int) = "⌛ DELAYED +$n – "

    // ️ is the invisible "show as emoji" mark some keyboards add after an emoji.
    // ⏳ (sand flowing) is accepted as well as ⌛ because the two are easy to mix up.
    private val doneRe = Regex("^\\s*✅\\uFE0F?\\s*DONE\\s*[–—-]\\s*", RegexOption.IGNORE_CASE)
    private val delayedRe = Regex("^\\s*[⌛⏳]\\uFE0F?\\s*DELAYED\\s*\\+(\\d+)\\s*[–—-]\\s*", RegexOption.IGNORE_CASE)
    private val followRe = Regex("^\\s*🔁\\uFE0F?\\s*FOLLOW-?UP\\s*[–—-]\\s*", RegexOption.IGNORE_CASE)

    fun parse(title: String): Parsed {
        doneRe.find(title)?.let { return Parsed(Kind.DONE, 0, title.substring(it.range.last + 1)) }
        delayedRe.find(title)?.let {
            val n = it.groupValues[1].toIntOrNull() ?: 1
            return Parsed(Kind.DELAYED, n, title.substring(it.range.last + 1))
        }
        followRe.find(title)?.let { return Parsed(Kind.FOLLOWUP, 0, title.substring(it.range.last + 1)) }
        return Parsed(Kind.NORMAL, 0, title)
    }

    fun build(kind: Kind, delayDays: Int, base: String): String = when (kind) {
        Kind.NORMAL -> base
        Kind.DONE -> DONE_PREFIX + base
        Kind.DELAYED -> delayedPrefix(delayDays) + base
        Kind.FOLLOWUP -> FOLLOWUP_PREFIX + base
    }
}

val zone: ZoneId get() = ZoneId.systemDefault()

data class Item(
    val id: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
    val recurring: Boolean,
    val description: String?,
) {
    val parsed: Parsed get() = Titles.parse(title)

    private fun dayOf(millis: Long): LocalDate {
        val z: ZoneId = if (allDay) ZoneOffset.UTC else zone
        return Instant.ofEpochMilli(millis).atZone(z).toLocalDate()
    }

    val startDay: LocalDate get() = dayOf(begin)
    val endDay: LocalDate get() = dayOf(maxOf(begin, end - 1))

    val followNote: String?
        get() {
            val d = description ?: return null
            val m = Regex("^Follow-up note: (.*)$", RegexOption.MULTILINE).find(d) ?: return null
            return m.groupValues[1].trim().ifEmpty { null }
        }
}

/** Someone on an event's guest list. Meeting rooms are resources, not people. */
data class Guest(val email: String?, val isResource: Boolean = false)

object Rules {
    /**
     * True for a meeting with other people: someone else organised it, or a guest other than
     * you is invited. [self] holds the addresses that are you (calendar address, Google account).
     */
    fun isMeeting(organizer: String?, guests: List<Guest>, self: Set<String>): Boolean {
        val me = self.map { it.trim().lowercase() }.toSet()
        fun isMe(email: String?) = email.isNullOrBlank() || email.trim().lowercase() in me
        if (!isMe(organizer)) return true
        return guests.any { !it.isResource && !isMe(it.email) }
    }

    /** New end time (millis) that makes the block run through [today]. */
    fun endThrough(item: Item, today: LocalDate): Long {
        return if (item.allDay) {
            today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            val oldEnd = Instant.ofEpochMilli(item.end).atZone(zone).toLocalTime()
            // An end at exactly midnight belongs to the previous day (see Item.endDay), so it must
            // become the midnight after today, or the block would still look late and re-stretch.
            val endAt = if (oldEnd == LocalTime.MIDNIGHT) today.plusDays(1).atStartOfDay(zone)
            else today.atTime(oldEnd).atZone(zone)
            val candidate = endAt.toInstant().toEpochMilli()
            if (candidate > item.begin) candidate else item.begin + 30 * 60 * 1000L
        }
    }

    /** The title and new end (null = keep) when the task is marked done on [today]. */
    fun done(item: Item, today: LocalDate): Pair<String, Long?> {
        val title = Titles.build(Kind.DONE, 0, item.parsed.base)
        // Done ends the block on the completion day, whether it was late or still running ahead.
        val end = if (item.endDay != today) endThrough(item, today) else null
        return title to end
    }

    data class Slot(val begin: Long, val end: Long, val allDay: Boolean)

    /**
     * A block made longer by the app (delayed or follow-up carry-over), not by the user.
     * Its extra days aren't part of the task, so moving it uses one day's worth.
     */
    private fun isStretched(item: Item) =
        item.parsed.kind == Kind.DELAYED || item.parsed.kind == Kind.FOLLOWUP

    /**
     * The title after a move. Reschedule clears any status tag; Later today ([keepStatus]) keeps
     * a delayed or follow-up tag, so the task stays delayed or a follow-up.
     */
    fun movedTitle(item: Item, keepStatus: Boolean): String {
        val p = item.parsed
        return if (keepStatus && (p.kind == Kind.DELAYED || p.kind == Kind.FOLLOWUP)) item.title else p.base
    }

    /**
     * Where the block lands when moved to [date], and to [time] when given. A task the user made
     * several days long keeps all its days; a stretched one moves as a single day.
     */
    fun rescheduleSlot(item: Item, date: LocalDate, time: LocalTime?): Slot {
        val singleDay = item.startDay == item.endDay
        if (item.allDay && time == null) {
            val days = if (isStretched(item)) 1L else ChronoUnit.DAYS.between(item.startDay, item.endDay) + 1
            return Slot(
                date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                date.plusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                allDay = true,
            )
        }
        val startTime = time ?: if (item.allDay) LocalTime.of(9, 0)
        else Instant.ofEpochMilli(item.begin).atZone(zone).toLocalTime()
        val begin = LocalDateTime.of(date, startTime).atZone(zone).toInstant().toEpochMilli()
        val length = when {
            item.allDay -> 30 * 60 * 1000L
            singleDay || !isStretched(item) -> item.end - item.begin
            else -> {
                // A stretched (delayed or follow-up) block: keep the task's length within a day,
                // e.g. 10:00-11:00 stretched over three days moves as one hour.
                val s = Instant.ofEpochMilli(item.begin).atZone(zone).toLocalTime()
                val e = Instant.ofEpochMilli(item.end).atZone(zone).toLocalTime()
                val diff = java.time.Duration.between(s, e).toMillis()
                if (diff > 0) diff else 30 * 60 * 1000L
            }
        }
        return Slot(begin, begin + maxOf(length, 15 * 60 * 1000L), allDay = false)
    }

    /**
     * The title and end time after the nightly stretch, or null when nothing changes.
     * Events that ended before [since] (the day the app was first used) are left alone, so
     * installing the app does not turn months of old calendar events into delayed tasks.
     */
    fun stretch(item: Item, today: LocalDate, since: LocalDate = LocalDate.MIN): Pair<String, Long>? {
        if (item.recurring) return null
        val p = item.parsed
        if (p.kind == Kind.DONE) return null
        val endDay = item.endDay
        if (endDay.isBefore(since)) return null
        if (!endDay.isBefore(today)) return null
        val late = ChronoUnit.DAYS.between(endDay, today).toInt()
        val title = when (p.kind) {
            Kind.NORMAL -> Titles.build(Kind.DELAYED, late, p.base)
            Kind.DELAYED -> Titles.build(Kind.DELAYED, p.delayDays + late, p.base)
            else -> item.title
        }
        return title to endThrough(item, today)
    }

    fun isActiveToday(item: Item, today: LocalDate): Boolean {
        val k = item.parsed.kind
        if (k == Kind.DONE) return false
        return !item.startDay.isAfter(today) && !item.endDay.isBefore(today)
    }

    fun isDoneToday(item: Item, today: LocalDate): Boolean {
        return item.parsed.kind == Kind.DONE && item.endDay == today
    }

    /** Delayed first (most late first), then today's tasks by time, then follow-ups. */
    fun order(items: List<Item>): List<Item> {
        fun rank(i: Item) = when (i.parsed.kind) {
            Kind.DELAYED -> 0
            Kind.NORMAL -> 1
            Kind.FOLLOWUP -> 2
            Kind.DONE -> 3
        }
        return items.sortedWith(
            compareBy<Item> { rank(it) }
                .thenByDescending { if (it.parsed.kind == Kind.DELAYED) it.parsed.delayDays else 0 }
                .thenByDescending { it.allDay }
                .thenBy { it.begin }
        )
    }
}
