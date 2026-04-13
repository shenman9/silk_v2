// backend/src/main/kotlin/com/silk/backend/claudecode/ClaudeCodeManager.kt
package com.silk.backend.claudecode

import com.silk.backend.Message
import com.silk.backend.MessageCategory
import com.silk.backend.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Claude Code 模式管理器。
 * 管理 per-user-per-group 的 CC 状态，路由命令和普通消息。
 * 所有 CC 执行委托给远程 Bridge Agent。
 */
object ClaudeCodeManager {

    private val logger = LoggerFactory.getLogger(ClaudeCodeManager::class.java)

    const val CC_AGENT_ID = "silk_ai_agent"
    const val CC_AGENT_NAME = "🤖 Claude Code"
    private const val MAX_QUEUE_SIZE = 10

    private val states = ConcurrentHashMap<String, UserCCState>()

    /** requestId → 回调上下文，用于 bridge 响应路由 */
    data class RequestContext(
        val userId: String,
        val groupId: String,
        val broadcastFn: suspend (Message) -> Unit,
        val startTime: Long = System.currentTimeMillis(),
    )

    private val activeRequests = ConcurrentHashMap<String, RequestContext>()

    /** 用户 CC 状态 */
    class UserCCState(
        @Volatile var active: Boolean = false,
        @Volatile var sessionId: String = UUID.randomUUID().toString(),
        @Volatile var sessionStarted: Boolean = false,
        @Volatile var running: Boolean = false,
        @Volatile var workingDir: String = System.getProperty("user.dir") ?: "/",
        @Volatile var cancelled: Boolean = false,
        val messageQueue: java.util.concurrent.ConcurrentLinkedDeque<QueuedMessage> = java.util.concurrent.ConcurrentLinkedDeque(),
    )

    data class QueuedMessage(val text: String, val userId: String, val userName: String)

    private fun key(userId: String, groupId: String) = "${userId}_${groupId}"

    private fun getOrCreateState(userId: String, groupId: String): UserCCState {
        return states.getOrPut(key(userId, groupId)) { UserCCState() }
    }

    /**
     * 被 ChatServer.broadcast() 调用的入口。
     * 返回 true 表示消息已被 CC 处理，ChatServer 不应再走 Silk AI 逻辑。
     */
    suspend fun handleIfActive(
        userId: String,
        groupId: String,
        text: String,
        userName: String,
        broadcastFn: suspend (Message) -> Unit,
    ): Boolean {
        val trimmed = text.trim()

        // "/cc" 任何时候都拦截
        if (trimmed.lowercase() == "/cc") {
            activate(userId, groupId, broadcastFn)
            return true
        }

        // 非 CC 模式 → 不拦截
        val state = states[key(userId, groupId)] ?: return false
        if (!state.active) return false

        // CC 模式下的消息路由
        routeMessage(userId, groupId, userName, trimmed, state, broadcastFn)
        return true
    }

    private suspend fun activate(userId: String, groupId: String, broadcastFn: suspend (Message) -> Unit) {
        val state = getOrCreateState(userId, groupId)
        state.active = true
        logger.info("[CC] 用户激活 CC 模式: userId={}, groupId={}, sessionId={}", userId, groupId, state.sessionId.take(8))
        broadcastFn(systemMessage(buildString {
            appendLine("🤖 Claude Code 已激活")
            appendLine("会话: ${state.sessionId.take(8)}... | 目录: ${state.workingDir}")
            append("发送消息开始编程，/help 查看命令，/exit 退出")
        }))
    }

