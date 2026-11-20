package com.yatesv.contacthub

import com.yatesv.contacthub.data.Quote
import com.yatesv.contacthub.data.VCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteTest {
    @Test fun makeThenSplitRoundTrips() {
        val body = Quote.make("Want to get food later?", "  Yes! ")
        assertEquals("> Want to get food later?\nYes!", body)
        val (q, rest) = Quote.split(body)
        assertEquals("Want to get food later?", q)
        assertEquals("Yes!", rest)
    }

    @Test fun replyingToAReplyQuotesOnlyTheReplyPart() {
        val first = Quote.make("hey", "what's up")
        assertEquals("> what's up\nnothing", Quote.make(first, "nothing"))
    }

    @Test fun longMessagesGetShortened() {
        val s = Quote.snippet("a".repeat(200))
        assertTrue(s.length <= 70); assertTrue(s.endsWith("…"))
    }

    @Test fun photoWithNoTextUsesLabel() = assertEquals("> 📷 Photo\nnice", Quote.make("", "nice"))

    @Test fun normalMessagesArentQuotes() {
        assertNull(Quote.split("> just a sentence").first)
        assertNull(Quote.split("hello\nthere").first)
        assertNull(Quote.split("> \nhi").first)
        assertNull(Quote.split("> quote\n   ").first)
    }

    @Test fun vcardHasTheBasics() {
        val v = VCard.build("Jane Q Doe", listOf("cell" to "+13375550100"), listOf("j@x.com"))
        assertTrue(v.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\n"))
        assertTrue(v.contains("FN:Jane Q Doe\r\n"))
        assertTrue(v.contains("N:Q Doe;Jane;;;\r\n"))
        assertTrue(v.contains("TEL;TYPE=CELL:+13375550100\r\n"))
        assertTrue(v.contains("EMAIL:j@x.com\r\n"))
        assertTrue(v.endsWith("END:VCARD\r\n"))
    }

    @Test fun vcardEscapesSpecialCharacters() =
        assertTrue(VCard.build("Smith, John; Jr", emptyList()).contains("FN:Smith\\, John\\; Jr"))
}
