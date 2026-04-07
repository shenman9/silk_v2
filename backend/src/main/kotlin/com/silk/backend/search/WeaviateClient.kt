package com.silk.backend.search

import com.silk.backend.ai.AIConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * Silk Weaviate 搜索客户端
 * 
 * 支持多用户隔离和 Foreground/Background 搜索模式：
 * 
 * - Foreground: 当前会话内的内容 (高优先级)
 * - Background: 用户参与的其他会话内容 (补充信息)
 * 
 * 使用示例:
 * ```kotlin
 * val client = WeaviateClient()
 * 
 * // 隔离搜索
 * val results = client.isolatedSearch(
 *     query = "身份验证",
 *     userId = "user_123",
 *     currentSessionId = "session_abc",
 *     mode = SearchMode.FOREGROUND_FIRST  // 优先当前会话
 * )
 * ```
 */
class WeaviateClient(
    private val baseUrl: String = AIConfig.requireWeaviateUrl()
) {
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { 
                ignoreUnknownKeys = true 
                encodeDefaults = true
            })
        }
        install(DefaultRequest) {
            val key = AIConfig.WEAVIATE_API_KEY.trim()
            if (key.isNotEmpty()) {
                headers.append(HttpHeaders.Authorization, "Bearer $key")
            }
        }
    }
    
    private val json = Json { ignoreUnknownKeys = true }

    private val logger = LoggerFactory.getLogger(WeaviateClient::class.java)

    /**
     * 检查 Weaviate 是否可用
     */
    suspend fun isReady(): Boolean {
        return try {
            logger.debug("🔍 [Weaviate] 检查连接: {}", baseUrl)
            val response = httpClient.get("$baseUrl/v1/.well-known/ready")
            val isOk = response.status == HttpStatusCode.OK
            logger.debug("🔍 [Weaviate] 连接状态: {}", if (isOk) "✅ 可用" else "❌ 不可用 (${response.status})")
            isOk
        } catch (e: Exception) {
            logger.error("❌ [Weaviate] 连接失败: {}", e.message)
            false
        }
    }
    
    // ==================== 多用户隔离搜索 ====================
    
    /**
     * 隔离搜索 - 核心搜索 API
     * 
     * @param query 搜索查询
     * @param userId 当前用户 ID (用于权限过滤)
     * @param currentSessionId 当前会话 ID (用于 foreground/background 分离)
     * @param mode 搜索模式
     * @param foregroundLimit Foreground 结果数量限制
     * @param backgroundLimit Background 结果数量限制
     * @param alpha 混合搜索参数 (0=关键词, 1=语义)
     */
    suspend fun isolatedSearch(
        query: String,
        userId: String,
        currentSessionId: String,
        mode: SearchMode = SearchMode.FOREGROUND_FIRST,
        foregroundLimit: Int = 10,
        backgroundLimit: Int = 5,
        alpha: Float = 0.5f
    ): IsolatedSearchResults = coroutineScope {
        
        val startTime = System.currentTimeMillis()
        
        when (mode) {
            SearchMode.FOREGROUND_ONLY -> {
                val foreground = foregroundSearch(query, userId, currentSessionId, foregroundLimit, alpha)
                IsolatedSearchResults(
                    foreground = foreground,
                    background = SearchResults(emptyList(), 0, 0, "background"),
                    queryTimeMs = System.currentTimeMillis() - startTime,
                    mode = mode
                )
            }
            
            SearchMode.BACKGROUND_ONLY -> {
                val background = backgroundSearch(query, userId, currentSessionId, backgroundLimit, alpha)
                IsolatedSearchResults(
                    foreground = SearchResults(emptyList(), 0, 0, "foreground"),
                    background = background,
                    queryTimeMs = System.currentTimeMillis() - startTime,
                    mode = mode
                )
            }
            
            SearchMode.FOREGROUND_FIRST -> {
                // 并行执行 foreground 和 background 搜索
                val foregroundDeferred = async { 
                    foregroundSearch(query, userId, currentSessionId, foregroundLimit, alpha) 
                }
                val backgroundDeferred = async { 
                    backgroundSearch(query, userId, currentSessionId, backgroundLimit, alpha) 
                }
                
                IsolatedSearchResults(
                    foreground = foregroundDeferred.await(),
                    background = backgroundDeferred.await(),
                    queryTimeMs = System.currentTimeMillis() - startTime,
                    mode = mode
                )
            }
            
            SearchMode.MERGED -> {
                // 合并搜索，按分数排序
                val foregroundDeferred = async { 
                    foregroundSearch(query, userId, currentSessionId, foregroundLimit * 2, alpha) 
                }
                val backgroundDeferred = async { 
                    backgroundSearch(query, userId, currentSessionId, backgroundLimit * 2, alpha) 
                }
                
                val foreground = foregroundDeferred.await()
                val background = backgroundDeferred.await()
                
                // 给 foreground 结果加权
                val boostedForeground = foreground.documents.map { 
                    it.copy(score = it.score * 1.5f, isForeground = true) 
                }
                val taggedBackground = background.documents.map { 
                    it.copy(isForeground = false) 
                }
                
                // 合并并排序
                val merged = (boostedForeground + taggedBackground)
                    .sortedByDescending { it.score }
                    .take(foregroundLimit + backgroundLimit)
                
                IsolatedSearchResults(
                    foreground = SearchResults(
                        documents = merged.filter { it.isForeground },
                        totalCount = foreground.totalCount,
                        queryTimeMs = foreground.queryTimeMs,
                        searchType = "foreground"
                    ),
                    background = SearchResults(
                        documents = merged.filter { !it.isForeground },
                        totalCount = background.totalCount,
                        queryTimeMs = background.queryTimeMs,
                        searchType = "background"
                    ),
                    merged = merged,
                    queryTimeMs = System.currentTimeMillis() - startTime,
                    mode = mode
                )
            }
        }
    }
    
    /**
     * Foreground 搜索 - 仅当前会话
     * 基于 sessionId 过滤，同一 session 的所有用户都能搜索到该 session 的文件和消息
     * （文件属于 session，不属于个人）
     */
    suspend fun foregroundSearch(
        query: String,
        userId: String,
        sessionId: String,
        limit: Int = 10,
        alpha: Float = 0.5f
    ): SearchResults = withContext(Dispatchers.IO) {
        
        val startTime = System.currentTimeMillis()
        
        // 过滤条件: 当前会话 (简化：不过滤 participants，因为 Weaviate 本地模式不支持数组过滤)
        val whereFilter = """
        {
            path: ["sessionId"],
            operator: Equal,
            valueText: "$sessionId"
        }
        """.trimIndent()
        
        // 执行主要的 BM25 搜索
        val mainResults = executeHybridSearch(query, whereFilter, limit, alpha, "foreground", startTime)
        
        // 额外搜索：从查询中提取英文关键词，搜索文件标题
        // 这解决了中文查询无法匹配英文文件名的问题（如 "介绍HersLaw" 无法找到 "HersLaw_Seminal.pdf"）
        val englishKeywords = extractEnglishKeywords(query)
        if (englishKeywords.isNotEmpty()) {
            val keywordQuery = englishKeywords.joinToString(" ")
            logger.debug("🔍 [Weaviate] 额外搜索英文关键词: {}", keywordQuery)
            
            val titleResults = executeHybridSearch(keywordQuery, whereFilter, limit / 2, alpha, "foreground_title", startTime)
            
            // 合并结果，去重
            val existingIds = mainResults.documents.map { it.id }.toSet()
            val newDocs = titleResults.documents.filter { it.id !in existingIds }
            
            if (newDocs.isNotEmpty()) {
                logger.debug("🔍 [Weaviate] 通过关键词搜索额外找到 {} 个文档", newDocs.size)
                return@withContext SearchResults(
                    documents = (mainResults.documents + newDocs).take(limit),
                    totalCount = mainResults.totalCount + newDocs.size,
                    queryTimeMs = System.currentTimeMillis() - startTime,
                    searchType = "foreground"
                )
            }
        }
        
        mainResults
    }
    
    /**
     * 从查询中提取英文关键词
     * 用于搜索英文文件名（如 HersLaw、PDF 等）
     */
    private fun extractEnglishKeywords(query: String): List<String> {
        // 匹配连续的英文字符（包括下划线）
        val regex = Regex("[a-zA-Z_][a-zA-Z0-9_]*")
        return regex.findAll(query)
            .map { it.value }
            .filter { it.length >= 3 }  // 至少3个字符
            .toList()
    }
    
    /**
     * Background 搜索 - 跨 session 搜索
     * 搜索其他 session 中的相关内容（排除当前 session）
     * 注：文件属于 session，不按 authorId 过滤
     * 
     * 重要：由于 Weaviate BM25 + NotEqual 组合有 bug，
     * 这里先执行不带 session 过滤的搜索，然后在应用层过滤
     */
    suspend fun backgroundSearch(
        query: String,
        userId: String,
        excludeSessionId: String,
        limit: Int = 5,
        alpha: Float = 0.5f
    ): SearchResults = withContext(Dispatchers.IO) {
        
        val startTime = System.currentTimeMillis()
        
        logger.debug("🔍 [Background] 跨 session 搜索: excludeSession={}, limit={}", excludeSessionId, limit)
        
        // 由于 Weaviate BM25 + NotEqual 有 bug，先搜索更多结果，然后应用层过滤
        val allResults = executeHybridSearchNoFilter(query, limit * 3, alpha, "background_raw", startTime)
        
        // 应用层过滤：排除当前 session
        val filteredDocs = allResults.documents.filter { it.sessionId != excludeSessionId }
        
        logger.debug("🔍 [Background] 原始结果: {}, 过滤后: {}", allResults.documents.size, filteredDocs.size)
        
        SearchResults(
            documents = filteredDocs.take(limit),
            totalCount = filteredDocs.size,
            queryTimeMs = System.currentTimeMillis() - startTime,
            searchType = "background"
        )
    }
    
    /**
     * 获取用户可访问的所有会话 ID
     */
    suspend fun getUserSessions(userId: String): List<SessionInfo> = withContext(Dispatchers.IO) {
        val graphqlQuery = """
        {
            Get {
                SilkSession(
                    where: {
                        path: ["participants"],
                        operator: ContainsAny,
                        valueTextArray: ["$userId"]
                    }
                    limit: 100
                ) {
                    sessionId
                    sessionName
                    participants
                    ownerId
                    lastActiveAt
                    isArchived
                    _additional { id }
                }
            }
        }
        """.trimIndent()
        
        try {
            val response = httpClient.post("$baseUrl/v1/graphql") {
                contentType(ContentType.Application.Json)
                setBody(GraphQLRequest(query = graphqlQuery))
            }
            
            val result = json.decodeFromString<JsonObject>(response.bodyAsText())
            val sessions = result["data"]?.jsonObject
                ?.get("Get")?.jsonObject
                ?.get("SilkSession")?.jsonArray
            
            sessions?.mapNotNull { obj ->
                val sessionObj = obj.jsonObject
                SessionInfo(
                    sessionId = sessionObj["sessionId"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    sessionName = sessionObj["sessionName"]?.jsonPrimitive?.content,
                    participants = sessionObj["participants"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    ownerId = sessionObj["ownerId"]?.jsonPrimitive?.content,
                    isArchived = sessionObj["isArchived"]?.jsonPrimitive?.booleanOrNull ?: false
                )
            } ?: emptyList()
        } catch (e: Exception) {
            logger.error("❌ 获取用户会话失败: {}", e.message)
            emptyList()
        }
    }
    
    // ==================== 索引操作 ====================
    
    /**
     * 注册/更新会话
     */
    suspend fun upsertSession(session: SessionInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = httpClient.post("$baseUrl/v1/objects") {
                contentType(ContentType.Application.Json)
                setBody(mapOf(
                    "class" to "SilkSession",
                    "properties" to mapOf(
                        "sessionId" to session.sessionId,
                        "sessionName" to session.sessionName,
                        "participants" to session.participants,
                        "ownerId" to session.ownerId,
                        "isArchived" to session.isArchived
                    )
                ))
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            logger.error("❌ 会话注册失败: {}", e.message)
            false
        }
    }
    
    /**
     * 添加用户到会话
     */
    suspend fun addParticipant(sessionId: String, userId: String): Boolean {
        // 获取现有会话
        val sessions = getUserSessions(userId)
        val session = sessions.find { it.sessionId == sessionId }
        
        if (session != null && userId in session.participants) {
            return true // 已经是参与者
        }
        
        // 更新参与者列表
        val newParticipants = (session?.participants ?: emptyList()) + userId
        return upsertSession(SessionInfo(
            sessionId = sessionId,
            sessionName = session?.sessionName,
            participants = newParticipants.distinct(),
            ownerId = session?.ownerId
        ))
    }
    
    /**
     * 清理文本内容，优化中文搜索
     * - 移除中文字符之间的多余空格（PDF 提取常见问题）
     * - 合并连续空白字符
     * - 移除特殊空白字符
     */
    private fun cleanTextForSearch(text: String): String {
        var result = text
        
        // 1. 移除中文字符之间的空格（如 "金 额" -> "金额"）
        // 匹配：中文字符 + 空格 + 中文字符
        result = result.replace(Regex("([\\u4e00-\\u9fa5])\\s+([\\u4e00-\\u9fa5])"), "$1$2")
        
        // 2. 多次执行，处理连续的中文字符空格（如 "税 率 / 征 收 率"）
        var prev = ""
        while (prev != result) {
            prev = result
            result = result.replace(Regex("([\\u4e00-\\u9fa5])\\s+([\\u4e00-\\u9fa5])"), "$1$2")
        }
        
        // 3. 合并连续的空白字符为单个空格
        result = result.replace(Regex("\\s+"), " ")
        
        // 4. 清理首尾空白
        result = result.trim()
        
        return result
    }
    
    /**
     * 简单的中文分词（基于单字和常见词组）
     * 用于搜索查询的分词，使 BM25 能匹配中文内容
     * 
     * 策略：将连续的中文字符拆分成单字 + 双字词组合
     * 例如："望远镜金额" -> "望 远 镜 金 额 望远 远镜 镜金 金额"
     */
    private fun tokenizeChineseQuery(query: String): String {
        val result = StringBuilder()
        val chinesePattern = Regex("[\\u4e00-\\u9fa5]+")
        
        var lastEnd = 0
        for (match in chinesePattern.findAll(query)) {
            // 添加非中文部分
            if (match.range.first > lastEnd) {
                result.append(query.substring(lastEnd, match.range.first))
            }
            
            val chineseText = match.value
            
            // 1. 添加每个单字（空格分隔）
            val chars = chineseText.toList()
            result.append(chars.joinToString(" "))
            
            // 2. 添加双字词组（提高匹配率）
            if (chars.size >= 2) {
                result.append(" ")
                for (i in 0 until chars.size - 1) {
                    result.append("${chars[i]}${chars[i+1]} ")
                }
            }
            
            lastEnd = match.range.last + 1
        }
        
        // 添加剩余的非中文部分
        if (lastEnd < query.length) {
            result.append(query.substring(lastEnd))
        }
        
        return result.toString().replace(Regex("\\s+"), " ").trim()
    }
    
    /**
     * 索引文档 (带用户隔离)
     */
    suspend fun indexDocument(
        document: IndexDocument,
        participants: List<String>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            logger.debug("📝 [Weaviate] 索引文档: {} (session: {})", document.title, document.sessionId)
            
            // 清理文本内容，优化搜索
            val cleanedContent = cleanTextForSearch(document.content)
            val cleanedTitle = document.title?.let { cleanTextForSearch(it) }
            val cleanedSummary = document.summary?.let { cleanTextForSearch(it) }
            
            // 构建 JSON 字符串（避免 Map 序列化问题）
            val participantsJson = participants.joinToString(",") { "\"$it\"" }
            val tagsJson = document.tags.joinToString(",") { "\"$it\"" }
            
            // 转义 JSON 特殊字符的辅助函数
            fun escapeJson(s: String): String = s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
            
            val jsonBody = buildString {
                append("{")
                append("\"class\":\"SilkContext\",")
                append("\"properties\":{")
                append("\"content\":\"${escapeJson(cleanedContent)}\",")
                cleanedTitle?.let { append("\"title\":\"${escapeJson(it)}\",") }
                cleanedSummary?.let { append("\"summary\":\"${escapeJson(it)}\",") }
                append("\"sourceType\":\"${document.sourceType}\",")
                document.fileType?.let { append("\"fileType\":\"$it\",") }
                append("\"sessionId\":\"${document.sessionId}\",")
                append("\"participants\":[$participantsJson],")
                document.authorId?.let { append("\"authorId\":\"$it\",") }
                document.authorName?.let { append("\"authorName\":\"${escapeJson(it)}\",") }
                document.filePath?.let { append("\"filePath\":\"${escapeJson(it)}\",") }
                document.sourceUrl?.let { append("\"sourceUrl\":\"$it\",") }
                document.timestamp?.let { append("\"timestamp\":\"$it\",") }
                if (document.tags.isNotEmpty()) { append("\"tags\":[$tagsJson],") }
                append("\"chunkIndex\":${document.chunkIndex},")
                append("\"totalChunks\":${document.totalChunks},")
                append("\"importance\":${document.importance}")
                append("}}")
            }
            
            logger.debug("📝 [Weaviate] JSON Body: {}...", jsonBody.take(200))
            
            val response = httpClient.post("$baseUrl/v1/objects") {
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }
            
            val success = response.status.isSuccess()
            if (success) {
                logger.info("✅ [Weaviate] 索引成功: {}", document.title)
            } else {
                val responseBody = response.bodyAsText()
                logger.error("❌ [Weaviate] 索引失败: {} - {}", response.status, responseBody)
            }
            success
        } catch (e: Exception) {
            logger.error("❌ [Weaviate] 索引异常: {}", e.message)
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 批量索引聊天消息
     */
    suspend fun indexChatMessages(
        sessionId: String,
        participants: List<String>,
        messages: List<ChatMessage>
    ): Int = withContext(Dispatchers.IO) {
        var indexed = 0
        
        for (msg in messages) {
            val success = indexDocument(
                document = IndexDocument(
                    content = msg.content,
                    title = "Chat: ${msg.userName}",
                    sourceType = "CHAT",
                    fileType = "MESSAGE",
                    sessionId = sessionId,
                    authorId = msg.userId,
                    authorName = msg.userName,
                    timestamp = msg.timestamp,
                    importance = if (msg.isImportant) 1.0 else 0.5
                ),
                participants = participants
            )
            if (success) indexed++
        }
        
        indexed
    }
    
    // ==================== 辅助方法 ====================
    
    private suspend fun executeHybridSearch(
        query: String,
        whereFilter: String,
        limit: Int,
        alpha: Float,
        searchType: String,
        startTime: Long
    ): SearchResults {
        // 对查询进行中文分词，优化 BM25 匹配
        val tokenizedQuery = tokenizeChineseQuery(query)
        logger.debug("🔍 [Weaviate] 执行搜索: query='{}' -> tokenized='{}', type={}, limit={}", query, tokenizedQuery, searchType, limit)
        
        // 使用 BM25 搜索 + where 过滤
        // 如果查询为空，则只使用 where 过滤
        val graphqlQuery = if (tokenizedQuery.isNotBlank()) {
            """
        {
            Get {
                SilkContext(
                    bm25: {
                        query: "${escapeGraphQL(tokenizedQuery)}"
                    }
                    where: $whereFilter
                    limit: $limit
                ) {
                    content
                    title
                    sourceType
                    fileType
                    sessionId
                    filePath
                    timestamp
                    authorId
                    authorName
                    importance
                    _additional {
                        id
                        score
                    }
                }
            }
        }
        """.trimIndent()
        } else {
            """
        {
            Get {
                SilkContext(
                    where: $whereFilter
                    limit: $limit
                ) {
                    content
                    title
                    sourceType
                    fileType
                    sessionId
                    filePath
                    timestamp
                    authorId
                    authorName
                    importance
                    _additional {
                        id
                    }
                }
            }
        }
        """.trimIndent()
        }
        
        logger.debug("🔍 [Weaviate] GraphQL Query:\n{}", graphqlQuery)
        
        try {
            val response = httpClient.post("$baseUrl/v1/graphql") {
                contentType(ContentType.Application.Json)
                setBody(GraphQLRequest(query = graphqlQuery))
            }
            
            val responseText = response.bodyAsText()
            logger.debug("🔍 [Weaviate] 响应: {}...", responseText.take(500))
            
            val result = json.decodeFromString<GraphQLResponse>(responseText)
            
            // 检查是否有错误
            if (result.errors?.isNotEmpty() == true) {
                logger.error("❌ [Weaviate] GraphQL 错误: {}", result.errors)
            }

            val rawDocuments = result.data?.get?.silkContext ?: emptyList()
            logger.debug("🔍 [Weaviate] 原始结果数: {}", rawDocuments.size)
            
            // 返回搜索结果（使用 BM25 分数）
            val documents = rawDocuments.map { obj ->
                val content = obj.content ?: ""
                
                SearchDocument(
                    id = obj.additional?.id ?: "",
                    content = content,
                    title = obj.title,
                    sourceType = obj.sourceType ?: "UNKNOWN",
                    fileType = obj.fileType,
                    sessionId = obj.sessionId ?: "",
                    filePath = obj.filePath,
                    sourceUrl = obj.sourceUrl,
                    timestamp = obj.timestamp,
                    authorId = obj.authorId,
                    authorName = obj.authorName,
                    chunkIndex = obj.chunkIndex ?: 0,
                    totalChunks = obj.totalChunks ?: 1,
                    tags = obj.tags ?: emptyList(),
                    score = obj.additional?.score ?: 1.0f,  // 使用 BM25 分数
                    importance = obj.importance ?: 0.5f
                )
            }.take(limit)
            
            logger.debug("🔍 [Weaviate] 返回结果数: {}", documents.size)
            
            return SearchResults(
                documents = documents,
                totalCount = documents.size,
                queryTimeMs = System.currentTimeMillis() - startTime,
                searchType = searchType
            )
        } catch (e: Exception) {
            logger.error("❌ [Weaviate] 搜索失败 ({}): {}", searchType, e.message)
            e.printStackTrace()
            return SearchResults(
                documents = emptyList(),
                totalCount = 0,
                queryTimeMs = System.currentTimeMillis() - startTime,
                searchType = searchType,
                error = e.message
            )
        }
    }
    
    /**
     * 使用 BM25 搜索，不带 where 过滤（用于 background 搜索）
     * 因为 Weaviate BM25 + NotEqual 组合有 bug
     */
    private suspend fun executeHybridSearchNoFilter(
        query: String,
        limit: Int,
        alpha: Float,
        searchType: String,
        startTime: Long
    ): SearchResults {
        // 对查询进行中文分词
        val tokenizedQuery = tokenizeChineseQuery(query)
        logger.debug("🔍 [Weaviate] BM25搜索 (无过滤): query='{}' -> tokenized='{}', limit={}", query, tokenizedQuery, limit)
        
        val graphqlQuery = """
        {
            Get {
                SilkContext(
                    bm25: {
                        query: "${escapeGraphQL(tokenizedQuery)}"
                    }
                    limit: $limit
                ) {
                    content
                    title
                    sourceType
                    fileType
                    sessionId
                    filePath
                    timestamp
                    authorId
                    authorName
                    importance
                    _additional {
                        id
                        score
                    }
                }
            }
        }
        """.trimIndent()
        
        try {
            val response = httpClient.post("$baseUrl/v1/graphql") {
                contentType(ContentType.Application.Json)
                setBody(GraphQLRequest(query = graphqlQuery))
            }
            
            val responseText = response.bodyAsText()
            val result = json.decodeFromString<GraphQLResponse>(responseText)
            
            if (result.errors?.isNotEmpty() == true) {
                logger.error("❌ [Weaviate] GraphQL 错误: {}", result.errors)
            }
            
            val documents = result.data?.get?.silkContext?.map { obj ->
                SearchDocument(
                    id = obj.additional?.id ?: "",
                    content = obj.content ?: "",
                    title = obj.title,
                    sourceType = obj.sourceType ?: "UNKNOWN",
                    fileType = obj.fileType,
                    sessionId = obj.sessionId ?: "",
                    filePath = obj.filePath,
                    sourceUrl = obj.sourceUrl,
                    timestamp = obj.timestamp,
                    authorId = obj.authorId,
                    authorName = obj.authorName,
                    chunkIndex = obj.chunkIndex ?: 0,
                    totalChunks = obj.totalChunks ?: 1,
                    tags = obj.tags ?: emptyList(),
                    score = obj.additional?.score ?: 1.0f,
                    importance = obj.importance ?: 0.5f
                )
            } ?: emptyList()
            
            logger.debug("🔍 [Weaviate] BM25 结果数: {}", documents.size)
            
            return SearchResults(
                documents = documents,
                totalCount = documents.size,
                queryTimeMs = System.currentTimeMillis() - startTime,
                searchType = searchType
            )
        } catch (e: Exception) {
            logger.error("❌ [Weaviate] BM25 搜索失败: {}", e.message)
            e.printStackTrace()
            return SearchResults(
                documents = emptyList(),
                totalCount = 0,
                queryTimeMs = System.currentTimeMillis() - startTime,
                searchType = searchType,
                error = e.message
            )
        }
    }
    
    private fun escapeGraphQL(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
    
    fun close() {
        httpClient.close()
    }
}

// ==================== 数据类 ====================

/**
 * 搜索模式
 */
enum class SearchMode {
    FOREGROUND_ONLY,   // 仅当前会话
    BACKGROUND_ONLY,   // 仅其他会话
    FOREGROUND_FIRST,  // 优先当前会话，同时返回其他会话
    MERGED             // 合并排序
}

/**
 * 隔离搜索结果
 */
@Serializable
data class IsolatedSearchResults(
    val foreground: SearchResults,
    val background: SearchResults,
    val merged: List<SearchDocument>? = null,
    val queryTimeMs: Long,
    val mode: SearchMode
)

@Serializable
data class SearchDocument(
    val id: String,
    val content: String,
    val title: String? = null,
    val sourceType: String,
    val fileType: String? = null,
    val sessionId: String,
    val filePath: String? = null,
    val sourceUrl: String? = null,
    val timestamp: String? = null,
    val authorId: String? = null,
    val authorName: String? = null,
    val chunkIndex: Int = 0,
    val totalChunks: Int = 1,
    val tags: List<String> = emptyList(),
    val score: Float = 0f,
    val importance: Float = 0.5f,
    val isForeground: Boolean = true
)

@Serializable
data class SearchResults(
    val documents: List<SearchDocument>,
    val totalCount: Int,
    val queryTimeMs: Long,
    val searchType: String,
    val error: String? = null
)

@Serializable
data class SessionInfo(
    val sessionId: String,
    val sessionName: String? = null,
    val participants: List<String> = emptyList(),
    val ownerId: String? = null,
    val isArchived: Boolean = false
)

@Serializable
data class IndexDocument(
    val content: String,
    val title: String? = null,
    val summary: String? = null,
    val sourceType: String,
    val fileType: String? = null,
    val sessionId: String,
    val authorId: String? = null,
    val authorName: String? = null,
    val filePath: String? = null,
    val sourceUrl: String? = null,
    val timestamp: String? = null,
    val tags: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val importance: Double = 0.5,
    val chunkIndex: Int = 0,
    val totalChunks: Int = 1
)

@Serializable
data class ChatMessage(
    val userId: String,
    val userName: String,
    val content: String,
    val timestamp: String,
    val isImportant: Boolean = false
)

// ===== GraphQL =====

@Serializable
data class GraphQLRequest(val query: String)

@Serializable
data class GraphQLResponse(
    val data: GraphQLData? = null,
    val errors: List<GraphQLError>? = null
)

@Serializable
data class GraphQLData(
    @kotlinx.serialization.SerialName("Get")
    val get: GetResult? = null
)

@Serializable
data class GetResult(
    @kotlinx.serialization.SerialName("SilkContext")
    val silkContext: List<SilkContextObject>? = null
)

@Serializable
data class SilkContextObject(
    val content: String? = null,
    val title: String? = null,
    val sourceType: String? = null,
    val fileType: String? = null,
    val sessionId: String? = null,
    val filePath: String? = null,
    val sourceUrl: String? = null,
    val timestamp: String? = null,
    val authorId: String? = null,
    val authorName: String? = null,
    val chunkIndex: Int? = null,
    val totalChunks: Int? = null,
    val tags: List<String>? = null,
    val importance: Float? = null,
    @kotlinx.serialization.SerialName("_additional")
    val additional: AdditionalInfo? = null
)

@Serializable
data class AdditionalInfo(
    val id: String? = null,
    val score: Float? = null
)

@Serializable
data class GraphQLError(
    val message: String,
    val path: List<String>? = null
)
