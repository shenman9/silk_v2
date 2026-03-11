package com.silk.backend

import com.silk.backend.models.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * 聊天历史管理器 - 负责持久化聊天记录到文件系统
 */
class ChatHistoryManager(
    private val baseDir: String = "chat_history"
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    
    init {
        // 确保基础目录存在
        File(baseDir).mkdirs()
        println("📁 聊天历史目录已创建: $baseDir")
    }
    
    /**
     * 获取会话目录路径
     */
    private fun getSessionDir(sessionName: String): String {
        // 清理会话名称，移除不安全的字符
        val safeName = sessionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "$baseDir/$safeName"
    }
    
    /**
     * 创建新会话
     */
    fun createSession(sessionName: String): SessionData {
        val sessionDir = getSessionDir(sessionName)
        File(sessionDir).mkdirs()
        
        val sessionData = SessionData(
            sessionId = generateSessionId(),
            sessionName = sessionName,
            createdAt = System.currentTimeMillis()
        )
        
        saveSessionData(sessionName, sessionData)
        
        // 创建空的聊天历史
        val chatHistory = ChatHistory(sessionId = sessionData.sessionId)
        saveChatHistory(sessionName, chatHistory)
        
        println("✅ 会话已创建: $sessionName (${sessionData.sessionId})")
        return sessionData
    }
    
    /**
     * 加载会话数据
     */
    fun loadSessionData(sessionName: String): SessionData? {
        val sessionFile = File("${getSessionDir(sessionName)}/session.json")
        return if (sessionFile.exists()) {
            try {
                json.decodeFromString<SessionData>(sessionFile.readText())
            } catch (e: Exception) {
                println("❌ 加载会话数据失败: ${e.message}")
                null
            }
        } else {
            null
        }
    }
    
    /**
     * 保存会话数据
     */
    fun saveSessionData(sessionName: String, sessionData: SessionData) {
        val sessionFile = File("${getSessionDir(sessionName)}/session.json")
        try {
            sessionFile.writeText(json.encodeToString(sessionData))
            println("💾 会话数据已保存: $sessionName")
        } catch (e: Exception) {
            println("❌ 保存会话数据失败: ${e.message}")
        }
    }
    
    /**
     * 加载聊天历史
     */
    fun loadChatHistory(sessionName: String): ChatHistory? {
        val historyFile = File("${getSessionDir(sessionName)}/chat_history.json")
        return if (historyFile.exists()) {
            try {
                json.decodeFromString<ChatHistory>(historyFile.readText())
            } catch (e: Exception) {
                println("❌ 加载聊天历史失败: ${e.message}")
                null
            }
        } else {
            null
        }
    }
    
    /**
     * 保存聊天历史
     */
    fun saveChatHistory(sessionName: String, chatHistory: ChatHistory) {
        val historyFile = File("${getSessionDir(sessionName)}/chat_history.json")
        try {
            historyFile.writeText(json.encodeToString(chatHistory))
            println("💾 聊天历史已保存: $sessionName (${chatHistory.messages.size} 条消息)")
        } catch (e: Exception) {
            println("❌ 保存聊天历史失败: ${e.message}")
        }
    }
    
    /**
     * 添加消息到历史记录
     */
    fun addMessage(
        sessionName: String,
        message: Message
    ) {
        val chatHistory = loadChatHistory(sessionName) ?: ChatHistory(sessionId = sessionName)
        
        val entry = ChatHistoryEntry(
            messageId = message.id,
            senderId = message.userId,
            senderName = message.userName,
            content = message.content,
            timestamp = message.timestamp,
            messageType = message.type.name
        )
        
        chatHistory.messages.add(entry)
        saveChatHistory(sessionName, chatHistory)
    }
    
    /**
     * 更新 session 的 AI 角色提示
     * @Silk 消息中的角色指令会被保存，用于后续 AI 回复
     */
    fun updateRolePrompt(sessionName: String, rolePrompt: String?) {
        val chatHistory = loadChatHistory(sessionName) ?: ChatHistory(sessionId = sessionName)
        chatHistory.rolePrompt = rolePrompt
        saveChatHistory(sessionName, chatHistory)
        println("🎭 角色提示已更新: $sessionName -> ${rolePrompt?.take(50)}...")
    }
    
    /**
     * 获取 session 的 AI 角色提示
     */
    fun getRolePrompt(sessionName: String): String? {
        return loadChatHistory(sessionName)?.rolePrompt
    }
    
    /**
     * 添加成员到会话
     */
    fun addMember(
        sessionName: String,
        userId: String,
        userName: String
    ) {
        var sessionData = loadSessionData(sessionName) ?: createSession(sessionName)
        
        // 检查成员是否已存在
        val existingMember = sessionData.members.find { it.userId == userId }
        if (existingMember != null) {
            // 更新为在线状态
            sessionData.members.remove(existingMember)
            sessionData.members.add(
                existingMember.copy(
                    isOnline = true,
                    leftAt = null
                )
            )
        } else {
            // 添加新成员
            sessionData.members.add(
                SessionMember(
                    userId = userId,
                    userName = userName,
                    joinedAt = System.currentTimeMillis(),
                    isOnline = true
                )
            )
        }
        
        saveSessionData(sessionName, sessionData)
        println("👤 成员已加入: $userName ($userId)")
    }
    
    /**
     * 移除成员（标记为离线）
     */
    fun removeMember(
        sessionName: String,
        userId: String
    ) {
        val sessionData = loadSessionData(sessionName) ?: return
        
        val member = sessionData.members.find { it.userId == userId }
        if (member != null) {
            sessionData.members.remove(member)
            sessionData.members.add(
                member.copy(
                    isOnline = false,
                    leftAt = System.currentTimeMillis()
                )
            )
            saveSessionData(sessionName, sessionData)
            println("👋 成员已离开: ${member.userName} ($userId)")
        }
    }
    
    /**
     * 获取所有会话列表
     */
    fun listSessions(): List<String> {
        val baseFolder = File(baseDir)
        return if (baseFolder.exists() && baseFolder.isDirectory) {
            baseFolder.listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.name }
                ?: emptyList()
        } else {
            emptyList()
        }
    }
    
    /**
     * 生成会话 ID
     */
    private fun generateSessionId(): String {
        return "session_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
}

