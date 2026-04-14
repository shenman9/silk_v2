package com.silk.backend.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * 工具权限控制系统
 * 
 * 用于管理 Silk AI 可使用的工具权限，防止模型执行危险操作
 * 
 * 权限级别：
 * - ALLOWED: 允许执行，无限制
 * - SANDBOXED: 在沙箱/限制环境下执行（如路径限制）
 * - DISABLED: 禁用，不允许执行
 */
object ToolPolicyManager {
    private val logger = LoggerFactory.getLogger(ToolPolicyManager::class.java)
    
    /**
     * 工具权限级别
     */
    enum class ToolPermission {
        ALLOWED,        // 允许执行
        SANDBOXED,      // 在沙箱/限制环境下执行
        DISABLED        // 禁用
    }
    
    /**
     * 工具策略配置
     */
    @Serializable
    data class ToolPolicy(
        val name: String,
        val permission: ToolPermission,
        val allowedPaths: List<String> = emptyList(),  // 对于文件操作，限制可访问的路径
        val deniedPaths: List<String> = emptyList(),   // 明确禁止的路径
        val safeCommands: List<String> = emptyList(),  // 对于命令执行，允许的安全命令
        val description: String
    )
    
    /**
     * 配置文件结构
     */
    @Serializable
    data class PolicyConfig(
        val policies: Map<String, ToolPolicyConfig>
    )
    
    @Serializable
    data class ToolPolicyConfig(
        val permission: String,
        val allowedPaths: List<String> = emptyList(),
        val deniedPaths: List<String> = emptyList(),
        val safeCommands: List<String> = emptyList()
    )
    
    // 默认策略配置
    private val defaultPolicies = mapOf(
        "search_context" to ToolPolicy(
            name = "search_context",
            permission = ToolPermission.ALLOWED,
            description = "搜索当前会话的已上传文件和聊天记录"
        ),
        "search_web" to ToolPolicy(
            name = "search_web",
            permission = ToolPermission.ALLOWED,
            description = "互联网搜索"
        ),
        "get_group_stats" to ToolPolicy(
            name = "get_group_stats",
            permission = ToolPermission.ALLOWED,
            description = "获取群组统计信息"
        ),
        "search_files" to ToolPolicy(
            name = "search_files",
            permission = ToolPermission.SANDBOXED,
            allowedPaths = listOf("chat_history/", "uploads/", "backend/chat_history/"),
            // 不在基础目录层级上禁止整个 "/home"（否则会把允许目录也一起拒绝）
            deniedPaths = listOf("/etc/", "/proc/", "/sys/"),
            description = "搜索文件（仅限用户上传目录）"
        ),
        "read_file" to ToolPolicy(
            name = "read_file",
            permission = ToolPermission.SANDBOXED,
            allowedPaths = listOf("chat_history/", "uploads/", "backend/chat_history/"),
            deniedPaths = listOf("/etc/", "/proc/", "/sys/", ".env", ".git"),
            description = "读取文件（仅限用户上传的文件）"
        ),
        "execute_command" to ToolPolicy(
            name = "execute_command",
            permission = ToolPermission.DISABLED,
            safeCommands = listOf("ls", "pwd", "echo", "date", "whoami"),
            description = "执行系统命令（默认禁用，仅允许安全命令）"
        )
    )
    
    // 当前使用的策略
    private var policies: Map<String, ToolPolicy> = defaultPolicies.toMutableMap()
    
    // 审计日志
    private val auditLog = mutableListOf<AuditEntry>()
    
    @Serializable
    data class AuditEntry(
        val timestamp: Long,
        val toolName: String,
        val permission: ToolPermission,
        val result: String,  // "ALLOWED", "DENIED", "SANDBOXED"
        val details: String,
        val sessionId: String? = null,
        val userId: String? = null
    )
    
    init {
        // 尝试从配置文件加载
        loadConfigFromFile()
    }
    
