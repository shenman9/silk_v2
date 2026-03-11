package com.silk.backend.models

import kotlinx.serialization.Serializable

/**
 * 会话数据 - 保存在 session.json
 */
@Serializable
data class SessionData(
    val sessionId: String,
    val sessionName: String,
    val createdAt: Long,
    val members: MutableList<SessionMember> = mutableListOf()
)

/**
 * 会话成员信息
 */
@Serializable
data class SessionMember(
    val userId: String,
    val userName: String,
    val joinedAt: Long,
    val leftAt: Long? = null,
    val isOnline: Boolean = true
)

/**
 * 聊天历史条目 - 保存在 chat_history.json
 */
@Serializable
data class ChatHistoryEntry(
    val messageId: String,
    val senderId: String,
    val senderName: String,
    val content: String,
    val timestamp: Long,
    val messageType: String // TEXT, JOIN, LEAVE, SYSTEM
)

/**
 * 完整的聊天历史记录
 */
@Serializable
data class ChatHistory(
    val sessionId: String,
    val messages: MutableList<ChatHistoryEntry> = mutableListOf(),
    // @Silk 设置的角色提示，作为 AI 回复的 system prompt suffix
    var rolePrompt: String? = null
)

