package com.toer15.queststeam.display

import org.junit.Assert.assertEquals
import org.junit.Test

class SoftKeysTest {
    @Test fun typingDeletingAndReplacingBecomeKeyPresses() {
        assertEquals(0 to "a", SoftKeys.diff("", "a"))
        assertEquals(0 to "c", SoftKeys.diff("ab", "abc"))
        assertEquals(1 to "", SoftKeys.diff("abc", "ab"))
        assertEquals(3 to "", SoftKeys.diff("abc", ""))
        // A keyboard suggestion replacing the last word: delete what differs, type the rest.
        assertEquals(2 to "llo", SoftKeys.diff("hexy", "hello"))
        assertEquals(0 to "", SoftKeys.diff("same", "same"))
    }
}
