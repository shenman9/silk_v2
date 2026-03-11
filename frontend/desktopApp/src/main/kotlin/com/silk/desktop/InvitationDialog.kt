package com.silk.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvitationDialog(
    group: Group,
    onDismiss: () -> Unit
) {
    var showSuccessMessage by remember { mutableStateOf<String?>(null) }
    
    // 生成邀请消息
    val invitationMessage = buildString {
        append("🎉 邀请您加入群组\n\n")
        append("群组名称：${group.name}\n")
        append("邀请码：${group.invitationCode}\n\n")
        append("━━━━━━━━━━━━━━━━━\n\n")
        append("📱 如何加入：\n")
        append("1. 下载并打开 Silk 应用\n")
        append("2. 注册或登录账号\n")
        append("3. 在群组列表点击「加入群组」\n")
        append("4. 输入邀请码：${group.invitationCode}\n\n")
        append("期待您的加入！")
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("邀请入群")
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 邀请信息预览
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "群组名称",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                        
                        Text(
                            text = "邀请码",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = group.invitationCode,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                // 成功提示
                if (showSuccessMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = showSuccessMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                
                // 分享方式
                Text(
                    text = "选择分享方式：",
                    style = MaterialTheme.typography.labelMedium
                )
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // COPY - 只复制邀请码
                    Button(
                        onClick = {
                            copyToClipboard(group.invitationCode)
                            showSuccessMessage = "✅ 邀请码已复制: ${group.invitationCode}"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("📋 COPY（复制邀请码）")
                    }
                    
                    // Invite Message - 复制完整邀请消息
                    OutlinedButton(
                        onClick = {
                            copyToClipboard(invitationMessage)
                            showSuccessMessage = "✅ 完整邀请消息已复制到剪贴板"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Message, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("💬 Invite Message（复制完整消息）")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

/**
 * 复制文本到剪贴板
 */
private fun copyToClipboard(text: String) {
    try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val selection = StringSelection(text)
        clipboard.setContents(selection, null)
        println("✅ 已复制到剪贴板")
    } catch (e: Exception) {
        println("❌ 复制失败: ${e.message}")
    }
}

/**
 * 分享到微信
 * 在macOS上使用URL Scheme打开微信
 */
private fun shareToWeChat(text: String): Boolean {
    return try {
        val osName = System.getProperty("os.name").lowercase()
        
        when {
            osName.contains("mac") -> {
                // macOS: 使用URL Scheme打开微信
                // 先复制文本到剪贴板，然后打开微信
                copyToClipboard(text)
                
                // 尝试打开微信（需要先安装微信）
                val command = arrayOf("open", "-a", "WeChat")
                Runtime.getRuntime().exec(command)
                
                println("✅ 已打开微信，邀请信息已复制到剪贴板")
                true
            }
            osName.contains("win") -> {
                // Windows: 复制到剪贴板并提示用户
                copyToClipboard(text)
                println("✅ 邀请信息已复制，请手动打开微信粘贴")
                true
            }
            else -> {
                // Linux: 复制到剪贴板
                copyToClipboard(text)
                println("✅ 邀请信息已复制，请手动打开微信粘贴")
                true
            }
        }
    } catch (e: Exception) {
        println("❌ 打开微信失败: ${e.message}")
        // 即使打开微信失败，也复制到剪贴板
        copyToClipboard(text)
        false
    }
}

/**
 * 通过SMS分享
 * 在macOS上使用Messages URL Scheme
 */
private fun shareViaSMS(text: String): Boolean {
    return try {
        val osName = System.getProperty("os.name").lowercase()
        
        when {
            osName.contains("mac") -> {
                // macOS: 使用 Messages.app URL Scheme
                // 格式：sms:&body=消息内容
                val encodedText = java.net.URLEncoder.encode(text, "UTF-8")
                val smsUrl = "sms:&body=$encodedText"
                
                // 使用Desktop API打开URL
                if (java.awt.Desktop.isDesktopSupported()) {
                    val desktop = java.awt.Desktop.getDesktop()
                    if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                        desktop.browse(URI(smsUrl))
                        println("✅ 已打开短信应用")
                        return true
                    }
                }
                
                // 备用方案：使用open命令
                val command = arrayOf("open", smsUrl)
                Runtime.getRuntime().exec(command)
                println("✅ 已打开短信应用")
                true
            }
            osName.contains("win") -> {
                // Windows: 复制到剪贴板
                copyToClipboard(text)
                println("⚠️ Windows 不支持直接打开短信，已复制到剪贴板")
                false
            }
            else -> {
                // Linux: 复制到剪贴板
                copyToClipboard(text)
                println("⚠️ Linux 不支持直接打开短信，已复制到剪贴板")
                false
            }
        }
    } catch (e: Exception) {
        println("❌ 打开短信应用失败: ${e.message}")
        copyToClipboard(text)
        false
    }
}

