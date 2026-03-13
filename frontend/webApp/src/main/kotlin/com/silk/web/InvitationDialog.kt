package com.silk.web

import androidx.compose.runtime.*
import kotlinx.browser.window
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

@Composable
fun InvitationDialog(
    group: Group,
    strings: com.silk.shared.i18n.Strings,
    onDismiss: () -> Unit
) {
    var showSuccessMessage by remember { mutableStateOf<String?>(null) }
    
    // 生成邀请消息
    val invitationMessage = """
🎀 邀请您加入 Silk 群组

群组名称：${group.name}
邀请码：${group.invitationCode}

━━━━━━━━━━━━━━━━━

📱 下载/访问 Silk：
• Android APK: ${window.location.protocol}//${window.location.hostname}:8003/api/files/download-apk
• Web 网页版: ${window.location.protocol}//${window.location.hostname}:8001

🚀 如何加入：
1. 下载或访问 Silk 应用
2. 注册或登录账号
3. 在群组列表点击「加入群组」
4. 输入邀请码：${group.invitationCode}

期待您的加入！
    """.trimIndent()
    
    // 对话框遮罩
    Div({
        style {
            position(Position.Fixed)
            top(0.px)
            left(0.px)
            width(100.percent)
            height(100.vh)
            backgroundColor(Color("rgba(74, 64, 56, 0.5)"))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            property("z-index", "1000")
            property("backdrop-filter", "blur(4px)")
        }
        onClick { onDismiss() }
    }) {
        Div({
            style {
                backgroundColor(Color(SilkColors.surfaceElevated))
                borderRadius(16.px)
                padding(28.px)
                width(480.px)
                maxWidth(90.vw)
                property("max-height", "80vh")
                property("overflow-y", "auto")
                property("box-shadow", "0 8px 32px rgba(169, 137, 77, 0.2)")
                property("border", "1px solid ${SilkColors.border}")
            }
            onClick { it.stopPropagation() }
        }) {
            // 标题
            H3({
                style {
                    marginTop(0.px)
                    marginBottom(24.px)
                    color(Color(SilkColors.primary))
                    property("font-weight", "600")
                    property("letter-spacing", "1px")
                }
            }) {
                Text(strings.inviteToGroup)
            }
            
            // 邀请信息预览 - 丝滑风格
            Div({
                style {
                    backgroundColor(Color(SilkColors.secondary))
                    borderRadius(12.px)
                    padding(20.px)
                    marginBottom(24.px)
                    property("border", "1px solid ${SilkColors.border}")
                }
            }) {
                Div({
                    style {
                        fontSize(12.px)
                        color(Color(SilkColors.textSecondary))
                        marginBottom(8.px)
                        property("letter-spacing", "0.5px")
                    }
                }) {
                    Text(strings.groupName)
                }
                Div({
                    style {
                        fontSize(17.px)
                        property("font-weight", "600")
                        marginBottom(20.px)
                        color(Color(SilkColors.textPrimary))
                    }
                }) {
                    Text(group.name)
                }
                
                Div({
                    style {
                        fontSize(12.px)
                        color(Color(SilkColors.textSecondary))
                        marginBottom(8.px)
                        property("letter-spacing", "0.5px")
                    }
                }) {
                    Text(strings.invitationCode)
                }
                Div({
                    style {
                        fontSize(28.px)
                        property("font-weight", "700")
                        color(Color(SilkColors.primary))
                        property("letter-spacing", "6px")
                        fontFamily("'Cormorant Garamond'", "Georgia", "serif")
                    }
                }) {
                    Text(group.invitationCode)
                }
            }
            
            // 成功提示 - 丝滑绿色
            if (showSuccessMessage != null) {
                Div({
                    style {
                        backgroundColor(Color("#F0F7EE"))
                        color(Color(SilkColors.success))
                        padding(14.px)
                        borderRadius(8.px)
                        marginBottom(20.px)
                        fontSize(13.px)
                        property("border", "1px solid ${SilkColors.success}")
                        property("letter-spacing", "0.5px")
                    }
                }) {
                    Text(showSuccessMessage!!)
                }
            }
            
            // 分享方式标题
            Div({
                style {
                    fontSize(13.px)
                    property("font-weight", "600")
                    marginBottom(14.px)
                    color(Color(SilkColors.textPrimary))
                    property("letter-spacing", "0.5px")
                }
            }) {
                Text(strings.selectShareMethod)
            }
            
            // 分享按钮 - 丝滑风格
            Div({
                style {
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    property("gap", "14px")
                }
            }) {
                // COPY - 只复制邀请码
                Button({
                    style {
                        width(100.percent)
                        padding(14.px)
                        property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                        color(Color.white)
                        border { width(0.px) }
                        borderRadius(8.px)
                        fontSize(14.px)
                        property("cursor", "pointer")
                        property("font-weight", "600")
                        property("letter-spacing", "1px")
                        property("box-shadow", "0 2px 8px rgba(169, 137, 77, 0.25)")
                        property("transition", "all 0.2s ease")
                    }
                    onClick {
                        copyToClipboard(group.invitationCode)
                        showSuccessMessage = strings.invitationCodeCopied.replace("{code}", group.invitationCode)
                    }
                }) {
                    Text(strings.copyInvitationCode)
                }
                
                // Invite Message - 复制完整邀请消息
                Button({
                    style {
                        width(100.percent)
                        padding(14.px)
                        backgroundColor(Color(SilkColors.surfaceElevated))
                        color(Color(SilkColors.primary))
                        border {
                            width(2.px)
                            style(LineStyle.Solid)
                            color(Color(SilkColors.primary))
                        }
                        borderRadius(8.px)
                        fontSize(14.px)
                        property("cursor", "pointer")
                        property("font-weight", "600")
                        property("letter-spacing", "1px")
                        property("transition", "all 0.2s ease")
                    }
                    onClick {
                        copyToClipboard(invitationMessage)
                        showSuccessMessage = strings.fullMessageCopied
                    }
                }) {
                    Text(strings.copyFullMessage)
                }
            }
            
            // 关闭按钮
            Div({
                style {
                    textAlign("center")
                    marginTop(24.px)
                }
            }) {
                Button({
                    style {
                        padding(12.px, 28.px)
                        backgroundColor(Color(SilkColors.secondary))
                        color(Color(SilkColors.textPrimary))
                        border { width(0.px) }
                        borderRadius(8.px)
                        property("cursor", "pointer")
                        fontSize(14.px)
                        property("font-weight", "500")
                        property("transition", "all 0.2s ease")
                    }
                    onClick { onDismiss() }
                }) {
                    Text(strings.closeButton)
                }
            }
        }
    }
}

