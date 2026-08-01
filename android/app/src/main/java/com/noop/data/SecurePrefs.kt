package com.noop.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.ConcurrentHashMap

/**
 * The one place an encrypted `SharedPreferences` file is opened.
 *
 * Two credential stores needed this and each grew its own copy: `AiKeyStore` (the AI provider API key)
 * and `OuraInstallKeyStore` (per-ring install keys). The bodies were byte-identical apart from the file
 * name, which is the shape that lets a hardening change, a key-scheme migration or a fallback fix land
 * on one store and silently miss the other. They are the app's only two encrypted files, so one helper
 * covers all of it.
 *
 * **Cached, which is the part that matters at runtime.** `EncryptedSharedPreferences.create` is not a
 * cheap accessor: it builds a [MasterKey] — an Android Keystore round trip — and sets up Tink AEAD
 * primitives, on every call. Neither store cached it, so all twenty of their read/write sites paid that
 * cost per operation. `CoachViewModel` reads five values in PROPERTY INITIALISERS, so opening the Coach
 * screen ran five full Keystore + Tink setups synchronously on the main thread. They now collapse to one
 * per file for the process lifetime.
 *
 * Instances are safe to hold: `SharedPreferences` is designed as a long-lived singleton (the platform
 * caches the plaintext kind by name internally; the encrypted wrapper is what does not), and only the
 * application context is captured, so nothing leaks an Activity.
 *
 * [ConcurrentHashMap.computeIfAbsent] rather than a plain map: `AiKeyStore` is reached from the main
 * thread through `CoachViewModel`, while `OuraInstallKeyStore` is reached from BLE callbacks on binder
 * threads. Two threads racing the first call must get the same instance, not two.
 */
object SecurePrefs {

    private val cache = ConcurrentHashMap<String, SharedPreferences>()

    /** The encrypted prefs for [fileName], created once per process and reused thereafter. */
    fun of(ctx: Context, fileName: String): SharedPreferences =
        cache.computeIfAbsent(fileName) {
            val app = ctx.applicationContext
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
}
