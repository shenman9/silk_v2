// backend/src/main/kotlin/com/silk/backend/claudecode/StreamParser.kt
package com.silk.backend.claudecode

import kotlinx.serialization.json.*

/**
 * 解析 Claude Code CLI 的 stream-json 输出（每行一个 JSON 对象）。
 * 无状态纯函数，与 hub_agent 的 stream_parser.py 逻辑对齐。
 */
object StreamParser {

    private val logger = org.slf4j.LoggerFactory.getLogger(StreamParser::class.java)

    /** 工具名 → 展示图标 */
    private val TOOL_ICONS = mapOf(
        "Read" to "📖", "Write" to "✍️", "Edit" to "📝",
        "NotebookEdit" to "📓", "Bash" to "💻", "Glob" to "🔍",
        "Grep" to "🔍", "Task" to "🤖", "WebFetch" to "🌐",
        "Agent" to "🤖", "TodoWrite" to "📝",
    )
    private const val PARAM_MAX = 60

    data class ToolLog(val line: String, val toolUseId: String? = null, val toolName: String? = null)
    data class ToolResult(val toolUseId: String, val isError: Boolean, val summary: String = "")
    data class ResultMeta(
        val costUsd: Double = 0.0,
        val durationMs: Long = 0,
        val numTurns: Int = 0,
        val sessionId: String = "",
    )

    data class ParsedLine(
        val textChunk: String = "",
        val toolLogs: List<ToolLog> = emptyList(),
        val toolResults: List<ToolResult> = emptyList(),
        val meta: ResultMeta? = null,
    )

    fun parseLine(jsonLine: String): ParsedLine {
        val data = try {
            Json.parseToJsonElement(jsonLine).jsonObject
        } catch (e: Exception) {
            logger.debug("[CC] JSONL 解析跳过: {}", e.message)
            return ParsedLine()
        }
        val eventType = data["type"]?.jsonPrimitive?.contentOrNull ?: return ParsedLine()
        return when (eventType) {
            "assistant" -> parseAssistant(data)
            "user" -> parseUser(data)
            "result" -> parseResult(data)
            "system" -> parseSystem(data)
            else -> ParsedLine()
        }
    }

    private fun parseAssistant(data: JsonObject): ParsedLine {
        val blocks = data["message"]?.jsonObject?.get("content")?.jsonArray ?: return ParsedLine()
        val textParts = mutableListOf<String>()
        val toolLogs = mutableListOf<ToolLog>()
        for (block in blocks) {
            if (block is JsonPrimitive) {
                textParts.add(block.content)
                continue
            }
            val obj = block.jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> textParts.add(obj["text"]?.jsonPrimitive?.contentOrNull ?: "")
                "thinking" -> toolLogs.add(ToolLog("💭 思考...", toolName = "thinking"))
                "tool_use" -> {
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
                    val input = obj["input"]?.jsonObject ?: JsonObject(emptyMap())
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    toolLogs.add(ToolLog(formatToolCall(name, input), id, name))
                }
            }
        }
        val text = textParts.filter { it.isNotEmpty() }.joinToString("\n\n")
        return ParsedLine(textChunk = text, toolLogs = toolLogs)
    }

    private fun parseUser(data: JsonObject): ParsedLine {
        val blocks = data["message"]?.jsonObject?.get("content")?.jsonArray ?: return ParsedLine()
        val results = mutableListOf<ToolResult>()
        for (block in blocks) {
            if (block !is JsonObject) continue
            if (block["type"]?.jsonPrimitive?.contentOrNull != "tool_result") continue
            val toolUseId = block["tool_use_id"]?.jsonPrimitive?.contentOrNull ?: ""
            val isError = block["is_error"]?.jsonPrimitive?.booleanOrNull ?: false
            val rawContent = block["content"]
            val contentStr = when {
                rawContent is JsonPrimitive -> rawContent.contentOrNull ?: ""
                rawContent is JsonArray -> rawContent.mapNotNull { el ->
                    el.jsonObject.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                        ?.get("text")?.jsonPrimitive?.contentOrNull
                }.joinToString("\n")
                else -> ""
            }
            val summary = if (isError && contentStr.isNotBlank()) {
                contentStr.lineSequence().first().trim().take(PARAM_MAX)
            } else ""
            results.add(ToolResult(toolUseId, isError, summary))
        }
        return ParsedLine(toolResults = results)
    }

    private fun parseResult(data: JsonObject): ParsedLine {
        val cost = data["cost_usd"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val duration = data["duration_ms"]?.jsonPrimitive?.longOrNull ?: 0L
        val turns = data["num_turns"]?.jsonPrimitive?.intOrNull ?: 0
        val sessionId = data["session_id"]?.jsonPrimitive?.contentOrNull ?: ""
        val meta = ResultMeta(cost, duration, turns, sessionId)
        // result 中可能包含 fallback 文本
        val resultText = data["result"]?.jsonPrimitive?.contentOrNull ?: ""
        return ParsedLine(textChunk = resultText, meta = meta)
    }

    private fun parseSystem(data: JsonObject): ParsedLine {
        val subtype = data["subtype"]?.jsonPrimitive?.contentOrNull ?: ""
        if (subtype == "compact_boundary") {
            val pre = data["compact_metadata"]?.jsonObject?.get("pre_tokens")?.jsonPrimitive?.intOrNull ?: 0
            val text = if (pre > 0) "上下文已压缩（压缩前 %,d tokens）".format(pre) else "上下文已压缩"
            return ParsedLine(textChunk = text)
        }
        return ParsedLine()
    }

    fun formatToolCall(toolName: String, input: JsonObject): String {
        val icon = TOOL_ICONS[toolName] ?: "🔧"
        val rawParam = input["file_path"] ?: input["notebook_path"] ?: input["command"]
            ?: input["pattern"] ?: input["path"] ?: input["description"]
            ?: input["url"] ?: input.values.firstOrNull()
        val param = when (rawParam) {
            is JsonPrimitive -> rawParam.contentOrNull ?: ""
            else -> ""
        }
        val display = if (param.length > PARAM_MAX) param.take(PARAM_MAX - 3) + "..." else param
        return if (display.isNotEmpty()) "$icon $toolName `$display`" else "$icon $toolName"
    }

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
