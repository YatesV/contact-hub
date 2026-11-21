package com.yatesv.contacthub

import androidx.compose.foundation.text.input.TextFieldState
import com.yatesv.contacthub.ui.FieldText
import org.junit.Assert.assertEquals
import org.junit.Test

/** The chat box text must read and write like the old plain String (send clears it, smart replies and @mentions set it). */
class FieldTextTest {
    @Test fun readsAndWritesLikeAString() {
        val state = TextFieldState("draft")
        val ft = FieldText(state)
        var text: String by ft
        assertEquals("draft", text)
        text = "hi @Sam "
        assertEquals("hi @Sam ", state.text.toString())
        assertEquals(8, state.selection.start) // cursor at the end, ready to keep typing
        text = ""
        assertEquals("", state.text.toString())
        state.edit { append("typed") }
        assertEquals("typed", text)
    }
}
