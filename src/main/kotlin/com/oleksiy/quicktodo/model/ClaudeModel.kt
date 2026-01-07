package com.oleksiy.quicktodo.model

enum class ClaudeModel(
    val displayName: String,
    val modelId: String
) {
    OPUS_4_5("Claude Opus 4.5", "claude-opus-4-5-20251101"),
    SONNET_4("Claude Sonnet 4", "claude-sonnet-4-20250514");

    override fun toString(): String = displayName

    companion object {
        fun default() = OPUS_4_5
        fun fromModelId(id: String) = entries.find { it.modelId == id } ?: default()
    }
}