    private suspend fun routeMessage(
        userId: String, groupId: String, userName: String,
        text: String, state: UserCCState,
        broadcastFn: suspend (Message) -> Unit,
    ) {
        when {
            text.lowercase() == "/exit" -> handleExit(userId, groupId, state, broadcastFn)
            text.lowercase() == "/new" -> handleNew(userId, groupId, state, broadcastFn)
            text.lowercase() == "/cancel" -> handleCancel(userId, state, broadcastFn)
            text.lowercase() == "/status" -> handleStatus(state, broadcastFn)
            text.lowercase() == "/help" -> handleHelp(broadcastFn)
            text.lowercase() == "/session" -> handleSessionList(userId, groupId, broadcastFn)
            text.lowercase().startsWith("/session ") -> handleSessionResume(userId, groupId, text.substring(9).trim(), state, broadcastFn)
            text.lowercase() == "/cd" -> handleCd(userId, groupId, null, state, broadcastFn)
            text.lowercase().startsWith("/cd ") -> handleCd(userId, groupId, text.substring(4).trim(), state, broadcastFn)
            text.lowercase() == "/queue" -> handleQueueView(state, broadcastFn)
            text.lowercase() == "/queue clear" -> handleQueueClear(state, broadcastFn)
            text.lowercase() == "/compact" -> executePrompt(userId, groupId, userName, "/compact", state, broadcastFn)
            else -> {
                if (state.running) {
                    enqueue(state, text, userId, userName, broadcastFn)
                } else {
                    executePrompt(userId, groupId, userName, text, state, broadcastFn)
                }
            }
        }
    }

    // ========== 命令处理 ==========

    private suspend fun handleExit(userId: String, groupId: String, state: UserCCState, broadcastFn: suspend (Message) -> Unit) {
        if (state.running) {
            val activeRequestId = activeRequests.entries.find { it.value.userId == userId }?.key
            if (activeRequestId != null) {
                BridgeRegistry.sendToBridge(userId, BridgeRequest(type = "cancel", requestId = activeRequestId))
            }
        }
        state.active = false
        state.running = false
        state.messageQueue.clear()
        logger.info("[CC] 用户退出 CC 模式: userId={}", userId)
        broadcastFn(systemMessage("已退出 Claude Code 模式"))
    }

    private suspend fun handleNew(userId: String, groupId: String, state: UserCCState, broadcastFn: suspend (Message) -> Unit) {
        if (state.running) {
            broadcastFn(systemMessage("任务运行中，请先 /cancel 再 /new"))
            return
        }
        if (!BridgeRegistry.isConnected(userId)) {
            broadcastFn(systemMessage("Bridge 未连接。"))
            return
        }
        val requestId = UUID.randomUUID().toString()
        activeRequests[requestId] = RequestContext(userId, groupId, broadcastFn)
        BridgeRegistry.sendToBridge(userId, BridgeRequest(type = "new_session", requestId = requestId))
    }

    private suspend fun handleCancel(userId: String, state: UserCCState, broadcastFn: suspend (Message) -> Unit) {
        if (!state.running) {
            broadcastFn(systemMessage("当前没有运行中的任务"))
            return
        }
        val dropped = state.messageQueue.size
        state.messageQueue.clear()
        state.cancelled = true
        val activeRequestId = activeRequests.entries.find { it.value.userId == userId }?.key
        if (activeRequestId != null) {
            BridgeRegistry.sendToBridge(userId, BridgeRequest(type = "cancel", requestId = activeRequestId))
        }
        if (!state.sessionStarted) state.sessionStarted = true
        val msg = buildString {
            append("已发送取消请求")
            if (dropped > 0) append("（队列中 $dropped 条指令已清空）")
        }
        broadcastFn(systemMessage(msg))
    }

    private suspend fun handleStatus(state: UserCCState, broadcastFn: suspend (Message) -> Unit) {
        broadcastFn(systemMessage(buildString {
            appendLine("📊 Claude Code 状态")
            appendLine("会话: ${state.sessionId.take(8)}...")
            appendLine("目录: ${state.workingDir}")
            appendLine("状态: ${if (state.running) "运行中" else "空闲"}")
            if (state.messageQueue.isNotEmpty()) {
                append("队列: ${state.messageQueue.size} 条")
            }
        }))
    }

