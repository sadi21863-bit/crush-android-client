package com.crush.mobile.execution

/**
 * V2-16 A2 remediation: the approved evidence vocabulary made explicit.
 *
 * A missing observation is NOT proof that an execution is lost. The container
 * has no CrushApi/WorkspaceManager at boot, so the production port returns
 * UNAVAILABLE truthfully. Deterministic tests inject the other cases; no
 * engine history is invented here.
 */
data class EngineEvidence(
    val result: ReconciliationResult,
    /** Set only for CONFIRMED evidence that names a terminal engine outcome. */
    val terminalLifecycle: ExecutionLifecycle? = null,
    val detail: String = "",
)

interface ExecutionEvidencePort {
    suspend fun probe(
        engineKeyToken: String,
        workspacePath: String,
        sessionId: String,
        generation: Long?,
    ): EngineEvidence
}

/** Production default: no engine query seam exists at container init. */
object UnavailableEngineEvidence : ExecutionEvidencePort {
    override suspend fun probe(
        engineKeyToken: String,
        workspacePath: String,
        sessionId: String,
        generation: Long?,
    ) = EngineEvidence(
        result = ReconciliationResult.UNAVAILABLE,
        detail = "engine history unavailable at startup",
    )
}
