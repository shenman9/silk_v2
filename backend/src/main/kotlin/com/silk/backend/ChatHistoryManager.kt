package com.silk.backend

import com.silk.backend.models.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 聊天历史管理器 - 负责持久化聊天记录到文件系统
 * 
 * 安全特性：
 * 1. 原子写入：先写入临时文件，再重命名，避免写入中断导致文件损坏
 * 2. 损坏文件备份：解析失败时自动备份损坏文件，防止数据丢失
 * 3. 不自动覆盖：加载失败时不创建新会话，避免覆盖历史数据
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
     * 对于群组会话，统一使用 group_ 前缀格式
     */
    private fun getSessionDir(sessionName: String): String {
        // 清理会话名称，移除不安全的字符
        val safeName = sessionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        
        // 统一群组会话的目录命名格式
        // 如果 sessionName 是 UUID 格式（不带 group_ 前缀），添加 group_ 前缀
        // 这样确保与 WebSocketConfig.kt 中的 uploads 目录命名一致
        val normalizedSessionName = if (safeName.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) {
            "group_$safeName"
        } else {
            safeName
        }
        
        return "$baseDir/$normalizedSessionName"
    }
    
    /**
     * 获取会话目录路径（不进行标准化，用于查找旧格式目录）
     */
    private fun getSessionDirLegacy(sessionName: String): String {
        val safeName = sessionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "$baseDir/$safeName"
    }

    private fun getSessionFile(sessionName: String): File {
        return File("${getSessionDir(sessionName)}/session.json")
    }

    private fun getHistoryFile(sessionName: String): File {
        return File("${getSessionDir(sessionName)}/chat_history.json")
    }
    
    /**
     * 备份损坏的文件
     * @param file 损坏的文件
     * @param reason 损坏原因
     */
    private fun backupCorruptedFile(file: File, reason: String) {
        if (!file.exists()) return
        
        val timestamp = System.currentTimeMillis()
        val backupFile = File("${file.parent}/${file.nameWithoutExtension}.corrupted_$timestamp.${file.extension}")
        try {
            Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            println("⚠️ 已备份损坏文件: ${file.name} -> ${backupFile.name} (原因: $reason)")
        } catch (e: Exception) {
            println("❌ 备份损坏文件失败: ${e.message}")
        }
    }
    
    /**
     * 原子写入文件
     * 先写入临时文件，成功后重命名，避免写入中断导致文件损坏
     */
    private fun atomicWrite(file: File, content: String) {
        val tempFile = File("${file.path}.tmp")
        try {
            // 写入临时文件
            tempFile.writeText(content)
            // 原子重命名
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            // 清理临时文件
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }
    
    /**
     * 创建新会话
     * 注意：如果会话已存在且有数据，此操作会被阻止（使用 ensureSessionExists 代替）
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
        
        // 仅在历史文件不存在时创建空历史，避免覆盖已有数据
        val historyFile = getHistoryFile(sessionName)
        if (!historyFile.exists()) {
            val chatHistory = ChatHistory(sessionId = sessionData.sessionId)
            saveChatHistory(sessionName, chatHistory)
        } else {
            println("🛡️ 检测到已有历史文件，跳过空历史初始化: $sessionName")
        }
        
        println("✅ 会话已创建: $sessionName (${sessionData.sessionId})")
        return sessionData
    }
    
    /**
     * 确保会话存在，如果不存在则创建
     * 如果会话数据损坏，返回 null 而不是创建新会话（避免覆盖历史）
     */
    fun ensureSessionExists(sessionName: String): SessionData? {
        val existing = loadSessionData(sessionName)
        if (existing != null) {
            return existing
        }
        
        // 检查是否有损坏的文件
        val sessionFile = getSessionFile(sessionName)
        if (sessionFile.exists()) {
            // 文件存在但解析失败，说明文件损坏，不要创建新会话
            println("⚠️ 会话文件损坏，拒绝创建新会话: $sessionName")
            return null
        }

        // session.json 不存在，但 chat_history.json 存在时，修复 session 元数据且绝不覆盖历史
        val historyFile = getHistoryFile(sessionName)
        if (historyFile.exists()) {
            val history = loadChatHistory(sessionName)
            if (history == null) {
                println("⚠️ 检测到历史文件但无法解析，拒绝创建新会话避免覆盖: $sessionName")
                return null
            }

            val recoveredSessionData = SessionData(
                sessionId = history.sessionId.ifBlank { generateSessionId() },
                sessionName = sessionName,
                createdAt = System.currentTimeMillis()
            )
            saveSessionData(sessionName, recoveredSessionData)
            println("🔧 已从历史文件恢复 session 元数据: $sessionName")
            return recoveredSessionData
        }
        
        // 文件不存在，可以安全创建
        return createSession(sessionName)
    }
    
    /**
     * 加载会话数据
     * @return SessionData 或 null（如果文件不存在或损坏）
     */
    fun loadSessionData(sessionName: String): SessionData? {
        val sessionFile = File("${getSessionDir(sessionName)}/session.json")
        return if (sessionFile.exists()) {
            try {
                val content = sessionFile.readText()
                if (content.isBlank()) {
                    println("❌ 会话文件为空: ${sessionFile.path}")
                    backupCorruptedFile(sessionFile, "文件为空")
                    return null
                }
                json.decodeFromString<SessionData>(content)
            } catch (e: Exception) {
                println("❌ 加载会话数据失败: ${e.message}")
                backupCorruptedFile(sessionFile, e.message ?: "JSON解析错误")
                null
            }
        } else {
            null
        }
    }
    
    /**
     * 保存会话数据（原子写入）
     */
    fun saveSessionData(sessionName: String, sessionData: SessionData) {
        val sessionDir = getSessionDir(sessionName)
        File(sessionDir).mkdirs()
        
        val sessionFile = File("$sessionDir/session.json")
        try {
            atomicWrite(sessionFile, json.encodeToString(sessionData))
            println("💾 会话数据已保存: $sessionName")
        } catch (e: Exception) {
            println("❌ 保存会话数据失败: ${e.message}")
        }
    }
    
    /**
     * 加载聊天历史
     * @return ChatHistory 或 null（如果文件不存在或损坏）
     */
    fun loadChatHistory(sessionName: String): ChatHistory? {
        val historyFile = File("${getSessionDir(sessionName)}/chat_history.json")
        return if (historyFile.exists()) {
            try {
                val content = historyFile.readText()
                if (content.isBlank()) {
                    println("❌ 历史文件为空: ${historyFile.path}")
                    backupCorruptedFile(historyFile, "文件为空")
                    return null
                }
                val history = json.decodeFromString<ChatHistory>(content)
                // 确保messages列表存在
                if (history.messages == null) {
                    println("⚠️ 历史文件缺少messages字段，已修复: $sessionName")
                    return ChatHistory(sessionId = history.sessionId, messages = mutableListOf())
                }
                history
            } catch (e: Exception) {
                println("❌ 加载聊天历史失败: ${e.message}")
                backupCorruptedFile(historyFile, e.message ?: "JSON解析错误")
                null
            }
        } else {
            null
        }
    }
    
    /**
     * 保存聊天历史（原子写入）
     */
    fun saveChatHistory(sessionName: String, chatHistory: ChatHistory) {
        val sessionDir = getSessionDir(sessionName)
        File(sessionDir).mkdirs()
        
        val historyFile = File("$sessionDir/chat_history.json")
        try {
            atomicWrite(historyFile, json.encodeToString(chatHistory))
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
        val historyFile = getHistoryFile(sessionName)
        val chatHistory = loadChatHistory(sessionName) ?: run {
            // 如果历史文件存在但加载失败，拒绝写入，避免覆盖潜在可恢复数据
            if (historyFile.exists()) {
                println("⚠️ 历史文件存在但无法解析，跳过写入避免覆盖: $sessionName")
                return
            }
            val sessionId = loadSessionData(sessionName)?.sessionId ?: sessionName
            ChatHistory(sessionId = sessionId)
        }
        
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
        val historyFile = getHistoryFile(sessionName)
        val chatHistory = loadChatHistory(sessionName) ?: run {
            if (historyFile.exists()) {
                println("⚠️ 历史文件存在但无法解析，跳过角色提示写入避免覆盖: $sessionName")
                return
            }
            val sessionId = loadSessionData(sessionName)?.sessionId ?: sessionName
            ChatHistory(sessionId = sessionId)
        }
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
     * 使用 ensureSessionExists 避免在文件损坏时覆盖历史数据
     */
    fun addMember(
        sessionName: String,
        userId: String,
        userName: String
    ) {
        var sessionData = ensureSessionExists(sessionName) ?: run {
            println("⚠️ 无法加载或创建会话，跳过成员添加: $sessionName")
            return
        }
        
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
     * 删除单条消息
     * @param sessionName 会话名称
     * @param messageId 要删除的消息ID
     * @return 是否删除成功
     */
    fun deleteMessage(sessionName: String, messageId: String): Boolean {
        val chatHistory = loadChatHistory(sessionName) ?: return false
        
        val initialSize = chatHistory.messages.size
        chatHistory.messages.removeIf { it.messageId == messageId }
        
        if (chatHistory.messages.size < initialSize) {
            saveChatHistory(sessionName, chatHistory)
            println("🗑️ 消息已删除: $messageId (会话: $sessionName)")
            return true
        }
        
        return false
    }
    
    /**
     * 批量删除消息
     * @param sessionName 会话名称
     * @param messageIds 要删除的消息ID列表
     * @return 删除的消息数量
     */
    fun deleteMessages(sessionName: String, messageIds: List<String>): Int {
        if (messageIds.isEmpty()) return 0
        
        val chatHistory = loadChatHistory(sessionName) ?: return 0
        
        val initialSize = chatHistory.messages.size
        chatHistory.messages.removeIf { it.messageId in messageIds }
        val deletedCount = initialSize - chatHistory.messages.size
        
        if (deletedCount > 0) {
            saveChatHistory(sessionName, chatHistory)
            println("🗑️ 批量删除消息: $deletedCount 条 (会话: $sessionName)")
        }
        
        return deletedCount
    }
    
    /**
     * 查找指定消息后的AI回复消息
     * 用于撤回 @silk 消息时，同时删除AI的回复
     * @param sessionName 会话名称
     * @param userMessageId 用户消息ID
     * @return AI回复消息的ID列表（可能有多条，如步骤消息和最终回复）
     */
    fun findAgentRepliesAfterMessage(sessionName: String, userMessageId: String): List<String> {
        val chatHistory = loadChatHistory(sessionName) ?: return emptyList()
        
        // 找到用户消息的位置
        val userMessageIndex = chatHistory.messages.indexOfFirst { it.messageId == userMessageId }
        if (userMessageIndex == -1) return emptyList()
        
        val userMessage = chatHistory.messages[userMessageIndex]
        val userTimestamp = userMessage.timestamp
        
        // AI Agent ID
        val agentId = "silk_agent"
        
        // 查找用户消息之后、连续的AI回复消息
        val agentReplies = mutableListOf<String>()
        var foundNextUserMessage = false
        
        for (i in (userMessageIndex + 1) until chatHistory.messages.size) {
            val msg = chatHistory.messages[i]
            
            // 如果遇到其他用户的消息，停止查找
            if (msg.senderId != agentId && msg.senderId != userMessage.senderId) {
                break
            }
            
            // 如果是AI的回复，添加到列表
            if (msg.senderId == agentId) {
                // 检查是否是连续的AI回复（时间间隔在5分钟内）
                val prevMsg = if (agentReplies.isEmpty()) userMessage else chatHistory.messages[i - 1]
                if (msg.timestamp - prevMsg.timestamp < 5 * 60 * 1000) {
                    agentReplies.add(msg.messageId)
                } else {
                    break
                }
            }
        }
        
        println("🔍 查找AI回复: 用户消息 $userMessageId -> 找到 ${agentReplies.size} 条AI回复")
        return agentReplies
    }
    
    /**
     * 获取 uploads 目录路径（公开方法，供 WebSocketConfig 等模块使用）
     * @param sessionName 会话名称
     * @return uploads 目录的 File 对象
     */
    fun getUploadsDir(sessionName: String): File {
        val sessionDir = getSessionDir(sessionName)
        val uploadDir = File("$sessionDir/uploads")
        uploadDir.mkdirs()
        return uploadDir
    }
    
    /**
     * 获取标准化后的会话名称（公开方法）
     * 用于调试和日志记录
     */
    fun getNormalizedSessionName(sessionName: String): String {
        val safeName = sessionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return if (safeName.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) {
            "group_$safeName"
        } else {
            safeName
        }
    }
    
    /**
     * 生成会话 ID
     */
    private fun generateSessionId(): String {
        return "session_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
}