    private suspend fun handleHelp(broadcastFn: suspend (Message) -> Unit) {
        broadcastFn(systemMessage(buildString {
            appendLine("📖 Claude Code 命令")
            appendLine("/exit — 退出 CC 模式")
            appendLine("/new — 新建会话")
            appendLine("/cancel — 取消当前任务")
            appendLine("/cd <path> — 切换工作目录")
            appendLine("/session — 查看历史会话")
            appendLine("/session <id> — 恢复会话")
            appendLine("/status — 查看当前状态")
            appendLine("/queue — 查看排队消息")
            appendLine("/queue clear — 清空队列")
            appendLine("/compact — 压缩上下文")
            append("/help — 显示此帮助")
        }))
    }

    private suspend fun handleSessionList(userId: String, groupId: String, broadcastFn: suspend (Message) -> Unit) {
        if (!BridgeRegistry.isConnected(userId)) {
            broadcastFn(systemMessage("Bridge 未连接，无法查看历史会话。请先配置并启动 Bridge。"))
            return
        }
        val requestId = UUID.randomUUID().toString()
        activeRequests[requestId] = RequestContext(userId, groupId, broadcastFn)
        BridgeRegistry.sendToBridge(userId, BridgeRequest(type = "list_sessions", requestId = requestId))
    }

    private suspend fun handleSessionResume(
        userId: String, groupId: String, idPrefix: String,
        state: UserCCState, broadcastFn: suspend (Message) -> Unit,
    ) {
        if (state.running) {
            broadcastFn(systemMessage("任务运行中，无法切换会话"))
            return
        }
        if (!BridgeRegistry.isConnected(userId)) {
            broadcastFn(systemMessage("Bridge 未连接，无法恢复会话。"))
            return
        }
        val requestId = UUID.randomUUID().toString()
        activeRequests[requestId] = RequestContext(userId, groupId, broadcastFn)
        BridgeRegistry.sendToBridge(userId, BridgeRequest(type = "resume_session", requestId = requestId, sessionIdPrefix = idPrefix))
    }

    private suspend fun handleCd(
        userId: String, groupId: String, path: String?,
        state: UserCCState, broadcastFn: suspend (Message) -> Unit,
    ) {
        if (state.running) {
            broadcastFn(systemMessage("任务运行中，请先 /cancel 再 /cd"))
            return
        }
        if (!BridgeRegistry.isConnected(userId)) {
            broadcastFn(systemMessage("Bridge 未连接，无法切换目录。"))
            return
        }
        val requestId = UUID.randomUUID().toString()
        activeRequests[requestId] = RequestContext(userId, groupId, broadcastFn)
        val targetPath = path ?: ""
        BridgeRegistry.sendToBridge(userId, BridgeRequest(type = "cd", requestId = requestId, path = targetPath))
    }

    private suspend fun handleQueueView(state: UserCCState, broadcastFn: suspend (Message) -> Unit) {
        if (state.messageQueue.isEmpty()) {
            broadcastFn(systemMessage("队列为空"))
            return
        }
        val text = buildString {
            appendLine("排队中的指令（共 ${state.messageQueue.size} 条）")
            for ((i, item) in state.messageQueue.toList().withIndex()) {
                val preview = if (item.text.length > 30) item.text.take(30) + "…" else item.text
                appendLine("${i + 1}. $preview")
            }
            append("/queue clear 清空全部")
        }
        broadcastFn(systemMessage(text))
    }

    private suspend fun handleQueueClear(state: UserCCState, broadcastFn: suspend (Message) -> Unit) {
        val count = state.messageQueue.size
        state.messageQueue.clear()
        broadcastFn(systemMessage("已清空队列（$count 条）"))
    }

    // ========== 执行 ==========

