package com.silk.backend.todos

import com.silk.backend.database.UserTodoRefreshStatusResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 用户待办异步刷新任务管理器：
 * - 同一 userId 同时仅允许一个刷新任务在跑
 * - 记录最近一次开始/结束/错误，供前端轮询状态
 */
object UserTodoRefreshAsyncManager {
    private data class RefreshState(
        val running: Boolean = false,
        val lastStartedAt: Long? = null,
        val lastFinishedAt: Long? = null,
        val lastError: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = ConcurrentHashMap<String, RefreshState>()

    fun start(userId: String): UserTodoRefreshStatusResponse {
        if (userId.isBlank()) {
            return UserTodoRefreshStatusResponse(
                success = false,
                message = "userId 不能为空"
            )
        }

        val current = states[userId]
        if (current?.running == true) {
            return toResponse(
                state = current,
                message = "刷新任务已在后台运行"
            )
        }

        val startAt = System.currentTimeMillis()
        states[userId] = RefreshState(
            running = true,
            lastStartedAt = startAt,
            lastFinishedAt = current?.lastFinishedAt,
            lastError = null
        )

        scope.launch {
            try {
                GroupTodoExtractionService.refreshTodosForUser(userId)
                states[userId] = RefreshState(
                    running = false,
                    lastStartedAt = startAt,
                    lastFinishedAt = System.currentTimeMillis(),
                    lastError = null
                )
            } catch (e: Exception) {
                states[userId] = RefreshState(
                    running = false,
                    lastStartedAt = startAt,
                    lastFinishedAt = System.currentTimeMillis(),
                    lastError = e.message?.take(200)
                )
                println("❌ 异步待办刷新异常 userId=${userId.take(8)}…: ${e.message}")
                e.printStackTrace()
            }
        }

        return status(userId, "已在后台启动待办刷新")
    }

    fun status(userId: String, message: String = "ok"): UserTodoRefreshStatusResponse {
        if (userId.isBlank()) {
            return UserTodoRefreshStatusResponse(
                success = false,
                message = "userId 不能为空"
            )
        }
        val state = states[userId] ?: RefreshState(running = false)
        return toResponse(state, message)
    }

    private fun toResponse(
        state: RefreshState,
        message: String
    ): UserTodoRefreshStatusResponse {
        return UserTodoRefreshStatusResponse(
            success = true,
            message = message,
            running = state.running,
            lastStartedAt = state.lastStartedAt,
            lastFinishedAt = state.lastFinishedAt,
            lastError = state.lastError
        )
    }
}
