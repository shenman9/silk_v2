package com.silk.backend

import io.ktor.server.application.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.time.Duration
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import com.silk.backend.database.UnreadRepository

@Serializable
data class Message(
    val id: String,
    val userId: String,
    val userName: String,
    val content: String,
    val timestamp: Long,
    val type: MessageType = MessageType.TEXT,
    val isTransient: Boolean = false,  // false = 普通消息（持久化），true = 临时消息（不持久化）
    val currentStep: Int? = null,      // 当前执行的步骤编号（用于进度条）
    val totalSteps: Int? = null,       // 总步骤数（用于进度条）
    val isIncremental: Boolean = false, // true = 增量消息（前端需拼接），false = 完整消息（前端直接替换）
    val category: MessageCategory = MessageCategory.NORMAL  // ✅ 消息类别（用于UI显示亮度区分）
)

@Serializable
enum class MessageType {
    TEXT, JOIN, LEAVE, SYSTEM, FILE, RECALL
}

@Serializable
enum class MessageCategory {
    NORMAL,           // 普通聊天消息（正常亮度）
    TODO_LIST,        // 待办事项列表（低亮度）
    STEP_PROCESS,     // 步骤执行过程（低亮度）
    FINAL_REPORT,     // 最终诊断报告（高亮度）
    AGENT_STATUS      // Agent 工作状态（灰色，低亮度）
}

@Serializable
data class User(
    val id: String,
    val name: String
)

