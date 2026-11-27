package com.crush.mobile.execution

/**
 * V2-16 A2 remediation (Finding B): an unacknowledged POST is ambiguous.
 *
 * No engine contract in this repository establishes that a workspace/session
 * 404 proves the request was never dispatched. The v0.100.0 OpenAPI spec
 * describes accept-then-detached-dispatch but gives no idempotency or
 * non-dispatch guarantee. Therefore automatic re-send is disabled, and the
 * decision is a pure, testable constant rather than an inferred HTTP rule.
 */
object ExecutionSendPolicy {
    const val mayAutoRetryAfterUnacknowledgedPost: Boolean = false
}
