package com.crush.mobile.ui.screens.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression pin for the 2026-11-11 P5 drive's chat-side F04 consent bug:
 * the dialog's Allow re-called the entry WITHOUT recording consent, so the
 * second gate returned Required again and the dialog re-opened forever
 * (27 logged cycles, zero DB writes on device; the Projects site never
 * showed it because ITS dialog records via grantTrustAndOpen first).
 * The fixed ordering lives in [recordConsentThen]; these rows pin it:
 * consent is recorded BEFORE the re-call, and a record failure never
 * re-calls (fail-closed - the next Confirm re-gates).
 */
class HandoffConsentOrderTest {

    @Test
    fun `consent records before the re-call`() = runBlocking {
        val order = mutableListOf<String>()
        val failure = recordConsentThen(
            record = { order += "record" },
            then = { order += "recall" },
        )
        assertNull(failure)
        assertEquals(listOf("record", "recall"), order)
    }

    @Test
    fun `a record failure never re-calls`() = runBlocking {
        var recalled = false
        val failure = recordConsentThen(
            record = { throw IllegalStateException("disk full") },
            then = { recalled = true },
        )
        assertNotNull(failure)
        assertEquals("disk full", failure?.message)
        assertFalse(recalled)
    }
}
