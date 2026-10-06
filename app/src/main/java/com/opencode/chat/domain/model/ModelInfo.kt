package com.opencode.chat.domain.model

data class ModelInfo(
    val id: String,
    val name: String,
    val provider: String = "",
    val reasoning: Boolean = false,
    val cost: ModelCost? = null
)

data class ModelCost(
    val input: Double = 0.0,
    val output: Double = 0.0,
    val cacheRead: Double = 0.0,
    val cacheWrite: Double = 0.0
)
