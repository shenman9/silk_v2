package com.silk.desktop

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silk.shared.models.Message
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import kotlinx.coroutines.delay

/**
 * 消息包装组件
 * 使用SelectionContainer提供文本选择和复制功能
 * 使用remember和LaunchedEffect确保正确的生命周期，避免ID冲突
 */
@Composable
fun MessageWithContextMenu(
    content: @Composable () -> Unit,
    message: Message,
    onCopy: () -> Unit = {},
    onForwardToWeChat: () -> Unit = {},
    onForwardToSMS: () -> Unit = {}
) {
    // 使用DisposableEffect确保每个消息的SelectionContainer正确管理
    DisposableEffect(message.id) {
        onDispose {
            // 清理工作
        }
    }
    
    // 使用key确保唯一性
    key(message.id) {
        SelectionContainer {
            content()
        }
    }
}

/**
 * 复制消息到剪贴板
 */
fun copyMessageToClipboard(text: String) {
    try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val selection = StringSelection(text)
        clipboard.setContents(selection, null)
        println("✅ 消息已复制到剪贴板")
    } catch (e: Exception) {
        println("❌ 复制失败: ${e.message}")
    }
}

/**
 * 转发消息到微信
 */
fun forwardToWeChat(text: String) {
    try {
        val osName = System.getProperty("os.name").lowercase()
        
        // 复制消息到剪贴板
        copyMessageToClipboard(text)
        
        when {
            osName.contains("mac") -> {
                // macOS: 打开微信
                Runtime.getRuntime().exec(arrayOf("open", "-a", "WeChat"))
                println("✅ 已打开微信，消息已复制到剪贴板")
            }
            osName.contains("win") -> {
                // Windows: 尝试启动微信
                try {
                    Runtime.getRuntime().exec("cmd /c start WeChat")
                    println("✅ 已打开微信，消息已复制到剪贴板")
                } catch (e: Exception) {
                    println("⚠️ 请手动打开微信，消息已复制到剪贴板")
                }
            }
            else -> {
                // Linux: 只复制到剪贴板
                println("ℹ️ 消息已复制到剪贴板，请手动打开微信粘贴")
            }
        }
    } catch (e: Exception) {
        println("❌ 转发失败: ${e.message}")
    }
}

/**
 * 转发消息到SMS
 */
fun forwardToSMS(text: String) {
    try {
        val osName = System.getProperty("os.name").lowercase()
        
        when {
            osName.contains("mac") -> {
                // macOS: 使用Messages.app URL Scheme
                val encodedText = java.net.URLEncoder.encode(text, "UTF-8")
                val smsUrl = "sms:&body=$encodedText"
                
                // 尝试使用Desktop API
                if (java.awt.Desktop.isDesktopSupported()) {
                    val desktop = java.awt.Desktop.getDesktop()
                    if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                        desktop.browse(URI(smsUrl))
                        println("✅ 已打开短信应用")
                        return
                    }
                }
                
                // 备用方案
                Runtime.getRuntime().exec(arrayOf("open", smsUrl))
                println("✅ 已打开短信应用")
            }
            else -> {
                // Windows/Linux: 复制到剪贴板
                copyMessageToClipboard(text)
                println("ℹ️ SMS功能仅支持macOS，消息已复制到剪贴板")
            }
        }
    } catch (e: Exception) {
        println("❌ 打开短信应用失败: ${e.message}")
        copyMessageToClipboard(text)
    }
}

/**
 * 消息操作工具栏（悬浮显示）
 */
@Composable
fun MessageActionBar(
    message: Message,
    onCopy: () -> Unit,
    onForwardWeChat: () -> Unit,
    onForwardSMS: () -> Unit
) {
    var showActions by remember { mutableStateOf(false) }
    var showSuccessMessage by remember { mutableStateOf<String?>(null) }
    
    Box {
        // 鼠标悬浮时显示操作按钮
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 复制按钮
            IconButton(
                onClick = {
                    copyMessageToClipboard(message.content)
                    showSuccessMessage = "已复制"
                    onCopy()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "复制",
                    modifier = Modifier.size(18.dp)
                )
            }
            
            // 转发到微信
            IconButton(
                onClick = {
                    forwardToWeChat(message.content)
                    showSuccessMessage = "已转发到微信"
                    onForwardWeChat()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Message,
                    contentDescription = "转发到微信",
                    modifier = Modifier.size(18.dp)
                )
            }
            
            // 转发到SMS
            IconButton(
                onClick = {
                    forwardToSMS(message.content)
                    showSuccessMessage = "已转发到短信"
                    onForwardSMS()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Sms,
                    contentDescription = "SMS转发",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        
        // 成功提示
        if (showSuccessMessage != null) {
            LaunchedEffect(showSuccessMessage) {
                kotlinx.coroutines.delay(2000)
                showSuccessMessage = null
            }
        }
    }
}

