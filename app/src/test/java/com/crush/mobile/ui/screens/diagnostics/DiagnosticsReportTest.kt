package com.crush.mobile.ui.screens.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2-6 carry-over "report-level redaction/tail test" (V2-0 sign-off §3).
 *
 * `buildReport` is the exact text a user pastes into a third-party app, so two
 * properties are load-bearing and were previously unpinned:
 *
 *  1. every field AND the log tail passes the shared [com.crush.mobile.util.AppLog.Masking]
 *     net - a key that reaches the clipboard has left the device;
 *  2. the tail is the newest slice, and the truncation marker tells the reader
 *     that earlier lines exist instead of silently pretending the log starts
 *     there.
 *
 * `parseUncaughtFromLog` is the other untested half: the crash block is the
 * reason the report exists, and its terminator is AppLog's own timestamp
 * format. Treating every line after `UNCAUGHT` as trace would swallow the next
 * log entry; requiring a blank line would lose a trace after a mid-write kill.
 */
class DiagnosticsReportTest {

    private fun report(
        crash: RecordedCrash? = null,
        logTail: List<String> = emptyList(),
        logTotalLines: Int = logTail.size,
        logTruncatedForDisplay: Boolean = false,
        engineResponding: Boolean? = true,
        notes: List<String> = emptyList(),
    ) = DiagnosticsReport(
        appVersionName = "1.0.0",
        versionCode = 42,
        androidRelease = "10",
        sdkInt = 29,
        manufacturer = "Xiaomi",
        model = "Redmi Note 7 Pro",
        abis = listOf("arm64-v8a"),
        engineBinaryPath = "/data/app/lib/arm64/libcrush.so",
        engineBinaryPresent = true,
        engineStateLabel = "running (pid 4211, port 39001, boot 1200ms)",
        enginePort = 39001,
        engineResponding = engineResponding,
        engineProbeNote = when (engineResponding) {
            true -> "GET /v1/version returned 200"
            false -> "GET /v1/version did not answer"
            null -> null
        },
        crash = crash,
        logPath = "/data/user/0/com.crush.mobile/files/app.log",
        logTail = logTail,
        logTotalLines = logTotalLines,
        logTruncatedForDisplay = logTruncatedForDisplay,
        notes = notes,
    )

    @Test
    fun `the report keeps its whole-block order`() {
        val text = buildReport(report(), "2026-11-23 10:00:00")

        val header = text.indexOf("=== Crush Mobile 1.0.0 (42) ===")
        val device = text.indexOf("--- device ---")
        val engine = text.indexOf("--- engine ---")
        val crash = text.indexOf("--- crash ---")
        val log = text.indexOf("--- app log (0 of 0 lines) ---")

        assertTrue("header first", header == 0)
        assertTrue("device after header", device > header)
        assertTrue("engine after device", engine > device)
        assertTrue("crash after engine", crash > engine)
        assertTrue("log last", log > crash)
    }

    @Test
    fun `a brand key in the log tail is masked on the way out`() {
        val text = buildReport(
            report(logTail = listOf("12:00:01.000 E/Net: 401 oc_sk_TESTONLY_ab12cd34efgh5678")),
            "now",
        )

        assertTrue("masked marker missing: $text", text.contains("[redacted-key]"))
        assertFalse("key leaked to the clipboard: $text", text.contains("TESTONLY"))
    }

    @Test
    fun `a key in the crash text is masked too`() {
        val text = buildReport(
            report(
                crash = RecordedCrash(
                    source = "app.log",
                    capturedAt = "12:00:01.000",
                    text = "header gsk_A1b2C3d4E5f6G7h8I9",
                ),
            ),
            "now",
        )

        assertTrue(text.contains("[redacted-key]"))
        assertFalse(text.contains("A1b2C3d4E5f6G7h8I9"))
    }

    @Test
    fun `the truncation marker only appears when lines were cut`() {
        val full = buildReport(
            report(logTail = listOf("line-1", "line-2"), logTotalLines = 2),
            "now",
        )
        assertFalse(full.contains("(earlier lines omitted)"))

        val cut = buildReport(
            report(
                logTail = (251..450).map { "line-$it" },
                logTotalLines = 450,
                logTruncatedForDisplay = true,
            ),
            "now",
        )
        assertTrue("missing truncation marker", cut.contains("(earlier lines omitted)"))
        assertTrue("newest line must survive", cut.contains("line-450"))
        assertFalse("an omitted line must not appear", cut.contains("line-250"))
    }

    @Test
    fun `a null probe is not rendered as a failure`() {
        assertEquals(
            true,
            buildReport(report(engineResponding = null), "now").contains("responding    : not checked"),
        )
        assertTrue(buildReport(report(engineResponding = false), "now").contains("responding    : no"))
        assertTrue(buildReport(report(engineResponding = true), "now").contains("responding    : yes"))
    }

    @Test
    fun `collection notes are shown and masked`() {
        val text = buildReport(
            report(notes = listOf("key slot oc_sk_NOTEONLY_ab12cd34efgh5678 unreadable")),
            "now",
        )

        assertTrue(text.contains("note      :"))
        assertFalse("a note must not leak a key", text.contains("NOTEONLY"))
    }

    @Test
    fun `no crash renders an honest empty section`() {
        assertTrue(buildReport(report(), "now").contains("--- crash ---\nnone recorded"))
    }

    @Test
    fun `the newest uncaught trace stops at the next log entry`() {
        val lines = listOf(
            "12:00:00.000 I/App: start",
            "12:00:01.000 E/App: UNCAUGHT on 'main'",
            "java.lang.IllegalStateException: boom",
            "\tat com.crush.mobile.X.y(X.kt:12)",
            "12:00:02.000 I/App: next entry",
        )

        val crash = parseUncaughtFromLog(lines)!!

        assertEquals("12:00:01.000", crash.capturedAt)
        assertTrue(crash.text.contains("thread: main"))
        assertTrue(crash.text.contains("java.lang.IllegalStateException: boom"))
        assertTrue(crash.text.contains("X.kt:12"))
        assertFalse("the next entry must not be swallowed", crash.text.contains("next entry"))
    }

    @Test
    fun `the LAST uncaught in the log wins`() {
        val lines = listOf(
            "12:00:01.000 E/App: UNCAUGHT on 'first'",
            "first-trace",
            "12:00:02.000 I/App: recovered",
            "12:00:03.000 E/App: UNCAUGHT on 'second'",
            "second-trace",
        )

        val crash = parseUncaughtFromLog(lines)!!

        assertTrue(crash.text.contains("second-trace"))
        assertFalse(crash.text.contains("first-trace"))
    }

    @Test
    fun `a mid-write header with no trace is still worth showing`() {
        val crash = parseUncaughtFromLog(listOf("12:00:01.000 E/App: UNCAUGHT on 'main'"))!!

        assertTrue(crash.text.contains("thread: main"))
        assertTrue(crash.text.contains("UNCAUGHT on 'main'"))
    }

    @Test
    fun `a log without a crash yields no crash`() {
        assertNull(parseUncaughtFromLog(emptyList()))
        assertNull(parseUncaughtFromLog(listOf("12:00:00.000 I/App: fine")))
    }
}
