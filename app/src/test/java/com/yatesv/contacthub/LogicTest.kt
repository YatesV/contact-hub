package com.yatesv.contacthub

import com.yatesv.contacthub.data.Categorize
import com.yatesv.contacthub.data.Category
import com.yatesv.contacthub.data.Geo
import com.yatesv.contacthub.data.Otp
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.data.Reactions
import com.yatesv.contacthub.data.Settings
import com.yatesv.contacthub.data.Social
import com.yatesv.contacthub.data.Socials
import com.yatesv.contacthub.data.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    @Test fun normalizesNumbers() {
        assertEquals("3375550101", Phone.norm("+1 (337) 555-0101"))
        assertEquals("3375550101", Phone.norm("337-555-0101"))
        assertEquals("amazon", Phone.norm("AMAZON"))
        assertEquals("3375550101,3375550202", Phone.convKey(listOf("+13375550202", "(337) 555-0101", "3375550101")))
        assertTrue(Phone.isGroupKey("1,2"))
        assertEquals("(337) 555-0101", Phone.pretty("+13375550101"))
    }

    @Test fun keepsInternationalNumbersWhole() {
        assertEquals("+447911123456", Phone.norm("+44 7911 123456"))
        assertEquals("+447911123456", Phone.norm("011 44 7911 123456"))
        assertEquals("+447911123456", Phone.norm("0044 7911 123456"))
        assertEquals(listOf("+447911123456"), Phone.addresses(Phone.convKey(listOf("+44 7911 123456"))))
    }

    @Test fun nameKeys() {
        assertTrue(Phone.isNameKey("n:~Brad T"))
        assertTrue(!Phone.isGroupKey("n:~Brad T"))
        assertTrue(Phone.isGroupKey("g:Family#sc1a2b"))
        assertEquals("Family", Phone.nameOf("g:Family#sc1a2b"))
        assertEquals("~Brad T", Phone.nameOf("n:~Brad T"))
        assertEquals(emptyList<String>(), Phone.addresses("n:~Brad T"))
    }

    @Test fun parsesIphoneReactions() {
        val r = Reactions.parse("Liked “see you at 8”")!!
        assertEquals("👍", r.emoji); assertEquals("see you at 8", r.target)
        assertEquals("😂", Reactions.parse("Laughed at \"lol no way\"")!!.emoji)
        assertEquals("❤️", Reactions.parse("Loved “ok”")!!.emoji)
        assertEquals("🔥", Reactions.parse("Reacted 🔥 to “new car”")!!.emoji)
        assertTrue(Reactions.parse("Removed a like from “hey”")!!.removed)
        assertEquals("👍", Reactions.parse("👍 to “hey”")!!.emoji)
        assertNull(Reactions.parse("I liked the movie"))
        assertNull(Reactions.parse("Liked it a lot"))
    }

    @Test fun matchesTruncatedQuotes() {
        assertTrue(Reactions.matches("Are you going to the game on fri…", "Are you going to the game on friday night?"))
        assertTrue(Reactions.matches("ok", "ok"))
        assertTrue(!Reactions.matches("ok…", "okay then"))
    }

    @Test fun findsCodes() {
        assertEquals("482913", Otp.find("Your verification code is 482913"))
        assertEquals("123456", Otp.find("G-123456 is your Google verification code"))
        assertEquals("123456", Otp.find("Use code 123-456 to sign in"))
        assertNull(Otp.find("See you at 1230"))
    }

    @Test fun categorizes() {
        assertEquals(Category.GROUPS, Categorize.of("1,2", "hi", true))
        assertEquals(Category.CODES, Categorize.of("12345", "Your code is 5555", false))
        assertEquals(Category.DELIVERIES, Categorize.of("12345", "Your package was delivered", false))
        assertEquals(Category.SPAM, Categorize.of("12345", "Congratulations you won! Click here https://x.co", false))
        assertEquals(Category.PERSONAL, Categorize.of("3375550101", "your package was delivered lol", true))
    }

    @Test fun cleansSocialLinks() {
        assertEquals("yatesv", Socials.clean(Social.INSTAGRAM, "https://www.instagram.com/yatesv/?igsh=abc"))
        assertEquals("yatesv", Socials.clean(Social.INSTAGRAM, "@yatesv"))
        assertEquals("yates.v", Socials.clean(Social.SNAPCHAT, "https://www.snapchat.com/add/yates.v?share_id=1"))
        assertEquals("yatesv", Socials.clean(Social.TIKTOK, "Check this out https://www.tiktok.com/@yatesv?_t=8"))
        assertEquals("https://vm.tiktok.com/ZMabc/", Socials.clean(Social.TIKTOK, "https://vm.tiktok.com/ZMabc/"))
        assertEquals(Social.SNAPCHAT, Socials.detect("https://snapchat.com/add/x"))
        assertEquals("https://www.tiktok.com/@yatesv", Socials.profileUrl(Social.TIKTOK, "yatesv"))
    }

    @Test fun streaks() {
        assertEquals(3, Stats.streak(setOf(10, 9, 8, 6), 10))
        assertEquals(2, Stats.streak(setOf(9, 8), 10))
        assertEquals(0, Stats.streak(setOf(5), 10))
    }

    @Test fun distance() {
        // DeRidder to Lake Charles is roughly 45 miles
        val d = Geo.miles(30.8463, -93.2890, 30.2266, -93.2174)
        assertTrue(d in 40.0..48.0)
    }

    @Test fun settingsRoundTrip() {
        val s = Settings(preset = "imessage", accent = 0xFF112233.toInt(), fontScale = 1.2f, autoReplyOn = true, myName = "Yates")
        assertEquals(s, Settings.fromJson(org.json.JSONObject(s.toJson().toString())))
    }
}