    private suspend fun enqueue(
        state: UserCCState, text: String, userId: String, userName: String,
        broadcastFn: suspend (Message) -> Unit,
    ) {
        if (state.messageQueue.size >= MAX_QUEUE_SIZE) {
            broadcastFn(systemMessage("队列已满（最多 $MAX_QUEUE_SIZE 条），请等待或 /cancel"))
            return
        }
        state.messageQueue.addLast(QueuedMessage(text, userId, userName))
        broadcastFn(statusMessage("指令已加入队列（第 ${state.messageQueue.size} 条）"))
    }

    private suspend fun executePrompt(
        userId: String, groupId: String, userName: String,
        prompt: String, state: UserCCState,
        broadcastFn: suspend (Message) -> Unit,
    ) {
        if (!BridgeRegistry.isConnected(userId)) {
            broadcastFn(systemMessage("Bridge 未连接，无法执行。请先在设置中生成 Token，然后启动 Bridge Agent。"))
            return
        }

        state.running = true
        state.cancelled = false

        val requestId = UUID.randomUUID().toString()
        activeRequests[requestId] = RequestContext(userId, groupId, broadcastFn)

        val isCompact = prompt == "/compact"
        val request = BridgeRequest(
            type = if (isCompact) "compact" else "execute",
            requestId = requestId,
            prompt = prompt,
            sessionId = state.sessionId,
            workingDir = state.workingDir,
            resume = if (isCompact) true else state.sessionStarted,
        )

        logger.info("[CC] 发送到 Bridge: type={}, prompt={}, sessionId={}, resume={}", request.type, prompt.take(30), state.sessionId.take(8), state.sessionStarted)
        val sent = BridgeRegistry.sendToBridge(userId, request)
        if (!sent) {
            state.running = false
            activeRequests.remove(requestId)
            broadcastFn(systemMessage("发送命令到 Bridge 失败"))
        }
    }

