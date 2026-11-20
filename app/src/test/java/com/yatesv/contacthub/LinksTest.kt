package com.yatesv.contacthub

import com.yatesv.contacthub.data.Links
import com.yatesv.contacthub.data.Links.Kind
import com.yatesv.contacthub.engine.LinkPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {
    private fun kinds(t: String) = Links.find(t).map { it.kind to it.text }

    @Test fun findsWebLinks() {
        assertEquals(listOf(Kind.URL to "https://youtu.be/abc123?t=10"), kinds("watch this https://youtu.be/abc123?t=10"))
        assertEquals(listOf(Kind.URL to "www.amazon.com/dp/B0C1"), kinds("www.amazon.com/dp/B0C1."))
        assertEquals(listOf(Kind.URL to "google.com"), kinds("just google.com it"))
        assertEquals(listOf(Kind.URL to "instagram.com/yatesv"), kinds("(instagram.com/yatesv)"))
        assertEquals(listOf(Kind.URL to "https://en.wikipedia.org/wiki/Bayou_(disambiguation)"), kinds("https://en.wikipedia.org/wiki/Bayou_(disambiguation)!"))
        assertEquals("https://google.com", Links.find("google.com")[0].target)
        assertEquals("https://youtu.be/x", Links.firstUrl("a youtu.be/x b https://c.com"))
    }

    @Test fun ignoresThingsThatArentLinks() {
        assertTrue(Links.find("it costs 3.50 ok").isEmpty())
        assertTrue(Links.find("lol...ok").isEmpty())
        assertTrue(Links.find("Your code is 482913").isEmpty())
        assertTrue(Links.find("version 1.2.3").isEmpty())
    }

    @Test fun findsEmailsPhonesAndCoords() {
        assertEquals(listOf(Kind.EMAIL to "me@example.com"), kinds("email me@example.com."))
        assertEquals("mailto:me@example.com", Links.find("me@example.com")[0].target)
        assertEquals(listOf(Kind.PHONE to "(337) 555-0101"), kinds("call (337) 555-0101 tonight"))
        assertEquals(listOf(Kind.PHONE to "337-555-0101"), kinds("337-555-0101"))
        assertEquals(listOf(Kind.PHONE to "+1 337 555 0101"), kinds("+1 337 555 0101"))
        assertEquals("tel:3375550101", Links.find("337.555.0101")[0].target)
        assertEquals(listOf(Kind.GEO to "30.8466, -93.2891"), kinds("meet at 30.8466, -93.2891"))
    }

    @Test fun mixesWithoutOverlap() {
        val l = Links.find("Go to https://site.com/call/3375550101 or call 337-555-0101, email a@b.co")
        assertEquals(listOf(Kind.URL, Kind.PHONE, Kind.EMAIL), l.map { it.kind })
    }

    @Test fun parsesPreviewTags() {
        val html = """<html><head><title>Fallback</title>
            <meta property="og:title" content="Cool &amp; Video">
            <meta content="/img/a.jpg" property="og:image">
            <meta property="og:site_name" content="YouTube"></head><body></body></html>"""
        val i = LinkPreview.parse("https://youtu.be/x", "https://www.youtube.com/watch?v=x", html)
        assertEquals("Cool & Video", i.title)
        assertEquals("https://www.youtube.com/img/a.jpg", i.image)
        assertEquals("YouTube", i.site)
        val t = LinkPreview.parse("https://a.com", "https://a.com/", "<head><title> Plain page </title></head>")
        assertEquals("Plain page", t.title); assertEquals("a.com", t.site)
    }
}
