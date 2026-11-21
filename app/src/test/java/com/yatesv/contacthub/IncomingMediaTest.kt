package com.yatesv.contacthub

import com.yatesv.contacthub.data.IncomingMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingMediaTest {
    @Test fun placeholdersAreRecognized() {
        listOf("Attachment", "Photo", "photo.", "Video", "GIF", "Sticker", "Sent a photo", "2 photos", "Voice message", "attachments")
            .forEach { assertTrue(it, IncomingMedia.isPlaceholder(it)) }
        listOf("Photo of the dog lol", "I found something", "What", "Look at this attachment", "")
            .forEach { assertFalse(it, IncomingMedia.isPlaceholder(it)) }
    }

    @Test fun extensions() {
        assertEquals("jpg", IncomingMedia.extFor("image/jpeg"))
        assertEquals("gif", IncomingMedia.extFor("image/gif"))
        assertEquals("mp4", IncomingMedia.extFor("video/mp4"))
        assertEquals("vcf", IncomingMedia.extFor("text/x-vcard"))
        assertEquals("pdf", IncomingMedia.extFor("application/pdf"))
        assertEquals("bin", IncomingMedia.extFor(null))
    }
}
