package com.gsm2computer.bridge.diag

import java.io.File
import java.time.Instant

/**
 * Bounded JSONL call trace. One JSON object per line, timestamps in UTC.
 *
 * Files live in [directory]:
 *   call-trace.jsonl      current
 *   call-trace.1.jsonl    previous
 *   ...
 *   call-trace.{keep-1}.jsonl
 *
 * Appends are serialized and never touch the audio threads; callers must
 * invoke [event] from a control thread.
 */
class CallTraceLog(
    private val directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val keep: Int = DEFAULT_KEEP,
) {
    private val lock = Any()
    private val current = File(directory, FILE_NAME)

    fun event(kind: String, fields: Map<String, Any?> = emptyMap(), nowMs: Long = System.currentTimeMillis()) {
        val payload = LinkedHashMap<String, Any?>()
        payload["ts"] = isoUtc(nowMs)
        payload["kind"] = kind
        for ((key, value) in fields) {
            if (key == "ts" || key == "kind") continue
            payload[key] = value
        }
        val line = encode(payload)
        synchronized(lock) {
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
                return
            }
            if (current.exists() && current.length() + line.length + 1 > maxBytes) {
                rotateLocked()
            }
            current.appendText(line + "\n")
            current.setReadable(true, false)
            directory.setReadable(true, false)
            directory.setExecutable(true, false)
        }
    }

    fun currentFile(): File = current

    private fun rotateLocked() {
        val slots = (keep - 1).coerceAtLeast(1)
        val oldest = File(directory, "call-trace.$slots.jsonl")
        if (oldest.exists()) oldest.delete()
        for (i in (slots - 1) downTo 1) {
            val src = File(directory, "call-trace.$i.jsonl")
            if (src.exists()) {
                src.renameTo(File(directory, "call-trace.${i + 1}.jsonl"))
            }
        }
        if (current.exists()) {
            current.renameTo(File(directory, "call-trace.1.jsonl"))
        }
    }

    companion object {
        const val FILE_NAME = "call-trace.jsonl"
        const val DIR_NAME = "call-trace"
        const val DEFAULT_MAX_BYTES = 2_000_000L
        const val DEFAULT_KEEP = 5

        fun isoUtc(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

        fun encode(fields: Map<String, Any?>): String {
            val sb = StringBuilder(256)
            sb.append('{')
            var first = true
            for ((key, value) in fields) {
                if (!first) sb.append(',')
                first = false
                sb.append(quote(key))
                sb.append(':')
                sb.append(encodeValue(value))
            }
            sb.append('}')
            return sb.toString()
        }

        private fun encodeValue(value: Any?): String = when (value) {
            null -> "null"
            is Boolean -> if (value) "true" else "false"
            is Int, is Long -> value.toString()
            is Float -> if (value.isFinite()) trimNumber(value.toDouble()) else "null"
            is Double -> if (value.isFinite()) trimNumber(value) else "null"
            is Number -> value.toString()
            else -> quote(value.toString())
        }

        private fun trimNumber(value: Double): String {
            if (value == value.toLong().toDouble()) return value.toLong().toString()
            return value.toString()
        }

        private fun quote(text: String): String {
            val sb = StringBuilder(text.length + 2)
            sb.append('"')
            for (c in text) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c.code < 0x20) {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}
