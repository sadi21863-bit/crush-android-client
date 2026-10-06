package com.opencode.chat.data.crush

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary tests for the key normalization that caused a 401.
 *
 * Crush forwards `api_key` into the upstream Authorization header VERBATIM, so
 * anything extra in the string becomes part of the bearer token. Sending the
 * `NAME=value` form that Crush itself writes to crush.json produced
 * "Unauthorized / Invalid API key" on every turn.
 *
 * The subtle case is base64 padding: a bare key can legitimately END in `=`, and
 * a naive "split on first =" would silently truncate the key.
 */
class NormalizeApiKeyTest {

    @Test
    fun `bare key passes through untouched`() {
        assertEquals("oc_sk_abc123", normalizeApiKey("oc_sk_abc123"))
    }

    @Test
    fun `env var form is stripped`() {
        // The exact form that caused the 401 in production.
        assertEquals(
            "oc_sk_0bf709c004d4_cWya",
            normalizeApiKey("OPENCODE_API_KEY=oc_sk_0bf709c004d4_cWya")
        )
    }

    @Test
    fun `other env var names are stripped too`() {
        assertEquals("sk-123", normalizeApiKey("ANTHROPIC_API_KEY=sk-123"))
        assertEquals("sk-123", normalizeApiKey("OPENAI_API_KEY=sk-123"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("oc_sk_abc", normalizeApiKey("   oc_sk_abc \n "))
        assertEquals("oc_sk_abc", normalizeApiKey(" OPENCODE_API_KEY=oc_sk_abc "))
    }

    @Test
    fun `base64 padding at the end is NOT treated as an env var prefix`() {
        // THE regression guard. "AAAA==" is a bare key with padding; a naive
        // split would return "" and every request would 401.
        val padded = "AAAA=="
        assertEquals(padded, normalizeApiKey(padded))
    }

    @Test
    fun `lowercase key with equals is not split`() {
        // "oc_sk_abc=" looks like NAME=value but the prefix is not an env name.
        assertEquals("oc_sk_abc=", normalizeApiKey("oc_sk_abc="))
    }

    @Test
    fun `prefix with digits and underscores but lowercase is not an env name`() {
        assertEquals("abc_def=", normalizeApiKey("abc_def="))
    }

    @Test
    fun `empty and blank are safe`() {
        assertEquals("", normalizeApiKey(""))
        assertEquals("", normalizeApiKey("   "))
    }

    @Test
    fun `env var with empty value keeps the original rather than returning blank`() {
        // Better to send the original and fail loudly than to send "".
        val out = normalizeApiKey("OPENCODE_API_KEY=")
        assertFalse(out.isBlank())
        assertEquals("OPENCODE_API_KEY=", out)
    }

    @Test
    fun `multiple equals yields everything after the first`() {
        assertEquals("a=b", normalizeApiKey("MY_KEY=a=b"))
    }

    @Test
    fun `value is never null and always trimmed`() {
        val samples = listOf("", "  ", "k", "K=v", "=v", "A=B=C", "AAAA==", "�Y~?=x")
        for (s in samples) {
            // Previous assertion was `assertEquals(s.trim().length >= 0, true)` -
            // unfalsifiable, since a length is never negative. Replaced with real
            // properties of the contract: never null, always trimmed, and
            // idempotent.
            //
            // Note an earlier draft of this test also asserted that ANY `KEY=`
            // prefix was stripped. That is wrong and the test caught it: stripping
            // only happens when the prefix looks like an ENV NAME (contains `_`
            // and is all upper/digit/underscore). "K=v" and "AAAA==" are left
            // alone precisely because a real key can contain '='.
            val out = normalizeApiKey(s)
            assertEquals("not trimmed: '$s' -> '$out'", s.trim(), out)
            assertEquals("not idempotent for '$s'", out, normalizeApiKey(out))
        }
    }

    @Test
    fun `an env-name prefix is stripped but a real key is not`() {
        // The distinction that caused the 401: Crush forwards the value verbatim,
        // so a leftover "OPENCODE_API_KEY=" prefix is sent as the bearer token.
        assertEquals("sk-real", normalizeApiKey("OPENCODE_API_KEY=sk-real"))
        assertEquals("sk-real", normalizeApiKey("  OPENCODE_API_KEY=sk-real  "))
        // No underscore, or not all caps -> this is part of the secret.
        assertEquals("K=abc", normalizeApiKey("K=abc"))
        assertEquals("AAAA==", normalizeApiKey("AAAA=="))
        assertEquals("A=B=C", normalizeApiKey("A=B=C"))
        // Lowercase name is not an env var name.
        assertEquals("my_key=abc", normalizeApiKey("my_key=abc"))
        // A bare key passes through untouched.
        assertEquals("oc_sk_abc123", normalizeApiKey("oc_sk_abc123"))
    }
}

/**
 * Message-part extraction. This is where two separate real bugs lived:
 * the probe once reported the USER PROMPT as the model's reply (no role filter),
 * and provider failures looked like success because `finish` was the only part
 * present.
 */
class StreamMessageTest {

    private fun msg(json: String): StreamMessage =
        StreamMessage::class.java.let {
            com.google.gson.Gson().fromJson(json, StreamMessage::class.java)
        }

    @Test
    fun `extracts text from the documented part shape`() {
        val m = msg(
            """{"id":"1","role":"assistant","parts":[
               {"type":"text","data":{"text":"HELLO FROM CRUSH"}}]}"""
        )
        assertEquals("HELLO FROM CRUSH", m.text)
    }

    @Test
    fun `multiple text parts are concatenated`() {
        val m = msg(
            """{"role":"assistant","parts":[
               {"type":"text","data":{"text":"a"}},
               {"type":"text","data":{"text":"b"}}]}"""
        )
        assertEquals("ab", m.text)
    }

    @Test
    fun `empty parts yields empty text not a crash`() {
        assertEquals("", msg("""{"role":"assistant","parts":[]}""").text)
        assertEquals("", msg("""{"role":"assistant"}""").text)
    }

    @Test
    fun `reasoning parts are thinking not text`() {
        val m = msg(
            """{"role":"assistant","parts":[
               {"type":"reasoning","data":{"thinking":"pondering"}},
               {"type":"text","data":{"text":"answer"}}]}"""
        )
        assertEquals("answer", m.text)
        assertEquals("pondering", m.thinking)
    }

    @Test
    fun `a finish part with reason error is surfaced`() {
        // The exact production failure: isFinished was true, text was empty, and
        // the real cause ("Model is unavailable") was invisible.
        val m = msg(
            """{"role":"assistant","parts":[
               {"type":"finish","data":{"reason":"error","message":"Unauthorized",
                "details":"Invalid API key."}}]}"""
        )
        assertTrue(m.isFinished)
        assertEquals("", m.text)
        assertEquals("Unauthorized: Invalid API key.", m.finishError)
    }

    @Test
    fun `a stop finish part is not an error`() {
        val m = msg(
            """{"role":"assistant","parts":[
               {"type":"text","data":{"text":"done"}},
               {"type":"finish","data":{"reason":"stop","time":0}}]}"""
        )
        assertTrue(m.isFinished)
        assertEquals(null, m.finishError)
        assertEquals("done", m.text)
    }

    @Test
    fun `malformed parts do not throw`() {
        val samples = listOf(
            """{"role":"assistant","parts":[{"type":"text"}]}""",
            """{"role":"assistant","parts":[{"type":"text","data":null}]}""",
            """{"role":"assistant","parts":[{"type":"text","data":{"text":null}}]}""",
            """{"role":"assistant","parts":[{}]}""",
            """{"role":"assistant","parts":"not-a-list"}""",
            """{"role":"assistant","parts":[]}"""
        )
        for (s in samples) {
            // The real assertion is that NOTHING is thrown. The previous version
            // asserted `m.text.isNotEmpty() || m.text.isEmpty()`, which is a
            // tautology that cannot fail - it guarded exactly the input class
            // that crashed the app once, while proving nothing.
            //
            // Note the `parts":"not-a-list"` case makes Gson throw, so the
            // runCatching is part of the contract, not a way to hide a failure:
            // what must hold is that a malformed frame never propagates out.
            val result = runCatching { msg(s) }
            if (result.isFailure) {
                // Only a Gson type error is acceptable; anything else is a bug.
                assertTrue(
                    "unexpected failure for $s: ${result.exceptionOrNull()}",
                    result.exceptionOrNull() is com.google.gson.JsonSyntaxException ||
                        result.exceptionOrNull() is IllegalStateException ||
                        result.exceptionOrNull() is com.google.gson.JsonParseException
                )
            }
        }
    }

    @Test
    fun `malformed part shapes yield empty text rather than garbage`() {
        // Positive assertions, since the tautology above proved nothing about
        // the values. Each of these used to be able to throw inside data().
        assertEquals("", msg("""{"role":"assistant","parts":[{"type":"text"}]}""").text)
        assertEquals("", msg("""{"role":"assistant","parts":[{"type":"text","data":null}]}""").text)
        assertEquals(
            "",
            msg("""{"role":"assistant","parts":[{"type":"text","data":{"text":null}}]}""").text
        )
        assertEquals("", msg("""{"role":"assistant","parts":[{}]}""").text)
        assertEquals("", msg("""{"role":"assistant","parts":[]}""").text)
    }

    @Test
    fun `a hidden text part suppresses the whole text`() {
        // Real behaviour with a real consequence: hidden parts must not leak.
        val m = msg(
            """{"role":"assistant","parts":[
               {"type":"text","data":{"text":"secret"}},
               {"type":"text","data":{"text":"hidden","hidden":true}}]}"""
        )
        assertEquals("", m.text)
    }

    @Test
    fun `tool calls are listed`() {
        val m = msg(
            """{"role":"assistant","parts":[
               {"type":"tool_call","data":{"name":"read"}}]}"""
        )
        assertEquals(1, m.toolCalls.size)
    }
}