class ChatServer(
    private val sessionName: String = "default_room"
) {
    private val connections = ConcurrentHashMap<String, WebSocketSession>()
    private val messageHistory = Collections.synchronizedList(mutableListOf<Message>())
    private val historyManager = ChatHistoryManager()
    private val silkAgent = SilkAgent().apply {
        initializeAgent(sessionName)  // 初始化 Agent 并传递 session name
    }
    // 直接调用模型的 Agent（简化流程：让模型自动使用 tool 能力）
    private val directModelAgent = com.silk.backend.ai.DirectModelAgent(sessionId = sessionName)
    private var messagesSinceAgentResponse = 0
    private var isAgentJoined = false
    
    // 已处理的URL缓存，避免重复下载（从持久化文件恢复）
    private val processedUrls = Collections.synchronizedSet(mutableSetOf<String>())
    private val processedUrlsFile: java.io.File
    
    init {
        // 初始化并从文件恢复已处理的URL列表（使用统一的目录命名逻辑）
        val uploadDir = historyManager.getUploadsDir(sessionName)
        uploadDir.mkdirs()
        processedUrlsFile = java.io.File(uploadDir, "processed_urls.txt")
        
        if (processedUrlsFile.exists()) {
            try {
                processedUrlsFile.readLines().forEach { line ->
                    if (line.isNotBlank()) {
                        processedUrls.add(line.trim().lowercase())
                    }
                }
                println("📋 已从缓存恢复 ${processedUrls.size} 个已处理的URL")
            } catch (e: Exception) {
                println("⚠️ 读取URL缓存失败: ${e.message}")
        }
        
        // ✅ 从持久化存储加载历史消息到内存（用于消息撤回等功能）
        try {
            val chatHistory = historyManager.loadChatHistory(sessionName)
            if (chatHistory != null && chatHistory.messages.isNotEmpty()) {
                chatHistory.messages.forEach { entry ->
                    val msg = Message(
                        id = entry.messageId,
                        userId = entry.senderId,
                        userName = entry.senderName,
                        content = entry.content,
                        timestamp = entry.timestamp,
                        type = try {
                            MessageType.valueOf(entry.messageType)
                        } catch (e: Exception) {
                            MessageType.TEXT
                        }
                    )
                    messageHistory.add(msg)
                }
                println("📜 已从持久化加载 ${messageHistory.size} 条历史消息到内存 (session: $sessionName)")
            }
        } catch (e: Exception) {
            println("⚠️ 加载历史消息到内存失败: ${e.message}")
            }
        }
    }
    
    // 保存已处理的URL到文件
    private fun saveProcessedUrl(url: String) {
        try {
            processedUrlsFile.appendText("$url\n")
        } catch (e: Exception) {
            println("⚠️ 保存URL缓存失败: ${e.message}")
        }
    }
    
    suspend fun join(userId: String, userName: String, session: WebSocketSession) {
        connections[userId] = session
        
        // 如果是第一个真实用户加入，让 Silk AI 也加入（静默模式）
        if (!isAgentJoined && userId != SilkAgent.AGENT_ID) {
            joinSilkAgentSilently()
        }
        
        // 添加成员到会话记录
        historyManager.addMember(sessionName, userId, userName)
        
        // 加载并发送历史消息给新用户
        val chatHistory = historyManager.loadChatHistory(sessionName)
        if (chatHistory != null) {
            chatHistory.messages.takeLast(50).forEach { entry ->
                val msg = Message(
                    id = entry.messageId,
                    userId = entry.senderId,
                    userName = entry.senderName,
                    content = entry.content,
                    timestamp = entry.timestamp,
                    type = MessageType.valueOf(entry.messageType)
                )
                session.send(Frame.Text(Json.encodeToString(msg)))
            }
            println("📜 已发送 ${chatHistory.messages.size.coerceAtMost(50)} 条历史消息给 $userName")
        } else {
            // 如果没有历史记录，发送内存中的消息
            messageHistory.takeLast(50).forEach { msg ->
                session.send(Frame.Text(Json.encodeToString(msg)))
            }
        }
        
        // 不发送加入消息到聊天室（避免产生无意义的历史记录）
        // 用户加入已经通过会话管理记录
        println("👤 用户已加入聊天室: $userName ($userId)")
    }
    
    /**
     * 让 Silk AI Agent 静默加入会话（不发送欢迎消息）
     */
    private suspend fun joinSilkAgentSilently() {
        if (isAgentJoined) return
        
        isAgentJoined = true
        historyManager.addMember(sessionName, SilkAgent.AGENT_ID, SilkAgent.AGENT_NAME)
        
        // 不发送加入消息和欢迎消息（避免无意义的 chat 消息）
        // Silk 只在用户发送消息时才响应
        
        println("🤖 Silk AI Agent 已静默加入会话")
    }
    
    suspend fun leave(userId: String, userName: String) {
        connections.remove(userId)
        
        // 标记成员为离线
        historyManager.removeMember(sessionName, userId)
        
        // 不发送离开消息到聊天室（避免产生无意义的历史记录）
        // 用户离开已经通过会话管理记录
        println("👋 用户已离开聊天室: $userName ($userId)")
    }
    
    suspend fun broadcast(message: Message) {
        // ✅ 添加调试日志
        println("📨 [broadcast] 收到消息: ID=${message.id}, User=${message.userName}, IsTransient=${message.isTransient}, Content=${message.content.take(30)}...")
        
        // ✅ 防止重复处理：检查消息是否已经在历史中
        if (!message.isTransient && messageHistory.any { it.id == message.id }) {
            println("⚠️ [broadcast] 忽略重复消息: ${message.id} from ${message.userName}")
            return
        }
        
        // 只有非临时消息才添加到内存历史和持久化
        if (!message.isTransient) {
            // 添加到内存历史
            messageHistory.add(message)
            
            // 持久化到文件系统
            historyManager.addMessage(sessionName, message)
            println("💾 [broadcast] 消息已保存: ${message.id}")
            
            // 记录新消息用于未读追踪（提取 groupId）
            // 使用服务器时间而非客户端时间，避免时钟不同步导致未读状态错误
            // 传入发送者ID，自动将发送者标记为已读（自己发的消息不应该显示为未读）
            val groupId = sessionName.removePrefix("group_")
            UnreadRepository.recordNewMessage(groupId, System.currentTimeMillis(), message.userId)
            
            // 索引到 Weaviate 搜索系统
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val historyEntry = com.silk.backend.models.ChatHistoryEntry(
                        messageId = message.id,
                        senderId = message.userId,
                        senderName = message.userName,
                        content = message.content,
                        timestamp = message.timestamp,
                        messageType = message.type.name
                    )
                    // 获取会话参与者（从连接列表中提取）
                    val participants = connections.keys.toList().ifEmpty { listOf(message.userId) }
                    
                    val indexed = silkAgent.indexMessageToSearch(historyEntry, participants)
                    if (indexed) {
                        println("🔍 [broadcast] 消息已索引到 Weaviate: ${message.id}")
                    }
                } catch (e: Exception) {
                    println("⚠️ [broadcast] Weaviate 索引失败: ${e.message}")
                }
            }
        } 
        // ✅ 优化：移除临时消息不保存的日志打印，减少日志量
        // else {
        //     println("📝 临时消息不保存: ${message.content.take(50)}...")
        // }
        
        val messageJson = Json.encodeToString(message)
        
        connections.values.forEach { session ->
            try {
                session.send(Frame.Text(messageJson))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        println("📤 [broadcast] 消息已广播到 ${connections.size} 个连接")
        
        // ✅ URL检测和网页下载索引
        if (message.type == MessageType.TEXT && !message.isTransient && message.userId != SilkAgent.AGENT_ID) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    processUrlsInMessage(message)
                } catch (e: Exception) {
                    println("⚠️ URL处理失败: ${e.message}")
                }
            }
        }
        
        // Silk AI 回复逻辑
        // 检查是否是 Silk 专属私聊会话（群组名以 "[Silk]" 开头）
        val isSilkPrivateChat = getGroupDisplayName(sessionName)?.startsWith("[Silk]") == true
        
        if (message.userId != SilkAgent.AGENT_ID && message.type == MessageType.TEXT && !message.isTransient) {
            messagesSinceAgentResponse++
            
            // 在 Silk 私聊中，所有消息都直接触发 AI 回复
            // 在普通群聊中，需要 @silk 才能触发 AI 回复
            val shouldTriggerAI = isSilkPrivateChat || 
                                  message.content.startsWith("@Silk") || 
                                  message.content.startsWith("@silk")
            
            if (shouldTriggerAI) {
                // 提取实际内容（移除 @silk 前缀，如果是私聊则直接使用原消息）
                val silkContent = if (isSilkPrivateChat) {
                    message.content  // Silk 私聊中直接使用原消息
                } else {
                    message.content
                        .removePrefix("@Silk").removePrefix("@silk")
                        .trim()
                }
                
                // 判断是否是角色设置消息
                val isRolePrompt = isRolePromptMessage(silkContent)
                
                if (!isSilkPrivateChat && silkContent.isBlank()) {
                    // 只有 @silk（非私聊），显示帮助信息
                    println("📖 [broadcast] @silk 帮助提示")
                    CoroutineScope(Dispatchers.IO).launch {
                        sendAgentStatus("""
                            🎯 Silk 使用帮助：
                            • @silk [问题] - 向 Silk 提问
                            • @silk 你是... - 设置 Silk 角色
                            • @silk 重置角色 - 恢复默认角色
                        """.trimIndent())
                    }
                } else if (silkContent == "重置角色" || silkContent.lowercase() == "reset") {
                    // 重置角色
                    historyManager.updateRolePrompt(sessionName, null)
                    println("🎭 [broadcast] 角色已重置")
                    CoroutineScope(Dispatchers.IO).launch {
                        sendAgentStatus("🎭 角色已重置为默认")
                    }
                } else if (isRolePrompt) {
                    // 角色设置消息
                    historyManager.updateRolePrompt(sessionName, silkContent)
                    println("🎭 [broadcast] 角色已设置: $silkContent")
                    
                    CoroutineScope(Dispatchers.IO).launch {
                        sendAgentStatus("🎭 角色已设置")
                        // 以新角色回复确认
                        generateIntelligentResponse("请简短地自我介绍（1-2句话）", message.userId)
                    }
                } else {
                    // 普通问题 - 使用搜索 + AI 回复
                    val logPrefix = if (isSilkPrivateChat) "[Silk私聊]" else "[@silk]"
                    println("💬 [broadcast] $logPrefix 问题: ${silkContent.take(50)}...")
                    
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            generateIntelligentResponse(silkContent, message.userId)
                        } catch (e: Exception) {
                            println("❌ 生成AI回答异常: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }
            } else {
                // 普通消息 - 只索引到 Weaviate，不生成 AI 回复
                // 索引已在上面的代码中完成（historyManager.addMessage 和 indexMessageToSearch）
                println("📝 [broadcast] 普通消息已索引，不触发 AI 回复: ${message.content.take(30)}...")
            }
        }
    }
    
    /**
     * 处理消息中的URL - 下载网页并索引
     */
    private suspend fun processUrlsInMessage(message: Message) {
        println("🔗 [URL检测] 开始检测消息: ${message.content.take(50)}...")
        val urls = com.silk.backend.utils.WebPageDownloader.extractUrls(message.content)
        println("🔗 [URL检测] 提取到 ${urls.size} 个URL: $urls")
        
        if (urls.isEmpty()) {
            println("🔗 [URL检测] 没有URL，跳过")
            return
        }
        
        // 过滤掉已经处理过的URL
        val newUrls = urls.filter { url -> 
            val normalized = url.lowercase().trimEnd('/')
            !processedUrls.contains(normalized)
        }
        
        if (newUrls.isEmpty()) {
            println("🔗 检测到 ${urls.size} 个URL，但都已处理过，跳过")
            return
        }
        
        println("🔗 检测到 ${urls.size} 个URL，其中 ${newUrls.size} 个是新的: $newUrls")
        
        // 创建上传目录（使用统一的方法获取目录路径）
        val uploadDir = historyManager.getUploadsDir(sessionName)
        
        for (url in newUrls) {
            val normalizedUrl = url.lowercase().trimEnd('/')
            
            try {
                // 发送状态消息
                broadcastSystemStatus("🌐 正在下载: $url")
                
                // 下载内容（支持网页和PDF）
                val content = com.silk.backend.utils.WebPageDownloader.downloadAndExtract(url)
                
                if (content != null) {
                    // ✅ 只有成功下载后才标记为已处理
                    processedUrls.add(normalizedUrl)
                    saveProcessedUrl(normalizedUrl)
                    
                    // 保存到文件
                    val savedFile = com.silk.backend.utils.WebPageDownloader.saveToFile(content, uploadDir)
                    
                    // 发送状态消息
                    val fileType = if (content.isPdf) "PDF" else "网页"
                    broadcastSystemStatus("📄 已下载$fileType: ${content.title}")
                    
                    // 索引到 Weaviate
                    val participants = connections.keys.toList().ifEmpty { listOf(message.userId) }
                    
                    // 创建一个代表内容的历史条目
                    val webPageEntry = com.silk.backend.models.ChatHistoryEntry(
                        messageId = "webpage_${System.currentTimeMillis()}",
                        senderId = message.userId,
                        senderName = "[$fileType] ${content.title}",
                        content = """
                            来源URL: ${content.url}
                            标题: ${content.title}
                            类型: $fileType
                            
                            ${content.textContent.take(10000)}
                        """.trimIndent(),
                        timestamp = System.currentTimeMillis(),
                        messageType = if (content.isPdf) "PDF" else "WEBPAGE"
                    )
                    
                    val indexed = silkAgent.indexMessageToSearch(webPageEntry, participants)
                    if (indexed) {
                        println("🔍 内容已索引: ${content.title}")
                        broadcastSystemStatus("✅ 已索引$fileType: ${content.title}")
                    }
                } else {
                    broadcastSystemStatus("⚠️ 无法下载: $url")
                }
            } catch (e: Exception) {
                println("❌ 处理URL失败: $url - ${e.message}")
                broadcastSystemStatus("❌ 处理链接失败: $url")
            }
        }
        
        // ✅ 处理完成后，延迟3秒清除状态消息（让用户能看到结果）
        kotlinx.coroutines.delay(3000)
        broadcastSystemStatus("CLEAR_STATUS")
    }
    
    /**
     * 判断是否是角色设置消息
     * 角色设置关键词：你是、扮演、假设你是、作为、角色是、请以...身份
     */
    private fun isRolePromptMessage(content: String): Boolean {
        val roleKeywords = listOf(
            "你是", "你现在是", "扮演", "假设你是", "作为", "角色是", 
            "请以", "假装你是", "模拟", "充当", "担任",
            "you are", "act as", "pretend to be", "role:", "persona:"
        )
        val lowerContent = content.lowercase()
        return roleKeywords.any { keyword -> 
            lowerContent.startsWith(keyword.lowercase()) || 
            lowerContent.contains("角色") ||
            lowerContent.contains("身份")
        }
    }
    
    /**
     * 发送 Agent 状态消息（灰色显示）- 内部使用
     */
    private suspend fun sendAgentStatus(status: String) {
        broadcastSystemStatus(status)
    }
    
    /**
     * 广播系统状态消息（灰色显示）- 公开方法，供其他模块调用
     */
    suspend fun broadcastSystemStatus(status: String) {
        println("📢 [状态广播] $status (连接数: ${connections.size})")
        
        val statusMessage = Message(
            id = generateId(),
            userId = SilkAgent.AGENT_ID,
            userName = "🔄 系统",
            content = status,
            timestamp = System.currentTimeMillis(),
            type = MessageType.SYSTEM,
            isTransient = true,
            category = MessageCategory.AGENT_STATUS
        )
        
        val messageJson = Json.encodeToString(statusMessage)
        connections.values.forEach { session ->
            try {
                session.send(Frame.Text(messageJson))
                println("   ✅ 状态已发送到一个连接")
            } catch (e: Exception) {
                println("   ❌ 状态发送失败: ${e.message}")
            }
        }
    }
    
    /**
     * 生成智能回答 - 简化流程
     * 
     * 直接调用模型，让模型使用其内置的 tool 能力（搜索文件、浏览器等）
     * 不再执行复杂的三层搜索流程
     */
    private suspend fun generateIntelligentResponse(userMessage: String, userId: String = "") {
        val callId = System.currentTimeMillis()
        println("🤖 [Agent-$callId] 开始直接调用模型 (userId=$userId)")
        
        // 发送开始状态
        sendAgentStatus("🤖 正在处理您的问题...")
        
        // 获取 session 的角色提示（通过 @Silk 设置）
        val rolePrompt = historyManager.getRolePrompt(sessionName)
        
        // 构建系统提示
        val systemPrompt = buildString {
            if (rolePrompt != null) {
                appendLine("你的角色设定：$rolePrompt")
                appendLine()
                appendLine("请以上述角色身份回答问题。")
            } else {
                appendLine("你是 Silk，一个智能助手。")
            }
            appendLine()
            appendLine("你可以使用工具来搜索文件、搜索互联网、读取文件等。请根据用户的问题选择合适的工具。")
        }
        
        // 加载聊天历史并设置到 Agent（用于群组统计等功能）
        val chatHistory = historyManager.loadChatHistory(sessionName)
        directModelAgent.setGroupChatHistory(chatHistory?.messages ?: emptyList())
        
        // 使用 DirectModelAgent 直接调用模型
        var fullResponse = ""
        try {
            val response = directModelAgent.processInput(
                userInput = userMessage,
                systemPrompt = systemPrompt
            ) { stepType, content, isComplete ->
                when (stepType) {
                    "thinking" -> {
                        sendAgentStatus(content)
                    }
                    "tool" -> {
                        sendAgentStatus(content)
                    }
                    "streaming_incremental" -> {
                        // ✅ 流式输出：发送增量消息
                        val incrementalMessage = Message(
                            id = "streaming_${System.currentTimeMillis()}",
                            userId = SilkAgent.AGENT_ID,
                            userName = SilkAgent.AGENT_NAME,
                            content = content,
                            timestamp = System.currentTimeMillis(),
                            type = MessageType.TEXT,
                            isTransient = true,   // 临时消息，不持久化
                            isIncremental = true   // 增量消息，前端需要拼接
                        )
                        val messageJson = Json.encodeToString(incrementalMessage)
                        connections.values.forEach { session ->
                            try {
                                session.send(Frame.Text(messageJson))
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                    "complete" -> {
                        fullResponse = content
                    }
                    "error" -> {
                        sendAgentStatus("❌ $content")
                    }
                }
                
                // 流式输出：发送增量消息
                if (stepType == "complete" && isComplete) {
                    // 发送最终消息
                    println("📤 [智能回答-$callId] 准备发送最终消息，内容长度: ${fullResponse.length}")
                    
                    val messageId = generateId()
                    println("📤 [智能回答-$callId] 生成消息ID: $messageId (响应userId=$userId)")
                    
                    val finalMessage = Message(
                        id = messageId,
                        userId = SilkAgent.AGENT_ID,
                        userName = SilkAgent.AGENT_NAME,
                        content = fullResponse,
                        timestamp = System.currentTimeMillis(),
                        type = MessageType.TEXT,
                        isTransient = false,
                        isIncremental = false
                    )
                    
                    // 检查是否已经在历史中（防止重复）
                    if (messageHistory.any { it.id == messageId }) {
                        println("⚠️ [智能回答-$callId] 消息ID已存在，跳过发送: $messageId")
                        return@processInput
                    }
                    
                    messageHistory.add(finalMessage)
                    historyManager.addMessage(sessionName, finalMessage)
                    println("📤 [智能回答-$callId] 已保存到历史，当前历史大小: ${messageHistory.size}")
                    
                    // 发送最终消息
                    val messageJson = Json.encodeToString(finalMessage)
                    println("📤 [智能回答-$callId] 发送最终消息到 ${connections.size} 个连接")
                    connections.values.forEach { session ->
                        try {
                            session.send(Frame.Text(messageJson))
                            println("   ✅ [智能回答-$callId] 已发送到一个连接")
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    println("📤 [智能回答-$callId] 最终消息发送完成 (messageId=$messageId)")
                }
            }
            
            fullResponse = response
            println("🏁 [generateIntelligentResponse-$callId] 函数执行完成，响应长度: ${fullResponse.length}")
            
        } catch (e: Exception) {
            println("❌ [generateIntelligentResponse-$callId] 生成AI回答失败: ${e.message}")
            e.printStackTrace()
            
            // 发送错误消息
            val errorMessage = Message(
                id = generateId(),
                userId = SilkAgent.AGENT_ID,
                userName = SilkAgent.AGENT_NAME,
                content = "抱歉，处理您的问题时发生了错误: ${e.message}",
                timestamp = System.currentTimeMillis(),
                type = MessageType.TEXT,
                isTransient = false,
                isIncremental = false
            )
            
            val messageJson = Json.encodeToString(errorMessage)
            connections.values.forEach { session ->
                try {
                    session.send(Frame.Text(messageJson))
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
        }
    }
    
    /**
     * 执行智能诊断（根据历史决定完整诊断或快速更新）
     */
    private suspend fun executeSmartDiagnosis() {
        // 检查是否有之前的诊断结果
        val hasPreviousDiagnosis = checkPreviousDiagnosis()
        
        if (hasPreviousDiagnosis) {
            println("📋 发现历史诊断，执行快速更新流程")
            // 执行快速诊断更新
            executeQuickDiagnosisUpdate()
        } else {
            println("📋 无历史诊断，执行完整11步诊断")
            // 执行完整诊断
            executeStepwiseAITask()
        }
    }
    
    /**
     * 检查是否有之前的诊断结果
     */
    private fun checkPreviousDiagnosis(): Boolean {
        return try {
            println("🔍 检查历史诊断文件:")
            println("   sessionName: $sessionName")
            
            // 尝试多个可能的路径（因为工作目录可能不同）
            val possiblePaths = listOf(
                "chat_history/$sessionName/last_diagnosis.json",
                "backend/chat_history/$sessionName/last_diagnosis.json",
                "/Users/mac/Documents/Silk/backend/chat_history/$sessionName/last_diagnosis.json"
            )
            
            possiblePaths.forEachIndexed { index, path ->
                val testFile = java.io.File(path)
                println("   路径${index + 1}: ${testFile.absolutePath}")
                println("     存在: ${testFile.exists()}")
                if (testFile.exists()) {
                    println("     大小: ${testFile.length()} bytes")
                }
            }
            
            val file = possiblePaths
                .map { java.io.File(it) }
                .firstOrNull { it.exists() && it.length() > 0 }
            
            if (file != null) {
                println("✅ 找到历史诊断文件: ${file.absolutePath}")
                println("   文件大小: ${file.length()} bytes")
                println("   将执行快速更新")
                true
            } else {
                println("ℹ️ 无历史诊断文件")
                println("   将执行完整11步诊断")
                false
            }
        } catch (e: Exception) {
            println("⚠️ 检查历史诊断失败: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 执行快速诊断更新（基于之前的诊断）
     */
    private suspend fun executeQuickDiagnosisUpdate() {
        val chatHistory = historyManager.loadChatHistory(sessionName)
        val historyEntries = chatHistory?.messages ?: emptyList()
        
        val latestUserName = historyEntries
            .filter { it.senderId != SilkAgent.AGENT_ID }
            .lastOrNull()?.senderName ?: "用户"
        
        val groupDisplayName = getGroupDisplayName(sessionName)
        
        // 定义回调
        val callback: suspend (String, String, Int?, Int?) -> Unit = { stepType, content, currentStep, totalSteps ->
            val agentMessage = Message(
                id = generateId(),
                userId = SilkAgent.AGENT_ID,
                userName = SilkAgent.AGENT_NAME,
                content = content,
                timestamp = System.currentTimeMillis(),
                type = MessageType.TEXT,
                isTransient = stepType != "PDF报告",
                currentStep = currentStep,
                totalSteps = totalSteps,
                isIncremental = stepType == "streaming_incremental"
            )
            
            if (!agentMessage.isTransient) {
                messageHistory.add(agentMessage)
                historyManager.addMessage(sessionName, agentMessage)
            }
            
            val messageJson = Json.encodeToString(agentMessage)
            connections.values.forEach { session ->
                try {
                    session.send(Frame.Text(messageJson))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            kotlinx.coroutines.delay(300)
        }
        
        // 执行快速诊断更新
        val message = historyEntries
            .filter { it.senderId != SilkAgent.AGENT_ID }
            .lastOrNull()?.content ?: ""
        
        silkAgent.executeDoctorDiagnosisUpdate(
            chatHistory = historyEntries,
            doctorMessage = message,
            callback = callback,
            userName = latestUserName,
            groupDisplayName = groupDisplayName
        )
    }
    
    /**
     * 执行多步骤 AI 任务
     * 类似于 MoxiTreat 的 stepwise_diagnosis
     */
    private suspend fun executeStepwiseAITask() {
        // 从持久化的历史中加载聊天记录
        val chatHistory = historyManager.loadChatHistory(sessionName)
        val historyEntries = chatHistory?.messages ?: emptyList()
        
        // 提取最新的用户名（用于 PDF 文件命名）
        val latestUserName = historyEntries
            .filter { it.senderId != SilkAgent.AGENT_ID }
            .lastOrNull()?.senderName ?: "用户"
        
        // 获取群组显示名称（用于PDF标题和文件名）
        val groupDisplayName = getGroupDisplayName(sessionName)
        
        // 获取Host用户ID（用于区分医生和病人）
        val hostId = getGroupHostId(sessionName)
        
        // ✅ 新的消息策略：
        // - TODO list和步骤结果作为独立消息保留（isTransient=false）
        // - streaming过程使用临时消息实时显示（isTransient=true）
        // - 通过category区分显示亮度
        val callback: suspend (String, String, Int?, Int?) -> Unit = { stepType, content, currentStep, totalSteps ->
            
            // ✅ 判断是否为临时消息
            val isTransient = when (stepType) {
                "todo_list" -> false          // TODO列表保留
                "step_complete" -> false      // ✅ 步骤完成结果保留
                "总结报告" -> false            // 总结报告保留
                "PDF报告" -> false             // PDF报告保留
                "streaming_incremental" -> true  // ✅ 流式输出是临时的（实时显示）
                else -> true                  // 其他都是临时的
            }
            
            // ✅ 根据stepType设置消息类别
            val category = when (stepType) {
                "todo_list" -> MessageCategory.TODO_LIST          // TODO列表（低亮度）
                "step_complete" -> MessageCategory.STEP_PROCESS   // 步骤结果（低亮度，可转发）
                "streaming_incremental" -> MessageCategory.STEP_PROCESS  // 流式输出（低亮度）
                "总结报告" -> MessageCategory.FINAL_REPORT        // 最终报告（高亮度）
                "PDF报告" -> MessageCategory.FINAL_REPORT         // PDF报告（高亮度）
                else -> MessageCategory.STEP_PROCESS              // 其他（低亮度）
            }
            
            // ✅ 判断是否为增量消息
            val isIncremental = stepType == "streaming_incremental"
            
            val agentMessage = Message(
                id = generateId(),
                userId = SilkAgent.AGENT_ID,
                userName = SilkAgent.AGENT_NAME,
                content = content,
                timestamp = System.currentTimeMillis(),
                type = MessageType.TEXT,
                isTransient = isTransient,
                currentStep = currentStep,
                totalSteps = totalSteps,
                isIncremental = isIncremental,
                category = category
            )
            
            // 所有非临时消息都保存到历史
            if (!isTransient) {
                messageHistory.add(agentMessage)
                historyManager.addMessage(sessionName, agentMessage)
            }
            
            // 直接发送给所有连接的客户端（临时和普通消息都发送）
            val messageJson = Json.encodeToString(agentMessage)
            connections.values.forEach { session ->
                try {
                    session.send(Frame.Text(messageJson))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            // 短暂延迟，让消息按顺序显示
            kotlinx.coroutines.delay(300)
        }
        
        // 执行多步骤任务（传递hostId以区分医生和病人）
        try {
            silkAgent.executeStepwiseTask(historyEntries, callback, latestUserName, groupDisplayName, hostId)
        } catch (e: Exception) {
            val errorMessage = Message(
                id = generateId(),
                userId = SilkAgent.AGENT_ID,
                userName = SilkAgent.AGENT_NAME,
                content = "❌ AI Agent 执行出错：${e.message}",
                timestamp = System.currentTimeMillis(),
                type = MessageType.SYSTEM
            )
            
            messageHistory.add(errorMessage)
            historyManager.addMessage(sessionName, errorMessage)
            
            val messageJson = Json.encodeToString(errorMessage)
            connections.values.forEach { session ->
                try {
                    session.send(Frame.Text(messageJson))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    fun getOnlineUsers(): List<User> {
        return connections.keys.map { userId ->
            User(id = userId, name = "User_$userId")
        }
    }
    
    /**
     * 检查用户是否是群组的Host（医生角色）
     */
    private fun checkIfUserIsHost(sessionName: String, userId: String): Boolean {
        return if (sessionName.startsWith("group_")) {
            val groupId = sessionName.removePrefix("group_")
            try {
                val group = com.silk.backend.database.GroupRepository.findGroupById(groupId)
                val isHost = group?.hostId == userId
                if (isHost) {
                    println("🩺 确认用户是Host（医生）: $userId")
                }
                isHost
            } catch (e: Exception) {
                println("⚠️ 检查Host角色失败: ${e.message}")
                false
            }
        } else {
            false
        }
    }
    
    /**
     * 执行医生诊断更新（Host的消息）
     */
    private suspend fun executeDoctorDiagnosisUpdate(doctorMessage: String) {
        // 定义回调函数，将AI的响应发送到聊天室
        val callback: suspend (String, String, Int?, Int?) -> Unit = { stepType, content, currentStep, totalSteps ->
            val agentMessage = Message(
                id = generateId(),
                userId = SilkAgent.AGENT_ID,
                userName = SilkAgent.AGENT_NAME,
                content = content,
                timestamp = System.currentTimeMillis(),
                type = MessageType.TEXT,
                isTransient = stepType == "processing",  // 处理中的消息是临时的
                currentStep = currentStep,
                totalSteps = totalSteps
            )
            
            // 非临时消息保存到历史
            if (!agentMessage.isTransient) {
                messageHistory.add(agentMessage)
                historyManager.addMessage(sessionName, agentMessage)
            }
            
            // 发送给所有客户端
            val messageJson = Json.encodeToString(agentMessage)
            connections.values.forEach { session ->
                try {
                    session.send(Frame.Text(messageJson))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            kotlinx.coroutines.delay(300)
        }
        
        // 获取群组显示名称
        val groupDisplayName = getGroupDisplayName(sessionName)
        
        // 加载聊天历史
        val chatHistory = historyManager.loadChatHistory(sessionName)
        val historyEntries = chatHistory?.messages ?: emptyList()
        
        // 提取用户名
        val userName = historyEntries
            .filter { it.senderId != SilkAgent.AGENT_ID }
            .lastOrNull()?.senderName ?: "用户"
        
        // 执行医生诊断更新
        try {
            silkAgent.executeDoctorDiagnosisUpdate(
                chatHistory = historyEntries,
                doctorMessage = doctorMessage,
                callback = callback,
                userName = userName,
                groupDisplayName = groupDisplayName
            )
        } catch (e: Exception) {
            println("❌ 医生诊断更新失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 获取群组的Host用户ID
     */
    private fun getGroupHostId(sessionName: String): String? {
        return if (sessionName.startsWith("group_")) {
            val groupId = sessionName.removePrefix("group_")
            try {
                val group = com.silk.backend.database.GroupRepository.findGroupById(groupId)
                group?.hostId
            } catch (e: Exception) {
                println("⚠️ 获取Host ID失败: ${e.message}")
                null
            }
        } else {
            null
        }
    }
    
    /**
     * 获取群组的显示名称
     * 从sessionName（格式：group_<uuid>）获取实际的群组名称
     */
    private fun getGroupDisplayName(sessionName: String): String? {
        return if (sessionName.startsWith("group_")) {
            // 提取群组ID
            val groupId = sessionName.removePrefix("group_")
            println("📋 正在查询群组名称，groupId: $groupId")
            
            // 从数据库查询群组名称
            try {
                val group = com.silk.backend.database.GroupRepository.findGroupById(groupId)
                if (group != null) {
                    println("📋 找到群组名称: ${group.name}")
                    group.name  // 返回群组的实际名称，例如："liaoheng's Sophie Ankle"
                } else {
                    println("⚠️ 未找到群组：$groupId")
                    null
                }
            } catch (e: Exception) {
                println("⚠️ 查询群组名称失败: ${e.message}")
                e.printStackTrace()
                null
            }
        } else {
            // 不是群组session，返回null（使用sessionName作为标题）
            println("📋 非群组session，使用sessionName: $sessionName")
            null
        }
    }
    
    /**
     * 撤回消息
     * @param messageId 要撤回的消息ID
     * @param userId 发起撤回的用户ID
     * @return 撤回结果：成功/失败，以及被删除的消息ID列表
     */
    suspend fun recallMessage(messageId: String, userId: String): RecallResult {
        println("🔄 [recallMessage] 开始撤回消息: $messageId by user $userId")
        println("🔄 [recallMessage] sessionName: $sessionName")
        
        // 1. 从历史记录中查找消息
        val chatHistory = historyManager.loadChatHistory(sessionName)
        println("🔄 [recallMessage] chatHistory: ${chatHistory != null}, messages count: ${chatHistory?.messages?.size}")
        if (chatHistory != null) {
            println("🔄 [recallMessage] message IDs in history: ${chatHistory.messages.map { it.messageId }}")
        }
        val messageEntry = chatHistory?.messages?.find { it.messageId == messageId }
        
        if (messageEntry == null) {
            println("❌ [recallMessage] 消息不存在: $messageId")
            return RecallResult(false, "消息不存在", emptyList())
        }
        
        // 2. 验证权限：只有消息发送者才能撤回
        if (messageEntry.senderId != userId) {
            println("❌ [recallMessage] 无权撤回此消息: sender=${messageEntry.senderId}, requester=$userId")
            return RecallResult(false, "只能撤回自己发送的消息", emptyList())
        }
        
        val deletedMessageIds = mutableListOf<String>()
        
        // 3. 检查是否是 @silk 消息
        val isSilkMessage = messageEntry.content.startsWith("@Silk") || messageEntry.content.startsWith("@silk")
        
        if (isSilkMessage) {
            println("🔄 [recallMessage] 检测到 @silk 消息，查找 Silk 的回复")
            
            // 4. 查找 Silk 的回复消息（在用户消息之后，最近的 Silk 消息）
            val messageIndex = chatHistory.messages.indexOf(messageEntry)
            val silkReply = chatHistory.messages
                .drop(messageIndex + 1)
                .firstOrNull { it.senderId == SilkAgent.AGENT_ID }
            
            if (silkReply != null) {
                println("🔄 [recallMessage] 找到 Silk 回复: ${silkReply.messageId}")
                
                // 5. 删除用户消息和 Silk 回复
                historyManager.deleteMessages(sessionName, listOf(messageId, silkReply.messageId))
                deletedMessageIds.add(messageId)
                deletedMessageIds.add(silkReply.messageId)
                
                // 6. 从内存历史中移除
                messageHistory.removeIf { it.id == messageId || it.id == silkReply.messageId }
                
                // 7. 广播撤回通知
                broadcastRecallNotification(listOf(messageId, silkReply.messageId))
                
                println("✅ [recallMessage] 已撤回用户消息和 Silk 回复")
            } else {
                println("⚠️ [recallMessage] 未找到 Silk 回复，只撤回用户消息")
                historyManager.deleteMessages(sessionName, listOf(messageId))
                deletedMessageIds.add(messageId)
                messageHistory.removeIf { it.id == messageId }
                broadcastRecallNotification(listOf(messageId))
            }
        } else {
            // 普通消息：直接删除
            println("🔄 [recallMessage] 普通消息，直接撤回")
            historyManager.deleteMessages(sessionName, listOf(messageId))
            deletedMessageIds.add(messageId)
            messageHistory.removeIf { it.id == messageId }
            broadcastRecallNotification(listOf(messageId))
        }
        
        return RecallResult(true, "撤回成功", deletedMessageIds)
    }
    
    /**
     * 广播撤回通知给所有连接的客户端
     */
    private suspend fun broadcastRecallNotification(messageIds: List<String>) {
        val recallMessage = Message(
            id = generateId(),
            userId = "system",
            userName = "系统",
            content = messageIds.joinToString(","),
            timestamp = System.currentTimeMillis(),
            type = MessageType.RECALL,
            isTransient = true
        )
        val notificationJson = Json.encodeToString(recallMessage)
        
        connections.values.forEach { session ->
            try {
                session.send(Frame.Text(notificationJson))
            } catch (e: Exception) {
                println("❌ [broadcastRecallNotification] 发送失败: ${e.message}")
            }
        }
        println("📢 [broadcastRecallNotification] 已广播撤回通知: $messageIds")
    }
    
    private fun generateId(): String {
        return System.currentTimeMillis().toString() + (0..999).random()
    }
}

/**
 * 消息撤回结果
 */
@Serializable
data class RecallResult(
    val success: Boolean,
    val message: String,
    val deletedMessageIds: List<String>
)

val chatServer = ChatServer()

fun Application.configureWebSockets() {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(30)   // 每30秒发送一次ping
        timeout = Duration.ofSeconds(120)     // 2分钟超时，确保AI有足够时间响应
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
}

