package com.silk.shared

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

actual class PlatformWebSocket actual constructor(
    private val serverUrl: String,
    private val onMessage: (String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: (String) -> Unit,
    private val onLog: LogCallback?
) {
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val unsafeSslSocketFactory = SSLContext.getInstance("TLS").run {
        init(null, arrayOf(trustAllManager), SecureRandom())
        socketFactory
    }

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                // 兼容远端自签证书与域名不匹配（例如证书 CN=localhost）。
                sslSocketFactory(unsafeSslSocketFactory, trustAllManager)
                hostnameVerifier { _, _ -> true }
            }
        }
        install(WebSockets) {
            pingInterval = 10_000  // 10秒 ping 间隔，更频繁的保活
        }
    }
    
    private var session: DefaultClientWebSocketSession? = null
    private var job: Job? = null
    private var keepAliveJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 连接状态追踪
    private val isConnecting = AtomicBoolean(false)
    private val isExplicitlyDisconnected = AtomicBoolean(false)
    
    // 自动重连配置
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val baseReconnectDelay = 1000L  // 1秒基础延迟
    
    private fun log(message: String) {
        println(message)
        onLog?.invoke(message)
    }
    
    actual val isConnected: Boolean
        get() = session != null && !isExplicitlyDisconnected.get()
    
    actual fun connect(userId: String, userName: String, groupId: String) {
        // 防止重复连接
        if (isConnecting.getAndSet(true)) {
            log("⚠️ [WebSocket] 已有连接正在进行中，跳过")
            return
        }
        
        isExplicitlyDisconnected.set(false)
        
        val safeUserName = userName.replace(" ", "_").replace("&", "_").replace("=", "_")
        val safeGroupId = groupId.replace(" ", "_").replace("&", "_").replace("=", "_")
        val fullUrl = "$serverUrl/chat?userId=$userId&userName=$safeUserName&groupId=$safeGroupId"
        
        log("🔗 [WebSocket] 连接到: $fullUrl")
        
        job = scope.launch {
            try {
                client.webSocket(urlString = fullUrl) {
                    session = this
                    isConnecting.set(false)
                    reconnectAttempts = 0  // 重置重连计数
                    log("✅ [WebSocket] 连接已打开")
                    withContext(Dispatchers.Main) { onConnected() }
                    
                    // 启动保活心跳
                    startKeepAlive()
                    
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    // 收到消息说明连接正常，重置保活计时
                                    withContext(Dispatchers.Main) { onMessage(text) }
                                }
                                is Frame.Ping -> {
                                    log("💓 [WebSocket] 收到 Ping")
                                }
                                is Frame.Pong -> {
                                    log("💓 [WebSocket] 收到 Pong")
                                }
                                else -> {}
                            }
                        }
                    } catch (e: CancellationException) {
                        log("⏹️ [WebSocket] 连接被取消（正常断开）")
                        throw e
                    } catch (e: Exception) {
                        log("❌ [WebSocket] 接收错误: ${e.message}")
                        // 非正常断开，尝试重连
                        if (!isExplicitlyDisconnected.get()) {
                            attemptReconnect(userId, userName, groupId)
                        }
                    }
                }
            } catch (e: Exception) {
                isConnecting.set(false)
                if (!isExplicitlyDisconnected.get()) {
                    log("❌ [WebSocket] 连接失败: ${e.message}")
                    withContext(Dispatchers.Main) { onError(e.message ?: "Unknown error") }
                    // 尝试重连
                    attemptReconnect(userId, userName, groupId)
                }
            } finally {
                session = null
                keepAliveJob?.cancel()
                if (!isExplicitlyDisconnected.get()) {
                    withContext(Dispatchers.Main) { onDisconnected() }
                }
            }
        }
    }
    
    /**
     * 启动保活心跳
     * 定期发送自定义心跳消息，确保连接活跃
     */
    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            var lastKeepAlive = System.currentTimeMillis()
            while (isActive && session != null) {
                delay(30_000)  // 每30秒检查一次
                val now = System.currentTimeMillis()
                if (now - lastKeepAlive > 60_000) {
                    // 超过60秒没有任何活动，发送心跳
                    try {
                        session?.send(Frame.Text("❤️"))
                        log("💓 [WebSocket] 发送保活心跳")
                        lastKeepAlive = now
                    } catch (e: Exception) {
                        log("⚠️ [WebSocket] 发送心跳失败: ${e.message}")
                        break
                    }
                }
            }
        }
    }
    
    /**
     * 尝试自动重连
     */
    private suspend fun attemptReconnect(userId: String, userName: String, groupId: String) {
        if (isExplicitlyDisconnected.get()) {
            log("⏹️ [WebSocket] 已明确断开，不重连")
            return
        }
        
        reconnectAttempts++
        if (reconnectAttempts > maxReconnectAttempts) {
            log("❌ [WebSocket] 重连次数超限 (${maxReconnectAttempts}次)")
            withContext(Dispatchers.Main) { onError("Connection lost after $maxReconnectAttempts reconnect attempts") }
            return
        }
        
        val delay = baseReconnectDelay * reconnectAttempts  // 指数退避
        log("🔄 [WebSocket] 尝试重连 (${reconnectAttempts}/${maxReconnectAttempts})，延迟 ${delay}ms...")
        delay(delay)
        
        isConnecting.set(false)
        connect(userId, userName, groupId)
    }
    
    actual fun send(message: String) {
        if (isExplicitlyDisconnected.get()) {
            log("⚠️ [WebSocket] 连接已断开，无法发送")
            return
        }
        
        scope.launch {
            try {
                session?.send(Frame.Text(message))
            } catch (e: Exception) {
                log("❌ [WebSocket] 发送失败: ${e.message}")
                // 发送失败可能意味着连接断了
                if (!isExplicitlyDisconnected.get()) {
                    withContext(Dispatchers.Main) { onError("Send failed: ${e.message}") }
                }
            }
        }
    }
    
    actual fun disconnect() {
        log("🔌 [WebSocket] 明确断开连接")
        isExplicitlyDisconnected.set(true)
        
        scope.launch {
            try {
                keepAliveJob?.cancel()
                session?.close(CloseReason(CloseReason.Codes.NORMAL, "Client disconnecting"))
            } catch (e: Exception) {
                log("⚠️ [WebSocket] 关闭异常: ${e.message}")
            }
            job?.cancel()
            session = null
            isConnecting.set(false)
        }
    }
    
    /**
     * 重置连接状态（用于重连场景）
     */
    fun reset() {
        isExplicitlyDisconnected.set(false)
        isConnecting.set(false)
        reconnectAttempts = 0
    }
}
