package com.noop.alarm

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences

internal class AlarmTestContext : ContextWrapper(null) {
    private val prefs = mutableMapOf<String, SharedPreferences>()

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.getOrPut(name) { InMemorySharedPreferences() }
}
