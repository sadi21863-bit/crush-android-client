package com.opencode.chat.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards on the credential never reaching the UI.
 *
 * The settings screen used to pre-fill a text field with the decrypted key and
 * offer an eye toggle to reveal it. Toggling the glyphs changed nothing about
 * who could read the secret: it was already in Compose state and on screen.
 * These tests pin the replacement - only a masked tail may be rendered.
 */
class KeyMaskTest {

    // Placeholder shape only. No real credential is used in or by this test.
    private val sample = "oc_sk_TESTONLY_abcdefgh1234"

    @Test
    fun `blank key yields no hint`() {
        assertEquals("", KeyMask.hint(null))
        assertEquals("", KeyMask.hint(""))
        assertEquals("", KeyMask.hint("    "))
    }

    @Test
    fun `hint never contains the body of the key`() {
        val h = KeyMask.hint(sample)
        assertTrue("hint leaked the secret: $h", !h.contains("abcdefgh"))
        assertTrue("hint leaked the secret: $h", !h.contains("TESTONLY"))
    }

    @Test
    fun `hint shows only a short tail`() {
        assertEquals("1234", KeyMask.hint(sample).takeLast(KeyMask.TAIL))
        assertTrue(KeyMask.hint(sample).all { it == '*' || it.isDigit() || it.isLetter() })
    }

    @Test
    fun `very short key is fully masked`() {
        // A key shorter than the tail must not be echoed back in the clear.
        assertEquals("***", KeyMask.hint("abc"))
        assertEquals("*", KeyMask.hint("a"))
    }

    @Test
    fun `fingerprint reveals length but not content`() {
        val f = KeyMask.fingerprint(sample)
        assertEquals("len=${sample.length}", f)
        assertTrue("fingerprint leaked: $f", !f.contains("abcdefgh"))
    }

    @Test
    fun `fingerprint of nothing is explicit`() {
        assertEquals("none", KeyMask.fingerprint(null))
        assertEquals("none", KeyMask.fingerprint(""))
    }
}
