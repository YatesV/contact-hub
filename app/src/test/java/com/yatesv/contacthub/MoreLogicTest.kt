package com.yatesv.contacthub

import com.yatesv.contacthub.data.Pdu
import com.yatesv.contacthub.data.Social
import com.yatesv.contacthub.engine.Updater
import com.yatesv.contacthub.ui.Motion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoreLogicTest {
    @Test fun mmsSendReqRoundTrip() {
        val img = ByteArray(3000) { (it * 7).toByte() }
        val bytes = Pdu.sendReq(listOf("+1 (337) 555-0101", "3375550102"), "Group hi 👋", listOf(Pdu.Part("image/jpeg", img, "photo.jpg")), "T123")
        val m = Pdu.parse(bytes)
        assertEquals(Pdu.SEND_REQ, m.type)
        assertEquals("T123", m.transactionId)
        assertEquals(listOf("+13375550101", "3375550102"), m.to)
        assertEquals("application/vnd.wap.multipart.mixed", m.contentType)
        assertEquals(2, m.parts.size)
        assertEquals("text/plain", m.parts[0].contentType)
        assertEquals("Group hi 👋", m.parts[0].text())
        assertEquals("image/jpeg", m.parts[1].contentType)
        assertEquals("photo.jpg", m.parts[1].name)
        assertArrayEquals(img, m.parts[1].data)
    }

    @Test fun uintvar() {
        assertArrayEquals(byteArrayOf(0x00), Pdu.encodeUintvar(0))
        assertArrayEquals(byteArrayOf(0x7F), Pdu.encodeUintvar(127))
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x00), Pdu.encodeUintvar(128))
        assertArrayEquals(byteArrayOf(0x83.toByte(), 0xD6.toByte(), 0x30), Pdu.encodeUintvar(60208))
    }

    /** Hand-built m-notification-ind, like a carrier sends. */
    @Test fun parsesNotificationInd() {
        val b = java.io.ByteArrayOutputStream()
        fun w(vararg v: Int) = v.forEach { b.write(it) }
        fun t(s: String) { b.write(s.toByteArray()); b.write(0) }
        w(0x8C, 0x82)                          // message type: notification-ind
        w(0x98); t("ABC123")                   // transaction id
        w(0x8D, 0x92)                          // version 1.2
        val from = "+13375550101/TYPE=PLMN".toByteArray()
        w(0x89, from.size + 2, 0x80); b.write(from); w(0)   // from: value-length, address-present, text
        w(0x8A, 0x80)                          // class personal
        w(0x8E, 0x02, 0x10, 0x00)              // message size 4096
        w(0x88, 0x05, 0x81, 0x03, 0x01, 0x51, 0x80) // expiry (relative)
        w(0x83); t("http://mms.carrier.example/abc")   // content location
        val m = Pdu.parse(b.toByteArray())
        assertEquals(Pdu.NOTIFICATION_IND, m.type)
        assertEquals("ABC123", m.transactionId)
        assertEquals("+13375550101", m.from)
        assertEquals(4096L, m.size)
        assertEquals("http://mms.carrier.example/abc", m.contentLocation)
    }

    /** Hand-built m-retrieve-conf with a text part (charset param) and a well-known image type. */
    @Test fun parsesRetrieveConf() {
        val b = java.io.ByteArrayOutputStream()
        fun w(vararg v: Int) = v.forEach { b.write(it) }
        fun t(s: String) { b.write(s.toByteArray()); b.write(0) }
        w(0x8C, 0x84); w(0x98); t("TX9"); w(0x8D, 0x92)
        val from = "+13375550199/TYPE=PLMN".toByteArray()
        w(0x89, from.size + 2, 0x80); b.write(from); w(0)
        w(0x97); t("+13375550101/TYPE=PLMN")   // to (me)
        w(0x97); t("+13375550102/TYPE=PLMN")   // to (friend)
        w(0x85, 0x04, 0x67, 0x00, 0x00, 0x01)   // date
        w(0x84, 0xB3)                          // content-type: multipart.related (well-known 0x33)
        w(0x02)                                // 2 parts
        val txt = "hey all".toByteArray()
        val h1 = byteArrayOf(0x03, 0x83.toByte(), 0x81.toByte(), 0xEA.toByte())
        w(h1.size, txt.size); b.write(h1); b.write(txt)
        val img = ByteArray(10) { 1 }
        w(1, img.size, 0x9E); b.write(img)     // image/jpeg as short form
        val m = Pdu.parse(b.toByteArray())
        assertEquals(Pdu.RETRIEVE_CONF, m.type)
        assertEquals("+13375550199", m.from)
        assertEquals(listOf("+13375550101", "+13375550102"), m.to)
        assertEquals(2, m.parts.size)
        assertEquals("hey all", m.parts[0].text())
        assertEquals("image/jpeg", m.parts[1].contentType)
        assertEquals(10, m.parts[1].data.size)
    }

    @Test fun notifyResp() {
        val m = Pdu.parse(Pdu.notifyResp("TX9"))
        assertEquals(Pdu.NOTIFYRESP_IND, m.type)
        assertEquals("TX9", m.transactionId)
    }

    @Test fun updateBuildNumber() {
        assertEquals(12, Updater.buildNumber("Orbit build #12"))
        assertEquals(0, Updater.buildNumber("latest"))
    }

    @Test fun springCurveSettles() {
        Motion.presets.values.forEach { p ->
            val c = Motion.curve(p, seconds = 3f)
            assertEquals(0f, c.first(), 0.001f)
            assertEquals(1f, c.last(), 0.02f)
        }
        // Bouncy overshoots, smooth doesn't
        assertTrue(Motion.curve(Motion.presets.getValue("bouncy")).max() > 1.05f)
        assertTrue(Motion.curve(Motion.presets.getValue("smooth")).max() <= 1.001f)
    }

    @Test fun socialEnumPackages() {
        assertEquals("com.instagram.android", Social.INSTAGRAM.pkg)
    }
}
