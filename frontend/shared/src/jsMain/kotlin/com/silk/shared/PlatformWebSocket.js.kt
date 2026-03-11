package com.silk.shared

import org.w3c.dom.WebSocket as BrowserWebSocket
import org.w3c.dom.MessageEvent
import org.w3c.dom.events.Event
import kotlinx.browser.window

actual class PlatformWebSocket actual constructor(
    private val serverUrl: String,
    private val onMessage: (String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: (String) -> Unit,
    private val onLog: LogCallback?
) {
    private var ws: BrowserWebSocket? = null
    
    private fun log(message: String) {
        println(message)
        onLog?.invoke(message)
    }
    
    actual val isConnected: Boolean
        get() = ws?.readyState == BrowserWebSocket.OPEN
    
    actual fun connect(userId: String, userName: String, groupId: String) {
        try {
            val safeUserName = userName.replace(" ", "_").replace("&", "_").replace("=", "_")
            val safeGroupId = groupId.replace(" ", "_").replace("&", "_").replace("=", "_")
            
            val fullUrl = "$serverUrl/chat?userId=$userId&userName=$safeUserName&groupId=$safeGroupId"
            log("🔗 [WebSocket] 连接到: $fullUrl")
            
            ws = BrowserWebSocket(fullUrl)
            
            ws?.onopen = { _: Event ->
                log("✅ [WebSocket] 连接已打开")
                onConnected()
            }
            
            ws?.onclose = { _: Event ->
                log("🔌 [WebSocket] 连接已关闭")
                onDisconnected()
            }
            
            ws?.onerror = { event: Event ->
                log("❌ [WebSocket] 错误")
                onError("WebSocket error")
            }
            
            ws?.onmessage = { event: MessageEvent ->
                val data = event.data
                if (data is String) {
                    onMessage(data)
                }
            }
        } catch (e: Exception) {
            log("❌ [WebSocket] 创建失败: ${e.message}")
            onError(e.message ?: "Unknown error")
        }
    }
    
    actual fun send(message: String) {
        try {
            if (isConnected) {
                ws?.send(message)
            } else {
                log("⚠️ [WebSocket] 未连接，无法发送消息")
            }
        } catch (e: Exception) {
            log("❌ [WebSocket] 发送失败: ${e.message}")
        }
    }
    
    actual fun disconnect() {
        try {
            ws?.close(1000, "Client disconnecting")
            ws = null
        } catch (e: Exception) {
            log("⚠️ [WebSocket] 关闭异常: ${e.message}")
        }
    }
}

