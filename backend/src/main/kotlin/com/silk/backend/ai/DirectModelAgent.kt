package com.silk.backend.ai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File

/**
 * 直接调用 Claude 的 Agent。
 * 通过 [ClaudeProcessClient] 启动 claude CLI 子进程。
 *
 * 工作流程：
 * 1. 接收用户输入
 * 2. 将完整对话历史 + system prompt 构造成一个 prompt
 * 3. 调用 claude -p（流式）
 * 4. 流式返回回复
 */
class DirectModelAgent(
    private val sessionId: String = "default"
) {
    private val logger = LoggerFactory.getLogger(DirectModelAgent::class.java)

    /**
     * 以 DEBUG 级别记录完整的 prompt 和 response。
     * 平时不输出；需要调试时在 logback.xml 中将本类设为 DEBUG 即可。
     */
    private fun logPromptAndResponse(
        path: String,
        prompt: String,
        response: String,
        durationMs: Long
    ) {
        if (!logger.isDebugEnabled) return
        logger.debug(
            "[IO] session={}  path={}  duration={}ms\n" +
            ">>> PROMPT ({} chars) >>>\n{}\n" +
            "<<< RESPONSE ({} chars) <<<\n{}",
            sessionId, path, durationMs,
            prompt.length, prompt,
            response.length, response
        )
    }

    /** Claude CLI 进程客户端 */
    private lateinit var claudeProcessClient: ClaudeProcessClient

    /** 工作目录（群组隔离），由 initClaudeClient 设置 */
    private var workspaceDir: String = ""

    // 对话历史（保持上下文）
    private val conversationHistory = mutableListOf<Message>()

    // 群聊历史记录（兼容旧接口，不再使用）
    private var groupChatHistory: List<com.silk.backend.models.ChatHistoryEntry> = emptyList()
    // 群组成员列表（兼容旧接口，不再使用）
    private var groupMembersList: List<Pair<String, String>> = emptyList()

    private val currentResponseReferences = mutableListOf<com.silk.backend.models.MessageReference>()

    var lastAgentResponse: AgentResponse? = null
        private set

    /**
     * 在工作目录就绪后初始化 ClaudeProcessClient。
     * 与构造分离，因为 workspaceDir 在构造时尚不可知。
     */
    fun initClaudeClient(workspaceDir: String) {
        this.workspaceDir = workspaceDir
        if (!::claudeProcessClient.isInitialized) {
            claudeProcessClient = ClaudeProcessClient(
                groupId = sessionId,
                workspaceDir = workspaceDir,
            )
        }
    }

    /**
     * 设置群聊历史记录（兼容旧接口，不再使用）
     */
    fun setGroupChatHistory(history: List<com.silk.backend.models.ChatHistoryEntry>) {
        groupChatHistory = history
    }

    /**
     * 设置群组成员列表（兼容旧接口，不再使用）
     */
    fun setGroupMembersList(members: List<Pair<String, String>>) {
        groupMembersList = members
    }

    /**
     * 将持久化的近期聊天历史注入 conversationHistory，
     * 仅在 conversationHistory 为空时执行（deploy/重启后首次调用）。
     */
    fun loadRecentHistory(entries: List<com.silk.backend.models.ChatHistoryEntry>, agentId: String) {
        if (conversationHistory.isNotEmpty()) return
        val recent = entries.filter { it.messageType == "TEXT" }.takeLast(20)
        if (recent.isEmpty()) return

        for (entry in recent) {
            val role = if (entry.senderId == agentId) "assistant" else "user"
            val content = if (role == "user") {
                "[${entry.senderName}] ${entry.content}"
            } else {
                entry.content
            }
            conversationHistory.add(Message(role = role, content = content))
        }
        logger.info("📜 已注入 {} 条近期历史到 conversationHistory (session: {})", recent.size, sessionId)
    }

    data class AgentResponse(
        val content: String,
        val references: List<com.silk.backend.models.MessageReference> = emptyList()
    )

    private data class FinalCitationResult(
        val content: String,
        val references: List<com.silk.backend.models.MessageReference>
    )

    /**
     * 处理用户输入
     * @param userInput 用户输入
     * @param systemPrompt 系统提示词（可选）
     * @param callback 流式输出回调
     * @return 最终回复
     */
    suspend fun processInput(
        userInput: String,
        systemPrompt: String? = null,
        requestUserId: String = "",
        accessibleSessionIds: List<String> = listOf(sessionId),
        callback: suspend (stepType: String, content: String, isComplete: Boolean) -> Unit
    ): String {
        currentResponseReferences.clear()
        lastAgentResponse = null

        val effectiveSystemPrompt = withCitationGuidelines(systemPrompt ?: "你是 Silk，一个智能助手。")

        // 1. 添加用户消息到历史
        conversationHistory.add(Message(role = "user", content = userInput))

        // 2. 保存 system prompt
        val existingSystemIndex = conversationHistory.indexOfFirst { it.role == "system" }
        if (existingSystemIndex >= 0) {
            conversationHistory[existingSystemIndex] = Message(role = "system", content = effectiveSystemPrompt)
        } else {
            conversationHistory.add(0, Message(role = "system", content = effectiveSystemPrompt))
        }

        // 3. 调用 Claude CLI（纯聊天）
        val wrappedCallback: suspend (String, String, Boolean) -> Unit = { stepType, content, isComplete ->
            if (stepType == "complete" && isComplete) {
                val finalized = finalizeAgentResponse(content)
                lastAgentResponse = AgentResponse(content = finalized.content, references = finalized.references)
                callback(stepType, finalized.content, isComplete)
            } else {
                callback(stepType, content, isComplete)
            }
        }
        val rawResponse = chat(wrappedCallback)
        if (lastAgentResponse == null) {
            val finalized = finalizeAgentResponse(rawResponse)
            lastAgentResponse = AgentResponse(content = finalized.content, references = finalized.references)
        }
        logger.info("[processInput] refs={}, contentHasCitation={}, contentLen={}",
            lastAgentResponse!!.references.size,
            lastAgentResponse!!.content.contains("[citation:"),
            lastAgentResponse!!.content.length)
        logger.info("[processInput] content_preview: {}",
            lastAgentResponse!!.content.take(300).replace('\n', ' '))
        logger.info("[processInput] content_tail: {}",
            lastAgentResponse!!.content.takeLast(400).replace('\n', ' '))
        return lastAgentResponse!!.content
    }

    private fun withCitationGuidelines(systemPrompt: String?): String {
        val base = systemPrompt ?: "你是 Silk，一个智能助手。"
        return buildString {
            appendLine(base)
            appendLine()
            appendLine("## 网络搜索规则（必须遵守）")
            appendLine("对于天气、新闻、实时数据、股价、赛事等时效性信息，你必须使用 web_search 工具获取最新结果，不能仅凭训练数据回答。")
            appendLine()
            appendLine("## 引用规则（必须遵守）")
            appendLine("当你使用网络搜索获取信息后，必须在回答中标注信息来源：")
            appendLine("- 引用网络搜索结果时，在相关内容末尾添加 [citation:数字]")
            appendLine("- 第一个搜索结果的引用编号为 [citation:1]，第二个为 [citation:2]，以此类推")
            appendLine("- 引用标记必须放在相关内容的句末或段末")
            appendLine("- 每个重要观点都必须标注来源引用，不能遗漏")
            appendLine("- 禁止堆砌大量引用标记；只为对应观点添加必要引用")
            appendLine("- 如果你没有使用网络搜索，则不需要添加引用标记")
        }
    }

    private fun registerReference(
        kind: String,
        title: String,
        url: String? = null,
        snippet: String? = null,
        path: String? = null
    ): Int {
        val index = currentResponseReferences.size + 1
        currentResponseReferences.add(
            com.silk.backend.models.MessageReference(
                kind = kind,
                index = index,
                title = title,
                url = url,
                snippet = snippet,
                path = path
            )
        )
        return index
    }

    /**
     * 构建 prompt 并调用 claude CLI。
     * 将 conversationHistory 格式化为文本 prompt，包含 system + 对话轮次 + 当前消息。
     */
    private suspend fun chat(
        callback: suspend (stepType: String, content: String, isComplete: Boolean) -> Unit
    ): String {
        if (!::claudeProcessClient.isInitialized) {
            val fallback = "backend/chat_workspaces/$sessionId"
            claudeProcessClient = ClaudeProcessClient(
                groupId = sessionId,
                workspaceDir = fallback,
            )
            java.io.File(fallback).mkdirs()
        }

        callback("thinking", "🤔 思考中...", false)

        // 将对话历史写入 chat_history.md，供 Claude 的 Grep/Read 工具搜索
        val historyFile = java.io.File(workspaceDir, "chat_history.md")
        historyFile.writeText(buildString {
            appendLine("# 群聊历史记录")
            appendLine()
            for (msg in conversationHistory) {
                if (msg.role == "system") continue
                when (msg.role) {
                    "user" -> appendLine("**User**: ${msg.content}")
                    "assistant" -> {
                        appendLine("**Assistant**: ${msg.content}")
                        appendLine()
                    }
                }
            }
        })

        // 构建工具/工作区上下文（两种路径共用）
        val toolContext = buildString {
            appendLine("## 可用工具")
            appendLine("你有以下工具可用：")
            appendLine("- **web_search**: 搜索互联网获取实时信息")
            appendLine("- **Grep**: 搜索工作区文件内容（包括 chat_history.md 中的历史消息）")
            appendLine("- **Read**: 读取工作区文件")
            appendLine("- **glob**: 查找工作区文件")
            appendLine()
            appendLine("群聊历史已保存到 `chat_history.md`，你可以用 Grep 搜索历史消息。")
        }

        val response = try {
            // 优先使用 Claude CLI（内置 web_search、Grep、Read、glob 工具，原生支持 [citation:N] 引用）
            try {
                chatViaClaudeProcess(toolContext, callback)
            } catch (e: Exception) {
                logger.warn("⚠️ [DirectModelAgent] Claude CLI 调用失败，回退到 API 路径: ${e.message}")
                val apiKey = AIConfig.ANTHROPIC_API_KEY
                if (apiKey.isNotBlank()) {
                    chatViaAnthropicApi(apiKey, toolContext, callback)
                } else {
                    throw e
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("❌ [DirectModelAgent] AI 调用失败: ${e.message}")
            callback("error", "❌ AI 调用失败: ${e.message}", true)
            "抱歉，处理您的问题时发生了错误。"
        }

        // 保存回复到对话历史
        conversationHistory.add(Message(role = "assistant", content = response))
        callback("complete", response, true)
        return response
    }

    private suspend fun chatViaClaudeProcess(
        toolContext: String,
        callback: suspend (String, String, Boolean) -> Unit
    ): String {
        val fullPrompt = buildString {
            for (msg in conversationHistory) {
                when (msg.role) {
                    "system" -> {
                        appendLine(msg.content)
                        appendLine()
                    }
                    "user" -> {
                        appendLine("User: ${msg.content}")
                    }
                    "assistant" -> {
                        appendLine("Assistant: ${msg.content}")
                        appendLine()
                    }
                }
            }
            appendLine()
            appendLine(toolContext)
            appendLine()
            appendLine("Assistant:")
        }

        val startTime = System.currentTimeMillis()
        val result = claudeProcessClient.streamCompletion(
            fullPrompt = fullPrompt,
            callback = callback,
        )
        logPromptAndResponse("claude-cli", fullPrompt, result, System.currentTimeMillis() - startTime)
        return result
    }

    private suspend fun chatViaAnthropicApi(
        apiKey: String,
        toolContext: String,
        callback: suspend (String, String, Boolean) -> Unit
    ): String {
        val systemMessages = conversationHistory.filter { it.role == "system" }.mapNotNull { it.content }
        val mergedSystem = buildString {
            if (systemMessages.isNotEmpty()) {
                systemMessages.forEach { appendLine(it) }
                appendLine()
            }
            appendLine(toolContext)
        }
        val nonSystemMessages = conversationHistory.filter { it.role != "system" }

        val apiPromptForLog = buildString {
            appendLine("[System]")
            appendLine(mergedSystem)
            appendLine()
            for (msg in nonSystemMessages) {
                appendLine("[${msg.role}]")
                appendLine(msg.content)
                appendLine()
            }
        }
        val apiStartTime = System.currentTimeMillis()

        val client = AnthropicClient(apiKey = apiKey)

        // web_search 是 Anthropic 服务端工具，Claude 自行调用并处理搜索结果
        val tools = listOf(
            Tool(
                type = "function",
                function = ToolDefinition(
                    name = "web_search",
                    description = "搜索互联网获取实时信息。当用户询问实时信息、最新新闻、你不知道的内容，或者需要外部数据来回答问题时使用。",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("query", buildJsonObject {
                                put("type", "string")
                                put("description", "搜索关键词")
                            })
                        })
                        put("required", buildJsonArray { add("query") })
                    }
                )
            )
        )

        val result = client.streamCompletion(
            systemPrompt = mergedSystem.trim(),
            messages = nonSystemMessages,
            tools = tools,
            callback = callback,
        )

        // 从 Anthropic 响应中提取服务端 web_search 返回的引用元数据
        val citationCount = result.citations?.size ?: 0
        result.citations?.forEach { cit ->
            registerReference("citation", cit.title, cit.url, cit.text)
        }

        logger.info("[chatViaAnthropicApi] result: content_len={}, citations={}, toolCalls={}",
            result.content.length, citationCount, result.toolCalls?.size ?: 0)
        // 记录回复的前 300 字，便于调试引用内容
        if (result.content.isNotEmpty()) {
            logger.info("[chatViaAnthropicApi] content_preview: {}",
                result.content.take(300).replace('\n', ' '))
        }

        logPromptAndResponse("anthropic-api", apiPromptForLog, result.content, System.currentTimeMillis() - apiStartTime)
        return result.content
    }

    // ── 引用处理 ──────────────────────────────────────────────────────

    private fun removeInvalidCitationMarkers(content: String): String {
        return content.replace(Regex("\\[(citation|available):(\\d+)\\]")) { match ->
            val kind = match.groupValues[1]
            val idx = match.groupValues[2].toIntOrNull() ?: 0
            val exists = currentResponseReferences.any { it.kind == kind && it.index == idx }
            if (exists) match.value else ""
        }
    }

    private fun ensureCitationMarkers(content: String): String {
        if (currentResponseReferences.isEmpty()) {
            // 没有注册引用时：citation 标记来自 Anthropic 服务端 web_search，始终保留
            // 只清理 available 标记（旧代码注入的本地文件引用，无引用时无效）
            return content.replace(Regex("\\[available:\\d+\\]"), "")
        }
        val cleaned = removeInvalidCitationMarkers(content)
        if (Regex("\\[(citation|available):\\d+\\]").containsMatchIn(cleaned)) return cleaned

        val topRefs = currentResponseReferences.take(3)
        val markers = topRefs.joinToString(" ") { ref ->
            "[${ref.kind}:${ref.index}]"
        }
        return "$cleaned $markers"
    }

    private fun finalizeAgentResponse(content: String): FinalCitationResult {
        // Claude CLI 路径：无真实引用（空或全是占位），直接剥离标记
        // 让 Sources: 部分（带真实链接）自然显示
        if (currentResponseReferences.isEmpty() || currentResponseReferences.all { it.url == null && it.path == null }) {
            val stripped = content.replace(Regex("\\[(citation|available):\\d+\\]"), "")
            return FinalCitationResult(stripped, emptyList())
        }
        val withMarkers = ensureCitationMarkers(content)
        return normalizeCitedReferences(withMarkers)
    }

    private fun normalizeCitedReferences(content: String): FinalCitationResult {
        val citedPattern = Regex("\\[(citation|available):(\\d+)\\]")
        val citedKeys = citedPattern.findAll(content)
            .mapNotNull { match ->
                val kind = match.groupValues[1]
                val idx = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                "$kind:$idx"
            }
            .distinct()
            .toList()

        if (citedKeys.isEmpty()) {
            // 文本中没有引用标记但有搜索结果的，仍然返回 references 供前端展示来源列表
            if (currentResponseReferences.isNotEmpty()) {
                return FinalCitationResult(content, currentResponseReferences.toList())
            }
            return FinalCitationResult(content, emptyList())
        }

        val citedRefs = citedKeys.mapNotNull { key ->
            val kind = key.substringBefore(":")
            val idx = key.substringAfter(":").toIntOrNull() ?: return@mapNotNull null
            currentResponseReferences.find { it.kind == kind && it.index == idx }
        }

        val reindexMap = mutableMapOf<String, Int>()
        val newRefs = mutableListOf<com.silk.backend.models.MessageReference>()
        var citationCounter = 0
        var availableCounter = 0

        // 先处理有元数据的引用
        for (ref in citedRefs) {
            val newIndex = if (ref.kind == "citation") {
                ++citationCounter
            } else {
                ++availableCounter
            }
            reindexMap["${ref.kind}:${ref.index}"] = newIndex
            newRefs.add(ref.copy(index = newIndex))
        }

        // 对文本中有标记但无对应元数据的（如 Claude CLI 输出的 [citation:N]），创建占位引用
        for (key in citedKeys) {
            if (reindexMap.containsKey(key)) continue
            val kind = key.substringBefore(":")
            val idx = key.substringAfter(":").toIntOrNull() ?: continue
            val newIndex = if (kind == "citation" || kind == "available") {
                if (kind == "citation") ++citationCounter else ++availableCounter
            } else continue
            reindexMap[key] = newIndex
            newRefs.add(
                com.silk.backend.models.MessageReference(
                    kind = kind,
                    index = newIndex,
                    title = "${if (kind == "citation") "来源" else "资料"} $idx",
                    snippet = null,
                    url = null,
                    path = null
                )
            )
        }

        val newContent = citedPattern.replace(content) { match ->
            val kind = match.groupValues[1]
            val oldIdx = match.groupValues[2].toInt()
            val newIdx = reindexMap["$kind:$oldIdx"] ?: oldIdx
            "[$kind:$newIdx]"
        }

        return FinalCitationResult(newContent, newRefs)
    }

    // ── 测试辅助 ──────────────────────────────────────────────────────

    internal fun citationGuidelinesForTest(prompt: String): String = withCitationGuidelines(prompt)

    internal fun referencesForTest(): List<com.silk.backend.models.MessageReference> =
        currentResponseReferences.toList()

    internal fun resetReferencesForTest() {
        currentResponseReferences.clear()
    }

    internal fun ensureCitationMarkersForTest(content: String): String =
        ensureCitationMarkers(content)

    internal fun registerCitationForTest(title: String, url: String): Int =
        registerReference(kind = "citation", title = title, url = url)

    internal fun citedReferencesForTest(content: String): List<com.silk.backend.models.MessageReference> {
        val result = normalizeCitedReferences(content)
        return result.references
    }

    internal fun finalizeCitationsForTest(content: String): AgentResponse {
        val result = finalizeAgentResponse(content)
        return AgentResponse(content = result.content, references = result.references)
    }

    /**
     * 清空对话历史
     */
    fun clearHistory() {
        conversationHistory.clear()
    }

    /**
     * 获取对话历史
     */
    fun getHistory(): List<Message> {
        return conversationHistory.toList()
    }
}