    private fun finishAndProcessQueue(
        userId: String, groupId: String,
        state: UserCCState,
        broadcastFn: suspend (Message) -> Unit,
    ) {
        if (state.messageQueue.isNotEmpty() && !state.cancelled) {
            val next = state.messageQueue.removeFirst()
            state.cancelled = false
            CoroutineScope(Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
                logger.error("[CC] 队列自动执行异常: {}", throwable.message, throwable)
                state.running = false
            }).launch {
                executePrompt(next.userId, groupId, next.userName, next.text, state, broadcastFn)
            }
        } else {
            state.running = false
        }
    }

    // ========== Bridge 消息处理 ==========

    /**
     * 处理来自 bridge 的消息。由 Routing.kt 的 /cc-bridge 端点调用。
     */
    suspend fun handleBridgeMessage(userId: String, jsonStr: String) {
        val data = try {
            Json.parseToJsonElement(jsonStr).jsonObject
        } catch (e: Exception) {
            logger.warn("[CC] Bridge 消息解析失败: {}", e.message)
            return
        }

        val type = data["type"]?.jsonPrimitive?.contentOrNull ?: return
        val requestId = data["requestId"]?.jsonPrimitive?.contentOrNull

        // hello message
        if (type == "hello") {
            val dir = data["defaultDir"]?.jsonPrimitive?.contentOrNull ?: ""
            BridgeRegistry.updateDefaultDir(userId, dir)
            logger.info("[CC] Bridge hello: userId={}, defaultDir={}", userId, dir)
            return
        }

        if (type == "pong") return

        val ctx = if (requestId != null) activeRequests[requestId] else null
        if (ctx == null && requestId != null) {
            logger.debug("[CC] Bridge 消息的 requestId 无匹配上下文: {}", requestId)
            return
        }

        val broadcastFn = ctx?.broadcastFn ?: return
        val stateKey = "${ctx.userId}_${ctx.groupId}"
        val state = states[stateKey] ?: return

        when (type) {
            "stream_text" -> {
                val text = data["text"]?.jsonPrimitive?.contentOrNull ?: ""
                broadcastFn(streamingMessage(text))
            }
            "tool_log" -> {
                val log = data["log"]?.jsonPrimitive?.contentOrNull ?: ""
                val stableId = data["stableId"]?.jsonPrimitive?.contentOrNull
                broadcastFn(statusMessage(log, stableId))
            }
            "status_update" -> {
                val status = data["status"]?.jsonPrimitive?.contentOrNull ?: ""
                broadcastFn(statusMessage(status, "cc_running_status"))
            }
            "complete" -> {
                val text = data["text"]?.jsonPrimitive?.contentOrNull ?: ""
                val metaObj = data["meta"]?.jsonObject
                val meta = if (metaObj != null) {
                    StreamParser.ResultMeta(
                        costUsd = metaObj["costUsd"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        durationMs = metaObj["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                        numTurns = metaObj["numTurns"]?.jsonPrimitive?.intOrNull ?: 0,
                        sessionId = metaObj["sessionId"]?.jsonPrimitive?.contentOrNull ?: "",
                    )
                } else null

                val wallClockMs = System.currentTimeMillis() - ctx.startTime
                if (text.isNotBlank()) {
                    broadcastFn(finalMessage(text))
                }
                val effectiveMeta = (meta ?: StreamParser.ResultMeta()).copy(durationMs = wallClockMs)
                val metaStr = StreamParser.formatMeta(effectiveMeta)
                if (metaStr.isNotBlank()) {
                    broadcastFn(statusMessage(metaStr))
                }
                if (meta?.sessionId?.isNotBlank() == true) {
                    state.sessionId = meta.sessionId
                }
                state.sessionStarted = true
                activeRequests.remove(requestId)
                finishAndProcessQueue(ctx.userId, ctx.groupId, state, broadcastFn)
            }
            "error" -> {
                val error = data["error"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
                val exitCode = data["exitCode"]?.jsonPrimitive?.intOrNull
                val wallClockMs = System.currentTimeMillis() - ctx.startTime
                val msg = buildString {
                    append("❌ $error")
                    if (exitCode != null) append(" (exit=$exitCode)")
                    append("（耗时: %.1fs）".format(wallClockMs / 1000.0))
                }
                broadcastFn(systemMessage(msg))
                state.sessionStarted = true
                activeRequests.remove(requestId)
                finishAndProcessQueue(ctx.userId, ctx.groupId, state, broadcastFn)
            }
            "cancelled" -> {
                activeRequests.remove(requestId)
                state.cancelled = true
                finishAndProcessQueue(ctx.userId, ctx.groupId, state, broadcastFn)
            }
            "cd_result" -> {
                val success = data["success"]?.jsonPrimitive?.booleanOrNull ?: false
                val resolvedPath = data["path"]?.jsonPrimitive?.contentOrNull ?: ""
                val cdError = data["error"]?.jsonPrimitive?.contentOrNull
                activeRequests.remove(requestId)
                if (success) {
                    state.sessionId = UUID.randomUUID().toString()
                    state.sessionStarted = false
                    state.workingDir = resolvedPath
                    state.messageQueue.clear()
                    broadcastFn(systemMessage("目录已切换，会话已重置\n新会话: ${state.sessionId.take(8)}... | 目录: $resolvedPath"))
                } else {
                    broadcastFn(systemMessage("目录切换失败: ${cdError ?: "未知错误"}"))
                }
            }
            "session_list" -> {
                activeRequests.remove(requestId)
                val sessions = data["sessions"]?.jsonArray
                if (sessions == null || sessions.isEmpty()) {
                    broadcastFn(systemMessage("暂无历史会话记录。完成第一次任务后将自动记录。"))
                } else {
                    val text = buildString {
                        appendLine("📋 历史会话（共 ${sessions.size} 个）")
                        for ((i, s) in sessions.withIndex()) {
                            val obj = s.jsonObject
                            val sid = obj["sessionId"]?.jsonPrimitive?.contentOrNull ?: ""
                            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: ""
                            val dir = obj["workingDir"]?.jsonPrimitive?.contentOrNull ?: ""
                            val lastAct = obj["lastActivity"]?.jsonPrimitive?.contentOrNull ?: ""
                            appendLine("${i + 1}. ${sid.take(8)}... | $title")
                            appendLine("   目录: $dir | 最近: $lastAct")
                        }
                        append("发送 `/session <id前缀>` 恢复会话")
                    }
                    broadcastFn(systemMessage(text))
                }
            }
            "session_resumed" -> {
                activeRequests.remove(requestId)
                val resumedId = data["sessionId"]?.jsonPrimitive?.contentOrNull ?: ""
                val resumedDir = data["workingDir"]?.jsonPrimitive?.contentOrNull ?: state.workingDir
                state.sessionId = resumedId
                state.workingDir = resumedDir
                state.sessionStarted = true
                state.messageQueue.clear()
                broadcastFn(systemMessage("已恢复会话 ${resumedId.take(8)}...\n目录: $resumedDir\n发送消息继续"))
            }
            "new_session" -> {
                activeRequests.remove(requestId)
                val newSessionId = data["sessionId"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
                state.sessionId = newSessionId
                state.sessionStarted = false
                state.messageQueue.clear()
                broadcastFn(systemMessage("会话已重置\n新会话: ${newSessionId.take(8)}... | 目录: ${state.workingDir}"))
            }
        }
    }

    /**
     * Bridge 断线处理。由 BridgeRegistry.unregister 调用。
     */
    fun handleBridgeDisconnect(userId: String) {
        for ((key, state) in states) {
            if (!key.startsWith("${userId}_")) continue
            if (!state.active) continue

            if (state.running) {
                logger.info("[CC] Bridge 断线，用户 {} 有运行中的任务", userId)
                state.running = false
                state.cancelled = false
                val toRemove = activeRequests.entries.filter { it.value.userId == userId }
                for (entry in toRemove) {
                    val ctx = entry.value
                    activeRequests.remove(entry.key)
                    CoroutineScope(Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
                        logger.error("[CC] Bridge 断线通知发送失败: {}", throwable.message)
                    }).launch {
                        ctx.broadcastFn(systemMessage("⚠️ Bridge 已断开，正在执行的任务已丢失"))
                    }
                }
            }

            if (state.messageQueue.isNotEmpty()) {
                val queueSize = state.messageQueue.size
                state.messageQueue.clear()
                logger.info("[CC] Bridge 断线，清空用户 {} 的消息队列（{}条）", userId, queueSize)
            }
        }
    }

    // ========== 消息构造 ==========

    private fun generateId(): String = UUID.randomUUID().toString()

    fun systemMessage(content: String) = Message(
        id = generateId(),
        userId = CC_AGENT_ID,
        userName = CC_AGENT_NAME,
        content = content,
        timestamp = System.currentTimeMillis(),
        type = MessageType.SYSTEM,
        isTransient = false,
    )

    private fun statusMessage(content: String, stableId: String? = null) = Message(
        id = stableId ?: generateId(),
        userId = CC_AGENT_ID,
        userName = CC_AGENT_NAME,
        content = content,
        timestamp = System.currentTimeMillis(),
        type = MessageType.SYSTEM,
        isTransient = true,
        category = MessageCategory.AGENT_STATUS,
    )

    private fun streamingMessage(accumulated: String) = Message(
        id = "cc_streaming",
        userId = CC_AGENT_ID,
        userName = CC_AGENT_NAME,
        content = accumulated,
        timestamp = System.currentTimeMillis(),
        type = MessageType.TEXT,
        isTransient = true,
        isIncremental = false,
    )

    private fun finalMessage(content: String) = Message(
        id = generateId(),
        userId = CC_AGENT_ID,
        userName = CC_AGENT_NAME,
        content = content,
        timestamp = System.currentTimeMillis(),
        type = MessageType.TEXT,
        isTransient = false,
        isIncremental = false,
    )
}
