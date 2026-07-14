package com.noop.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemorySharedPreferencesTest {

    @Test fun storesAndReturnsString() {
        val p = InMemorySharedPreferences()
        p.edit().putString("k", "v").apply()
        assertEquals("v", p.getString("k", null))
    }

    @Test fun returnsDefaultForMissingKey() {
        val p = InMemorySharedPreferences()
        assertEquals("fallback", p.getString("missing", "fallback"))
    }

    @Test fun removesKey() {
        val p = InMemorySharedPreferences()
        p.edit().putString("k", "v").apply()
        p.edit().remove("k").apply()
        assertEquals(null, p.getString("k", null))
    }

    @Test fun clearsAllKeys() {
        val p = InMemorySharedPreferences()
        p.edit().putString("a", "1").putInt("b", 2).putBoolean("c", true).apply()
        p.edit().clear().apply()
        assertTrue(p.all.isEmpty())
    }
}
