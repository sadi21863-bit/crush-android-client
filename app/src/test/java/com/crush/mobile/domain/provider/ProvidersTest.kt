package com.crush.mobile.domain.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider resolution.
 *
 * These pin the rules the plumbing depends on. None of them are clever; each one
 * exists because the alternative is a provider change that half-applies, which
 * surfaces as a turn failing with a message about the wrong provider.
 */
class ProvidersTest {

    @Test
    fun `the default is the provider that has been verified`() {
        assertEquals("opencode-zen", Providers.DEFAULT.id)
        assertTrue(Providers.DEFAULT.isKnown)
    }

    @Test
    fun `an unknown id resolves to the default rather than failing`() {
        // A provider id persisted by an older build must not strand the user
        // behind a settings screen they cannot reach. Losing the preference is
        // recoverable; not booting is not.
        assertSame(Providers.DEFAULT, Providers.resolve("some-provider-from-the-future"))
    }

    @Test
    fun `a null id resolves to the default`() {
        assertSame(Providers.DEFAULT, Providers.resolve(null))
        assertSame(Providers.DEFAULT, Providers.resolve(""))
    }

    @Test
    fun `a known id resolves to itself`() {
        assertEquals("opencode-go", Providers.resolve("opencode-go").id)
    }

    @Test
    fun `the bare name opencode is not accepted`() {
        // Crush returns HTTP 500 "provider with ID opencode not found" for this.
        // If it ever appeared in ALL it would fail every turn, so the test below
        // plus this one together guard the id vocabulary.
        assertNull(Providers.byId("opencode"))
    }

    @Test
    fun `no id is a prefix of another, and opencode alone is not an id`() {
        // This test previously asserted no id is ever a bare provider name, and
        // failed the build the moment real Catwalk ids went in: `openai` and
        // `anthropic` ARE plain. The assumption was wrong, not the table.
        //
        // The invariant that actually matters is ambiguity. Plain `opencode` 500s
        // on the engine, and `opencode-zen` / `opencode-go` sit under that same
        // stem - which is precisely how someone ends up sending `opencode`.
        assertNull("plain opencode 500s on the engine", Providers.byId("opencode"))

        val ids = Providers.ALL.map { it.id }
        for (a in ids) {
            for (b in ids) {
                if (a != b) {
                    assertTrue(
                        "`$a` is a prefix of `$b` - ambiguous provider ids",
                        !b.startsWith("$a-")
                    )
                }
            }
        }
    }

    @Test
    fun `every provider carries a hint about where its key comes from`() {
        // A provider the user cannot obtain a key for is not usable, and an
        // empty hint is indistinguishable from one we forgot to fill in.
        assertTrue(Providers.ALL.all { it.keyHint.isNotBlank() })
    }

    @Test
    fun `every provider has a display name`() {
        assertTrue(Providers.ALL.all { it.displayName.isNotBlank() })
    }

    @Test
    fun `ids are unique`() {
        // A duplicate would make resolution ambiguous and BY-ID ambiguous with it.
        assertEquals(
            Providers.ALL.size,
            Providers.ALL.map { it.id }.distinct().size
        )
    }

    @Test
    fun `an unverified provider is not advertised as known`() {
        // opencode-go came from Crush's own provider list, but no handshake has
        // completed against it. Labelling it available would take the user
        // through key setup before telling them it does not work.
        val go = Providers.byId("opencode-go")!!
        assertFalse("no key has completed a handshake against it", go.isKnown)
    }

    @Test
    fun `openrouter is offered because it has a real free tier`() {
        // The route to testing while Zen's free tier is withdrawn: OpenRouter's
        // :free models are callable with a plain API key.
        val or = Providers.byId("openrouter")!!
        assertTrue(or.hasFreeTier)
        assertEquals("openrouter.ai/keys", or.keyHint)
    }

    @Test
    fun `the free tier list is not empty and excludes paid providers`() {
        assertTrue("testing needs somewhere to point at", Providers.FREE_TIER.isNotEmpty())
        assertTrue(
            "a paid provider must never be offered as free",
            Providers.FREE_TIER.none { it.id == "openai" || it.id == "anthropic" }
        )
    }

    @Test
    fun `opencode-zen is NOT advertised as having a free tier`() {
        // Measured 2026-10-11: every one of its 43 models is now 402 or 403. The
        // catalogue still lists ten `-free` ids, which is how it stayed advertised.
        assertFalse(Providers.byId("opencode-zen")!!.hasFreeTier)
    }

    @Test
    fun `copilot is listed but labelled as needing OAuth`() {
        // So its absence is a known gap rather than an oversight, and the user is
        // not sent to paste a key that does not exist.
        val cp = Providers.byId("copilot")!!
        assertFalse(cp.isKnown)
        assertTrue(
            "must not imply a pasteable key exists: ${cp.keyHint}",
            cp.keyHint.contains("OAuth", ignoreCase = true)
        )
    }

    @Test
    fun `isUnknownToApp recognises an id this build has never seen`() {
        assertTrue(Providers.isUnknownToApp("a-brand-new-provider"))
        assertFalse(Providers.isUnknownToApp("opencode-zen"))
    }

    @Test
    fun `resolution is stable across repeated calls`() {
        // setProvider compares against the resolved id to decide whether to
        // re-bootstrap, so an unstable resolver would either no-op a real change
        // or loop forever on a no-op change.
        val first = Providers.resolve("opencode-go").id
        val second = Providers.resolve("opencode-go").id
        assertEquals(first, second)
    }
}