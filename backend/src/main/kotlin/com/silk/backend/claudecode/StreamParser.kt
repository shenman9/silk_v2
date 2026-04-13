package com.silk.backend.claudecode

import kotlinx.serialization.Serializable

/**
 * Bridge 返回的元信息数据类。
 * 流解析逻辑已移至 Python Bridge Agent。
 */
object StreamParser {

    @Serializable
    data class ResultMeta(
        val costUsd: Double = 0.0,
        val durationMs: Long = 0,
        val numTurns: Int = 0,
        val sessionId: String = "",
    )

    /** 将元信息格式化为用户可读字符串 */
    fun formatMeta(meta: ResultMeta): String {
        val parts = mutableListOf<String>()
        if (meta.costUsd > 0) parts.add("费用: ${"$"}%.4f".format(meta.costUsd))
        if (meta.durationMs > 0) parts.add("耗时: %.1fs".format(meta.durationMs / 1000.0))
        if (meta.numTurns > 0) parts.add("轮次: ${meta.numTurns}")
        if (meta.sessionId.isNotBlank()) parts.add("会话: ${meta.sessionId.take(8)}...")
        return parts.joinToString(" | ")
    }
}
