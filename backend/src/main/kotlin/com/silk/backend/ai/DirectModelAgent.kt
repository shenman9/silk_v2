package com.silk.backend.ai

import com.silk.backend.search.WeaviateClient
import com.silk.backend.search.SearchMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
 * 直接调用模型的 Agent
 * 简化流程：直接调用模型，让模型自动使用其 tool 能力（搜索文件、浏览器等）
 * 
 * 工作流程：
 * 1. 接收用户输入
 * 2. 直接调用模型 API（支持 tool calling）
 * 3. 如果模型返回 tool_call，自动执行并返回结果
 * 4. 循环直到模型返回最终回复
 */
class DirectModelAgent(
    private val apiKey: String = AIConfig.API_KEY,
    private val sessionId: String = "default"
) {
    private val logger = LoggerFactory.getLogger(DirectModelAgent::class.java)
    
    private val httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofMillis(AIConfig.TIMEOUT))
        .build()
    
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    
    // 对话历史（保持上下文）
    private val conversationHistory = mutableListOf<Message>()
    
    // Weaviate 搜索客户端（用于搜索已上传的 PDF 等文件）
    private val weaviateClient = WeaviateClient(AIConfig.requireWeaviateUrl())
    
    /**
     * OpenAI 兼容的消息格式
     */
    @Serializable
    data class Message(
        val role: String,
        val content: String? = null,
        val tool_calls: List<ToolCall>? = null,
        val tool_call_id: String? = null
    )
    
    /**
     * Tool 调用
     */
    @Serializable
    data class ToolCall(
        val id: String,
        val type: String = "function",
        val function: ToolFunction
    )
    
    @Serializable
    data class ToolFunction(
        val name: String,
        val arguments: String
    )
    
    /**
     * Tool 定义
     */
    @Serializable
    data class Tool(
        val type: String = "function",
        val function: ToolDefinition
    )
    
    @Serializable
    data class ToolDefinition(
        val name: String,
        val description: String,
        val parameters: JsonObject
    )
    
    /**
     * API 请求
     */
    @Serializable
    data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val tools: List<Tool>? = null,
        val temperature: Double = 0.7,
        val max_tokens: Int = 4096,
        val stream: Boolean = false
    )
    
    /**
     * API 响应
     */
    @Serializable
    data class ChatResponse(
        val choices: List<Choice>,
        val usage: Usage? = null
    )
    
    @Serializable
    data class Choice(
        val message: ResponseMessage,
        val finish_reason: String? = null
    )
    
    @Serializable
    data class ResponseMessage(
        val role: String,
        val content: String? = null,
        val tool_calls: List<ToolCall>? = null
    )
    
    @Serializable
    data class Usage(
        val prompt_tokens: Int,
        val completion_tokens: Int,
        val total_tokens: Int
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
        callback: suspend (stepType: String, content: String, isComplete: Boolean) -> Unit
    ): String {
        // 添加用户消息到历史
        conversationHistory.add(Message(role = "user", content = userInput))
        
        // 如果是首次对话，添加系统提示
        if (conversationHistory.size == 1 && systemPrompt != null) {
            conversationHistory.add(0, Message(role = "system", content = systemPrompt))
        }
        
        // 直接调用模型（支持 tool calling）
        return chatWithTools(callback)
    }
    
    /**
     * 调用模型（支持 tool calling）
     * 循环处理：调用模型 -> 检查 tool_call -> 执行 tool -> 返回结果 -> 再次调用
     */
    private suspend fun chatWithTools(
        callback: suspend (stepType: String, content: String, isComplete: Boolean) -> Unit
    ): String {
        val maxIterations = 50  // 防止无限循环
        var iteration = 0
        // 每轮用户对话只执行一次 search_context（向量检索一次即可，避免模型反复调工具）
        var searchContextCalledThisTurn = false
        
        while (iteration < maxIterations) {
            iteration++
            
            callback("thinking", "🤔 思考中...", false)
            
            // 调用模型
            val response = callModel()
            
            val message = response.choices.firstOrNull()?.message
            if (message == null) {
                callback("error", "❌ 模型返回空响应", true)
                return "抱歉，发生了错误。"
            }
            
            // 检查是否有 tool_calls
            val toolCalls = message.tool_calls
            
            if (toolCalls != null && toolCalls.isNotEmpty()) {
                // 模型想要使用工具
                callback("tool", "🔧 使用工具处理...", false)
                
                // 添加 assistant 消息（包含 tool_calls）到历史
                conversationHistory.add(Message(
                    role = "assistant",
                    content = message.content,
                    tool_calls = toolCalls
                ))
                
                // 执行所有 tool_calls
                for (toolCall in toolCalls) {
                    val toolResult = if (toolCall.function.name == "search_context" && searchContextCalledThisTurn) {
                        "【仅执行一次】已执行过文档搜索。请根据上方已有的搜索结果直接回答用户，不要再次调用本工具。"
                    } else {
                        if (toolCall.function.name == "search_context") searchContextCalledThisTurn = true
                        executeTool(toolCall, callback)
                    }
                    
                    // 添加 tool 结果到历史
                    conversationHistory.add(Message(
                        role = "tool",
                        content = toolResult,
                        tool_call_id = toolCall.id
                    ))
                }
                
                // 继续循环，让模型处理 tool 结果
                continue
            }
            
            // 没有 tool_calls，返回最终回复
            val finalContent = message.content ?: ""
            
            // 添加 assistant 消息到历史
            conversationHistory.add(Message(role = "assistant", content = finalContent))
            
            callback("complete", finalContent, true)
            return finalContent
        }
        
        callback("error", "❌ 超过最大迭代次数", true)
        return "抱歉，处理时间过长。"
    }
    
    /**
     * 调用模型 API
     */
    private suspend fun callModel(): ChatResponse {
        val tools = getAvailableTools()
        
        val request = ChatRequest(
            model = AIConfig.MODEL,
            messages = conversationHistory.toList(),
            tools = if (tools.isNotEmpty()) tools else null,
            temperature = 0.7,
            max_tokens = 4096,
            stream = false
        )
        
        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("${AIConfig.requireApiBaseUrl()}/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofMillis(AIConfig.TIMEOUT))
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(request)))
            .build()
        
        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            logger.error("❌ API 调用失败: ${response.statusCode()} - ${response.body()}")
            throw Exception("API 调用失败: ${response.statusCode()}")
        }
        
        return json.decodeFromString(response.body())
    }
    
    /**
     * 获取可用的工具列表
     * 这里定义模型可以使用的工具
     */
    private fun getAvailableTools(): List<Tool> {
        // 辅助函数：创建 required 数组
        fun requiredArray(vararg items: String): JsonArray {
            return JsonArray(items.map { JsonPrimitive(it) })
        }
        
        return listOf(
            // 上下文搜索工具（搜索已上传的PDF文件和聊天记录）
            Tool(
                function = ToolDefinition(
                    name = "search_context",
                    description = "搜索当前会话中已上传的文件（PDF等）和聊天记录。当用户询问关于已上传文件的内容时，使用此工具搜索一次即可，然后根据返回的检索结果直接回答用户。通常只需调用一次，不要重复调用。",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("query", buildJsonObject {
                                put("type", "string")
                                put("description", "搜索关键词，可以是问题或相关术语")
                            })
                        })
                        put("required", requiredArray("query"))
                    }
                )
            ),
            // 文件搜索工具
            Tool(
                function = ToolDefinition(
                    name = "search_files",
                    description = "搜索本地文件系统中的文件。可以搜索文件名或文件内容。",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("query", buildJsonObject {
                                put("type", "string")
                                put("description", "搜索关键词")
                            })
                            put("path", buildJsonObject {
                                put("type", "string")
                                put("description", "搜索路径（可选，默认为当前目录）")
                            })
                        })
                        put("required", requiredArray("query"))
                    }
                )
            ),
            // 网页搜索工具
            Tool(
                function = ToolDefinition(
                    name = "search_web",
                    description = "在互联网上搜索信息。当需要查找外部信息、最新资讯或实时数据时使用。",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("query", buildJsonObject {
                                put("type", "string")
                                put("description", "搜索关键词")
                            })
                        })
                        put("required", requiredArray("query"))
                    }
                )
            ),
            // 读取文件工具
            Tool(
                function = ToolDefinition(
                    name = "read_file",
                    description = "读取指定文件的内容。",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("path", buildJsonObject {
                                put("type", "string")
                                put("description", "文件路径")
                            })
                        })
                        put("required", requiredArray("path"))
                    }
                )
            ),
            // 执行命令工具
            Tool(
                function = ToolDefinition(
                    name = "execute_command",
                    description = "执行系统命令。谨慎使用，仅用于必要的系统操作。",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("command", buildJsonObject {
                                put("type", "string")
                                put("description", "要执行的命令")
                            })
                        })
                        put("required", requiredArray("command"))
                    }
                )
            )
        )
    }
    
    /**
     * 执行工具调用
     */
    private suspend fun executeTool(
        toolCall: ToolCall,
        callback: suspend (stepType: String, content: String, isComplete: Boolean) -> Unit
    ): String {
        val toolName = toolCall.function.name
        val arguments = toolCall.function.arguments
        
        logger.info("🔧 执行工具: $toolName, 参数: $arguments")
        callback("tool", "🔧 执行: $toolName", false)
        
        return try {
            val args = json.parseToJsonElement(arguments).jsonObject
            
            when (toolName) {
                "search_files" -> {
                    val query = args["query"]?.jsonPrimitive?.content ?: ""
                    val path = args["path"]?.jsonPrimitive?.content ?: "."
                    searchFiles(query, path)
                }
                
                "search_web" -> {
                    val query = args["query"]?.jsonPrimitive?.content ?: ""
                    searchWeb(query)
                }
                
                "read_file" -> {
                    val path = args["path"]?.jsonPrimitive?.content ?: ""
                    readFile(path)
                }
                
                "execute_command" -> {
                    val command = args["command"]?.jsonPrimitive?.content ?: ""
                    executeCommand(command)
                }
                
                "search_context" -> {
                    val query = args["query"]?.jsonPrimitive?.content ?: ""
                    searchContext(query)
                }
                
                else -> "未知工具: $toolName"
            }
        } catch (e: Exception) {
            logger.error("❌ 工具执行失败: ${e.message}")
            "工具执行失败: ${e.message}"
        }
    }
    
    // ==================== 工具实现 ====================
    
    /**
     * 搜索本地文件
     */
    private fun searchFiles(query: String, path: String): String {
        return try {
            val searchDir = java.io.File(path)
            if (!searchDir.exists()) {
                return "目录不存在: $path"
            }
            
            val results = mutableListOf<String>()
            val maxResults = 10
            
            searchDir.walkTopDown()
                .take(1000)  // 限制搜索深度
                .filter { it.isFile }
                .filter { it.name.contains(query, ignoreCase = true) }
                .take(maxResults)
                .forEach { file ->
                    results.add("📄 ${file.path} (${file.length()} bytes)")
                }
            
            if (results.isEmpty()) {
                "未找到匹配的文件"
            } else {
                "找到 ${results.size} 个文件:\n" + results.joinToString("\n")
            }
        } catch (e: Exception) {
            "搜索失败: ${e.message}"
        }
    }
    
    /**
     * 网页搜索 (优先使用 SerpAPI，备选 Brave Search)
     */
    private fun searchWeb(query: String): String {
        // 优先使用 SerpAPI
        if (AIConfig.SERPAPI_KEY.isNotEmpty()) {
            val serpResult = searchWithSerpAPI(query)
            if (serpResult.isNotEmpty()) {
                return serpResult
            }
        }
        
        // 备选：Brave Search API
        if (AIConfig.BRAVE_API_KEY.isNotEmpty()) {
            return searchWithBrave(query)
        }
        
        return "⚠️ 未配置搜索 API Key，请设置环境变量 SERPAPI_KEY 或 BRAVE_API_KEY"
    }
    
    /**
     * 使用 SerpAPI 搜索 (优先)
     */
    private fun searchWithSerpAPI(query: String): String {
        return try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://serpapi.com/search?q=$encodedQuery&api_key=${AIConfig.SERPAPI_KEY}&num=5&hl=zh-CN"
            
            logger.info("🔍 SerpAPI Search: $query")
            
            val serpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
            
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build()
            
            val response = serpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() == 200) {
                parseSerpAPIResponse(response.body(), query)
            } else {
                logger.error("❌ SerpAPI 失败: ${response.statusCode()}")
                ""  // 返回空字符串，让备选搜索引擎处理
            }
        } catch (e: Exception) {
            logger.error("❌ SerpAPI 搜索异常: ${e.message}")
            ""  // 返回空字符串，让备选搜索引擎处理
        }
    }
    
    /**
     * 解析 SerpAPI 响应
     */
    private fun parseSerpAPIResponse(responseBody: String, query: String): String {
        return try {
            val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
            val organicResults = jsonResponse["organic_results"]?.jsonArray
            
            if (organicResults == null || organicResults.isEmpty()) {
                return ""  // 返回空字符串，让备选搜索引擎处理
            }
            
            val results = StringBuilder()
            results.append("🔍 **搜索结果: $query** (via SerpAPI/Google)\n\n")
            
            val maxResults = minOf(5, organicResults.size)
            for (i in 0 until maxResults) {
                val result = organicResults[i].jsonObject
                val title = result["title"]?.jsonPrimitive?.content ?: ""
                val snippet = result["snippet"]?.jsonPrimitive?.content ?: ""
                val link = result["link"]?.jsonPrimitive?.content ?: ""
                
                results.append("**${i + 1}. $title**\n")
                if (snippet.isNotEmpty()) {
                    results.append("   $snippet\n")
                }
                if (link.isNotEmpty()) {
                    results.append("   📎 $link\n")
                }
                results.append("\n")
            }
            
            results.toString()
        } catch (e: Exception) {
            logger.error("❌ 解析 SerpAPI 响应失败: ${e.message}")
            ""
        }
    }
    
    /**
     * 使用 Brave Search API (备选)
     */
    private fun searchWithBrave(query: String): String {
        return try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://api.search.brave.com/res/v1/web/search?q=$encodedQuery&count=5"
            
            logger.info("🔍 Brave Search: $query")
            
            val braveClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
            
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("X-Subscription-Token", AIConfig.BRAVE_API_KEY)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build()
            
            val response = braveClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() == 200) {
                parseBraveSearchResponse(response.body(), query)
            } else {
                logger.error("❌ Brave Search 失败: ${response.statusCode()}")
                "网页搜索失败: HTTP ${response.statusCode()}"
            }
        } catch (e: Exception) {
            logger.error("❌ Brave Search 异常: ${e.message}")
            "网页搜索功能暂不可用: ${e.message}\n\n提示：模型可以使用自身知识库回答问题。"
        }
    }
    
    /**
     * 解析 Brave Search 响应
     */
    private fun parseBraveSearchResponse(responseBody: String, query: String): String {
        return try {
            val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
            val webResults = jsonResponse["web"]?.jsonObject?.get("results")?.jsonArray
            
            if (webResults == null || webResults.isEmpty()) {
                return "🔍 搜索 \"$query\" 未找到相关结果"
            }
            
            val results = StringBuilder()
            results.append("🔍 **搜索结果: $query**\n\n")
            
            val maxResults = minOf(5, webResults.size)
            for (i in 0 until maxResults) {
                val result = webResults[i].jsonObject
                val title = result["title"]?.jsonPrimitive?.content ?: ""
                val description = result["description"]?.jsonPrimitive?.content ?: ""
                val url = result["url"]?.jsonPrimitive?.content ?: ""
                
                results.append("**${i + 1}. $title**\n")
                if (description.isNotEmpty()) {
                    results.append("   $description\n")
                }
                if (url.isNotEmpty()) {
                    results.append("   📎 $url\n")
                }
                results.append("\n")
            }
            
            results.toString()
        } catch (e: Exception) {
            logger.error("❌ 解析 Brave Search 响应失败: ${e.message}")
            "解析搜索结果失败: ${e.message}"
        }
    }
    
    /**
     * 读取文件
     */
    private fun readFile(path: String): String {
        return try {
            val file = java.io.File(path)
            if (!file.exists()) {
                return "文件不存在: $path"
            }
            
            if (file.length() > 10000) {
                // 文件太大，只读取前 10000 字符
                val content = file.readText().take(10000)
                "📄 文件: $path (前 10000 字符)\n\n$content\n\n... (文件共 ${file.length()} 字符)"
            } else {
                "📄 文件: $path\n\n${file.readText()}"
            }
        } catch (e: Exception) {
            "读取文件失败: ${e.message}"
        }
    }
    
    /**
     * 执行命令
     */
    private fun executeCommand(command: String): String {
        // 安全限制：只允许执行特定的安全命令
        val safeCommands = listOf("ls", "pwd", "echo", "date", "whoami", "cat")
        val commandPrefix = command.trim().split(" ").first()
        
        if (commandPrefix !in safeCommands) {
            return "⚠️ 安全限制：只允许执行以下命令: ${safeCommands.joinToString(", ")}"
        }
        
        return try {
            val process = ProcessBuilder(command.split(" "))
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            
            "💻 执行: $command\n\n$output"
        } catch (e: Exception) {
            "命令执行失败: ${e.message}"
        }
    }
    
    /**
     * 搜索已上传的上下文（PDF文件、聊天记录等）
     * 使用 Weaviate 进行语义搜索
     */
    private fun searchContext(query: String): String {
        return try {
            logger.info("🔍 [Weaviate] 搜索上下文: $query (sessionId: $sessionId)")
            
            // 使用 runBlocking 调用 suspend 函数
            val result = runBlocking {
                // 先检查 Weaviate 是否可用
                if (!weaviateClient.isReady()) {
                    return@runBlocking null
                }
                
                // 执行隔离搜索（只搜索当前会话）
                weaviateClient.isolatedSearch(
                    query = query,
                    userId = "default_user",
                    currentSessionId = sessionId,
                    mode = SearchMode.FOREGROUND_ONLY,
                    foregroundLimit = 10,
                    alpha = 0.5f
                )
            }
            
            if (result == null) {
                return "⚠️ Weaviate 搜索服务不可用，请确保 Weaviate 已启动"
            }
            
            var documents = result.foreground.documents
            
            // 如果搜索结果为空，尝试更广泛的搜索策略
            if (documents.isEmpty()) {
                logger.info("🔍 [Weaviate] 初次搜索无结果，尝试更广泛的搜索...")
                
                // 策略1: 如果查询包含中文关键词，尝试英文同义词
                val expandedQueries = expandQuery(query)
                for (expandedQuery in expandedQueries) {
                    val expandedResult = runBlocking {
                        if (!weaviateClient.isReady()) return@runBlocking null
                        weaviateClient.isolatedSearch(
                            query = expandedQuery,
                            userId = "default_user",
                            currentSessionId = sessionId,
                            mode = SearchMode.FOREGROUND_ONLY,
                            foregroundLimit = 10,
                            alpha = 0.5f
                        )
                    }
                    if (expandedResult != null && expandedResult.foreground.documents.isNotEmpty()) {
                        documents = expandedResult.foreground.documents
                        logger.info("🔍 [Weaviate] 扩展搜索 '$expandedQuery' 找到 ${documents.size} 条结果")
                        break
                    }
                }
                
                // 策略2: 如果仍然没有结果，搜索该会话中的所有文件（不限关键词）
                if (documents.isEmpty()) {
                    val allFilesResult = runBlocking {
                        if (!weaviateClient.isReady()) return@runBlocking null
                        weaviateClient.isolatedSearch(
                            query = "file pdf document",  // 通用文件关键词
                            userId = "default_user",
                            currentSessionId = sessionId,
                            mode = SearchMode.FOREGROUND_ONLY,
                            foregroundLimit = 20,
                            alpha = 0.5f
                        )
                    }
                    if (allFilesResult != null && allFilesResult.foreground.documents.isNotEmpty()) {
                        documents = allFilesResult.foreground.documents
                        logger.info("🔍 [Weaviate] 通用文件搜索找到 ${documents.size} 条结果")
                    }
                }
            }
            
            if (documents.isEmpty()) {
                "🔍 搜索 \"$query\" 未找到相关内容。\n\n提示：可能原因：\n1. 当前会话中没有上传相关文件\n2. 文件内容中没有匹配的关键词\n3. 尝试使用不同的关键词搜索\n\n💡 您可以尝试:\n- 使用英文关键词搜索（如果文件是英文的）\n- 直接描述文件内容，如 '总结上传的文件'"
            } else {
                val sb = StringBuilder()
                sb.append("🔍 **搜索结果: $query** (找到 ${documents.size} 条相关内容)\n\n")
                
                // 按文件分组，避免重复显示同一文件的多个块
                val groupedDocs = documents.groupBy { it.title ?: "无标题" }
                var index = 0
                for ((title, docs) in groupedDocs) {
                    index++
                    // 取该文件得分最高的块
                    val bestDoc = docs.maxByOrNull { it.score } ?: docs.first()
                    sb.append("---\n")
                    sb.append("**${index}. [${bestDoc.sourceType}] $title**\n")
                    if (bestDoc.filePath != null) {
                        sb.append("   📁 文件: ${bestDoc.filePath}\n")
                    }
                    sb.append("   📊 相关度: ${(bestDoc.score * 100).toInt()}%\n")
                    sb.append("\n**内容片段:**\n")
                    // 限制内容长度
                    val content = if (bestDoc.content.length > 800) {
                        bestDoc.content.take(800) + "..."
                    } else {
                        bestDoc.content
                    }
                    sb.append("$content\n\n")
                    
                    // 如果同一文件有多个块，显示提示
                    if (docs.size > 1) {
                        sb.append("   _(该文件共有 ${docs.size} 个相关片段)_\n\n")
                    }
                }
                
                sb.toString()
            }
        } catch (e: Exception) {
            logger.error("❌ 搜索上下文失败: ${e.message}", e)
            "搜索失败: ${e.message}"
        }
    }
    
    /**
     * 扩展查询词，添加中英文同义词
     */
    private fun expandQuery(query: String): List<String> {
        val expanded = mutableListOf<String>()
        
        // 中英文同义词映射
        val synonyms = mapOf(
            "文章" to listOf("article", "paper", "document", "pdf"),
            "文件" to listOf("file", "document", "pdf"),
            "内容" to listOf("content", "text", "body"),
            "总结" to listOf("summary", "abstract", "overview"),
            "什么" to listOf("what"),
            "讲" to listOf("about", "discuss", "describe"),
            "介绍" to listOf("introduction", "intro", "overview"),
            "方法" to listOf("method", "approach", "technique"),
            "结果" to listOf("result", "outcome", "finding"),
            "结论" to listOf("conclusion", "summary"),
            "问题" to listOf("problem", "question", "issue"),
            "解决" to listOf("solution", "solve", "resolve")
        )
        
        // 替换中文关键词为英文
        var englishQuery = query
        for ((cn, enList) in synonyms) {
            if (query.contains(cn)) {
                // 用第一个英文同义词替换
                englishQuery = englishQuery.replace(cn, enList.first())
            }
        }
        if (englishQuery != query) {
            expanded.add(englishQuery)
        }
        
        // 添加一些通用的英文搜索词
        if (query.contains("文章") || query.contains("文件") || query.contains("这篇")) {
            expanded.add("pdf document content")
            expanded.add("paper abstract introduction")
        }
        
        return expanded
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
