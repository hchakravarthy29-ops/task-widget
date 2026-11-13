package com.harshith.taskwidget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingTest {
    private val me = setOf("me@gmail.com")
    // A secondary Google calendar: its own address plus the account that owns it.
    private val tasksCalendar = setOf("abc123@group.calendar.google.com", "me@gmail.com")

    @Test
    fun ownEventWithNoGuestsIsATask() {
        assertFalse(Rules.isMeeting("me@gmail.com", emptyList(), me))
        assertFalse(Rules.isMeeting(null, emptyList(), me))
    }

    @Test
    fun ownEventListingOnlyYourselfIsATask() {
        assertFalse(Rules.isMeeting("me@gmail.com", listOf(Guest("me@gmail.com")), me))
    }

    @Test
    fun addressesMatchIgnoringCase() {
        assertFalse(Rules.isMeeting("Me@Gmail.com", listOf(Guest(" ME@gmail.com ")), me))
    }

    @Test
    fun eventOnSecondaryCalendarIsATask() {
        val organizer = "abc123@group.calendar.google.com"
        assertFalse(Rules.isMeeting(organizer, listOf(Guest(organizer)), tasksCalendar))
    }

    @Test
    fun eventYouOrganisedWithAGuestIsAMeeting() {
        val guests = listOf(Guest("me@gmail.com"), Guest("friend@example.com"))
        assertTrue(Rules.isMeeting("me@gmail.com", guests, me))
    }

    @Test
    fun invitationFromSomeoneElseIsAMeeting() {
        assertTrue(Rules.isMeeting("boss@work.com", listOf(Guest("me@gmail.com")), me))
        // Even when the guest list is hidden from you.
        assertTrue(Rules.isMeeting("boss@work.com", emptyList(), me))
    }

    @Test
    fun meetingRoomAloneDoesNotMakeAMeeting() {
        val guests = listOf(Guest("me@gmail.com"), Guest("room-4b@resource.calendar.google.com", isResource = true))
        assertFalse(Rules.isMeeting("me@gmail.com", guests, me))
    }
}