    /**
     * 从配置文件加载策略
     */
    private fun loadConfigFromFile() {
        val configPath = System.getenv("TOOL_POLICY_CONFIG") ?: "tool_policy.json"
        val configFile = File(configPath)
        
        if (configFile.exists()) {
            try {
                val json = Json { ignoreUnknownKeys = true }
                val config = json.decodeFromString<PolicyConfig>(configFile.readText())
                
                for ((toolName, policyConfig) in config.policies) {
                    val permission = try {
                        ToolPermission.valueOf(policyConfig.permission.uppercase())
                    } catch (e: Exception) {
                        ToolPermission.DISABLED
                    }
                    
                    policies = policies.toMutableMap().apply {
                        put(toolName, ToolPolicy(
                            name = toolName,
                            permission = permission,
                            allowedPaths = policyConfig.allowedPaths,
                            deniedPaths = policyConfig.deniedPaths,
                            safeCommands = policyConfig.safeCommands,
                            description = defaultPolicies[toolName]?.description ?: "自定义工具"
                        ))
                    }
                }
                
                logger.info("✅ 已从配置文件加载工具策略: ${config.policies.size} 个工具")
            } catch (e: Exception) {
                logger.warn("⚠️ 加载配置文件失败，使用默认策略: ${e.message}")
            }
        } else {
            logger.info("📋 未找到配置文件，使用默认策略")
        }
    }
    
    /**
     * 获取工具策略
     */
    fun getPolicy(toolName: String): ToolPolicy {
        return policies[toolName] ?: ToolPolicy(
            name = toolName,
            permission = ToolPermission.DISABLED,
            description = "未知工具（默认禁用）"
        )
    }
    
    /**
     * 检查工具是否允许执行
     */
    fun isAllowed(toolName: String): Boolean {
        val policy = getPolicy(toolName)
        return policy.permission != ToolPermission.DISABLED
    }
    
    /**
     * 验证文件路径是否允许访问
     * 
     * @return Pair(是否允许, 错误信息)
     */
    fun validateFilePath(path: String, policy: ToolPolicy): Pair<Boolean, String> {
        if (policy.permission == ToolPermission.ALLOWED) {
            // 无限制模式，只检查禁止路径
            for (denied in policy.deniedPaths) {
                if (pathContains(path, denied)) {
                    return Pair(false, "⛔ 路径在禁止访问列表中: $path")
                }
            }
            return Pair(true, "")
        }
        
        if (policy.permission == ToolPermission.SANDBOXED) {
            // 沙箱模式，需要同时检查禁止和允许列表
            
            // 先检查禁止列表
            for (denied in policy.deniedPaths) {
                if (pathContains(path, denied)) {
                    return Pair(false, "⛔ 路径在禁止访问列表中: $path")
                }
            }
            
            // 再检查允许列表
            if (policy.allowedPaths.isNotEmpty()) {
                var isAllowed = false
                for (allowed in policy.allowedPaths) {
                    if (pathContains(path, allowed)) {
                        isAllowed = true
                        break
                    }
                }
                if (!isAllowed) {
                    return Pair(false, "⛔ 路径不在允许访问的目录中。允许的目录: ${policy.allowedPaths}")
                }
            }
            
            return Pair(true, "")
        }
        
        // DISABLED 或其他情况
        return Pair(false, "⛔ 工具已被禁用")
    }
    
    /**
     * 检查路径是否包含指定模式
     */
    private fun pathContains(path: String, pattern: String): Boolean {
        val normalizedPath = try {
            File(path).canonicalPath
        } catch (e: Exception) {
            path
        }
        
        val normalizedPattern = try {
            File(pattern).canonicalPath
        } catch (e: Exception) {
            pattern
        }
        
        return normalizedPath.contains(normalizedPattern, ignoreCase = true) ||
               normalizedPath.startsWith(normalizedPattern, ignoreCase = true)
    }
    
