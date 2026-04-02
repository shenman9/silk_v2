package com.silk.backend

import com.silk.backend.ai.AIStepwiseAgent
import com.silk.backend.ai.SearchDrivenAgent
import com.silk.backend.ai.DirectModelAgent
import com.silk.backend.models.ChatHistoryEntry
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Silk AI 代理 - 自动加入每个聊天会话的 AI 角色
 * 
 * 简化版本：直接调用后台模型，让模型自动使用其 tool 能力（搜索文件、浏览器等）
 */
class SilkAgent {
    companion object {
        const val AGENT_ID = "silk_ai_agent"
        const val AGENT_NAME = "🤖 Silk"
        private val logger = LoggerFactory.getLogger(SilkAgent::class.java)
        
        // AI Agent 触发词
        val AGENT_TRIGGERS = listOf(
            "@silk", "@ai", "@agent"
        )
    }
    
    // ✅ 简化：只使用 DirectModelAgent，直接调用模型
    private var directAgent: DirectModelAgent? = null
    private var currentSessionId: String = "default"
    
    // 保留旧 agent 用于兼容（可选）
    private var stepwiseAgent: AIStepwiseAgent? = null
    private var searchAgent: SearchDrivenAgent? = null
    
    fun initializeAgent(sessionName: String) {
        currentSessionId = sessionName
        // ✅ 使用简化的 DirectModelAgent
        directAgent = DirectModelAgent(sessionId = sessionName)
        // 旧 agent 初始化移除，不再需要复杂的搜索流程
    }
    
    private val greetings = listOf(
        "大家好！我是 Silk，您的智能聊天助手 🤖",
        "欢迎来到聊天室！有什么我可以帮助的吗？",
        "你好！我会在这里陪伴大家聊天 😊",
        "Hi！很高兴见到大家！"
    )
    
    private val responses = mapOf(
        "你好" to listOf("你好！很高兴见到你 😊", "Hi！有什么我可以帮忙的吗？"),
        "hello" to listOf("Hello! Nice to meet you!", "Hi there! 👋"),
        "帮助" to listOf(
            "我可以为您提供以下帮助：\n• 欢迎新用户\n• 回答常见问题\n• 活跃聊天氛围",
            "需要什么帮助吗？随时告诉我！"
        ),
        "时间" to listOf("现在是 ${getCurrentTime()}", "当前时间：${getCurrentTime()}"),
        "谢谢" to listOf("不客气！😊", "很高兴能帮到你！", "随时为您服务！"),
        "拜拜" to listOf("再见！期待下次见面！👋", "拜拜！祝你有美好的一天！", "See you later! 👋"),
        "bye" to listOf("Goodbye! Have a great day! 👋", "See you! 👋"),
    )
    
    /**
     * 获取欢迎消息
     */
    fun getWelcomeMessage(): String {
        return greetings.random()
    }
    
    /**
     * 欢迎新用户
     */
    fun welcomeUser(userName: String): String {
        return "欢迎 $userName 加入聊天室！🎉"
    }
    
    /**
     * 根据消息内容生成回复
     */
    fun generateResponse(messageContent: String): String? {
        val content = messageContent.lowercase().trim()
        
        // 如果消息包含 AI 的名字，增加回复概率
        val mentionedAI = content.contains("silk") || 
                         content.contains("机器人") || 
                         content.contains("ai") ||
                         content.contains("助手")
        
        // 检查是否匹配预设的回复
        responses.forEach { (trigger, replies) ->
            if (content.contains(trigger)) {
                return replies.random()
            }
        }
        
        // 如果被提及但没有匹配的回复
        if (mentionedAI) {
            return getDefaultResponse()
        }
        
        // 随机回复（低概率）
        if (Math.random() < 0.1) {  // 10% 概率随机回复
            return getEncouragingMessage()
        }
        
        return null  // 不回复
    }
    
    /**
     * 获取默认回复
     */
    private fun getDefaultResponse(): String {
        val defaults = listOf(
            "我在这里！有什么需要帮助的吗？",
            "你叫我了吗？😊",
            "是的，我是 Silk！",
            "有什么我可以做的？"
        )
        return defaults.random()
    }
    
    /**
     * 获取鼓励性消息
     */
    private fun getEncouragingMessage(): String {
        val messages = listOf(
            "聊得很开心！😊",
            "继续！我在听 👂",
            "有趣的话题！",
            "说得好！👍"
        )
        return messages.random()
    }
    
    /**
     * 获取当前时间
     */
    private fun getCurrentTime(): String {
        val now = LocalTime.now()
        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        return now.format(formatter)
    }
    
    /**
     * 获取会话统计信息
     */
    fun getSessionStats(messageCount: Int, userCount: Int): String {
        return """
            📊 会话统计：
            • 消息数量：$messageCount
            • 在线用户：$userCount
            • 状态：活跃中 ✅
        """.trimIndent()
    }
    
