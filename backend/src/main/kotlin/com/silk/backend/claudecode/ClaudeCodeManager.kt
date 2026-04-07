// backend/src/main/kotlin/com/silk/backend/claudecode/ClaudeCodeManager.kt
package com.silk.backend.claudecode

import com.silk.backend.Message
import com.silk.backend.MessageCategory
import com.silk.backend.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Claude Code 模式管理器。
 * 管理 per-user-per-group 的 CC 状态，路由命令和普通消息。
 */
object ClaudeCodeManager {

    private val logger = LoggerFactory.getLogger(ClaudeCodeManager::class.java)

    const val CC_AGENT_ID = "silk_ai_agent"        // 复用 Silk AI 的 ID，前端可识别为 AI 消息
    const val CC_AGENT_NAME = "🤖 Claude Code"    // 用不同名字区分
    private const val MAX_QUEUE_SIZE = 10

    private val states = ConcurrentHashMap<String, UserCCState>()
    private val sessionStore = SessionStore(File("chat_history"))
    private val runner = ClaudeCodeRunner()

    /** 用户 CC 状态 */
    class UserCCState(
        @Volatile var active: Boolean = false,
        var sessionId: String = UUID.randomUUID().toString(),
        @Volatile var sessionStarted: Boolean = false,
        @Volatile var running: Boolean = false,
        var workingDir: String = ClaudeCodeRunner.defaultWorkingDir,
        @Volatile var cancelled: Boolean = false,
        val messageQueue: java.util.concurrent.ConcurrentLinkedDeque<QueuedMessage> = java.util.concurrent.ConcurrentLinkedDeque(),
        @Volatile var currentProcess: Process? = null,
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
            text.lowercase() == "/cancel" -> handleCancel(state, broadcastFn)
            text.lowercase() == "/status" -> handleStatus(state, broadcastFn)
            text.lowercase() == "/help" -> handleHelp(broadcastFn)
            text.lowercase() == "/session" -> handleSessionList(userId, broadcastFn)
            text.lowercase().startsWith("/session ") -> handleSessionResume(userId, groupId, text.substring(9).trim(), state, broadcastFn)
            text.lowercase() == "/cd" -> handleCd(userId, groupId, null, state, broadcastFn)
            text.lowercase().startsWith("/cd ") -> handleCd(userId, groupId, text.substring(4).trim(), state, broadcastFn)
            text.lowercase() == "/queue" -> handleQueueView(state, broadcastFn)
            text.lowercase() == "/queue clear" -> handleQueueClear(state, broadcastFn)
            text.lowercase() == "/compact" -> executePrompt(userId, groupId, userName, "/compact", state, broadcastFn)
            else -> {
                // 普通消息 → 执行或入队
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
        killProcess(state)
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
        val oldId = state.sessionId.take(8)
        state.sessionId = UUID.randomUUID().toString()
        state.sessionStarted = false
        state.messageQueue.clear()
        logger.info("[CC] 新建会话: old={}, new={}", oldId, state.sessionId.take(8))
        broadcastFn(systemMessage("会话已重置\n新会话: ${state.sessionId.take(8)}... | 目录: ${state.workingDir}"))
    }

    private suspend fun handleCancel(state: UserCCState, broadcastFn: suspend (Message) -> Unit) {
        if (!state.running) {
            broadcastFn(systemMessage("当前没有运行中的任务"))
            return
        }
        val dropped = state.messageQueue.size
        state.messageQueue.clear()
        killProcess(state)
        state.cancelled = true
        if (!state.sessionStarted) state.sessionStarted = true
        val msg = buildString {
            append("已取消当前任务")
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

    private suspend fun handleSessionList(userId: String, broadcastFn: suspend (Message) -> Unit) {
        val sessions = sessionStore.loadUserSessions(userId)
        if (sessions.isEmpty()) {
            broadcastFn(systemMessage("暂无历史会话记录。完成第一次任务后将自动记录。"))
            return
        }
        val text = buildString {
            appendLine("📋 历史会话（共 ${sessions.size} 个）")
            for ((i, s) in sessions.withIndex()) {
                val star = if (s.starred) "⭐ " else ""
                appendLine("${i + 1}. $star${s.sessionId.take(8)}... | ${s.title}")
                appendLine("   目录: ${s.workingDir} | 最近: ${s.lastActivity}")
            }
            append("发送 `/session <id前缀>` 恢复会话")
        }
        broadcastFn(systemMessage(text))
    }

    private suspend fun handleSessionResume(
        userId: String, groupId: String, idPrefix: String,
        state: UserCCState, broadcastFn: suspend (Message) -> Unit,
    ) {
        if (state.running) {
            broadcastFn(systemMessage("任务运行中，无法切换会话"))
            return
        }
        val sessions = sessionStore.loadUserSessions(userId)
        val target = sessions.find { it.sessionId.startsWith(idPrefix) }
        if (target == null) {
            broadcastFn(systemMessage("未找到匹配 \"$idPrefix\" 的会话"))
            return
        }
        val dir = target.workingDir
        if (!File(dir).isDirectory) {
            broadcastFn(systemMessage("工作目录已不存在: $dir"))
            return
        }
        killProcess(state)
        state.sessionId = target.sessionId
        state.workingDir = dir
        state.running = false
        state.sessionStarted = true // 恢复的会话用 --resume
        state.messageQueue.clear()
        logger.info("[CC] 恢复会话: sessionId={}, dir={}", target.sessionId.take(8), dir)
        broadcastFn(systemMessage("已恢复会话 ${target.sessionId.take(8)}...\n目录: $dir\n发送消息继续"))
    }

    private suspend fun handleCd(
        userId: String, groupId: String, path: String?,
        state: UserCCState, broadcastFn: suspend (Message) -> Unit,
    ) {
        if (state.running) {
            broadcastFn(systemMessage("任务运行中，请先 /cancel 再 /cd"))
            return
        }
        val newDir = if (path.isNullOrBlank()) {
            ClaudeCodeRunner.defaultWorkingDir
        } else {
            val resolved = File(path).canonicalPath
            if (!File(resolved).isDirectory) {
                broadcastFn(systemMessage("目录不存在: $resolved"))
                return
            }
            resolved
        }
        state.sessionId = UUID.randomUUID().toString()
        state.sessionStarted = false
        state.workingDir = newDir
        state.messageQueue.clear()
        logger.info("[CC] 切换目录: dir={}, newSession={}", newDir, state.sessionId.take(8))
        broadcastFn(systemMessage("目录已切换，会话已重置\n新会话: ${state.sessionId.take(8)}... | 目录: $newDir"))
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

    private fun executePrompt(
        userId: String, groupId: String, userName: String,
        prompt: String, state: UserCCState,
        broadcastFn: suspend (Message) -> Unit,
    ) {
        state.running = true
        state.cancelled = false

        // 持久化会话记录
        val title = if (prompt.length > 50) prompt.take(50) + "…" else prompt
        sessionStore.upsertSession(userId, state.sessionId, state.workingDir, title)

        val stableSessionId = state.sessionId
        val stableResume = state.sessionStarted
        val stableWorkingDir = state.workingDir
        val startTime = System.currentTimeMillis()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                logger.info("[CC] 开始执行: prompt={}, sessionId={}, resume={}", prompt.take(30), stableSessionId.take(8), stableResume)
                runner.execute(
                    prompt = prompt,
                    sessionId = stableSessionId,
                    workingDir = stableWorkingDir,
                    resume = stableResume,
                    onStreamText = { accumulated ->
                        logger.info("[CC] onStreamText: {}字符", accumulated.length)
                        broadcastFn(streamingMessage(accumulated))
                    },
                    onToolLog = { log ->
                        logger.info("[CC] onToolLog: {}", log.take(80))
                        broadcastFn(statusMessage(log))
                    },
                    onComplete = { fullText, meta ->
                        val wallClockMs = System.currentTimeMillis() - startTime
                        logger.info("[CC] onComplete: text={}字符, meta={}, wallClockMs={}", fullText.length, meta != null, wallClockMs)
                        // 发送最终持久化消息
                        if (fullText.isNotBlank()) {
                            broadcastFn(finalMessage(fullText))
                        }
                        // 发送元信息，使用 wall-clock 时间替换 CLI 报告的 duration
                        val effectiveMeta = (meta ?: StreamParser.ResultMeta()).copy(durationMs = wallClockMs)
                        val metaStr = StreamParser.formatMeta(effectiveMeta)
                        if (metaStr.isNotBlank()) {
                            broadcastFn(statusMessage(metaStr))
                        }
                        state.sessionStarted = true
                        onTaskFinished(userId, groupId, userName, state, broadcastFn)
                    },
                    onError = { error ->
                        val wallClockMs = System.currentTimeMillis() - startTime
                        logger.error("[CC] onError: {}, wallClockMs={}", error.take(200), wallClockMs)
                        broadcastFn(systemMessage("❌ $error（耗时: %.1fs）".format(wallClockMs / 1000.0)))
                        state.sessionStarted = true
                        onTaskFinished(userId, groupId, userName, state, broadcastFn)
                    },
                    processRef = { process -> state.currentProcess = process },
                )
            } catch (e: Exception) {
                val wallClockMs = System.currentTimeMillis() - startTime
                logger.error("[CC] 执行异常: {}, wallClockMs={}", e.message, wallClockMs)
                broadcastFn(systemMessage("❌ 执行异常: ${e.message}（耗时: %.1fs）".format(wallClockMs / 1000.0)))
                onTaskFinished(userId, groupId, userName, state, broadcastFn)
            }
        }
    }

    private fun onTaskFinished(
        userId: String, groupId: String, userName: String,
        state: UserCCState,
        broadcastFn: suspend (Message) -> Unit,
    ) {
        state.currentProcess = null
        if (state.messageQueue.isNotEmpty() && !state.cancelled) {
            val next = state.messageQueue.removeFirst()
            logger.info("[CC] 自动取出队列指令: userId={}, remaining={}", userId, state.messageQueue.size)
            state.cancelled = false
            executePrompt(next.userId, groupId, next.userName, next.text, state, broadcastFn)
        } else {
            state.running = false
        }
    }

    private fun killProcess(state: UserCCState) {
        state.currentProcess?.let { proc ->
            if (proc.isAlive) {
                proc.destroyForcibly()
                logger.info("[CC] 已终止子进程")
            }
        }
        state.currentProcess = null
    }

    // ========== 消息构造 ==========

    private fun generateId(): String = System.currentTimeMillis().toString() + (0..999).random()

    fun systemMessage(content: String) = Message(
        id = generateId(),
        userId = CC_AGENT_ID,
        userName = CC_AGENT_NAME,
        content = content,
        timestamp = System.currentTimeMillis(),
        type = MessageType.SYSTEM,
        isTransient = false,
    )

    private fun statusMessage(content: String) = Message(
        id = generateId(),
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
        isIncremental = false, // 替换模式：发送完整累积文本
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
