package com.silk.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: String,
    val userId: String,
    val userName: String,
    val content: String,
    val timestamp: Long,
    val type: MessageType = MessageType.TEXT,
    val isTransient: Boolean = false,  // false = 普通消息，true = 临时消息
    val currentStep: Int? = null,      // 当前执行的步骤编号（1-11）
    val totalSteps: Int? = null,       // 总步骤数（11）
    val isIncremental: Boolean = false, // true = 增量消息（需拼接），false = 完整消息（直接替换）
    val category: MessageCategory = MessageCategory.NORMAL  // ✅ 消息类别（用于UI显示亮度）
)

@Serializable
enum class MessageType {
    TEXT, JOIN, LEAVE, SYSTEM
}

@Serializable
enum class MessageCategory {
    NORMAL,           // 普通聊天消息（正常亮度）
    TODO_LIST,        // 待办事项列表（低亮度）
    STEP_PROCESS,     // 步骤执行过程（低亮度，可转发）
    FINAL_REPORT,     // 最终诊断报告（高亮度）
    AGENT_STATUS      // Agent 工作状态（灰色，低亮度）
}

@Serializable
data class User(
    val id: String,
    val name: String
)

