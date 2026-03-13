package com.silk.shared

import com.silk.shared.models.Message
import com.silk.shared.models.MessageType
import com.silk.shared.models.MessageCategory
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.datetime.Clock

// 日志回调
typealias LogCallback = (String) -> Unit

// 平台特定的 WebSocket 实现
expect class PlatformWebSocket(
    serverUrl: String,
    onMessage: (String) -> Unit,
    onConnected: () -> Unit,
    onDisconnected: () -> Unit,
    onError: (String) -> Unit,
    onLog: LogCallback?
) {
    fun connect(userId: String, userName: String, groupId: String)
    fun send(message: String)
    fun disconnect()
    val isConnected: Boolean
}

class ChatClient(
    private val serverUrl: String = "ws://localhost:8006",
    private val onLog: LogCallback? = null
) {
    private fun log(message: String) {
        println(message)
        onLog?.invoke(message)
    }
    
    init {
        log("✅ ChatClient 已创建")
        log("   服务器 URL: $serverUrl")
    }
    
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()
    
    // 单独的临时消息状态（只保留最新的一条）- 用于 AI 增量回复
    private val _transientMessage = MutableStateFlow<Message?>(null)
    val transientMessage: StateFlow<Message?> = _transientMessage.asStateFlow()
    
    // 系统状态消息列表（用于显示搜索、索引等状态）
    private val _statusMessages = MutableStateFlow<List<Message>>(emptyList())
    val statusMessages: StateFlow<List<Message>> = _statusMessages.asStateFlow()
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private var webSocket: PlatformWebSocket? = null
    private var currentUserId: String = ""
    private var currentUserName: String = ""
    
    suspend fun connect(userId: String, userName: String, groupId: String = "default_room") {
        log("🚀 [ChatClient] connect() 开始执行")
        currentUserId = userId
        currentUserName = userName
        
        _connectionState.value = ConnectionState.CONNECTING
        
        webSocket = PlatformWebSocket(
            serverUrl = serverUrl,
            onMessage = { text ->
                handleMessage(text)
            },
            onConnected = {
                log("✅ [ChatClient] WebSocket 连接成功")
                _connectionState.value = ConnectionState.CONNECTED
            },
            onDisconnected = {
                log("🔌 [ChatClient] WebSocket 已断开")
                _connectionState.value = ConnectionState.DISCONNECTED
            },
            onError = { error ->
                log("❌ [ChatClient] WebSocket 错误: $error")
                _connectionState.value = ConnectionState.DISCONNECTED
            },
            onLog = onLog
        )
        
        webSocket?.connect(userId, userName, groupId)
    }
    
    private fun handleMessage(text: String) {
        log("📨 [ChatClient] 收到消息: ${text.take(100)}...")
        try {
            val message = Json.decodeFromString<Message>(text)
            log("✅ [ChatClient] 解析成功: ${message.type}, 用户: ${message.userName}, category: ${message.category}")
            
            when {
                // 撤回消息：从列表中移除指定消息
                message.type == MessageType.RECALL -> {
                    log("🗑️ [ChatClient] 收到撤回消息: ${message.content}")
                    // content 包含要撤回的消息ID，可能是多个ID（用逗号分隔）
                    val messageIdsToRemove = message.content.split(",").map { it.trim() }
                    _messages.value = _messages.value.filter { msg -> 
                        msg.id !in messageIdsToRemove 
                    }
                    log("🗑️ [ChatClient] 已移除 ${messageIdsToRemove.size} 条消息")
                }
                // Agent 状态消息：添加到状态消息列表（灰色显示）
                message.category == MessageCategory.AGENT_STATUS -> {
                    // 特殊处理：如果内容以 "CLEAR_STATUS" 开头，清空状态列表
                    if (message.content.startsWith("CLEAR_STATUS")) {
                        log("🧹 [ChatClient] 清除状态消息")
                        _statusMessages.value = emptyList()
                    } else {
                        log("🔄 [ChatClient] Agent 状态消息: ${message.content}")
                        // 添加到状态列表，保留最近10条
                        _statusMessages.value = (_statusMessages.value + message).takeLast(10)
                    }
                }
                // 增量临时消息：拼接到已有内容尾部
                message.isTransient && message.isIncremental -> {
                    log("📝 [ChatClient] 增量临时消息")
                    val existing = _transientMessage.value
                    if (existing != null &&
                        existing.userId == message.userId &&
                        existing.type == message.type) {
                        _transientMessage.value = existing.copy(
                            content = existing.content + message.content,
                            timestamp = message.timestamp,
                            currentStep = message.currentStep,
                            totalSteps = message.totalSteps
                        )
                    } else {
                        _transientMessage.value = message
                    }
                }
                // 完整临时消息：直接替换
                message.isTransient -> {
                    log("📝 [ChatClient] 完整临时消息")
                    _transientMessage.value = message
                }
                // 普通消息：添加到消息列表（如果不存在）
                else -> {
                    // ✅ 防止重复：检查消息是否已存在（可能是自己发送的消息）
                    val exists = _messages.value.any { it.id == message.id }
                    if (!exists) {
                        log("💬 [ChatClient] 普通消息，添加到列表")
                        _messages.value = _messages.value + message
                    } else {
                        log("⚠️ [ChatClient] 消息已存在，跳过: ${message.id}")
                    }
                    _transientMessage.value = null
                    // 收到最终消息后清空状态消息
                    _statusMessages.value = emptyList()
                }
            }
        } catch (e: Exception) {
            log("❌ [ChatClient] 解析消息失败: ${e.message}")
        }
    }
    
    suspend fun sendMessage(userId: String, userName: String, content: String) {
        val message = Message(
            id = generateId(),
            userId = userId,
            userName = userName,
            content = content,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            type = MessageType.TEXT
        )
        
        // ✅ 立即在本地显示消息（乐观更新）
        _messages.value = _messages.value + message
        log("📝 [ChatClient] 消息已添加到本地列表")
        
        try {
            val jsonMessage = Json.encodeToString(message)
            log("📤 [ChatClient] 发送消息: ${content.take(50)}...")
            webSocket?.send(jsonMessage)
            log("✅ [ChatClient] 消息已发送到服务器")
        } catch (e: Exception) {
            log("❌ [ChatClient] 发送消息失败: ${e.message}")
            // 发送失败时可以考虑移除消息或标记为失败，这里暂时保留显示
        }
    }
    
    suspend fun disconnect() {
        try {
            webSocket?.disconnect()
            webSocket = null
            _connectionState.value = ConnectionState.DISCONNECTED
            log("✅ [ChatClient] 已断开连接")
        } catch (e: Exception) {
            log("⚠️ [ChatClient] 断开连接: ${e.message}")
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }
    
    fun clearMessages() {
        log("🗑️ [ChatClient] 清空所有消息")
        _messages.value = emptyList()
        _transientMessage.value = null
    }
    
    fun clearTransientOnly() {
        log("🗑️ [ChatClient] 只清空临时消息")
        _transientMessage.value = null
    }
    
    private fun generateId(): String {
        return "${Clock.System.now().toEpochMilliseconds()}_${(0..9999).random()}"
    }
}

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED
}