    /**
     * 判断是否应该回复（避免过度活跃）
     */
    fun shouldRespond(messageContent: String, messagesSinceLastResponse: Int): Boolean {
        val content = messageContent.lowercase()
        
        // 如果直接提到 AI，总是回复
        if (content.contains("silk") || 
            content.contains("@silk") ||
            content.contains("机器人")) {
            return true
        }
        
        // 如果是问候语，回复
        if (content.matches(Regex(".*(你好|hello|hi|嗨).*"))) {
            return messagesSinceLastResponse >= 2  // 至少间隔2条消息
        }
        
        // 如果很久没回复，偶尔活跃一下
        if (messagesSinceLastResponse >= 10) {
            return Math.random() < 0.3  // 30% 概率
        }
        
        return false
    }
    
    /**
     * 判断是否触发多步骤 AI Agent
     */
    fun shouldTriggerStepwiseAgent(messageContent: String): Boolean {
        val content = messageContent.lowercase()
        return AGENT_TRIGGERS.any { trigger ->
            content.contains(trigger.lowercase())
        }
    }
    
    /**
     * 执行多步骤 AI 分析任务
     * 类似于 MoxiTreat 的 stepwise_diagnosis
     */
    suspend fun executeStepwiseTask(
        chatHistory: List<ChatHistoryEntry>,
        callback: suspend (stepType: String, message: String, currentStep: Int?, totalSteps: Int?) -> Unit,
        userName: String = "用户",
        groupDisplayName: String? = null,
        hostId: String? = null
    ): AIStepwiseAgent.DiagnosisResult {
        return (stepwiseAgent ?: throw IllegalStateException("Agent not initialized"))
            .executeStepwiseDiagnosis(chatHistory, callback, userName, groupDisplayName, hostId)
    }
    
    /**
     * 执行医生诊断更新（Host角色的额外诊断）
     */
    suspend fun executeDoctorDiagnosisUpdate(
        chatHistory: List<ChatHistoryEntry>,
        doctorMessage: String,
        callback: suspend (stepType: String, message: String, currentStep: Int?, totalSteps: Int?) -> Unit,
        userName: String = "用户",
        groupDisplayName: String? = null
    ) {
        (stepwiseAgent ?: throw IllegalStateException("Agent not initialized"))
            .processDoctorDiagnosisUpdate(chatHistory, doctorMessage, callback, userName, groupDisplayName)
    }
    
    /**
     * 生成streaming响应（快速对话回答）
     * @param prompt AI prompt
     * @param callback 回调函数 (content, isComplete)
     */
    suspend fun generateStreamingResponse(
        prompt: String,
        callback: suspend (content: String, isComplete: Boolean) -> Unit
    ) {
        (stepwiseAgent ?: throw IllegalStateException("Agent not initialized"))
            .generateQuickResponse(prompt, callback)
    }
    
    /**
     * 使用搜索驱动的智能响应（调用 Weaviate 搜索）
     * @param userInput 用户输入
     * @param recentHistory 最近聊天历史
     * @param userId 发送消息的用户 ID
     * @param callback 状态回调 (stepType, content, isComplete)
     * @return AI 回复
     */
    suspend fun generateSearchDrivenResponse(
        userInput: String,
        recentHistory: List<ChatHistoryEntry> = emptyList(),
        userId: String = "user",
        callback: suspend (stepType: String, content: String, isComplete: Boolean) -> Unit
    ): String {
        val agent = searchAgent ?: SearchDrivenAgent(sessionId = currentSessionId, userId = userId).also { searchAgent = it }
        
        val result = agent.processInput(
            userInput = userInput,
            recentHistory = recentHistory,
            callback = callback
        )
        
        return result.reply
    }
    
    /**
     * 索引消息到 Weaviate
     */
    suspend fun indexMessageToSearch(message: ChatHistoryEntry, participants: List<String>): Boolean {
        return try {
            searchAgent?.indexMessage(message, participants) ?: false
        } catch (e: Exception) {
            logger.error("❌ 索引消息失败: {}", e.message)
            false
        }
    }
    
    /**
     * 获取触发提示
     */
    fun getAgentTriggerHelp(): String {
        return """
💡 如何触发 Silk AI Agent 多步骤任务：
发送以下任意关键词：
${AGENT_TRIGGERS.joinToString(", ")}

示例：
- "@analyze" - 分析聊天历史
- "@分析" - 执行完整的分析任务
- "@执行任务" - 启动 AI Agent

AI Agent 将会：
1. 📋 显示执行计划（To Do List）
2. 🔄 逐步执行每个任务
3. ✅ 实时报告每步结果
4. 🎉 完成后发送总结
""".trimIndent()
    }
}

