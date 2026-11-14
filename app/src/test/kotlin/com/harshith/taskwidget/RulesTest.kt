package com.harshith.taskwidget

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * Rules run in the phone's zone. Tests pin it to America/New_York, which has DST and sits
 * behind UTC, so all-day (UTC) events fall on the previous local evening if handled wrongly.
 */
class RulesTest {
    private val ny: ZoneId = ZoneId.of("America/New_York")
    private lateinit var saved: TimeZone
    private val today = LocalDate.of(2026, 11, 14)
    private var nextId = 1L

    @Before
    fun pinZone() {
        saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ny))
    }

    @After
    fun restoreZone() = TimeZone.setDefault(saved)

    private fun ms(t: LocalDateTime) = t.atZone(ny).toInstant().toEpochMilli()
    private fun utcMidnight(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun local(millis: Long) = Instant.ofEpochMilli(millis).atZone(ny).toLocalDateTime()

    private fun timed(title: String, start: LocalDateTime, end: LocalDateTime, recurring: Boolean = false) =
        Item(nextId++, title, ms(start), ms(end), false, 0, recurring, null)

    /** All-day from [first] through [last] inclusive, stored as UTC midnights like CalendarContract. */
    private fun allDay(title: String, first: LocalDate, last: LocalDate = first) =
        Item(nextId++, title, utcMidnight(first), utcMidnight(last.plusDays(1)), true, 0, false, null)

    private fun Item.after(change: Pair<String, Long>?): Item {
        assertNotNull("expected a stretch", change)
        return copy(title = change!!.first, end = change.second)
    }

    // ----- stretch: delayed -----

    @Test
    fun timedTaskOneDayLate() {
        val item = timed("Call bank", LocalDateTime.of(2026, 11, 13, 10, 0), LocalDateTime.of(2026, 11, 13, 11, 0))
        val s = item.after(Rules.stretch(item, today))
        assertEquals("⌛ DELAYED +1 – Call bank", s.title)
        assertEquals(item.begin, s.begin)
        assertEquals(LocalDateTime.of(2026, 11, 14, 11, 0), local(s.end))
        assertEquals(LocalDate.of(2026, 11, 13), s.startDay)
        assertEquals(today, s.endDay)
        assertTrue(Rules.isActiveToday(s, today))
    }

    @Test
    fun timedTaskThreeDaysLate() {
        val item = timed("Call bank", LocalDateTime.of(2026, 11, 11, 10, 0), LocalDateTime.of(2026, 11, 11, 11, 0))
        val s = item.after(Rules.stretch(item, today))
        assertEquals("⌛ DELAYED +3 – Call bank", s.title)
        assertEquals(LocalDateTime.of(2026, 11, 14, 11, 0), local(s.end))
    }

    @Test
    fun delayGrowsEachDay() {
        var item = timed("Call bank", LocalDateTime.of(2026, 11, 13, 10, 0), LocalDateTime.of(2026, 11, 13, 11, 0))
        for (n in 1..3) {
            val day = LocalDate.of(2026, 11, 13).plusDays(n.toLong())
            item = item.after(Rules.stretch(item, day))
            assertEquals("⌛ DELAYED +$n – Call bank", item.title)
            assertEquals(day, item.endDay)
        }
    }

    @Test
    fun delayCatchesUpAfterMissedDays() {
        // Delayed +1 through the 12th, then the phone was off until the 14th.
        val item = timed("⌛ DELAYED +1 – Call bank", LocalDateTime.of(2026, 11, 11, 10, 0), LocalDateTime.of(2026, 11, 12, 11, 0))
        assertEquals("⌛ DELAYED +3 – Call bank", item.after(Rules.stretch(item, today)).title)
    }

    @Test
    fun stretchIsIdempotentWithinADay() {
        // provideGlance runs sync on every update; a second pass the same day must change nothing.
        val items = listOf(
            timed("Call bank", LocalDateTime.of(2026, 11, 13, 10, 0), LocalDateTime.of(2026, 11, 13, 11, 0)),
            timed("Late night", LocalDateTime.of(2026, 11, 12, 22, 0), LocalDateTime.of(2026, 11, 13, 0, 0)),
            allDay("Groceries", LocalDate.of(2026, 11, 12)),
            timed("🔁 FOLLOW-UP – Report", LocalDateTime.of(2026, 11, 13, 9, 0), LocalDateTime.of(2026, 11, 13, 9, 30)),
        )
        for (item in items) {
            val once = item.after(Rules.stretch(item, today))
            assertNull("re-stretched: ${once.title}", Rules.stretch(once, today))
        }
    }

    @Test
    fun timedTaskEndingAtMidnightStretchesToNextMidnight() {
        // Regression: an end of 00:00 used to become today 00:00, which still reads as yesterday,
        // so every widget update added another day and the task vanished from the list.
        val item = timed("Late night", LocalDateTime.of(2026, 11, 12, 22, 0), LocalDateTime.of(2026, 11, 13, 0, 0))
        assertEquals(LocalDate.of(2026, 11, 12), item.endDay)
        val s = item.after(Rules.stretch(item, today))
        assertEquals("⌛ DELAYED +2 – Late night", s.title)
        assertEquals(LocalDateTime.of(2026, 11, 15, 0, 0), local(s.end))
        assertEquals(today, s.endDay)
        assertTrue(Rules.isActiveToday(s, today))
    }

    @Test
    fun timedStretchKeepsWallClockEndAcrossDst() {
        // US DST ended on 1 Nov 2026: 11:00 EDT on 31 Oct should stretch to 11:00 EST on 2 Nov.
        val item = timed("Call bank", LocalDateTime.of(2026, 10, 31, 10, 0), LocalDateTime.of(2026, 10, 31, 11, 0))
        val s = item.after(Rules.stretch(item, LocalDate.of(2026, 11, 2)))
        assertEquals("⌛ DELAYED +2 – Call bank", s.title)
        assertEquals(LocalDateTime.of(2026, 11, 2, 11, 0), local(s.end))
    }

    // ----- all-day vs timed -----

    @Test
    fun allDayDaysAreReadInUtcNotLocal() {
        // 12 Nov 00:00 UTC is 11 Nov 19:00 in New York; the task still belongs to the 12th.
        val item = allDay("Groceries", LocalDate.of(2026, 11, 12))
        assertEquals(LocalDate.of(2026, 11, 12), item.startDay)
        assertEquals(LocalDate.of(2026, 11, 12), item.endDay)
    }

    @Test
    fun allDayTaskLateStretchesToUtcMidnightAfterToday() {
        val item = allDay("Groceries", LocalDate.of(2026, 11, 12))
        val s = item.after(Rules.stretch(item, today))
        assertEquals("⌛ DELAYED +2 – Groceries", s.title)
        assertEquals(item.begin, s.begin)
        assertEquals(utcMidnight(LocalDate.of(2026, 11, 15)), s.end)
        assertEquals(today, s.endDay)
        assertTrue(Rules.isActiveToday(s, today))
    }

    @Test
    fun multiDayAllDayRunningThroughTodayIsNotLate() {
        val item = allDay("Trip", LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 15))
        assertNull(Rules.stretch(item, today))
        assertTrue(Rules.isActiveToday(item, today))
    }

    @Test
    fun todayFutureRecurringAndDoneAreNotStretched() {
        val at = { d: Int, h: Int -> LocalDateTime.of(2026, 11, d, h, 0) }
        assertNull(Rules.stretch(timed("Today", at(14, 9), at(14, 10)), today))
        assertNull(Rules.stretch(timed("Tomorrow", at(15, 9), at(15, 10)), today))
        assertNull(Rules.stretch(timed("Standup", at(10, 9), at(10, 10), recurring = true), today))
        assertNull(Rules.stretch(timed("✅ DONE – Old", at(10, 9), at(10, 10)), today))
    }

    // ----- tracking starts on install day -----

    @Test
    fun eventsEndedBeforeInstallDayAreLeftAlone() {
        val installed = LocalDate.of(2026, 11, 14)
        val oldMeeting = timed("Team meeting", LocalDateTime.of(2026, 10, 2, 10, 0), LocalDateTime.of(2026, 10, 2, 11, 0))
        val yesterday = allDay("Birthday", LocalDate.of(2026, 11, 13))
        assertNull(Rules.stretch(oldMeeting, today, since = installed))
        assertNull(Rules.stretch(yesterday, today, since = installed))
    }

    @Test
    fun tasksFromInstallDayOnAreStillDelayed() {
        val installed = LocalDate.of(2026, 11, 14)
        val item = timed("Call bank", LocalDateTime.of(2026, 11, 14, 10, 0), LocalDateTime.of(2026, 11, 14, 11, 0))
        val nextDay = LocalDate.of(2026, 11, 15)
        val s = item.after(Rules.stretch(item, nextDay, since = installed))
        assertEquals("⌛ DELAYED +1 – Call bank", s.title)
        // Once stretched it ends on or after install day, so it keeps growing.
        assertEquals("⌛ DELAYED +2 – Call bank", s.after(Rules.stretch(s, nextDay.plusDays(1), since = installed)).title)
    }

    @Test
    fun eventRunningAcrossInstallDayIsTracked() {
        val installed = LocalDate.of(2026, 11, 13)
        val trip = allDay("Pack for trip", LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 13))
        assertEquals("⌛ DELAYED +1 – Pack for trip", trip.after(Rules.stretch(trip, today, since = installed)).title)
    }

    // ----- follow-up -----

    @Test
    fun followUpCarriesOverWithoutBecomingDelayed() {
        val item = timed("🔁 FOLLOW-UP – Report", LocalDateTime.of(2026, 11, 12, 9, 0), LocalDateTime.of(2026, 11, 12, 9, 30))
        val s = item.after(Rules.stretch(item, today))
        assertEquals("🔁 FOLLOW-UP – Report", s.title)
        assertEquals(item.begin, s.begin)
        assertEquals(LocalDateTime.of(2026, 11, 14, 9, 30), local(s.end))
        assertTrue(Rules.isActiveToday(s, today))
    }

    // ----- done -----

    @Test
    fun delayedTaskThenDoneToday() {
        val item = timed("Call bank", LocalDateTime.of(2026, 11, 12, 10, 0), LocalDateTime.of(2026, 11, 12, 11, 0))
        val delayed = item.after(Rules.stretch(item, today))
        val (title, end) = Rules.done(delayed, today)
        assertEquals("✅ DONE – Call bank", title)
        assertNull("already runs through today", end)
        val done = delayed.copy(title = title)
        assertFalse(Rules.isActiveToday(done, today))
        assertTrue(Rules.isDoneToday(done, today))
        assertNull("done stops the stretch", Rules.stretch(done, today.plusDays(1)))
    }

    @Test
    fun doneBeforeSyncEndsBlockOnCompletionDay() {
        // Delayed through yesterday, marked done before today's sync ran.
        val item = timed("⌛ DELAYED +1 – Call bank", LocalDateTime.of(2026, 11, 12, 10, 0), LocalDateTime.of(2026, 11, 13, 11, 0))
        val (title, end) = Rules.done(item, today)
        assertEquals("✅ DONE – Call bank", title)
        assertEquals(LocalDateTime.of(2026, 11, 14, 11, 0), local(end!!))
        assertTrue(Rules.isDoneToday(item.copy(title = title, end = end), today))
    }

    @Test
    fun doneEarlyOnMultiDayBlockEndsItToday() {
        // A Fri-to-Mon block finished on Sat must end today and count as done today.
        val item = timed("Move house", LocalDateTime.of(2026, 11, 13, 10, 0), LocalDateTime.of(2026, 11, 16, 12, 0))
        val (title, end) = Rules.done(item, today)
        assertEquals(LocalDateTime.of(2026, 11, 14, 12, 0), local(end!!))
        val done = item.copy(title = title, end = end)
        assertTrue(Rules.isDoneToday(done, today))
        assertFalse(Rules.isActiveToday(done, today))

        val trip = allDay("Trip prep", LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 16))
        val (_, tripEnd) = Rules.done(trip, today)
        assertEquals(utcMidnight(LocalDate.of(2026, 11, 15)), tripEnd)
    }

    @Test
    fun rescheduleStretchedBlockEndingBeforeStartTimeUsesHalfHour() {
        // 22:00 start stretched to an 09:00 end: no sensible same-day length, so 30 minutes.
        val item = timed("⌛ DELAYED +1 – Odd", LocalDateTime.of(2026, 11, 12, 22, 0), LocalDateTime.of(2026, 11, 14, 9, 0))
        val slot = Rules.rescheduleSlot(item, LocalDate.of(2026, 11, 15), null)
        assertEquals(30 * 60 * 1000L, slot.end - slot.begin)
    }

    @Test
    fun doneYesterdayIsNotCountedToday() {
        val item = timed("✅ DONE – Call bank", LocalDateTime.of(2026, 11, 13, 10, 0), LocalDateTime.of(2026, 11, 13, 11, 0))
        assertFalse(Rules.isDoneToday(item, today))
        assertFalse(Rules.isActiveToday(item, today))
    }

    // ----- order -----

    @Test
    fun orderIsDelayedThenTodayThenFollowUps() {
        val at = { h: Int -> LocalDateTime.of(2026, 11, 14, h, 0) }
        val d1 = timed("⌛ DELAYED +1 – a", at(8), at(9))
        val d3 = timed("⌛ DELAYED +3 – b", at(8), at(9))
        val nine = timed("nine", at(9), at(10))
        val eight = timed("eight", at(8), at(9))
        val whole = allDay("whole day", today)
        val follow = timed("🔁 FOLLOW-UP – c", at(7), at(8))
        val ordered = Rules.order(listOf(follow, nine, d1, whole, eight, d3))
        assertEquals(listOf(d3, d1, whole, eight, nine, follow), ordered)
    }

    // ----- reschedule -----

    @Test
    fun rescheduleTimedKeepsTimeAndLength() {
        val item = timed("Call bank", LocalDateTime.of(2026, 11, 14, 10, 0), LocalDateTime.of(2026, 11, 14, 11, 30))
        val slot = Rules.rescheduleSlot(item, LocalDate.of(2026, 11, 16), null)
        assertFalse(slot.allDay)
        assertEquals(LocalDateTime.of(2026, 11, 16, 10, 0), local(slot.begin))
        assertEquals(LocalDateTime.of(2026, 11, 16, 11, 30), local(slot.end))
    }

    @Test
    fun rescheduleTimedToNewTime() {
        val item = timed("Call bank", LocalDateTime.of(2026, 11, 14, 10, 0), LocalDateTime.of(2026, 11, 14, 11, 0))
        val slot = Rules.rescheduleSlot(item, LocalDate.of(2026, 11, 15), LocalTime.of(14, 30))
        assertEquals(LocalDateTime.of(2026, 11, 15, 14, 30), local(slot.begin))
        assertEquals(LocalDateTime.of(2026, 11, 15, 15, 30), local(slot.end))
    }

    @Test
    fun rescheduleAllDayStaysAllDay() {
        val item = allDay("Groceries", LocalDate.of(2026, 11, 12))
        val slot = Rules.rescheduleSlot(item, LocalDate.of(2026, 11, 21), null)
        assertTrue(slot.allDay)
        assertEquals(utcMidnight(LocalDate.of(2026, 11, 21)), slot.begin)
        assertEquals(utcMidnight(LocalDate.of(2026, 11, 22)), slot.end)
    }

    @Test
    fun rescheduleAllDayWithTimeBecomesHalfHourTimed() {
        val item = allDay("Groceries", LocalDate.of(2026, 11, 12))
        val slot = Rules.rescheduleSlot(item, LocalDate.of(2026, 11, 15), LocalTime.of(18, 0))
        assertFalse(slot.allDay)
        assertEquals(LocalDateTime.of(2026, 11, 15, 18, 0), local(slot.begin))
        assertEquals(LocalDateTime.of(2026, 11, 15, 18, 30), local(slot.end))
    }

    @Test
    fun rescheduleStretchedDelayedBlockCollapsesAndDropsTag() {
        val item = timed("⌛ DELAYED +2 – Call bank", LocalDateTime.of(2026, 11, 12, 10, 0), LocalDateTime.of(2026, 11, 14, 11, 0))
        val slot = Rules.rescheduleSlot(item, LocalDate.of(2026, 11, 15), null)
        assertEquals(LocalDateTime.of(2026, 11, 15, 10, 0), local(slot.begin))
        assertEquals("keeps its one-hour length", LocalDateTime.of(2026, 11, 15, 11, 0), local(slot.end))
        assertEquals("Call bank", item.parsed.base) // Repo.reschedule writes the base title
        val moved = item.copy(title = item.parsed.base, begin = slot.begin, end = slot.end)
        assertNull(Rules.stretch(moved, today))
        assertFalse(Rules.isActiveToday(moved, today))
    }

    @Test
    fun rescheduleMultiDayAllDayTaskKeepsAllItsDays() {
        val trip = allDay("Trip prep", LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 16))
        val slot = Rules.rescheduleSlot(trip, LocalDate.of(2026, 11, 20), null)
        assertTrue(slot.allDay)
        assertEquals(utcMidnight(LocalDate.of(2026, 11, 20)), slot.begin)
        assertEquals("three days: 20th to 22nd", utcMidnight(LocalDate.of(2026, 11, 23)), slot.end)
    }

    @Test
    fun rescheduleStretchedAllDayTaskMovesAsOneDay() {
        // Delayed from the 12th: the extra days came from the stretch, not the task.
        val late = allDay("⌛ DELAYED +2 – Groceries", LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 14))
        val slot = Rules.rescheduleSlot(late, LocalDate.of(2026, 11, 20), null)
        assertEquals(utcMidnight(LocalDate.of(2026, 11, 21)), slot.end)
    }

    @Test
    fun rescheduleMultiDayTimedTaskKeepsFullLength() {
        val item = timed("Hackathon", LocalDateTime.of(2026, 11, 14, 9, 0), LocalDateTime.of(2026, 11, 15, 17, 0))
        val slot = Rules.rescheduleSlot(item, LocalDate.of(2026, 11, 21), null)
        assertEquals(LocalDateTime.of(2026, 11, 21, 9, 0), local(slot.begin))
        assertEquals(LocalDateTime.of(2026, 11, 22, 17, 0), local(slot.end))
    }

    @Test
    fun laterTodayKeepsTagButRescheduleClearsIt() {
        val at = { h: Int -> LocalDateTime.of(2026, 11, 14, h, 0) }
        val delayed = timed("⌛ DELAYED +2 – Call bank", at(10), at(11))
        val follow = timed("🔁 FOLLOW-UP – Report", at(10), at(11))
        assertEquals("⌛ DELAYED +2 – Call bank", Rules.movedTitle(delayed, keepStatus = true))
        assertEquals("🔁 FOLLOW-UP – Report", Rules.movedTitle(follow, keepStatus = true))
        assertEquals("Call bank", Rules.movedTitle(delayed, keepStatus = false))
        assertEquals("Report", Rules.movedTitle(follow, keepStatus = false))
        assertEquals("Plain", Rules.movedTitle(timed("Plain", at(10), at(11)), keepStatus = true))
    }

    @Test
    fun delayedTaskSnoozedLaterTodayKeepsCountingNextDay() {
        // Delayed +2 task, moved to 3 PM today with Later today; not done by tomorrow.
        val late = timed("⌛ DELAYED +2 – Call bank", LocalDateTime.of(2026, 11, 12, 10, 0), LocalDateTime.of(2026, 11, 14, 11, 0))
        val slot = Rules.rescheduleSlot(late, today, LocalTime.of(15, 0))
        val snoozed = late.copy(title = Rules.movedTitle(late, keepStatus = true), begin = slot.begin, end = slot.end)
        assertTrue(Rules.isActiveToday(snoozed, today))
        assertNull(Rules.stretch(snoozed, today))
        assertEquals("⌛ DELAYED +3 – Call bank", snoozed.after(Rules.stretch(snoozed, today.plusDays(1))).title)
    }

    @Test
    fun rescheduleShortTaskGetsAtLeastFifteenMinutes() {
        val item = timed("Pill", LocalDateTime.of(2026, 11, 14, 8, 0), LocalDateTime.of(2026, 11, 14, 8, 5))
        val slot = Rules.rescheduleSlot(item, LocalDate.of(2026, 11, 15), null)
        assertEquals(15 * 60 * 1000L, slot.end - slot.begin)
    }
}
