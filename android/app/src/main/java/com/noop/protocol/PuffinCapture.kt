package com.noop.protocol

import android.util.Base64
import androidx.annotation.VisibleForTesting
import com.noop.ble.PuffinExperiment
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * PuffinCapture — the pure Kotlin twin of the Swift PuffinCapture (WhoopProtocol/PuffinCapture.swift).
 *
 * Records every WHOOP 5.0 / MG "puffin" frame with full provenance:
 *   • decoded biometrics (HR, RR, motion, skin-temp, battery)
 *   • wall-clock receipt time (ISO-8601 UTC with ms)
 *   • live HR at capture time (when available)
 *   • characteristic UUID the frame arrived on
 *   • decode hints (CRC status, frame family, packet-type alias)
 *
 * JSON lines are appended to a daily file in the app's files dir:
 *   files/puffin/puffin_YYYY-MM-DD.jsonl
 *
 * Zero networking, zero cloud. The file is user-exportable via the in-app "Share strap log"
 * (or `adb pull`), so researchers can share clean captures without ever touching a server.
 * Mirrors the Swift design: single public function `record()`, called from the live path
 * (WhoopBleClient.onNotification) and the historical offload path (Backfiller) —
 * the same frame is never recorded twice because the caller only calls it once per emission.
 *
 * Concurrency: thread-safe append via [ConcurrentLinkedQueue] + a single background writer
 * coroutine. The writer is started on first `record()` and shuts down when the queue
 * drains idle for 30 s (so a burst of live frames keeps it alive; a quiet night lets it
 * exit cleanly). Matches the Swift `DispatchQueue` writer pattern.
 *
 * The decoder is the shared [Framing.parseFrame] so live + historical paths produce
 * identical field sets. The JSON shape is deliberately flat so `jq`/`pandas.read_json`
 * work without a schema step.
 */
object PuffinCapture {

    private const val DIR_NAME = "puffin"
    private const val FILE_PREFIX = "puffin_"
    private const val FILE_SUFFIX = ".jsonl"
    private const val IDLE_SHUTDOWN_MS = 30_000L

    private val writerQueue = ConcurrentLinkedQueue<PuffinRecord>()
    private var writerJob: kotlinx.coroutines.Job? = null
    private var lastWriteMs = 0L

    /** Called once per complete puffin frame (live or historical offload). */
    @VisibleForTesting
    fun record(
        frame: ByteArray,
        characteristic: String,
        liveHr: Int?,
        family: DeviceFamily = DeviceFamily.WHOOP5,
        appFilesDir: File
    ) {
        val parsed = Framing.parseFrame(frame, family)
        if (!parsed.ok) return // garbage frames are not recorded

        val now = System.currentTimeMillis()
        val iso = DateTimeFormatter.ISO_INSTANT
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(now))

        // Provenance hints for downstream analysis
        val decodeHints = mutableMapOf<String, Any?>()
        decodeHints["crc_ok"] = parsed.crcOk
        decodeHints["frame_family"] = when (family) {
            DeviceFamily.WHOOP4 -> "whoop4"
            DeviceFamily.WHOOP5 -> "whoop5"
        }
        decodeHints["packet_type"] = parsed.typeName
        // Raw frame as base64 for perfect reproducibility
        decodeHints["frame_b64"] = Base64.encodeToString(frame, Base64.NO_WRAP)

        val record = PuffinRecord(
            received_at = iso,
            characteristic = characteristic,
            live_hr = liveHr,
            decoded = parsed.parsed,
            decode_hints = decodeHints
        )

        writerQueue.add(record)
        ensureWriter(appFilesDir)
    }

    private fun ensureWriter(appFilesDir: File) {
        if (writerJob != null && writerJob!!.isActive) return
        writerJob = kotlinx.coroutines.Dispatchers.IO.asCoroutineDispatcher().let { dispatcher ->
            kotlinx.coroutines.CoroutineScope(dispatcher).launch {
                val dir = File(appFilesDir, DIR_NAME)
                if (!dir.exists()) dir.mkdirs()
                var lastFlush = System.currentTimeMillis()
                while (true) {
                    val record = writerQueue.poll()
                    if (record != null) {
                        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
                            .withZone(ZoneOffset.UTC)
                            .format(Instant.ofEpochMilli(System.currentTimeMillis()))
                        val file = File(dir, "$FILE_PREFIX$date$FILE_SUFFIX")
                        file.outputStream().use { out ->
                            out.write((Json.encodeToString(record) + "\n").toByteArray(StandardCharsets.UTF_8))
                        }
                        lastFlush = System.currentTimeMillis()
                    } else {
                        // Idle shutdown: if queue has been empty for IDLE_SHUTDOWN_MS, exit
                        if (System.currentTimeMillis() - lastFlush > IDLE_SHUTDOWN_MS) {
                            writerJob = null
                            return@launch
                        }
                        kotlinx.coroutines.delay(100)
                    }
                }
            }
        }
    }

    /** Flush any queued records and stop the writer (for tests / graceful shutdown). */
    @VisibleForTesting
    suspend fun flushAndStop() {
        writerJob?.join()
        writerJob = null
    }

    /** Test helper: return the current queue size. */
    @VisibleForTesting
    fun queueSize(): Int = writerQueue.size
}

/**
 * JSON line schema for a single captured puffin frame.
 *
 * - `received_at`: ISO-8601 UTC with millisecond precision (wall clock at capture)
 * - `characteristic`: BLE characteristic UUID the frame was notified on
 * - `live_hr`: Most recent live HR (bpm) at capture time, or null if none yet
 * - `decoded`: Flat map of all fields from [Framing.parseFrame] (HR, RR, motion, etc.)
 * - `decode_hints`: Provenance (crc_ok, frame_family, packet_type, frame_b64)
 */
@Serializable
data class PuffinRecord(
    val received_at: String,
    val characteristic: String,
    val live_hr: Int?,
    val decoded: Map<String, Any?>,
    val decode_hints: Map<String, Any?>
) {
    // Helper to create a record with typed decoded map for testing
    @VisibleForTesting
    companion object {
        fun create(
            receivedAt: String,
            characteristic: String,
            liveHr: Int?,
            decoded: Map<String, Any?>,
            decodeHints: Map<String, Any?>
        ): PuffinRecord = PuffinRecord(receivedAt, characteristic, liveHr, decoded, decodeHints)
    }
}