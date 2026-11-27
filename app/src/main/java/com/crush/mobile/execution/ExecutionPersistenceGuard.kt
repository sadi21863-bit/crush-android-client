package com.crush.mobile.execution

/**
 * V2-16 A2 remediation: the single decision rule for every guarded durable
 * write on the send path.
 *
 * Room's guarded UPDATEs return an affected-row count. Zero means the guard
 * rejected the write; an exception means the write did not happen. Neither is
 * success. Keeping this rule in one pure object both removes the original
 * "exception checked, zero ignored" divergence and makes it directly testable
 * without a ViewModel, Context or database.
 */
object ExecutionPersistenceGuard {

    /** True ONLY for one committed row. Never infer success from a thrown write. */
    fun committed(result: Result<Int>): Boolean = result.getOrNull() == 1
}
