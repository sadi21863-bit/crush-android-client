package com.crush.mobile.ui.screens.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Synthetic secret must never reach the persisted/on-screen crash archive. */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsRecorderRedactionTest {

    @Test(timeout = 120_000)
    fun `a provider shaped secret in a throwable is masked in the archive`() {
        val context = RuntimeEnvironment.getApplication()
        DiagnosticsRecorder.install(context)
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull(handler)
        val secret = "oc_sk_syntheticSecret1234567890abcdef"
        handler!!.uncaughtException(
            Thread.currentThread(),
            IllegalStateException("request denied: $secret"),
        )

        val archived = DiagnosticsRecorder.readArchived(context)
        assertNotNull(archived)
        assertFalse(
            "raw synthetic secret must not be persisted",
            archived!!.text.contains(secret),
        )
    }
}
