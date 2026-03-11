package com.silk.shared

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*

actual class PlatformWebSocket actual constructor(
    private val serverUrl: String,
    private val onMessage: (String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: (String) -> Unit,
    private val onLog: LogCallback?
) {
    private val client = HttpClient(OkHttp) {
        install(WebSockets) {
            pingInterval = 15_000
        }
    }
    
    private var session: DefaultClientWebSocketSession? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private fun log(message: String) {
        println(message)
        onLog?.invoke(message)
    }
    
    actual val isConnected: Boolean
        get() = session != null
    
    actual fun connect(userId: String, userName: String, groupId: String) {
        val safeUserName = userName.replace(" ", "_").replace("&", "_").replace("=", "_")
        val safeGroupId = groupId.replace(" ", "_").replace("&", "_").replace("=", "_")
        val fullUrl = "$serverUrl/chat?userId=$userId&userName=$safeUserName&groupId=$safeGroupId"
        
        log("🔗 [WebSocket] 连接到: $fullUrl")
        
        job = scope.launch {
            try {
                client.webSocket(urlString = fullUrl) {
                    session = this
                    log("✅ [WebSocket] 连接已打开")
                    withContext(Dispatchers.Main) { onConnected() }
                    
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    withContext(Dispatchers.Main) { onMessage(text) }
                                }
                                else -> {}
                            }
                        }
                    } catch (e: CancellationException) {
                        // Normal cancellation
                    } catch (e: Exception) {
                        log("❌ [WebSocket] 接收错误: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                log("❌ [WebSocket] 连接失败: ${e.message}")
                withContext(Dispatchers.Main) { onError(e.message ?: "Unknown error") }
            } finally {
                session = null
                withContext(Dispatchers.Main) { onDisconnected() }
            }
        }
    }
    
    actual fun send(message: String) {
        scope.launch {
            try {
                session?.send(Frame.Text(message))
            } catch (e: Exception) {
                log("❌ [WebSocket] 发送失败: ${e.message}")
            }
        }
    }
    
    actual fun disconnect() {
        scope.launch {
            try {
                session?.close(CloseReason(CloseReason.Codes.NORMAL, "Client disconnecting"))
            } catch (e: Exception) {
                log("⚠️ [WebSocket] 关闭异常: ${e.message}")
            }
            job?.cancel()
            session = null
        }
    }
}

