package com.crush.mobile.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * V2-7: the reasoning display preference.
 *
 * The parse must fail safe to COLLAPSED on absent/corrupt values - a broken
 * preferences file must not crash Settings or silently change the default.
 */
class ReasoningDisplayTest {

    @Test
    fun `absent or junk values fail to collapsed`() {
        assertEquals(ReasoningDisplay.COLLAPSED, ReasoningDisplay.parse(null))
        assertEquals(ReasoningDisplay.COLLAPSED, ReasoningDisplay.parse(""))
        assertEquals(ReasoningDisplay.COLLAPSED, ReasoningDisplay.parse("junk"))
    }

    @Test
    fun `every stored form round trips`() {
        ReasoningDisplay.entries.forEach { display ->
            assertEquals(display, ReasoningDisplay.parse(display.stored))
        }
    }

    @Test
    fun `the development-build hidden value maps to off`() {
        // An unreleased build wrote a middle "hidden" state. It must resolve to
        // OFF, not resurrect a block the owner removed.
        assertEquals(ReasoningDisplay.OFF, ReasoningDisplay.parse("hidden"))
    }

    @Test
    fun `stored forms are stable`() {
        assertEquals("collapsed", ReasoningDisplay.COLLAPSED.stored)
        assertEquals("off", ReasoningDisplay.OFF.stored)
    }
}
