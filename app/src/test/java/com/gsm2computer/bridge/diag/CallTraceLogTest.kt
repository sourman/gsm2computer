package com.gsm2computer.bridge.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CallTraceLogTest {

    @Test
    fun linesAreUtcJsonAndRotate() {
        val dir = File(System.getProperty("java.io.tmpdir"), "call-trace-test-" + System.nanoTime())
        dir.mkdirs()
        try {
            val log = CallTraceLog(dir, maxBytes = 180, keep = 3)
            log.event(
                "ws_failure",
                mapOf("code" to -1, "message" to "reset \"peer\"", "ok" to false),
                nowMs = 1_759_617_771_700L,
            )
            val first = log.currentFile().readText()
            assertTrue(first.contains("\"ts\":\"${CallTraceLog.isoUtc(1_759_617_771_700L)}\""))
            assertTrue(CallTraceLog.isoUtc(1_759_617_771_700L).endsWith("Z"))
            assertTrue(first.contains("\"kind\":\"ws_failure\""))
            assertTrue(first.contains("\"code\":-1"))
            assertTrue(first.contains("reset \\\"peer\\\""))
            assertTrue(first.contains("\"ok\":false"))
            repeat(8) {
                log.event("sample", mapOf("call_state" to "BRIDGED", "n" to it), nowMs = 1_759_617_772_000L + it)
            }
            assertTrue(File(dir, "call-trace.1.jsonl").exists())
            assertTrue(log.currentFile().exists())
            assertFalse(File(dir, "call-trace.3.jsonl").exists())
            assertEquals(3, dir.listFiles()?.count { it.name.startsWith("call-trace") && it.name.endsWith(".jsonl") })
        } finally {
            dir.deleteRecursively()
        }
    }
}