/**
 * 复制到剪贴板（Web版）
 * 注意：navigator.clipboard 仅在 HTTPS 或 localhost 下可用
 */
fun copyToClipboard(text: String) {
    // 使用 JS 调用，因为 Kotlin/JS 的类型可能不完整
    js("""
        (function(textToCopy) {
            // 优先使用 Clipboard API（HTTPS/localhost 环境）
            if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
                navigator.clipboard.writeText(textToCopy)
                    .then(function() {
                        console.log('✅ 已复制到剪贴板');
                    })
                    .catch(function(err) {
                        console.log('❌ Clipboard API 失败:', err);
                        fallbackCopy(textToCopy);
                    });
            } else {
                // 备用方案：使用 execCommand
                fallbackCopy(textToCopy);
            }
            
            function fallbackCopy(text) {
                try {
                    var textArea = document.createElement('textarea');
                    textArea.value = text;
                    textArea.style.position = 'fixed';
                    textArea.style.left = '-9999px';
                    textArea.style.top = '-9999px';
                    document.body.appendChild(textArea);
                    textArea.focus();
                    textArea.select();
                    var success = document.execCommand('copy');
                    document.body.removeChild(textArea);
                    if (success) {
                        console.log('✅ 使用备用方案复制成功');
                    } else {
                        console.log('❌ execCommand 返回 false');
                    }
                } catch (e) {
                    console.log('❌ 备用方案也失败:', e);
                }
            }
        })
    """)(text)
}
