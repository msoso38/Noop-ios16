package com.noop.ble

import android.util.Log
import com.noop.BuildConfig
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * DEBUG-BUILD ONLY pairing + raw MG + **ML training** telemetry uploader.
 *
 * NEVER active in release/main builds ([BuildConfig.DEBUG] gate). Posts redacted strap log lines,
 * raw GATT hex, and structured [ML_SAMPLE] rows to the PC collector so you can leave the
 * laptop open, wear the strap (optionally with the official WHOOP app still bonded), and bank
 * training data for offline algorithm reconstruction.
 *
 * Endpoints are tried in order (same Wi‑Fi LAN first, then Tailscale):
 * so Tailscale is optional when phone + PC share home Wi‑Fi.
 *
 * Nothing is uploaded to WHOOP corporate servers — only the developer LAN/Tailscale PC collector.
 */
internal object RemotePairingTelemetry {
    private const val TAG = "NoopPairingTelemetry"

    /**
     * Overnight dual-app capture can emit hundreds of thousands of RAW_GATT / ML_SAMPLE lines.
     * Cap is per process lifetime; reconnect/app restart resets it. Log noise still filtered;
     * RAW_GATT + ML_SAMPLE always count against this higher budget.
     */
    private const val MAX_LINES_PER_PROCESS = 2_000_000

    /**
     * Prefer home LAN so Tailscale is not required. Tailscale stays as a secure remote fallback.
     * 8091 = dedicated telemetry receiver; 8090 also accepts /noop-pairing-log as backup.
     */
    private val ENDPOINTS = listOf(
        "http://<DESK_LAN_IP>:8091/noop-pairing-log",
        "http://<DESK_LAN_IP>:8090/noop-pairing-log",
        "http://100.96.149.116:8091/noop-pairing-log",
        "http://100.96.149.116:8090/noop-pairing-log",
    )

