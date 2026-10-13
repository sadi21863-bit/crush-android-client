package com.opencode.chat.ui.screens.chat

import com.opencode.chat.data.api.FirstWorking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app tells a user when it cannot find a model it can call.
 *
 * WHY THE 402/403 SPLIT MATTERS
 *
 * Measured against a live key on 2026-10-11: the key was reported "active" by the
 * provider's own dashboard, `GET /v1/models` returned HTTP 200 with 43 models, and
 * `POST /v1/chat/completions` succeeded on NONE of them. 33 answered 402
 * (payment required); exactly the 10 whose ids end in `-free` answered 403
 * ("model access is disabled").
 *
 * Reported as one undifferentiated count, that is "this key cannot call anything",
 * which sends the user to check whether the key is valid. It IS valid. What
 * changed upstream is that the free tier is advertised and no longer granted, and
 * those need opposite responses: nothing is wrong with your key, versus your key
 * needs a plan.
 */
class ModelAccessExplanationTest {

    private fun explain(
        denied: Int,
        payment: Int = 0,
        retired: Int = 0,
        badKey: Boolean = false
    ) = ModelChoice.explainNoUsableModel(
        listed = 43,
        result = FirstWorking(
            model = null,
            probed = 43,
            retired = retired,
            denied = denied,
            badKey = badKey,
            paymentRequired = payment
        )
    )

    @Test
    fun `the withdrawn-free-tier case says the key works`() {
        // THE case this feature exists for: the user's key is valid, the website
        // says active, and the app cannot explain why nothing works.
        val msg = explain(denied = 10, payment = 33)

        assertTrue(
            "must state the key works, got: $msg",
            msg.contains("works", ignoreCase = true)
        )
        assertTrue(
            "must point at the free tier being withdrawn, got: $msg",
            msg.contains("free tier", ignoreCase = true)
        )
    }

    @Test
    fun `the withdrawn-free-tier case names both status counts`() {
        // Without the split the user cannot tell this apart from a dead key.
        val msg = explain(denied = 10, payment = 33)
        assertTrue("should mention the payment-required count: $msg", msg.contains("33"))
        assertTrue("should mention the free-model count: $msg", msg.contains("10"))
    }

    @Test
    fun `an all-payment catalogue does not blame the free tier`() {
        val msg = explain(denied = 0, payment = 43)
        assertTrue(
            "should not claim the free tier was withdrawn: $msg",
            !msg.contains("withdrawn", ignoreCase = true)
        )
        assertTrue("should say a plan is needed: $msg", msg.contains("plan", ignoreCase = true))
    }

    @Test
    fun `a rejected key still says to replace the key`() {
        val msg = explain(denied = 0, badKey = true)
        assertTrue("a 401 is the one case the key itself is the problem: $msg", msg.contains("Replace", ignoreCase = true))
    }

    @Test
    fun `a rejected key never claims the free tier was withdrawn`() {
        val msg = explain(denied = 0, payment = 0, badKey = true)
        assertFalse(
            "blaming upstream for a bad key sends the user the wrong way: $msg",
            msg.contains("withdrawn", ignoreCase = true)
        )
    }

    @Test
    fun `a purely denied catalogue still mentions model access`() {
        val msg = explain(denied = 12, payment = 0)
        assertTrue("should ask for model access: $msg", msg.contains("model access", ignoreCase = true))
    }

    @Test
    fun `retired models are reported separately from denied ones`() {
        val msg = explain(denied = 0, payment = 0, retired = 7)
        assertTrue("should mention retirement: $msg", msg.contains("retired", ignoreCase = true))
    }

    @Test
    fun `nothing probed reports an unreachable service rather than a verdict`() {
        val msg = explain(denied = 0, payment = 0, retired = 0)
        assertTrue(
            "with zero failures we know nothing, so must not blame the key: $msg",
            msg.contains("Could not reach", ignoreCase = true)
        )
    }
}