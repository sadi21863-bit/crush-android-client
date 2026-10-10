package com.opencode.chat.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `Process.pid()` reflection conversion.
 *
 * `Process.pid()` returns a **`long`**, so reflection hands back a
 * `java.lang.Long`. The original code did `as? Int`, which can never succeed on a
 * boxed Long - so the helper returned -1 on every platform that has `pid()`, and
 * diagnostics cheerfully reported "engine ready pid=-1" for a perfectly healthy
 * engine. Anything reading the pid (diagnostics, fault injection, the chaos
 * scripts) was working from a fabricated value.
 */
class PidConversionTest {

    @Test
    fun `a Long pid converts correctly`() {
        // The real case: java.lang.Long, which `as? Int` silently rejected.
        assertEquals(4242, pidFromReflective(java.lang.Long.valueOf(4242L)))
    }

    @Test
    fun `an Int pid still converts`() {
        assertEquals(99, pidFromReflective(java.lang.Integer.valueOf(99)))
    }

    @Test
    fun `a realistic Android pid converts`() {
        // Pids on a busy phone are in the tens of thousands.
        assertEquals(31459, pidFromReflective(java.lang.Long.valueOf(31459L)))
    }

    @Test
    fun `null yields -1 rather than throwing`() {
        // pid() does not exist before API 26, so reflection can yield null.
        assertEquals(-1, pidFromReflective(null))
    }

    @Test
    fun `zero and negatives yield -1`() {
        assertEquals(-1, pidFromReflective(java.lang.Long.valueOf(0L)))
        assertEquals(-1, pidFromReflective(java.lang.Long.valueOf(-5L)))
    }

    @Test
    fun `a value beyond Int range yields -1 instead of truncating`() {
        // Truncating would produce a plausible-looking but wrong pid, which is
        // worse than reporting none.
        assertEquals(-1, pidFromReflective(java.lang.Long.valueOf(Long.MAX_VALUE)))
    }

    @Test
    fun `a non-numeric type yields -1`() {
        assertEquals(-1, pidFromReflective("not a number"))
    }
}
