package com.silk.web

import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

@Composable
fun LoginScene(appState: WebAppState) {
    val scope = rememberCoroutineScope()
    
    // 检查是否是意外到达登录页（用户没有明确退出登录）
    // 如果是，自动恢复到群组列表页面
    LaunchedEffect(Unit) {
        console.log("🔍 [LoginScene] 检查是否需要恢复会话...")
        val restored = appState.checkAndRestoreSession()
        if (restored) {
            console.log("✅ [LoginScene] 会话已恢复，跳转到群组列表")
        }
    }
    
    var isLogin by remember { mutableStateOf(true) }
    var loginName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
    // 设置全局样式，去掉浏览器滚动条
    Style {
        """
        html, body {
            height: 100%;
            margin: 0;
            padding: 0;
            overflow: hidden;
        }
        #root {
            height: 100%;
        }
        """.trimIndent()
    }
    
    Div({
        style {
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            height(100.vh)
            width(100.vw)
            property("background", SilkColors.backgroundGradient)
            property("overflow", "auto")
        }
    }) {
        Div({
            style {
                backgroundColor(Color(SilkColors.surfaceElevated))
                padding(48.px, 40.px)
                borderRadius(16.px)
                property("box-shadow", "0 8px 32px rgba(169, 137, 77, 0.15)")
                width(420.px)
                maxWidth(90.vw)
                property("border", "1px solid ${SilkColors.border}")
            }
        }) {
            // Logo
            Div({
                style {
                    textAlign("center")
                    marginBottom(12.px)
                }
            }) {
                Span({
                    style {
                        fontSize(42.px)
                        property("font-weight", "700")
                        color(Color(SilkColors.primary))
                        property("letter-spacing", "6px")
                        fontFamily("'Cormorant Garamond'", "Georgia", "serif")
                        property("text-transform", "uppercase")
                    }
                }) {
                    Text("Silk")
                }
            }
            
            // 副标题
            Div({
                style {
                    textAlign("center")
                    marginBottom(36.px)
                    fontSize(13.px)
                    color(Color(SilkColors.textLight))
                    property("letter-spacing", "3px")
                    property("font-style", "italic")
                }
            }) {
                Text("smooth & simple")
            }
            
            // 标题
            H2({
                style {
                    textAlign("center")
                    color(Color(SilkColors.textPrimary))
                    marginBottom(32.px)
                    fontSize(22.px)
                    property("font-weight", "600")
                    property("letter-spacing", "1px")
                }
            }) {
                Text(if (isLogin) "欢迎回来" else "创建账号")
            }
            
            // 登录名
            Div({ style { marginBottom(20.px) } }) {
                Label { 
                    Span({
                        style {
                            fontSize(13.px)
                            color(Color(SilkColors.textSecondary))
                            property("letter-spacing", "0.5px")
                        }
                    }) {
                        Text("登录名")
                    }
                }
                Input(InputType.Text) {
                    value(loginName)
                    onInput { loginName = it.value }
                    style {
                        width(100.percent)
                        padding(14.px)
                        fontSize(14.px)
                        marginTop(8.px)
                        border {
                            width(1.px)
                            style(LineStyle.Solid)
                            color(Color(SilkColors.border))
                        }
                        borderRadius(8.px)
                        property("box-sizing", "border-box")
                        property("background", SilkColors.surface)
                        property("color", SilkColors.textPrimary)
                        property("transition", "all 0.2s ease")
                        fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
                    }
                    if (!isLoading) {
                        attr("placeholder", "请输入登录名")
                    }
                }
            }
            
            // 密码
            Div({ style { marginBottom(20.px) } }) {
                Label { 
                    Span({
                        style {
                            fontSize(13.px)
                            color(Color(SilkColors.textSecondary))
                            property("letter-spacing", "0.5px")
                        }
                    }) {
                        Text("密码")
                    }
                }
                Input(InputType.Password) {
                    value(password)
                    onInput { password = it.value }
                    style {
                        width(100.percent)
                        padding(14.px)
                        fontSize(14.px)
                        marginTop(8.px)
                        border {
                            width(1.px)
                            style(LineStyle.Solid)
                            color(Color(SilkColors.border))
                        }
                        borderRadius(8.px)
                        property("box-sizing", "border-box")
                        property("background", SilkColors.surface)
                        property("color", SilkColors.textPrimary)
                        property("transition", "all 0.2s ease")
                        fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
                    }
                    if (!isLoading) {
                        attr("placeholder", "请输入密码")
                    }
                }
            }
            
            // 注册时的额外字段
            if (!isLogin) {
                Div({ style { marginBottom(20.px) } }) {
                    Label { 
                        Span({
                            style {
                                fontSize(13.px)
                                color(Color(SilkColors.textSecondary))
                                property("letter-spacing", "0.5px")
                            }
                        }) {
                            Text("姓名")
                        }
                    }
                    Input(InputType.Text) {
                        value(fullName)
                        onInput { fullName = it.value }
                        style {
                            width(100.percent)
                            padding(14.px)
                            fontSize(14.px)
                            marginTop(8.px)
                            border {
                                width(1.px)
                                style(LineStyle.Solid)
                                color(Color(SilkColors.border))
                            }
                            borderRadius(8.px)
                            property("box-sizing", "border-box")
                            property("background", SilkColors.surface)
                            property("color", SilkColors.textPrimary)
                            fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
                        }
                        attr("placeholder", "请输入姓名")
                    }
                }
                
                Div({ style { marginBottom(20.px) } }) {
                    Label { 
                        Span({
                            style {
                                fontSize(13.px)
                                color(Color(SilkColors.textSecondary))
                                property("letter-spacing", "0.5px")
                            }
                        }) {
                            Text("手机号")
                        }
                    }
                    Input(InputType.Text) {
                        value(phoneNumber)
                        onInput { phoneNumber = it.value }
                        style {
                            width(100.percent)
                            padding(14.px)
                            fontSize(14.px)
                            marginTop(8.px)
                            border {
                                width(1.px)
                                style(LineStyle.Solid)
                                color(Color(SilkColors.border))
                            }
                            borderRadius(8.px)
                            property("box-sizing", "border-box")
                            property("background", SilkColors.surface)
                            property("color", SilkColors.textPrimary)
                            fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
                        }
                        attr("placeholder", "请输入手机号")
                    }
                }
            }
            
            // 错误提示
            if (errorMessage.isNotEmpty()) {
                Div({
                    style {
                        color(Color(SilkColors.error))
                        fontSize(13.px)
                        marginBottom(20.px)
                        textAlign("center")
                        padding(12.px)
                        backgroundColor(Color("#FDF5F5"))
                        borderRadius(8.px)
                        property("border", "1px solid ${SilkColors.error}")
                    }
                }) {
                    Text(errorMessage)
                }
            }
            
            // 登录/注册按钮
            Button({
                style {
                    width(100.percent)
                    padding(14.px)
                    property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                    color(Color.white)
                    border { width(0.px) }
                    borderRadius(8.px)
                    fontSize(15.px)
                    property("font-weight", "600")
                    property("letter-spacing", "2px")
                    property("cursor", if (isLoading) "not-allowed" else "pointer")
                    property("opacity", if (isLoading) "0.7" else "1")
                    property("box-shadow", "0 4px 16px rgba(169, 137, 77, 0.3)")
                    property("transition", "all 0.2s ease")
                    fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
                }
                onClick {
                    if (!isLoading) {
                        scope.launch {
                            isLoading = true
                            errorMessage = ""
                            
                            try {
                                val response = if (isLogin) {
                                    ApiClient.login(loginName, password)
                                } else {
                                    ApiClient.register(loginName, fullName, phoneNumber, password)
                                }
                                
                                if (response.success && response.user != null) {
                                    console.log("${if (isLogin) "登录" else "注册"}成功:", response.user.fullName)
                                    appState.setUser(response.user)
                                } else {
                                    errorMessage = response.message
                                }
                            } catch (e: Exception) {
                                errorMessage = "操作失败: ${e.message}"
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                }
            }) {
                Text(if (isLoading) "处理中..." else if (isLogin) "登 录" else "注 册")
            }
            
            // 切换登录/注册
            Div({
                style {
                    textAlign("center")
                    marginTop(24.px)
                    fontSize(13.px)
                    color(Color(SilkColors.textSecondary))
                    property("cursor", "pointer")
                    property("letter-spacing", "0.5px")
                    property("transition", "color 0.2s ease")
                }
                onClick {
                    if (!isLoading) {
                        isLogin = !isLogin
                        errorMessage = ""
                    }
                }
            }) {
                Text(if (isLogin) "没有账号？点击注册" else "已有账号？点击登录")
            }
        }
    }
}
