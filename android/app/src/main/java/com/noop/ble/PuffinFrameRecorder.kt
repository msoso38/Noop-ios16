package com.noop.ble

import android.content.Context
import androidx.lifecycle.MutableStateFlow
import androidx.lifecycle.asStateFlow
import com.noop.protocol.DeviceFamily
import com.noop.protocol.Framing
import com.noop.protocol.PuffinCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * PuffinFrameRecorder — the Android app glue for WHOOP 5.0/MG frame capture.
 *
 * Mirrors the Swift [PuffinFrameRecorder] (Strand/BLE/PuffinFrameRecorder.swift).
 * Gates recording on the user-facing "Record Puffin frames" toggle, stamps every
 * complete frame with wall-clock receipt time + the most recent live HR, and
 * delegates JSON persistence to the pure [PuffinCapture] module.
 *
 * Two entry points (mirroring the two Swift call sites):
 *   1. [onLiveFrame] — called from [WhoopBleClient] on every live notification
 *      (realtime stream + historical offload frames). The caller is the single
 *      source
 *      owner of "did I already record this frame?" — it only calls once per
 *      emitted [Framing.ParsedFrame].
 *   2. [onHistoricalFrame] — called from [Backfiller] for historical frames
 *      that arrive via the offload path. Same contract: one call per frame.
 *
 * The toggle is persisted in [NoopPrefs] (shared with Settings screen). Default OFF
 * (opt-in, manual-first, privacy-safe). When OFF, all calls are no-ops with zero
 * allocation. When ON, the recorder is lazy-initialised on first frame.
 *
 * Concurrency: [PuffinCapture] uses a background writer coroutine; this object is
 * just a thin, thread-safe gate + stamper.
 */
class PuffinFrameRecorder private constructor(
    private val context: Context,
    private val getLiveHr: () -> Int?,
    private val prefs: NoopPrefs
) {

    /** Whether the user has enabled frame recording. Drives the Settings toggle. */
    val isEnabled: MutableStateFlow<Boolean> = MutableStateFlow(prefs.puffinCaptureEnabled(context))
        .also { it.value = prefs.puffinCaptureEnabled(context) } // seed from prefs

    /** Enable/disable recording (called from Settings screen). */
    fun setEnabled(enabled: Boolean) {
        prefs.setPuffinCaptureEnabled(context, enabled)
        isEnabled.value = enabled
    }

    /** Called from [WhoopBleClient] on every live puffin notification frame. */
    fun onLiveFrame(frame: ByteArray, characteristic: String) {
        if (!isEnabled.value) return
        val hr = getLiveHr()
        PuffinCapture.record(
            frame = frame,
            characteristic = characteristic,
            liveHr = hr,
            family = DeviceFamily.WHOOP5,
            appFilesDir = context.filesDir
        )
    }

    /** Called from [Backfiller] on every historical puffin frame during offload. */
    fun onHistoricalFrame(frame: ByteArray, characteristic: String) {
        if (!isEnabled.value) return
        // Historical path has no "live HR now" — pass null, the JSON will show null.
        PuffinCapture.record(
            frame = frame,
            characteristic = characteristic,
            liveHr = null,
            family = DeviceFamily.WHOOP5,
            appFilesDir = context.filesDir
        )
    }

    /** Factory that reads the current prefs + live-HR supplier and returns a recorder. */
    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun create(
            context: Context,
            getLiveHr: () -> Int?,
            prefs: NoopPrefs = NoopPrefs
        ): PuffinFrameRecorder {
            return PuffinFrameRecorder(context, getLiveHr, prefs)
        }
    }
}

/**
 * NoopPrefs extension for the puffin-capture toggle.
 * Keeps the key string in one place (matches the Swift @AppStorage key).
 */
interface NoopPrefs {
    const val KEY_PUFFIN_CAPTURE = "noop.puffinCapture"

    fun puffinCaptureEnabled(context: Context): Boolean =
        of(context).getBoolean(KEY_PUFFIN_CAPTURE, false)

    fun setPuffinCaptureEnabled(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_PUFFIN_CAPTURE, enabled).apply()
    }
}

/** Default implementation delegates to the real [NoopPrefs] object in MainActivity. */
object NoopPrefs : NoopPrefs