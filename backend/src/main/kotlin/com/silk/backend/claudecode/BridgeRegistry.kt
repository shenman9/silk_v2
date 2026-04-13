package com.silk.backend.claudecode

import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridge 连接管理器。
 * 维护已连接的 Bridge WebSocket sessions，按 userId 索引。
 */
object BridgeRegistry {

    private val logger = LoggerFactory.getLogger(BridgeRegistry::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    data class BridgeConnection(
        val session: WebSocketSession,
        @field:Volatile var defaultDir: String = "",
    )

    // userId → BridgeConnection
    private val bridges = ConcurrentHashMap<String, BridgeConnection>()

    /**
     * 注册 bridge 连接。驱逐同用户旧连接。
     */
    suspend fun register(userId: String, session: WebSocketSession, defaultDir: String) {
        val old = bridges.put(userId, BridgeConnection(session, defaultDir))
        if (old != null && old.session != session) {
            logger.info("[Bridge] 驱逐用户 {} 的旧 bridge 连接", userId)
            try {
                old.session.close(CloseReason(CloseReason.Codes.NORMAL, "replaced by new bridge"))
            } catch (_: Exception) {}
        }
        logger.info("[Bridge] 用户 {} 的 bridge 已注册, defaultDir={}", userId, defaultDir)
    }

    /**
     * 注销 bridge 连接。
     * 若有运行中任务，通知 ClaudeCodeManager 处理断线。
     */
    fun unregister(userId: String) {
        val removed = bridges.remove(userId)
        if (removed != null) {
            logger.info("[Bridge] 用户 {} 的 bridge 已注销", userId)
            ClaudeCodeManager.handleBridgeDisconnect(userId)
        }
    }

    /**
     * 发送 JSON 命令到 bridge。
     * @return true 如果发送成功，false 如果 bridge 未连接或发送失败
     */
    suspend fun sendToBridge(userId: String, message: BridgeRequest): Boolean {
        val conn = bridges[userId] ?: return false
        return try {
            val jsonStr = json.encodeToString(message)
            conn.session.send(Frame.Text(jsonStr))
            true
        } catch (e: Exception) {
            logger.error("[Bridge] 发送消息到用户 {} 的 bridge 失败: {}", userId, e.message)
            false
        }
    }

    /**
     * 查询 bridge 是否在线
     */
    fun isConnected(userId: String): Boolean = bridges.containsKey(userId)

    /**
     * 获取连接信息
     */
    fun getConnection(userId: String): BridgeConnection? = bridges[userId]

    /**
     * 更新 bridge 的 defaultDir
     */
    fun updateDefaultDir(userId: String, dir: String) {
        bridges[userId]?.defaultDir = dir
    }
}

/**
 * Silk → Bridge 的命令消息
 */
@Serializable
data class BridgeRequest(
    val type: String,
    val requestId: String = "",
    val prompt: String = "",
    val sessionId: String = "",
    val workingDir: String = "",
    val resume: Boolean = false,
    val path: String = "",
    val sessionIdPrefix: String = "",
)