    /**
     * 验证命令是否允许执行
     */
    fun validateCommand(command: String, policy: ToolPolicy): Pair<Boolean, String> {
        if (policy.permission == ToolPermission.DISABLED) {
            return Pair(false, "⛔ 命令执行功能已禁用")
        }
        
        // 提取命令前缀
        val commandPrefix = command.trim().split(" ").firstOrNull() ?: ""
        
        // 检查是否在安全命令列表中
        if (commandPrefix in policy.safeCommands) {
            return Pair(true, "")
        }
        
        // 如果是 ALLOWED 模式且有安全命令列表，只允许列表中的命令
        if (policy.permission == ToolPermission.ALLOWED && policy.safeCommands.isNotEmpty()) {
            if (commandPrefix !in policy.safeCommands) {
                return Pair(false, "⛔ 只允许执行安全命令: ${policy.safeCommands}")
            }
        }
        
        // SANDBOXED 模式，只允许安全命令
        if (policy.permission == ToolPermission.SANDBOXED) {
            if (commandPrefix !in policy.safeCommands) {
                return Pair(false, "⛔ 沙箱模式下只允许执行: ${policy.safeCommands}")
            }
        }
        
        return Pair(true, "")
    }
    
    /**
     * 记录审计日志
     */
    fun logAudit(
        toolName: String,
        permission: ToolPermission,
        result: String,
        details: String,
        sessionId: String? = null,
        userId: String? = null
    ) {
        val entry = AuditEntry(
            timestamp = System.currentTimeMillis(),
            toolName = toolName,
            permission = permission,
            result = result,
            details = details,
            sessionId = sessionId,
            userId = userId
        )
        auditLog.add(entry)
        
        // 限制日志大小
        if (auditLog.size > 1000) {
            auditLog.removeAt(0)
        }
        
        // 打印日志
        when (result) {
            "DENIED" -> logger.warn("🚫 [审计] 工具 '$toolName' 被拒绝: $details")
            "ALLOWED" -> logger.info("✅ [审计] 工具 '$toolName' 允许执行: $details")
            "SANDBOXED" -> logger.info("🔒 [审计] 工具 '$toolName' 沙箱执行: $details")
        }
    }
    
    /**
     * 获取审计日志
     */
    fun getAuditLog(limit: Int = 100): List<AuditEntry> {
        return auditLog.takeLast(limit)
    }

    internal fun resetForTest() {
        policies = defaultPolicies.toMutableMap()
        auditLog.clear()
    }
    
    /**
     * 获取所有策略（用于调试/展示）
     */
    fun getAllPolicies(): Map<String, ToolPolicy> {
        return policies.toMap()
    }
    
    /**
     * 动态更新策略
     */
    fun updatePolicy(toolName: String, policy: ToolPolicy) {
        policies = policies.toMutableMap().apply { put(toolName, policy) }
        logger.info("📝 已更新工具策略: $toolName -> ${policy.permission}")
    }
    
    /**
     * 生成工具权限帮助信息
     */
    fun getPermissionHelp(): String {
        val sb = StringBuilder()
        sb.append("📋 **工具权限状态**\n\n")
        
        for ((name, policy) in policies) {
            val icon = when (policy.permission) {
                ToolPermission.ALLOWED -> "✅"
                ToolPermission.SANDBOXED -> "🔒"
                ToolPermission.DISABLED -> "⛔"
            }
            sb.append("$icon **$name**: ${policy.description}\n")
            sb.append("   权限: ${policy.permission}\n")
            if (policy.allowedPaths.isNotEmpty()) {
                sb.append("   允许路径: ${policy.allowedPaths.joinToString(", ")}\n")
            }
            if (policy.deniedPaths.isNotEmpty()) {
                sb.append("   禁止路径: ${policy.deniedPaths.joinToString(", ")}\n")
            }
            sb.append("\n")
        }
        
        return sb.toString()
    }
}
