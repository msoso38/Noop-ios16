package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileDisplayNameTest {

    @Test
    fun normalizedDisplayName_isLocalPresentationSafe() {
        assertEquals("", ProfileStore.normalizedDisplayName(null))
        assertEquals("", ProfileStore.normalizedDisplayName("   "))
        assertEquals("developer", ProfileStore.normalizedDisplayName("  developer  "))
    }

    @Test
    fun normalizedDisplayName_capsLengthWithoutChangingContent() {
        val source = "x".repeat(41)
        val result = ProfileStore.normalizedDisplayName(source)
        assertEquals(40, result.length)
        assertEquals("x".repeat(40), result)
    }
}
