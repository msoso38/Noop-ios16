package com.noop.protocol

/**
 * One captured raw BLE frame plus the provenance a protocol mapper needs to correlate bytes against
 * ground truth. Covers BOTH WHOOP 4.0 (classic envelope) and WHOOP 5.0/MG (puffin envelope) — nothing
 * here is family-specific.
 *
 * `hex`/`char`/`tsMs`/`hr`/`typeName`/`seq`/`crcOk`/`ok` are the same fields, names and casing as the
 * macOS/iOS `RawCaptureRecord` (`Packages/RawCapture`), so a capture from either platform is directly
 * comparable. `sessionId`/`offload`/`size`/`parsed` are an Android-specific superset (session-scoped
 * capture + the already-decoded fields) that a plain hex-only reader ignores.
 */
data class RawCaptureRecord(
    val hex: String,
    val char: String,
    val tsMs: Long,
    val hr: Int?,
    val typeName: String?,
    val seq: Int?,
    val crcOk: Boolean?,
    val ok: Boolean,
    val sessionId: String,
    val offload: Boolean,
    val size: Int,
    val parsed: Map<String, Any?>,
)

object RawCaptureJsonl {
    fun encode(record: RawCaptureRecord): String =
        buildString {
            append('{')
            appendField("hex", record.hex)
            append(',')
            appendField("char", record.char)
            append(',')
            appendField("ts_ms", record.tsMs)
            append(',')
            appendField("hr", record.hr)
            append(',')
            appendField("type_name", record.typeName)
            append(',')
            appendField("seq", record.seq)
            append(',')
            appendField("crc_ok", record.crcOk)
            append(',')
            appendField("ok", record.ok)
            append(',')
            appendField("session_id", record.sessionId)
            append(',')
            appendField("offload", record.offload)
            append(',')
            appendField("size", record.size)
            append(',')
            appendQuoted("parsed")
            append(':')
            appendJsonValue(record.parsed)
            append('}')
        }

    private fun StringBuilder.appendField(name: String, value: Any?) {
        appendQuoted(name)
        append(':')
        appendJsonValue(value)
    }

    private fun StringBuilder.appendJsonValue(value: Any?) {
        when (value) {
            null -> append("null")
            is Boolean -> append(value)
            is Number -> append(value)
            is Map<*, *> -> {
                append('{')
                value.entries
                    .sortedBy { it.key.toString() }
                    .forEachIndexed { index, entry ->
                        if (index > 0) append(',')
                        appendQuoted(entry.key.toString())
                        append(':')
                        appendJsonValue(entry.value)
                    }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendJsonValue(item)
                }
                append(']')
            }
            is IntArray -> appendJsonValue(value.asIterable())
            is LongArray -> appendJsonValue(value.asIterable())
            is DoubleArray -> appendJsonValue(value.asIterable())
            is BooleanArray -> appendJsonValue(value.asIterable())
            else -> appendQuoted(value.toString())
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        append("\\u")
                        append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }
}
