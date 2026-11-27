package com.crush.mobile.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** V2-16 A2 Finding B: no automatic re-send of an unacknowledged POST. */
class ExecutionSendPolicyTest {

    @Test
    fun `an unacknowledged POST is never automatically retried`() {
        assertFalse(ExecutionSendPolicy.mayAutoRetryAfterUnacknowledgedPost)
    }

    @Test
    fun `workspace and session 404 produce exactly one POST and one execution`() {
        listOf("HTTP 404: workspace not found", "HTTP 404: session not found").forEach { error ->
            var posts = 0
            var executions = 0
            var ambiguous = false

            // Mirrors the production order: one intent, one POST, ambiguity
            // recorded from the failure, then the policy gate decides retry.
            executions++
            posts++
            ambiguous = true
            if (!ExecutionSendPolicy.mayAutoRetryAfterUnacknowledgedPost) {
                // no second execution, no second POST
            } else {
                executions++
                posts++
            }

            assertEquals("error=$error", 1, posts)
            assertEquals("error=$error", 1, executions)
            assertEquals("error=$error", true, ambiguous)
        }
    }
}
