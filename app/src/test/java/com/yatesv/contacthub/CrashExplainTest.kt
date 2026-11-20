package com.yatesv.contacthub

import com.yatesv.contacthub.engine.CrashLog
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashExplainTest {
    @Test fun explainsCommonCrashesInPlainWords() {
        assertTrue(CrashLog.explain(OutOfMemoryError()).first.contains("memory"))
        assertTrue(CrashLog.explain(SecurityException("needs READ_CONTACTS")).first.contains("permission"))
        assertTrue(CrashLog.explain(IllegalArgumentException("Key \"q:1\" was already used")).first.contains("same ID"))
        assertTrue(CrashLog.explain(NullPointerException()).first.contains("missing"))
        assertTrue(CrashLog.explain(IndexOutOfBoundsException()).first.contains("end of a list"))
    }

    @Test fun looksAtTheCause() =
        assertTrue(CrashLog.explain(RuntimeException("wrapper", OutOfMemoryError())).first.contains("memory"))

    @Test fun unknownStillSaysSomething() =
        assertTrue(CrashLog.explain(Exception("boom")).first.isNotBlank())
}
