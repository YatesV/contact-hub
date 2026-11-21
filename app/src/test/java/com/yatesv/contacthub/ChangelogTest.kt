package com.yatesv.contacthub

import com.yatesv.contacthub.engine.Changelog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Keeps the bundled patch notes valid: every update numbered, newest first, with notes. */
class ChangelogTest {
    private val json = File("src/main/assets/changelog.json").readText()

    @Test fun bundledFileIsValid() {
        val list = Changelog.parse(json, nextBuild = 9999)
        assertTrue(list.size >= 15)
        assertEquals("Exactly one unnumbered (next) update", 1, Regex("\"build\"\\s*:\\s*\"next\"").findAll(json).count())
        assertEquals(9999, list.first().build)
        assertEquals("No duplicate build numbers", list.size, list.map { it.build }.toSet().size)
        list.forEach { u ->
            assertTrue("Build ${u.build} needs notes", u.notes.isNotEmpty() && u.notes.all { it.isNotBlank() })
            assertTrue("Build ${u.build} needs a title", u.title.isNotBlank())
            assertTrue("Build ${u.build} date ${u.date}", Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""").matches(u.date))
        }
        assertEquals(1, list.last().build)
    }

    @Test fun prettyDate() {
        assertEquals("Nov 20, 7:50 PM", Changelog.prettyDate("2026-11-20T19:50"))
        assertEquals("garbage", Changelog.prettyDate("garbage"))
    }

    @Test fun recognizesAndroid17SelfUpdateBlock() {
        assertTrue(com.yatesv.contacthub.engine.Updater.isSelfUpdateBlock("INSTALL_FAILED_ABORTED: Self update is blocked by unknown source package"))
        assertTrue(!com.yatesv.contacthub.engine.Updater.isSelfUpdateBlock("INSTALL_FAILED_ABORTED: User rejected permissions"))
    }
}