    private val sentLines = AtomicInteger(0)
    private val mlSamplesSent = AtomicInteger(0)
    private val lastMlAtMs = AtomicLong(0L)
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "noop-pairing-telemetry").apply { isDaemon = true }
    }

    fun send(line: String) {
        if (!BuildConfig.DEBUG) return
        if (!isInterestingLine(line)) return
        if (sentLines.incrementAndGet() > MAX_LINES_PER_PROCESS) return
        postBody(line.take(2_000) + "\n")
    }

    /**
     * Bulk-upload a batch of already-redacted strap log lines (DEBUG only).
     * Used so you never has to email logs — the app pushes them over LAN/Tailscale.
     */
    fun sendBatch(lines: List<String>) {
        if (!BuildConfig.DEBUG) return
        if (lines.isEmpty()) return
        val body = buildString {
            for (l in lines.take(400)) {
                if (sentLines.incrementAndGet() > MAX_LINES_PER_PROCESS) break
                append(l.take(2_000)).append('\n')
            }
        }
        if (body.isNotEmpty()) postBody(body)
    }

    /**
     * Structured training row for offline ML reconstruction.
     * Always uploaded (not filtered as "interesting log"). Throttled only when [minIntervalMs] > 0
     * for high-frequency sources; pass 0 to bank every beat.
     *
     * Fields are evidence-only — never invent SpO2/BP/AFib. Nulls omit the key.
     */
    fun sendMlSample(
        family: String,
        mode: String,
        hr: Int? = null,
        rrMs: List<Int> = emptyList(),
        batteryPct: Double? = null,
        stepCounter: Int? = null,
        activityClass: Int? = null,
        source: String = "open",
        note: String? = null,
        minIntervalMs: Long = 0L,
    ) {
        if (!BuildConfig.DEBUG) return
        val now = System.currentTimeMillis()
        if (minIntervalMs > 0L) {
            val prev = lastMlAtMs.get()
            if (now - prev < minIntervalMs) return
            lastMlAtMs.set(now)
        }
        if (sentLines.incrementAndGet() > MAX_LINES_PER_PROCESS) return
        mlSamplesSent.incrementAndGet()
        val rrPart = if (rrMs.isEmpty()) "" else {
            " rr=[" + rrMs.take(12).joinToString(",") + "]"
        }
        val line = buildString {
            append("ML_SAMPLE ts_ms=$now family=$family mode=$mode source=$source")
            if (hr != null) append(" hr=$hr")
            append(rrPart)
            if (batteryPct != null) append(" batt=${"%.1f".format(batteryPct)}")
            if (stepCounter != null) append(" steps=$stepCounter")
            if (activityClass != null) append(" act=$activityClass")
            if (note != null) append(" note=${note.take(80)}")
        }
        postBody(line + "\n")
    }

    /**
     * A real, user-confirmed sport label for a saved workout window. Unlike ML_SAMPLE this contains no
     * free text, route, device id, or health claim. The PC trainer joins it to already-collected samples.
     */
    fun sendMlWorkoutLabel(
        startTsMs: Long,
        endTsMs: Long,
        sportKey: String,
        provenance: String,
    ) {
        if (!BuildConfig.DEBUG || endTsMs <= startTsMs || sportKey !in TRAINABLE_SPORT_KEYS) return
        if (sentLines.incrementAndGet() > MAX_LINES_PER_PROCESS) return
        val now = System.currentTimeMillis()
        postBody(
            "ML_WORKOUT_LABEL v=1 label_ts_ms=$now start_ts_ms=$startTsMs end_ts_ms=$endTsMs " +
                "sport_key=$sportKey provenance=$provenance\n",
        )
    }

    /**
     * Raw GATT hex dump (debug + MG work only). Always prefixed so the PC log is filterable.
     * Full frames up to 512 bytes (covers type-47 v18/v26). Larger frames are chunked across
     * multiple lines so PC-side decode never loses @57 step / @73 temp / CRC tail.
     * Does not invent decode — capture only. Always uploaded (not log-filtered).
     */
    fun sendRawGatt(uuid: String, bytes: ByteArray, family: String, mode: String = "exclusive") {
        if (!BuildConfig.DEBUG) return
        if (sentLines.incrementAndGet() > MAX_LINES_PER_PROCESS) return
        val cap = 512
        val slice = if (bytes.size <= cap) bytes else bytes.copyOf(cap)
        val hex = buildString(slice.size * 2) {
            for (b in slice) append("%02x".format(b))
        }
        val more = if (bytes.size > cap) "…(+${bytes.size - cap}b)" else ""
        val line =
            "RAW_GATT DEBUG_ONLY family=$family mode=$mode uuid=$uuid len=${bytes.size} hex=$hex$more"
        postBody(line + "\n")
    }

    fun mlSamplesSentCount(): Int = mlSamplesSent.get()

    private val TRAINABLE_SPORT_KEYS = setOf(
        "running", "walking", "cycling", "swimming", "rowing", "hiking", "tennis", "golf",
        "basketball", "soccer", "football", "baseball", "volleyball", "boxing", "yoga",
        "pilates", "dance", "skiing", "snowboarding", "traditionalstrengthtraining",
    )

    private fun postBody(body: String) {
        worker.execute {
            for (endpoint in ENDPOINTS) {
                val ok = runCatching {
                    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 1_800
                        readTimeout = 1_800
                        doOutput = true
                        setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                        setRequestProperty("X-NOOP-Version", BuildConfig.VERSION_NAME)
                        setRequestProperty("X-NOOP-Build", "DEBUG")
                    }
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
                    val code = conn.responseCode
                    runCatching { conn.inputStream.use { it.readBytes() } }
                    runCatching { conn.errorStream?.use { it.readBytes() } }
                    conn.disconnect()
                    code in 200..299 || code == 204
                }.getOrDefault(false)
                if (ok) return@execute
            }
            Log.d(TAG, "all endpoints failed (PC offline or different Wi‑Fi)")
        }
    }

    private fun isInterestingLine(line: String): Boolean {
        val s = line.lowercase()
        return s.contains("whoop 5") ||
            s.contains("whoop5") ||
            s.contains("whoop 3") ||
            s.contains("whoop3") ||
            s.contains("mg") ||
            s.contains("mgdebug") ||
            s.contains("raw_gatt") ||
            s.contains("ml_sample") ||
            s.contains("alongside") ||
            s.contains("client_hello") ||
            s.contains("encryptedbond") ||
            s.contains("createbond") ||
            s.contains("bond") ||
            s.contains("pair") ||
            s.contains("gatt") ||
            s.contains("mtu") ||
            s.contains("discover") ||
            s.contains("service") ||
            s.contains("subscrib") ||
            s.contains("watchdog") ||
            s.contains("write") ||
            s.contains("disconnect") ||
            s.contains("status=") ||
            s.contains("haptic") ||
            s.contains("buzz") ||
            s.contains("charge") ||
            s.contains("battery") ||
            s.contains("step") ||
            s.contains("0x") ||
            s.contains("frame") ||
            s.contains("notify") ||
            s.contains("fd4b") ||
            s.contains("live hr") ||
            s.contains("plain live") ||
            s.contains("collect") ||
            s.contains("upload")
    }
}
