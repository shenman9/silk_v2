package com.silk.backend.ai

import com.silk.backend.models.ChatHistoryEntry
import com.silk.backend.search.WeaviateClient
import com.silk.backend.search.SearchMode
import com.silk.backend.search.IsolatedSearchResults
import com.silk.backend.search.ExternalSearchService
import com.silk.backend.search.ExternalSearchResults
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 搜索驱动的智能助手
 * 
 * 三层搜索工作流程：
 * 1. 接收用户输入
 * 2. 并行执行三层搜索：
 *    - Layer 1 (FOREGROUND): Weaviate 当前会话上下文
 *    - Layer 2 (BACKGROUND): Weaviate 其他会话历史
 *    - Layer 3 (EXTERNAL): 外部搜索引擎（DuckDuckGo/SerpAPI）
 * 3. 按倒序 3→2→1 合并结果（外部→历史→当前）
 * 4. 分析用户意图和情绪
 * 5. 基于搜索结果生成回复
 */
class SearchDrivenAgent(
    private val apiKey: String = AIConfig.API_KEY,
    private val sessionId: String = "default",
    private val userId: String = "default_user"
) {
    private val logger = LoggerFactory.getLogger(SearchDrivenAgent::class.java)
    
    private val httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)  // 强制使用 HTTP/1.1，避免 HTTP/2 升级导致 vLLM 兼容问题
        .connectTimeout(Duration.ofMillis(AIConfig.TIMEOUT))
        .build()
    
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    
    private val weaviateClient = WeaviateClient(AIConfig.requireWeaviateUrl())
    private val externalSearchService = ExternalSearchService()
    
    /**
     * 用户意图分析结果
     */
    @Serializable
    data class IntentAnalysis(
        val goal: String = "",
        val emotion: String = "中性",
        val needsHelp: Boolean = false,
        val helpType: String = "",
        val confidence: Float = 0.5f
    )
    
    /**
     * 智能响应结果
     */
    data class AgentResponse(
        val reply: String,
        val intentAnalysis: IntentAnalysis,
        val foregroundContextUsed: Int,
        val backgroundContextUsed: Int,
        val externalContextUsed: Int,  // 外部搜索结果数量
        val searchTimeMs: Long,
        val totalTimeMs: Long
    )
    
    /**
     * 三层搜索结果
     */
    data class ThreeLayerSearchResults(
        val foreground: String,       // Layer 1: 当前会话上下文
        val background: String,       // Layer 2: 历史会话上下文
        val external: String,         // Layer 3: 外部搜索结果
        val foregroundCount: Int,
        val backgroundCount: Int,
        val externalCount: Int,
        val totalTimeMs: Long
    )
    
    /**
     * 处理用户输入，返回智能响应
     * 
     * @param userInput 用户输入
     * @param recentHistory 最近的聊天历史
     * @param callback 流式输出回调
     */
    suspend fun processInput(
        userInput: String,
        recentHistory: List<ChatHistoryEntry> = emptyList(),
        callback: suspend (stepType: String, content: String, isComplete: Boolean) -> Unit
    ): AgentResponse {
        val startTime = System.currentTimeMillis()
        
        // Step 1: 三层并行搜索
        callback("searching", "🔍 正在执行三层搜索...", false)
        
        val searchResults = performThreeLayerSearch(userInput, callback)
        
        callback("searching", """✅ 三层搜索完成：
  • Layer 1 (当前会话): ${searchResults.foregroundCount} 条
  • Layer 2 (历史会话): ${searchResults.backgroundCount} 条
  • Layer 3 (外部搜索): ${searchResults.externalCount} 条""", true)
        delay(300)
        
        // Step 2: 分析用户意图
        callback("analyzing", "🧠 分析用户意图...", false)
        
        val intentAnalysis = analyzeIntent(userInput, recentHistory)
        
        callback("analyzing", """
            📊 意图分析：
            - 目标：${intentAnalysis.goal}
            - 情绪：${intentAnalysis.emotion}
            - 需要帮助：${if (intentAnalysis.needsHelp) "是" else "否"}
            ${if (intentAnalysis.needsHelp) "- 帮助类型：${intentAnalysis.helpType}" else ""}
        """.trimIndent(), true)
        delay(300)
        
        // Step 3: 生成回复（使用三层上下文）
        callback("generating", "✨ 生成回复...", false)
        
        val reply = if (intentAnalysis.needsHelp || userInput.contains("?") || userInput.contains("？")) {
            generateThreeLayerEnhancedReply(
                userInput = userInput,
                searchResults = searchResults,
                intentAnalysis = intentAnalysis,
                callback = callback
            )
        } else {
            // 简单响应，不需要复杂处理
            generateSimpleResponse(userInput, intentAnalysis)
        }
        
        callback("complete", reply, true)
        
        val totalTimeMs = System.currentTimeMillis() - startTime
        
        return AgentResponse(
            reply = reply,
            intentAnalysis = intentAnalysis,
            foregroundContextUsed = searchResults.foregroundCount,
            backgroundContextUsed = searchResults.backgroundCount,
            externalContextUsed = searchResults.externalCount,
            searchTimeMs = searchResults.totalTimeMs,
            totalTimeMs = totalTimeMs
        )
    }
    
    /**
     * 执行三层并行搜索
     * Layer 1: FOREGROUND (当前会话)
     * Layer 2: BACKGROUND (历史会话)
     * Layer 3: EXTERNAL (外部搜索引擎)
     */
    private suspend fun performThreeLayerSearch(
        query: String,
        callback: suspend (String, String, Boolean) -> Unit
    ): ThreeLayerSearchResults = coroutineScope {
        val startTime = System.currentTimeMillis()
        
        // 并行执行三层搜索
        callback("searching", "  ├─ Layer 1: 搜索当前会话...", false)
        callback("searching", "  ├─ Layer 2: 搜索历史会话...", false)
        callback("searching", "  └─ Layer 3: 搜索外部引擎...", false)
        
        // Layer 1 & 2: Weaviate 搜索
        val weaviateDeferred = async {
            try {
                if (weaviateClient.isReady()) {
                    weaviateClient.isolatedSearch(
                        query = query,
                        userId = userId,
                        currentSessionId = sessionId,
                        mode = SearchMode.FOREGROUND_FIRST,
                        foregroundLimit = 5,
                        backgroundLimit = 3,
                        alpha = 0.5f
                    )
                } else {
                    logger.warn("⚠️ Weaviate 不可用")
                    null
                }
            } catch (e: Exception) {
                logger.error("❌ Weaviate 搜索失败: ${e.message}")
                null
            }
        }
        
        // Layer 3: 外部搜索
        val externalDeferred = async {
            try {
                externalSearchService.search(query, limit = 3)
            } catch (e: Exception) {
                logger.error("❌ 外部搜索失败: ${e.message}")
                ExternalSearchResults(
                    success = false,
                    source = "error",
                    results = emptyList(),
                    searchTimeMs = 0
                )
            }
        }
        
        // 等待所有搜索完成
        val weaviateResults = weaviateDeferred.await()
        val externalResults = externalDeferred.await()
        
        // 构建 Layer 1: FOREGROUND 上下文 - ⚠️ 最高可靠性：当前会话的直接相关信息
        val foregroundContext = weaviateResults?.foreground?.documents
            ?.joinToString("\n\n") { doc ->
                // 前缀：[FOREGROUND/可靠] 表示这是当前会话的高可信度信息
                "[FOREGROUND/可靠-当前会话] [${doc.sourceType}] ${doc.title ?: "无标题"}\n${doc.content.take(300)}"
            } ?: "无当前会话相关内容"
        
        // 构建 Layer 2: BACKGROUND 上下文 - ⚠️ 中等可靠性：历史会话的参考信息
        val backgroundContext = weaviateResults?.background?.documents
            ?.joinToString("\n\n") { doc ->
                // 前缀：[BACKGROUND/参考] 表示这是历史会话的参考信息，可能与当前上下文有偏差
                "[BACKGROUND/参考-历史会话] [历史: ${doc.sessionId}] ${doc.title ?: "无标题"}\n${doc.content.take(200)}"
            } ?: "无历史相关内容"
        
        // 构建 Layer 3: EXTERNAL 上下文 - ⚠️ 最低可靠性：互联网搜索结果，仅供参考
        val externalContext = if (externalResults.success && externalResults.results.isNotEmpty()) {
            externalResults.results.joinToString("\n\n") { result ->
                // 前缀：[INTERNET/待验证] 表示这是互联网搜索结果，信息可能不准确，需要谨慎使用
                "[INTERNET/待验证-外部搜索] [${result.source}] ${result.title}\n${result.snippet.take(200)}\n来源URL: ${result.url}"
            }
        } else {
            "无外部搜索结果"
        }
        
        val foregroundCount = weaviateResults?.foreground?.documents?.size ?: 0
        val backgroundCount = weaviateResults?.background?.documents?.size ?: 0
        val externalCount = if (externalResults.success) externalResults.results.size else 0
        
        logger.info("🔍 三层搜索完成: L1=$foregroundCount, L2=$backgroundCount, L3=$externalCount")
        
        ThreeLayerSearchResults(
            foreground = foregroundContext,
            background = backgroundContext,
            external = externalContext,
            foregroundCount = foregroundCount,
            backgroundCount = backgroundCount,
            externalCount = externalCount,
            totalTimeMs = System.currentTimeMillis() - startTime
        )
    }
    
    /**
     * 生成三层上下文增强的回复
     * 按倒序 3→2→1 呈现上下文（外部→历史→当前）
     */
    private suspend fun generateThreeLayerEnhancedReply(
        userInput: String,
        searchResults: ThreeLayerSearchResults,
        intentAnalysis: IntentAnalysis,
        callback: suspend (String, String, Boolean) -> Unit
    ): String {
        // 判断搜索结果是否为空
        val hasSearchResults = searchResults.foregroundCount > 0 || 
                               searchResults.backgroundCount > 0 || 
                               searchResults.externalCount > 0
        
        // 判断是否有外部搜索结果
        val hasExternalResults = searchResults.externalCount > 0
        
        val noResultsNote = if (!hasSearchResults) {
            """
=== ⚠️ 搜索结果为空 ===
三层搜索均未找到相关信息。根据【回答来源标注规则】：
- 如果这是一个通用知识问题（历史、科学、公司信息等），请使用你的 AI 知识库回答
- 回答末尾需标注："💡 *此回答基于 AI 知识库*"
- 如果这是关于当前会话或用户个人信息的问题，请坦诚告知没有找到相关信息

"""
        } else if (hasExternalResults && searchResults.foregroundCount == 0 && searchResults.backgroundCount == 0) {
            """
=== ℹ️ 仅有外部搜索结果 ===
当前会话和历史会话中未找到相关信息，但外部搜索找到了一些参考资料。
- 如果使用外部搜索结果回答，请在末尾标注："🌐 *此回答基于互联网搜索结果*"

"""
        } else {
            ""
        }
        
        // 按倒序 3→2→1 构建上下文（优先级从低到高）
        val prompt = """
${AIConfig.COMMON_PROMPT}

【用户输入】
$userInput

$noResultsNote=== 搜索结果说明 ===
搜索结果按可靠性从低到高排列，每条信息前有来源标识前缀：
• [INTERNET/待验证-外部搜索]: 互联网搜索结果，可靠性最低，信息可能不准确或过时，仅作为补充参考
• [BACKGROUND/参考-历史会话]: 历史会话记录，可靠性中等，可借鉴但可能与当前上下文有偏差
• [FOREGROUND/可靠-当前会话]: 当前会话内容，可靠性最高，与用户问题直接相关

=== Layer 3: 外部搜索结果（优先级最低，仅供参考）===
${if (searchResults.externalCount > 0) searchResults.external else "（无外部搜索结果）"}

=== Layer 2: 历史会话上下文（中等优先级，历史参考）===
${if (searchResults.backgroundCount > 0) searchResults.background else "（无历史相关内容）"}

=== Layer 1: 当前会话上下文（最高优先级，最相关信息）===
${if (searchResults.foregroundCount > 0) searchResults.foreground else "（无当前会话相关内容）"}

=== 用户意图分析 ===
- 目标：${intentAnalysis.goal}
- 情绪：${intentAnalysis.emotion}
- 帮助类型：${intentAnalysis.helpType}

请基于以上信息生成回复。注意：
1. 优先采信 [FOREGROUND/可靠] 标记的内容，这是当前会话的直接相关信息
2. [BACKGROUND/参考] 标记的历史内容可作为补充参考
3. [INTERNET/待验证] 标记的外部搜索结果仅作为知识补充，使用时需谨慎
4. 如果信息来源相互矛盾，以 FOREGROUND > BACKGROUND > INTERNET 的顺序取舍
5. 语气友好专业，简洁明了

【回答来源标注（必须遵守）】：
- 如果回答主要基于 [INTERNET] 外部搜索结果 → 末尾标注："🌐 *此回答基于互联网搜索结果*"
- 如果搜索结果为空，使用 AI 自身知识回答 → 末尾标注："💡 *此回答基于 AI 知识库*"
- 如果回答基于 [FOREGROUND] 或 [BACKGROUND] 会话内容 → 无需额外标注
        """.trimIndent()
        
        if (apiKey.isEmpty()) {
            return buildFallbackResponse(searchResults, intentAnalysis)
        }
        
        return try {
            val responseBuilder = StringBuilder()
            
            callAIApiStreaming(prompt) { chunk ->
                responseBuilder.append(chunk)
                if (responseBuilder.length % 100 == 0) {
                    callback("streaming", chunk, false)
                }
            }
            
            responseBuilder.toString()
        } catch (e: Exception) {
            logger.error("生成回复失败: ${e.message}")
            buildFallbackResponse(searchResults, intentAnalysis)
        }
    }
    
    /**
     * 构建降级响应（API 不可用时）
     */
    private fun buildFallbackResponse(
        searchResults: ThreeLayerSearchResults,
        intentAnalysis: IntentAnalysis
    ): String {
        val hasContext = searchResults.foregroundCount > 0 || 
                        searchResults.backgroundCount > 0 || 
                        searchResults.externalCount > 0
        
        return "我理解你想要${intentAnalysis.goal}。" +
               if (hasContext) {
                   "根据搜索结果，我找到了一些相关信息：\n" +
                   "• 当前会话: ${searchResults.foregroundCount} 条\n" +
                   "• 历史记录: ${searchResults.backgroundCount} 条\n" +
                   "• 外部参考: ${searchResults.externalCount} 条\n" +
                   "请告诉我更多细节，我可以更好地帮助你。"
               } else {
                   "暂时没有找到直接相关的信息。请提供更多细节，我来帮你查找。"
               }
    }
    
    /**
     * 分析用户意图
     */
    private suspend fun analyzeIntent(
        userInput: String,
        recentHistory: List<ChatHistoryEntry>
    ): IntentAnalysis {
        if (apiKey.isEmpty()) {
            return analyzeIntentOffline(userInput)
        }
        
        val historyContext = recentHistory.takeLast(5).joinToString("\n") {
            "${it.senderName}: ${it.content}"
        }
        
        val prompt = """
${AIConfig.INTENT_ANALYSIS_PROMPT}

【最近对话】
$historyContext

【当前用户输入】
$userInput

请以 JSON 格式回复。
        """.trimIndent()
        
        return try {
            val response = callAIApi(prompt)
            
            // 解析 JSON 响应
            val jsonMatch = Regex("\\{[^}]+\\}").find(response)
            if (jsonMatch != null) {
                val jsonObj = json.parseToJsonElement(jsonMatch.value).jsonObject
                IntentAnalysis(
                    goal = jsonObj["goal"]?.jsonPrimitive?.content ?: "",
                    emotion = jsonObj["emotion"]?.jsonPrimitive?.content ?: "中性",
                    needsHelp = jsonObj["needs_help"]?.jsonPrimitive?.booleanOrNull ?: false,
                    helpType = jsonObj["help_type"]?.jsonPrimitive?.content ?: "",
                    confidence = jsonObj["confidence"]?.jsonPrimitive?.floatOrNull ?: 0.5f
                )
            } else {
                analyzeIntentOffline(userInput)
            }
        } catch (e: Exception) {
            logger.error("意图分析失败: ${e.message}")
            analyzeIntentOffline(userInput)
        }
    }
    
    /**
     * 离线意图分析
     */
    private fun analyzeIntentOffline(userInput: String): IntentAnalysis {
        val input = userInput.lowercase()
        
        val goal = when {
            input.contains("如何") || input.contains("怎么") -> "寻求方法指导"
            input.contains("为什么") -> "理解原因"
            input.contains("什么是") || input.contains("是什么") -> "获取信息"
            input.contains("帮") -> "请求帮助"
            input.contains("?") || input.contains("？") -> "提出问题"
            else -> "进行对话"
        }
        
        val emotion = when {
            input.contains("谢谢") || input.contains("感谢") || input.contains("棒") -> "积极"
            input.contains("困难") || input.contains("问题") || input.contains("不行") -> "焦虑"
            input.contains("不懂") || input.contains("迷惑") -> "困惑"
            else -> "中性"
        }
        
        val needsHelp = input.contains("?") || input.contains("？") || 
                       input.contains("帮") || input.contains("如何") ||
                       input.contains("怎么")
        
        return IntentAnalysis(
            goal = goal,
            emotion = emotion,
            needsHelp = needsHelp,
            helpType = if (needsHelp) "信息查询" else "",
            confidence = 0.6f
        )
    }
    
    
    /**
     * 生成简单响应
     */
    private fun generateSimpleResponse(userInput: String, intent: IntentAnalysis): String {
        return when {
            userInput.contains("你好") || userInput.contains("hello") -> 
                "你好！我是 Silk，有什么可以帮助你的吗？"
            userInput.contains("谢谢") -> 
                "不客气！有其他问题随时问我。"
            userInput.contains("再见") || userInput.contains("bye") -> 
                "再见！期待下次交流。"
            else -> 
                "我注意到你说了「$userInput」。${if (intent.emotion == "积极") "很高兴！" else ""}有什么我可以帮忙的吗？"
        }
    }
    
    /**
     * 调用 AI API（非流式）
     */
    private suspend fun callAIApi(prompt: String): String {
        val requestBody = ApiRequest(
            model = AIConfig.MODEL,
            messages = listOf(ApiMessage(role = "user", content = prompt)),
            temperature = 0.7,
            max_tokens = 2000,
            stream = false
        )
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(AIConfig.requireApiBaseUrl()))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(requestBody)))
            .timeout(Duration.ofMillis(AIConfig.TIMEOUT))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        return if (response.statusCode() == 200) {
            val apiResponse = json.decodeFromString<ApiResponse>(response.body())
            apiResponse.choices.firstOrNull()?.message?.content ?: ""
        } else {
            throw Exception("API 调用失败：${response.statusCode()}")
        }
    }
    
    /**
     * 调用 AI API（流式）
     */
    private suspend fun callAIApiStreaming(
        prompt: String,
        onChunk: suspend (String) -> Unit
    ): String {
        val requestBody = ApiRequest(
            model = AIConfig.MODEL,
            messages = listOf(ApiMessage(role = "user", content = prompt)),
            temperature = 0.7,
            max_tokens = 2000,
            stream = true
        )
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(AIConfig.requireApiBaseUrl()))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(requestBody)))
            .timeout(Duration.ofMillis(AIConfig.TIMEOUT))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        
        if (response.statusCode() != 200) {
            throw Exception("API 调用失败：${response.statusCode()}")
        }
        
        val fullText = StringBuilder()
        
        response.body().bufferedReader().use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.startsWith("data: ")) {
                    val jsonData = line!!.substring(6).trim()
                    if (jsonData == "[DONE]") break
                    
                    try {
                        val streamResponse = json.decodeFromString<StreamResponse>(jsonData)
                        val delta = streamResponse.choices.firstOrNull()?.delta
                        val content = delta?.content ?: ""
                        val reasoning = delta?.reasoning ?: ""
                        val combinedText = content + reasoning

                        if (combinedText.isNotEmpty()) {
                            fullText.append(combinedText)
                            onChunk(combinedText)
                        }
                    } catch (e: Exception) {
                        // 忽略解析错误
                    }
                }
            }
        }
        
        return fullText.toString()
    }
    
    /**
     * 索引新消息到搜索系统
     */
    suspend fun indexMessage(
        message: ChatHistoryEntry,
        participants: List<String>
    ): Boolean {
        return try {
            weaviateClient.indexDocument(
                document = com.silk.backend.search.IndexDocument(
                    content = message.content,
                    title = "Chat: ${message.senderName}",
                    sourceType = "CHAT",
                    fileType = "MESSAGE",
                    sessionId = sessionId,
                    authorId = message.senderId,
                    authorName = message.senderName,
                    timestamp = java.time.Instant.ofEpochMilli(message.timestamp).toString()
                ),
                participants = participants
            )
        } catch (e: Exception) {
            logger.error("索引消息失败: ${e.message}")
            false
        }
    }
    
    fun close() {
        weaviateClient.close()
        externalSearchService.close()
    }
}

