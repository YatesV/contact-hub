package com.yatesv.contacthub

import com.yatesv.contacthub.data.Birthdays
import com.yatesv.contacthub.data.CatchUp
import com.yatesv.contacthub.data.Nudge
import com.yatesv.contacthub.data.Recurring
import com.yatesv.contacthub.data.Relationship
import com.yatesv.contacthub.data.Snooze
import com.yatesv.contacthub.data.Spam
import com.yatesv.contacthub.data.Trail
import com.yatesv.contacthub.data.TrailPoint
import com.yatesv.contacthub.engine.Ai
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class OrbitLogicTest {
    private val tz = TimeZone.getTimeZone("America/Chicago")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) =
        Calendar.getInstance(tz).apply { clear(); set(y, m - 1, d, h, min, 0) }.timeInMillis

    @Test fun recurringKeepsTimeAndSkipsPast() {
        val first = at(2026, 1, 31, 9)
        // Monthly from Jan 31 lands on Feb 28, not Mar 3
        val next = Recurring.next(first, "monthly", now = first, tz = tz)!!
        val c = Calendar.getInstance(tz).apply { timeInMillis = next }
        assertEquals(Calendar.FEBRUARY, c.get(Calendar.MONTH)); assertEquals(28, c.get(Calendar.DAY_OF_MONTH)); assertEquals(9, c.get(Calendar.HOUR_OF_DAY))
        // Yearly birthday text sent late (phone was off) still schedules next year's, not one in the past
        val bday = at(2025, 11, 20, 9)
        val nextYear = Recurring.next(bday, "yearly", now = at(2026, 11, 20, 10), tz = tz)!!
        assertEquals(at(2027, 11, 20, 9), nextYear)
        assertEquals(at(2026, 1, 8, 9), Recurring.next(at(2026, 1, 1, 9), "weekly", now = at(2026, 1, 1, 9), tz = tz))
        assertNull(Recurring.next(first, "", now = first, tz = tz))
    }

    @Test fun birthdays() {
        val today = at(2026, 11, 20)
        assertTrue(Birthdays.isToday("2001-11-20", today, tz))
        assertTrue(Birthdays.isToday("--11-20", today, tz))
        assertTrue(Birthdays.isToday("11-20", today, tz))
        assertFalse(Birthdays.isToday("2001-11-21", today, tz))
        // Feb 29 birthdays show on Feb 28 when it isn't a leap year
        assertTrue(Birthdays.isToday("2004-02-29", at(2027, 2, 28), tz))
        assertFalse(Birthdays.isToday("2004-02-29", at(2028, 2, 28), tz))
        assertEquals(25, Birthdays.turning("2001-11-20", today, tz))
        assertNull(Birthdays.turning("--11-20", today, tz))
    }

    @Test fun nudgesAndSnoozes() {
        val now = at(2026, 11, 20)
        val day = 86_400_000L
        assertTrue(Nudge.due(now - 15 * day, 14, 0, now))
        assertFalse(Nudge.due(now - 13 * day, 14, 0, now))
        assertFalse(Nudge.due(now - 20 * day, 14, now - 2 * day, now)) // already nudged recently
        assertTrue(Snooze.hidden(until = now + 3600_000, snoozedAt = now - 1000, lastIncoming = now - 5000, now = now))
        assertFalse(Snooze.hidden(until = now + 3600_000, snoozedAt = now - 1000, lastIncoming = now - 10, now = now)) // new message brings it back
        assertFalse(Snooze.hidden(until = now - 1, snoozedAt = now - 10_000, lastIncoming = 0, now = now))
        assertTrue(Snooze.presets(at(2026, 11, 20, 23), tz).none { it.first.startsWith("Tonight") })
    }

    @Test fun relationship() {
        val d = 86_400_000L
        val base = at(2026, 11, 1, 20)
        val msgs = listOf(base to true, base + 60_000 to false, base + d to true, base + 2 * d to false, base + 5 * d to true)
        val r = Relationship.compute(msgs, now = base + 5 * d + 1000, tz = tz)!!
        assertEquals(base, r.first); assertEquals(5, r.total); assertEquals(3, r.mine); assertEquals(2, r.theirs)
        assertEquals(3, r.longestStreak); assertEquals(1, r.currentStreak); assertEquals(20, r.topHour)
        assertNull(Relationship.compute(emptyList()))
    }

    @Test fun spam() {
        assertNull(Spam.reason("3375550101", inContacts = true, reported = true, verificationFailed = true, myNumbers = emptyList()))
        assertEquals("You reported this number", Spam.reason("3375550101", false, true, false, emptyList()))
        assertTrue(Spam.reason("3374621234", false, false, false, listOf("+1 337-462-9999"))!!.contains("own number"))
        assertNull(Spam.reason("3374621234", false, false, false, listOf("3375559999")))
    }

    @Test fun catchUpFindsQuestionsLinksAndPlans() {
        val lines = listOf(
            CatchUp.Line("Abrie", "hey"), CatchUp.Line("Abrie", "are you coming tonight?"),
            CatchUp.Line("Sam", "look at this youtube.com/watch?v=1"), CatchUp.Line("Sam", "meet at 7pm friday"),
        )
        val out = CatchUp.summarize(lines, "YatesV")
        assertTrue(out[0].startsWith("4 messages"))
        assertTrue(out.any { it.startsWith("❓") && "tonight" in it })
        assertTrue(out.any { it.startsWith("🔗") })
        assertTrue(out.any { it.startsWith("📅") && "7pm" in it })
    }

    @Test fun trailKeepsMovesForTwoDays() {
        val now = at(2026, 11, 20, 12)
        var t = emptyList<TrailPoint>()
        t = Trail.add(t, TrailPoint(now - 50 * 3_600_000L, 30.0, -93.0), now)
        assertTrue(t.isEmpty()) // older than 48 h
        t = Trail.add(t, TrailPoint(now - 60_000, 30.0, -93.0), now)
        t = Trail.add(t, TrailPoint(now - 30_000, 30.00001, -93.0), now) // didn't move: skipped
        t = Trail.add(t, TrailPoint(now, 30.01, -93.0), now)
        assertEquals(2, t.size)
        assertEquals(2, Trail.today(t, now, tz).size)
    }

    @Test fun resamplesAudio() {
        val pcm = ByteArray(8000 * 2) // 1 s at 8 kHz
        assertEquals(16000 * 2, Ai.resample16k(pcm, 8000).size)
        assertEquals(pcm.size, Ai.resample16k(pcm, 16000).size)
    }
}
