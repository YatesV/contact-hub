package com.yatesv.contacthub

import com.yatesv.contacthub.data.FriendMatch
import com.yatesv.contacthub.data.FriendMatch.Friend
import com.yatesv.contacthub.ui.CurveEasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendMatchTest {
    private val contacts = listOf("Jordan Smith" to "1", "Abby Lee" to "2", "Marcus" to "3", "José Álvarez" to "4", "Sam Carter" to "5", "Sam Ortiz" to "6")

    @Test fun matchesByName() {
        val friends = listOf(
            Friend("jsmith22", "Jordan Smith 🏀"), Friend("abby.lee", "abby lee"), Friend("marc_us", "Marcus"),
            Friend("josealvarez", ""), Friend("sammy", "Sam"), Friend("random", "Someone Else"),
        )
        val m = FriendMatch.match(friends, contacts).associateBy { it.phoneKey }
        assertEquals(3, m["1"]!!.score)                  // emoji ignored
        assertEquals("abby.lee", m["2"]!!.friend.username)
        assertEquals(3, m["3"]!!.score)
        assertEquals(2, m["4"]!!.score)                  // accents + username = first+last
        assertTrue("5" !in m && "6" !in m)               // "Sam" is ambiguous (two Sams)
    }

    @Test fun oneFriendOneContact() {
        val m = FriendMatch.match(listOf(Friend("jordan", "Jordan Smith")), contacts + ("Jordan Smith" to "9"))
        assertEquals(1, m.size)
    }

    @Test fun curveEasingEnds() {
        val e = CurveEasing.parse("0.3,0.2;0.6,1.1")
        assertEquals(0f, e.transform(0f), 0.001f)
        assertEquals(1f, e.transform(1f), 0.001f)
        assertEquals(1.1f, e.transform(0.6f), 0.001f)   // passes through your dots
        assertEquals(e.serialize(), CurveEasing.parse(e.serialize()).serialize())
    }
}
