package com.harshith.taskwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitlesTest {
    @Test
    fun prefixesUseEnDashWithSpaces() {
        assertEquals("✅ DONE – ", Titles.DONE_PREFIX)
        assertEquals("🔁 FOLLOW-UP – ", Titles.FOLLOWUP_PREFIX)
        assertEquals("⌛ DELAYED +4 – ", Titles.delayedPrefix(4))
    }

    @Test
    fun parsesPlainTitle() {
        assertEquals(Parsed(Kind.NORMAL, 0, "Call bank"), Titles.parse("Call bank"))
    }

    @Test
    fun parsesDone() {
        assertEquals(Parsed(Kind.DONE, 0, "Call bank"), Titles.parse("✅ DONE – Call bank"))
    }

    @Test
    fun parsesDelayedWithDays() {
        assertEquals(Parsed(Kind.DELAYED, 1, "Call bank"), Titles.parse("⌛ DELAYED +1 – Call bank"))
        assertEquals(Parsed(Kind.DELAYED, 12, "Taxes"), Titles.parse("⌛ DELAYED +12 – Taxes"))
    }

    @Test
    fun parsesFollowUp() {
        assertEquals(Parsed(Kind.FOLLOWUP, 0, "Report"), Titles.parse("🔁 FOLLOW-UP – Report"))
        assertEquals(Parsed(Kind.FOLLOWUP, 0, "Report"), Titles.parse("🔁 FOLLOWUP – Report"))
    }

    @Test
    fun toleratesHandTypedVariants() {
        // Hyphen or em dash, missing spaces, lower case: e.g. edited by hand in Google Calendar.
        assertEquals(Parsed(Kind.DONE, 0, "Call bank"), Titles.parse("✅ DONE - Call bank"))
        assertEquals(Parsed(Kind.DONE, 0, "Call bank"), Titles.parse("✅done—Call bank"))
        assertEquals(Parsed(Kind.DELAYED, 2, "Call bank"), Titles.parse("  ⌛ delayed +2 -Call bank"))
    }

    @Test
    fun keywordsInsideTitleAreNotStatus() {
        assertEquals(Kind.NORMAL, Titles.parse("Get DONE – the thing").kind)
        assertEquals(Kind.NORMAL, Titles.parse("Report ✅ DONE – x").kind)
        assertEquals(Kind.NORMAL, Titles.parse("DONE – no emoji").kind)
    }

    @Test
    fun buildAndParseRoundTrip() {
        for (kind in Kind.values()) {
            val days = if (kind == Kind.DELAYED) 3 else 0
            val built = Titles.build(kind, days, "Pay rent – March")
            assertEquals(Parsed(kind, days, "Pay rent – March"), Titles.parse(built))
        }
    }

    @Test
    fun followNoteReadsFirstNoteLine() {
        fun item(desc: String?) = Item(1, "t", 0, 1, false, 0, false, desc)
        assertEquals("send the draft", item("Follow-up note: send the draft\n\nAgenda").followNote)
        assertNull(item(null).followNote)
        assertNull(item("Agenda only").followNote)
        assertNull(item("Follow-up note:   ").followNote)
        assertTrue(item("Agenda\nFollow-up note: later line").followNote == "later line")
    }
}
