package com.silk.web

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.renderComposable
import com.silk.shared.ChatClient
import com.silk.shared.ConnectionState
import com.silk.shared.models.Message
import com.silk.shared.models.MessageType
import com.silk.shared.models.UserSettings
import kotlinx.coroutines.launch
import kotlinx.browser.window
import kotlinx.browser.document
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// 时间格式化函数在文件后面定义

// 文件信息数据类
data class FileInfo(
    val name: String,
    val size: Long,
    val uploadTime: Long,
    val downloadUrl: String
)

// Silk 配色方案
object SilkColors {
    // 主色调 - 温暖的香槟金
    const val primary = "#C9A86C"
    const val primaryDark = "#A8894D"
    const val primaryLight = "#E0CDA0"
    
    // 次要色调 - 奶油丝绸
    const val secondary = "#E8D5B5"
    const val secondaryDark = "#D4C4A0"
    
    // 背景色 - 温暖的奶白色
    const val background = "#FDF8F0"
    const val backgroundGradient = "linear-gradient(135deg, #FDF8F0 0%, #F5EDE0 50%, #EDE4D3 100%)"
    const val surface = "#FFFBF5"
    const val surfaceElevated = "#FFFFFF"
    
    // 文字颜色
    const val textPrimary = "#4A4038"
    const val textSecondary = "#8A7B6A"
    const val textLight = "#B8A890"
    
    // 功能色
    const val success = "#7DAE6C"
    const val warning = "#E8B86C"
    const val error = "#D97B7B"
    const val info = "#7BA8C9"
    
    // 边框和分隔线
    const val border = "#E8E0D4"
    const val divider = "#F0E8DC"
}

fun main() {
    console.log("🧵 Silk 正在启动...")
    console.log("1️⃣ 准备渲染...")
    
    renderComposable(rootElementId = "root") {
        console.log("2️⃣ renderComposable 已调用")
        
        Style(SilkStylesheet)
        console.log("3️⃣ Silk样式已加载")
        
        SilkApp()
        console.log("4️⃣ 主应用组件已渲染")
    }
    
    console.log("✅ Silk 启动完成")
}

@Composable
fun SilkApp() {
    val appState = remember { WebAppState() }
    
    console.log("📍 当前场景:", appState.currentScene.toString())
    console.log("👤 当前用户:", appState.currentUser?.fullName ?: "未登录")
    console.log("👥 选中群组:", appState.selectedGroup?.name ?: "未选择")
    
    // 确保只渲染当前场景
    when (appState.currentScene) {
        Scene.LOGIN -> {
            console.log("🔐 [ONLY] 渲染登录场景")
            LoginScene(appState)
        }
        Scene.GROUP_LIST -> {
            console.log("📋 [ONLY] 渲染群组列表场景（不连接WebSocket）")
            GroupListScene(appState)
            // 确保不会渲染ChatScene
        }
        Scene.CONTACTS -> {
            console.log("👤 [ONLY] 渲染联系人场景")
            ContactsScene(appState)
        }
        Scene.CHAT_ROOM -> {
            console.log("💬 [ONLY] 渲染聊天室场景（将连接WebSocket）")
            // 只有在这个场景才渲染ChatScene
            if (appState.selectedGroup != null && appState.currentUser != null) {
                ChatScene(appState)
            } else {
                console.error("❌ CHAT_ROOM场景但缺少群组或用户")
                Div({ style { padding(20.px) } }) {
                    Text("状态错误，请返回重试")
                    Button({ onClick { appState.navigateBack() } }) {
                        Text("返回群组列表")
                    }
                }
            }
        }
        Scene.SETTINGS -> {
            console.log("⚙️ [ONLY] 渲染设置场景")
            SettingsScene(appState)
        }
    }
}

@Composable
fun ChatScene(appState: WebAppState) {
    console.log("🎬 ChatScene被调用")
    
    val group = appState.selectedGroup
    val user = appState.currentUser
    
    console.log("   群组:", group?.name ?: "null")
    console.log("   用户:", user?.fullName ?: "null")
    
    if (group == null || user == null) {
        console.log("⚠️ 群组或用户为空，显示错误页面")
        Div({ style { padding(20.px) } }) {
            Text("错误：缺少群组或用户信息")
            Button({ onClick { appState.navigateBack() } }) {
                Text("返回")
            }
        }
        return
    }
    
    console.log("✅ 群组和用户都有效，渲染聊天界面")
    // 使用原来的ChatApp逻辑，但传入用户和群组信息
    ChatAppWithGroup(user, group, appState)
}

// Silk样式表 - 丝滑温暖风格
object SilkStylesheet : StyleSheet() {
    val container by style {
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        height(100.vh)
        fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
        property("overflow", "hidden")
        property("background", SilkColors.backgroundGradient)
    }
    
    val header by style {
        property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
        color(Color.white)
        padding(16.px)
        fontSize(24.px)
        property("font-weight", "600")
        property("letter-spacing", "2px")
        property("box-shadow", "0 2px 12px rgba(169, 137, 77, 0.3)")
    }
    
    val statusBar by style {
        padding(8.px, 16.px)
        display(DisplayStyle.Flex)
        property("justify-content", "space-between")
        property("align-items", "center")
        property("font-size", "13px")
        property("letter-spacing", "1px")
    }
    
    val messagesContainer by style {
        property("flex", "1")
        property("overflow-y", "auto")
        padding(16.px)
        property("background", SilkColors.backgroundGradient)
    }
    
    val messageCard by style {
        backgroundColor(Color(SilkColors.surfaceElevated))
        borderRadius(12.px)
        padding(14.px, 16.px)
        marginBottom(10.px)
        property("box-shadow", "0 2px 8px rgba(169, 137, 77, 0.08)")
        property("border", "1px solid ${SilkColors.border}")
        property("transition", "all 0.2s ease")
    }
    
    val messageHeader by style {
        display(DisplayStyle.Flex)
        property("justify-content", "space-between")
        marginBottom(6.px)
    }
    
    val userName by style {
        property("font-weight", "600")
        color(Color(SilkColors.primary))
        property("letter-spacing", "0.5px")
    }
    
    val timestamp by style {
        fontSize(11.px)
        color(Color(SilkColors.textLight))
        property("font-style", "italic")
    }
    
    val systemMessage by style {
        fontSize(12.px)
        color(Color(SilkColors.textSecondary))
        property("text-align", "center")
        marginBottom(8.px)
        property("font-style", "italic")
    }
    
    val inputContainer by style {
        display(DisplayStyle.Flex)
        padding(16.px)
        backgroundColor(Color(SilkColors.surfaceElevated))
        property("border-top", "1px solid ${SilkColors.border}")
        property("gap", "10px")
        property("box-shadow", "0 -2px 12px rgba(169, 137, 77, 0.05)")
    }
    
    val input by style {
        property("flex", "1")
        padding(14.px)
        border {
            width(1.px)
            style(LineStyle.Solid)
            color(Color(SilkColors.border))
        }
        borderRadius(8.px)
        fontSize(14.px)
        fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
        property("background", SilkColors.surface)
        property("color", SilkColors.textPrimary)
        property("transition", "all 0.2s ease")
    }
    
    val button by style {
        padding(12.px, 24.px)
        property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
        color(Color.white)
        border { width(0.px) }
        borderRadius(8.px)
        property("cursor", "pointer")
        fontSize(14.px)
        property("font-weight", "600")
        property("letter-spacing", "1px")
        property("transition", "all 0.2s ease")
        property("box-shadow", "0 2px 8px rgba(169, 137, 77, 0.25)")
    }
    
    val buttonHover by style {
        property("background", "linear-gradient(135deg, ${SilkColors.primaryDark} 0%, #8A7040 100%)")
        property("transform", "translateY(-1px)")
        property("box-shadow", "0 4px 12px rgba(169, 137, 77, 0.35)")
    }
    
    // 临时消息样式 - 更柔和
    val transientMessageCard by style {
        backgroundColor(Color(SilkColors.secondary))
        borderRadius(12.px)
        padding(12.px, 14.px)
        marginBottom(10.px)
        property("opacity", "0.85")
        property("font-style", "italic")
        property("font-size", "13px")
        property("border-left", "3px solid ${SilkColors.warning}")
        property("box-shadow", "0 2px 6px rgba(169, 137, 77, 0.1)")
    }
    
    // 进度条样式 - 丝滑金色
    val progressBarContainer by style {
        marginTop(10.px)
        marginBottom(8.px)
    }
    
    val progressBar by style {
        width(100.percent)
        height(4.px)
        backgroundColor(Color(SilkColors.border))
        borderRadius(2.px)
        property("overflow", "hidden")
        property("position", "relative")
    }
    
    val progressFill by style {
        height(100.percent)
        property("background", "linear-gradient(90deg, ${SilkColors.primary}, ${SilkColors.primaryLight})")
        property("transition", "width 0.3s ease")
        property("box-shadow", "0 0 8px rgba(201, 168, 108, 0.5)")
    }
    
    // ==================== AI 消息卡片样式 ====================
    // AI 消息卡片 - 渐变背景
    val aiMessageCard by style {
        property("background", "linear-gradient(135deg, #F8FBFF 0%, #EEF4FF 50%, #E8F0FE 100%)")
        borderRadius(16.px)
        padding(16.px, 20.px)
        marginBottom(12.px)
        property("box-shadow", "0 4px 20px rgba(59, 130, 246, 0.15)")
        property("border", "1px solid rgba(59, 130, 246, 0.2)")
        property("position", "relative")
        property("overflow", "hidden")
    }
    
    // AI 头像区域
    val aiAvatar by style {
        width(36.px)
        height(36.px)
        property("background", "linear-gradient(135deg, #3B82F6 0%, #8B5CF6 100%)")
        borderRadius(50.percent)
        display(DisplayStyle.Flex)
        property("justify-content", "center")
        property("align-items", "center")
        property("font-size", "18px")
        property("flex-shrink", "0")
    }
    
    // AI 消息头部
    val aiMessageHeader by style {
        display(DisplayStyle.Flex)
        property("align-items", "center")
        property("gap", "10px")
        marginBottom(12.px)
    }
    
    // AI 标签
    val aiBadge by style {
        padding(4.px, 10.px)
        property("background", "linear-gradient(135deg, #3B82F6 0%, #8B5CF6 100%)")
        borderRadius(12.px)
        color(Color.white)
        fontSize(11.px)
        property("font-weight", "600")
        property("letter-spacing", "0.5px")
    }
    
    // AI 消息内容区域
    val aiMessageContent by style {
        property("line-height", "1.8")
        property("color", "#1E293B")
        property("font-size", "14px")
    }
    
    // Markdown 标题样式
    val markdownH1 by style {
        fontSize(20.px)
        property("font-weight", "700")
        color(Color("#1E293B"))
        marginTop(16.px)
        marginBottom(12.px)
        paddingBottom(8.px)
        property("border-bottom", "2px solid #E2E8F0")
    }
    
    val markdownH2 by style {
        fontSize(18.px)
        property("font-weight", "600")
        color(Color("#334155"))
        marginTop(14.px)
        marginBottom(10.px)
        property("border-left", "3px solid #3B82F6")
        paddingLeft(10.px)
    }
    
    val markdownH3 by style {
        fontSize(16.px)
        property("font-weight", "600")
        color(Color("#475569"))
        marginTop(12.px)
        marginBottom(8.px)
    }
    
    // Markdown 代码块
    val markdownCodeBlock by style {
        property("background", "#1E293B")
        color(Color("#E2E8F0"))
        padding(16.px)
        borderRadius(8.px)
        property("font-family", "'JetBrains Mono', 'Fira Code', monospace")
        fontSize(13.px)
        property("overflow-x", "auto")
        marginTop(10.px)
        marginBottom(10.px)
        property("line-height", "1.6")
    }
    
    // Markdown 行内代码
    val markdownInlineCode by style {
        property("background", "rgba(59, 130, 246, 0.1)")
        color(Color("#3B82F6"))
        padding(2.px, 6.px)
        borderRadius(4.px)
        property("font-family", "'JetBrains Mono', 'Fira Code', monospace")
        fontSize(13.px)
    }
    
    // Markdown 引用
    val markdownBlockquote by style {
        property("border-left", "4px solid #3B82F6")
        paddingLeft(16.px)
        marginLeft(0.px)
        property("background", "rgba(59, 130, 246, 0.05)")
        padding(12.px, 16.px)
        borderRadius(0.px, 8.px, 8.px, 0.px)
        marginTop(10.px)
        marginBottom(10.px)
        property("font-style", "italic")
        color(Color("#64748B"))
    }
    
    // Markdown 列表
    val markdownList by style {
        marginLeft(20.px)
        marginTop(8.px)
        marginBottom(8.px)
    }
    
    val markdownListItem by style {
        marginBottom(6.px)
        property("line-height", "1.6")
        property("position", "relative")
    }
    
    // Markdown 链接
    val markdownLink by style {
        color(Color("#3B82F6"))
        property("text-decoration", "none")
        property("border-bottom", "1px solid rgba(59, 130, 246, 0.3)")
        property("transition", "all 0.2s")
    }
    
    // Markdown 分割线
    val markdownHr by style {
        property("border", "none")
        height(1.px)
        property("background", "linear-gradient(90deg, transparent, #E2E8F0, transparent)")
        marginTop(16.px)
        marginBottom(16.px)
    }
    
    // Markdown 表格
    val markdownTable by style {
        width(100.percent)
        property("border-collapse", "collapse")
        marginTop(10.px)
        marginBottom(10.px)
        property("font-size", "13px")
    }
    
    val markdownTableHeader by style {
        property("background", "rgba(59, 130, 246, 0.1)")
        property("font-weight", "600")
        padding(10.px)
        property("text-align", "left")
        property("border-bottom", "2px solid #E2E8F0")
    }
    
    val markdownTableCell by style {
        padding(10.px)
        property("border-bottom", "1px solid #E2E8F0")
    }
    
    // Markdown 加粗
    val markdownBold by style {
        property("font-weight", "700")
        color(Color("#1E293B"))
    }
    
    // Markdown 斜体
    val markdownItalic by style {
        property("font-style", "italic")
        color(Color("#64748B"))
    }
}

@Composable
fun ChatAppWithGroup(user: User, group: Group, appState: WebAppState) {
    console.log("🎯 ChatAppWithGroup - 用户:", user.fullName, "群组:", group.name)
    
    val scope = rememberCoroutineScope()
    
    // Language and strings
    var userLanguage by remember { mutableStateOf<com.silk.shared.models.Language>(com.silk.shared.models.Language.CHINESE) }
    val strings = com.silk.shared.i18n.getStrings(userLanguage)
    
    // Load user language preference
    // Reload when user changes OR when navigating to chat scene
    LaunchedEffect(user.id, appState.currentScene) {
        if (appState.currentScene == Scene.CHAT_ROOM) {
            scope.launch {
                try {
                    val response = ApiClient.getUserSettings(user.id)
                    if (response.success && response.settings != null) {
                        userLanguage = response.settings!!.language
                    }
                } catch (e: Exception) {
                    console.error("Failed to load user settings:", e)
                }
            }
        }
    }
    
    // 动态生成 WebSocket URL，根据当前页面的协议和主机
    val wsUrl = remember {
        val protocol = if (window.location.protocol == "https:") "wss:" else "ws:"
        val host = window.location.hostname
        val url = "$protocol//$host:${BuildConfig.BACKEND_HTTP_PORT}"
        console.log("🔌 WebSocket URL: $url")
        url
    }
    
    val chatClient = remember { ChatClient(wsUrl) }
    val messages by chatClient.messages.collectAsState()
    val transientMessage by chatClient.transientMessage.collectAsState()
    val statusMessages by chatClient.statusMessages.collectAsState()
    val connectionState by chatClient.connectionState.collectAsState()
    val hasStartedFinalAnswer = transientMessage?.let { msg ->
        msg.content.isNotBlank() &&
            msg.currentStep == null &&
            msg.totalSteps == null &&
            !isLikelyAgentStatusContent(msg.content)
    } == true
    
    // Track if we've sent the default instruction for this session
    var hasSentDefaultInstruction by remember { mutableStateOf(false) }
    
    var messageText by remember { mutableStateOf("") }
    var showInvitationDialog by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var showFolderExplorer by remember { mutableStateOf(false) }
    var folderFiles by remember { mutableStateOf<List<FileInfo>>(emptyList()) }
    var isLoadingFiles by remember { mutableStateOf(false) }
    
    // Drag-and-drop state
    var isDraggingOver by remember { mutableStateOf(false) }
    
    // 添加成员到群组相关状态
    var showAddMemberDialog by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var groupMembers by remember { mutableStateOf<List<GroupMember>>(emptyList()) }
    var isLoadingContacts by remember { mutableStateOf(false) }
    var addMemberResult by remember { mutableStateOf<String?>(null) }
    
    // 查看成员列表相关状态
    var showMembersDialog by remember { mutableStateOf(false) }
    var selectedMemberForInvite by remember { mutableStateOf<GroupMember?>(null) }
    var isInvitingMember by remember { mutableStateOf(false) }
    var inviteMemberResult by remember { mutableStateOf<String?>(null) }
    
    // @ mention 功能状态
    var showMentionMenu by remember { mutableStateOf(false) }
    var mentionSearchText by remember { mutableStateOf("") }
    var mentionStartIndex by remember { mutableStateOf(-1) }
    var mentionMenuPosition by remember { mutableStateOf(Pair(0.0, 0.0)) } // (left, bottom)
    
    // 消息撤回相关状态：正在撤回中的消息ID集合，防止重复点击
    var recallingMessageIds by remember { mutableStateOf(setOf<String>()) }
    
    // 消息转发相关状态
    var showForwardDialog by remember { mutableStateOf(false) }
    var messageToForward by remember { mutableStateOf<Message?>(null) }
    var userGroups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var isLoadingGroups by remember { mutableStateOf(false) }
    var forwardResult by remember { mutableStateOf<String?>(null) }
    
    // 从群组成员列表和消息历史中提取用户列表（去重）
    // 优先使用 groupMembers（包含所有成员），然后补充消息历史中的成员
    val sessionUsers = remember(groupMembers, messages) {
        val users = mutableSetOf<Pair<String, String>>() // (id, name)
        // 始终添加 Silk AI
        users.add("silk_ai_agent" to "🤖 Silk")
        // 添加群组成员列表中的所有成员
        groupMembers.forEach { member ->
            users.add(member.id to member.fullName)
        }
        // 添加当前用户（以防万一）
        users.add(user.id to user.fullName)
        // 从消息中提取其他用户（补充可能不在成员列表中的用户，如已退群的用户）
        messages.forEach { msg ->
            if (msg.userId != "silk_ai_agent" && msg.userId != user.id) {
                users.add(msg.userId to msg.userName)
            }
        }
        users.toList()
    }
    
    LaunchedEffect(group.id) {
        console.log("🔌 准备建立WebSocket连接...")
        console.log("   用户ID:", user.id)
        console.log("   用户名:", user.fullName)
        console.log("   群组ID:", group.id)
        console.log("   群组名:", group.name)
        
        // Reset flag when group changes
        hasSentDefaultInstruction = false
        
        // 加载群成员列表（用于 @ mention 功能）
        try {
            val membersResponse = ApiClient.getGroupMembers(group.id)
            groupMembers = membersResponse.members.sortedByDescending { it.id == group.hostId }
            console.log("✅ 群成员列表已加载，共 ${groupMembers.size} 人")
        } catch (e: dynamic) {
            console.error("❌ 加载群成员列表失败:", e.toString())
        }
        
        // 延迟1秒，确保页面已完全渲染
        kotlinx.coroutines.delay(1000)
        
        try {
            console.log("🔌 开始连接WebSocket...")
            chatClient.connect(user.id, user.fullName, group.id)
            console.log("✅ WebSocket连接成功")
        } catch (e: dynamic) {
            console.error("❌ WebSocket连接失败")
            console.error("   错误:", e.toString())
            console.error("   可能原因: 后端未运行或网络问题")
            console.error("   建议: 检查后端是否在${BuildConfig.BACKEND_HTTP_PORT}端口运行")
            // 不再抛出异常，静默失败
        }
    }
    
    DisposableEffect(group.id) {
        onDispose {
            console.log("🧹 组件清理 - 确保WebSocket已断开")
            // 使用try-catch包装整个清理逻辑
            try {
                scope.launch {
                    try {
                        chatClient.disconnect()
                        console.log("✅ 清理：WebSocket已断开")
                    } catch (e: dynamic) {
                        // 完全静默，可能已经在返回按钮中断开了
                    }
                    
                    // 等待服务器处理完成
                    kotlinx.coroutines.delay(200)
                    
                    // 最后标记已读，确保时间戳晚于所有消息
                    try {
                        ApiClient.markGroupAsRead(user.id, group.id)
                        console.log("✅ 清理：已标记群组为已读")
                    } catch (e: dynamic) {
                        // 静默处理
                    }
                }
            } catch (e: dynamic) {
                // 完全忽略所有错误
            }
        }
    }
    
    // 自动滚动到底部
    LaunchedEffect(messages.size, transientMessage, statusMessages.size) {
        js("""
            setTimeout(function() {
                var messagesContainer = document.getElementById('messages');
                if (messagesContainer) {
                    messagesContainer.scrollTop = messagesContainer.scrollHeight;
                }
            }, 100);
        """)
    }
    
    Div({ classes(SilkStylesheet.container) }) {
        // Header - 丝滑风格
        Div({ 
            classes(SilkStylesheet.header)
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                property("gap", "12px")
            }
        }) {
            // 返回按钮
            Button({
                style {
                    padding(10.px, 14.px)
                    backgroundColor(Color("rgba(255,255,255,0.15)"))
                    color(Color.white)
                    border { width(0.px) }
                    borderRadius(8.px)
                    property("cursor", "pointer")
                    fontSize(18.px)
                    property("backdrop-filter", "blur(4px)")
                    property("transition", "all 0.2s ease")
                }
                onClick { 
                    console.log("👈 用户点击返回按钮")
                    scope.launch {
                        // 1. 先断开WebSocket连接
                        try {
                            console.log("🔌 正在断开WebSocket...")
                            chatClient.disconnect()
                            console.log("✅ WebSocket已断开")
                        } catch (e: dynamic) {
                            console.log("ℹ️ WebSocket断开（忽略错误）")
                        }
                        
                        // 2. 等待服务器完成所有消息处理
                        kotlinx.coroutines.delay(300)
                        
                        // 3. 最后标记已读 - 在断开连接之后调用
                        // 这样可以确保标记时间晚于用户发送的所有消息
                        try {
                            ApiClient.markGroupAsRead(user.id, group.id)
                            console.log("✅ 已标记群组为已读")
                        } catch (e: dynamic) {
                            console.log("⚠️ 标记已读失败")
                        }
                        
                        // 4. 返回到群组列表
                        console.log("📋 返回到群组列表")
                        appState.navigateBack()
                    }
                }
            }) {
                Text("←")
            }
            
            Div({ 
                style { 
                    property("flex", "1") 
                    property("letter-spacing", "2px")
                } 
            }) {
                Text(group.name)
            }
            
            // 右侧按钮组
            Div({
                style {
                    display(DisplayStyle.Flex)
                    property("gap", "10px")
                    alignItems(AlignItems.Center)
                }
            }) {
                // 📁 文件夹按钮 - 查看session文件
                Button({
                    style {
                        padding(10.px, 14.px)
                        backgroundColor(Color("rgba(255,255,255,0.2)"))
                        color(Color.white)
                        border { width(0.px) }
                        borderRadius(8.px)
                        property("cursor", "pointer")
                        fontSize(18.px)
                        property("backdrop-filter", "blur(4px)")
                        property("transition", "all 0.2s ease")
                    }
                    onClick {
                        showFolderExplorer = true
                        isLoadingFiles = true
                        // FolderExplorerDialog 会自动加载文件列表
                    }
                }) {
                    Text("📁")
                }
                
                // 邀请按钮
                Button({
                    style {
                        padding(10.px, 14.px)
                        backgroundColor(Color("rgba(255,255,255,0.15)"))
                        color(Color.white)
                        border { width(0.px) }
                        borderRadius(8.px)
                        property("cursor", "pointer")
                        fontSize(16.px)
                        property("backdrop-filter", "blur(4px)")
                        property("transition", "all 0.2s ease")
                    }
                    onClick { showInvitationDialog = true }
                }) {
                    Text(strings.inviteButton)
                }
                
                // ➕ 添加成员按钮
                Button({
                    style {
                        padding(10.px, 14.px)
                        backgroundColor(Color("rgba(255,255,255,0.2)"))
                        color(Color.white)
                        border { width(0.px) }
                        borderRadius(8.px)
                        property("cursor", "pointer")
                        fontSize(16.px)
                        property("backdrop-filter", "blur(4px)")
                        property("transition", "all 0.2s ease")
                    }
                    onClick {
                        // 加载联系人和群组成员
                        scope.launch {
                            isLoadingContacts = true
                            val contactsResponse = ApiClient.getContacts(user.id)
                            contacts = contactsResponse.contacts ?: emptyList()
                            val membersResponse = ApiClient.getGroupMembers(group.id)
                            // 将群主排在第一位
                            groupMembers = membersResponse.members.sortedByDescending { it.id == group.hostId }
                            isLoadingContacts = false
                            showAddMemberDialog = true
                        }
                    }
                }) {
                    Text("➕")
                }
                
                // 👥 查看成员按钮
                Button({
                    style {
                        padding(10.px, 14.px)
                        backgroundColor(Color("rgba(255,255,255,0.15)"))
                        color(Color.white)
                        border { width(0.px) }
                        borderRadius(8.px)
                        property("cursor", "pointer")
                        fontSize(14.px)
                        property("backdrop-filter", "blur(4px)")
                        property("transition", "all 0.2s ease")
                    }
                    onClick {
                        // 加载联系人和群组成员
                        scope.launch {
                            isLoadingContacts = true
                            val contactsResponse = ApiClient.getContacts(user.id)
                            contacts = contactsResponse.contacts ?: emptyList()
                            val membersResponse = ApiClient.getGroupMembers(group.id)
                            // 将群主排在第一位
                            groupMembers = membersResponse.members.sortedByDescending { it.id == group.hostId }
                            isLoadingContacts = false
                            showMembersDialog = true
                        }
                    }
                }) {
                    Text(strings.membersButton)
                }
            }
        }
        
        // Status Bar - 丝滑渐变状态
        Div({ 
            classes(SilkStylesheet.statusBar)
            style {
                property("background", when (connectionState) {
                    ConnectionState.CONNECTED -> "linear-gradient(90deg, ${SilkColors.success}, #8DBE7C)"
                    ConnectionState.CONNECTING -> "linear-gradient(90deg, ${SilkColors.warning}, #ECC88C)"
                    ConnectionState.DISCONNECTED -> "linear-gradient(90deg, ${SilkColors.error}, #E99B9B)"
                })
                color(Color.white)
            }
        }) {
            Span {
                Text(when (connectionState) {
                    ConnectionState.CONNECTED -> "✓ ${strings.connected}"
                    ConnectionState.CONNECTING -> "⟳ ${strings.connecting}"
                    ConnectionState.DISCONNECTED -> "✗ ${strings.disconnected}"
                })
            }
            
            if (connectionState == ConnectionState.DISCONNECTED) {
                Button({
                    classes(SilkStylesheet.button)
                    style { 
                        padding(8.px, 16.px)
                        fontSize(12.px)
                    }
                    onClick {
                        scope.launch {
                            chatClient.connect(user.id, user.fullName, group.id)
                        }
                    }
                }) {
                    Text(strings.reconnecting)
                }
            }
        }
        
        // Messages container with drag-and-drop support
        Div({ 
            classes(SilkStylesheet.messagesContainer)
            id("messages")
            style {
                property("position", "relative")
                property("transition", "all 0.2s ease")
            }
        }) {
            // 显示系统状态消息（灰色）- 放在消息列表前，避免遮挡最终回复
            if (statusMessages.isNotEmpty() && !hasStartedFinalAnswer) {
                Div({
                    style {
                        backgroundColor(Color("#F5F5F5"))
                        borderRadius(8.px)
                        padding(10.px, 14.px)
                        marginBottom(8.px)
                        property("border-left", "3px solid #9E9E9E")
                    }
                }) {
                    statusMessages.forEach { status ->
                        Div({
                            style {
                                color(Color("#757575"))
                                fontSize(13.px)
                                fontStyle("italic")
                                marginBottom(4.px)
                                display(DisplayStyle.Flex)
                                alignItems(AlignItems.Center)
                                property("gap", "8px")
                            }
                        }) {
                            Text(status.content)
                        }
                    }
                }
            }

            // 显示所有普通消息
            messages.forEach { message ->
                MessageItem(
                    message = message,
                    isTransient = false,
                    currentUserId = user.id,
                    groupId = group.id,
                    isRecalling = message.id in recallingMessageIds,
                    onRecall = { messageId ->
                        if (messageId !in recallingMessageIds) {
                            recallingMessageIds = recallingMessageIds + messageId
                            scope.launch {
                                try {
                                    val response = ApiClient.recallMessage(group.id, messageId, user.id)
                                    if (!response.success) {
                                        window.alert("撤回失败: ${response.message}")
                                    }
                                } catch (e: Exception) {
                                    console.error("❌ 撤回消息失败:", e)
                                    window.alert("撤回失败: ${e.message}")
                                } finally {
                                    recallingMessageIds = recallingMessageIds - messageId
                                }
                            }
                        }
                    },
                    onCopy = { content ->
                        copyTextToClipboard(content)
                        console.log("✅ 消息已复制到剪贴板")
                    },
                    onForward = { msg ->
                        // 设置转发消息并显示转发对话框
                        messageToForward = msg
                        scope.launch {
                            isLoadingGroups = true
                            val response = ApiClient.getUserGroups(user.id)
                            userGroups = response.groups?.filter { it.id != group.id } ?: emptyList()
                            isLoadingGroups = false
                            showForwardDialog = true
                        }
                    }
                )
            }

            // 显示临时消息（如果有）
            transientMessage?.let { message ->
                if (
                    message.content.isNotBlank() &&
                    message.currentStep == null &&
                    message.totalSteps == null &&
                    !isLikelyAgentStatusContent(message.content)
                ) {
                    // 进入最终答案流阶段后，强制按 AI 正文样式渲染（即使后端 category 仍是 AGENT_STATUS）
                    MessageItem(
                        message = message.copy(category = com.silk.shared.models.MessageCategory.NORMAL),
                        isTransient = true,
                        currentUserId = user.id,
                        groupId = group.id
                    )
                } else {
                    // 处理中阶段（含工具调用与步骤）按临时状态样式显示
                    TransientMessageItem(message)
                }
            }
        }
        
        // Drag-and-drop event handlers - directly manipulate DOM for immediate visual feedback
        DisposableEffect(group.id) {
            val sessionId = group.id
            val userId = user.id
            val protocol = window.location.protocol
            val host = window.location.hostname
            val uploadUrl = "$protocol//$host:${BuildConfig.BACKEND_HTTP_PORT}/api/files/upload"
            val primaryColor = SilkColors.primary
            
            // Store values in window for JavaScript to access
            window.asDynamic().tempDragDropSessionId = sessionId
            window.asDynamic().tempDragDropUserId = userId
            window.asDynamic().tempDragDropUploadUrl = uploadUrl
            window.asDynamic().tempDragDropPrimaryColor = primaryColor
            
            js("""
                setTimeout(function() {
                    var container = document.getElementById('messages');
                    if (!container) {
                        console.error('❌ Drag-and-drop: messages container not found');
                        return;
                    }
                    
                    console.log('✅ Drag-and-drop: messages container found');
                    
                    // Clean up existing handlers if any
                    if (container._dragHandlers) {
                        container.removeEventListener('dragenter', container._dragHandlers.dragenter);
                        container.removeEventListener('dragover', container._dragHandlers.dragover);
                        container.removeEventListener('dragleave', container._dragHandlers.dragleave);
                        container.removeEventListener('drop', container._dragHandlers.drop);
                        if (container._dragHandlers.overlay && container._dragHandlers.overlay.parentNode) {
                            container._dragHandlers.overlay.parentNode.removeChild(container._dragHandlers.overlay);
                        }
                        delete container._dragHandlers;
                    }
                    
                    var sessionId = window.tempDragDropSessionId;
                    var userId = window.tempDragDropUserId;
                    var uploadUrl = window.tempDragDropUploadUrl;
                    var primaryColor = window.tempDragDropPrimaryColor;
                    
                    // Create overlay element for drag feedback
                    var overlay = document.createElement('div');
                    overlay.id = 'drag-drop-overlay';
                    overlay.style.cssText = 'position: absolute; top: 0; left: 0; right: 0; bottom: 0; ' +
                        'background: rgba(201, 168, 108, 0.1); display: none; ' +
                        'align-items: center; justify-content: center; z-index: 100; pointer-events: none; ' +
                        'border-radius: 8px;';
                    
                    var overlayContent = document.createElement('div');
                    overlayContent.style.cssText = 'background: #FFFFFF; padding: 32px 48px; ' +
                        'border-radius: 16px; box-shadow: 0 8px 32px rgba(169, 137, 77, 0.3); ' +
                        'border: 2px solid ' + primaryColor + '; text-align: center;';
                    
                    overlayContent.innerHTML = '<div style="font-size: 48px; margin-bottom: 16px;">📎</div>' +
                        '<div style="font-size: 18px; color: ' + primaryColor + '; font-weight: 600; margin-bottom: 8px;">拖放文件到此区域上传</div>' +
                        '<div style="font-size: 14px; color: #8A7B6A;">释放文件即可上传</div>';
                    
                    overlay.appendChild(overlayContent);
                    container.appendChild(overlay);
                    
                    var dragEnterCount = 0;
                    
                    var handleDragEnter = function(event) {
                        event.preventDefault();
                        event.stopPropagation();
                        dragEnterCount++;
                        console.log('📎 Drag enter, count:', dragEnterCount);
                        container.style.border = '3px dashed ' + primaryColor;
                        container.style.background = 'linear-gradient(135deg, rgba(224, 205, 160, 0.4) 0%, rgba(232, 213, 181, 0.4) 100%)';
                        container.style.boxShadow = 'inset 0 0 20px rgba(224, 205, 160, 0.6)';
                        overlay.style.display = 'flex';
                        overlay.style.alignItems = 'center';
                        overlay.style.justifyContent = 'center';
                    };
                    
                    var handleDragOver = function(event) {
                        event.preventDefault();
                        event.stopPropagation();
                        if (event.dataTransfer) {
                            event.dataTransfer.dropEffect = 'copy';
                        }
                    };
                    
                    var handleDragLeave = function(event) {
                        event.preventDefault();
                        event.stopPropagation();
                        dragEnterCount--;
                        if (dragEnterCount <= 0) {
                            dragEnterCount = 0;
                            container.style.border = '';
                            container.style.background = '';
                            container.style.boxShadow = '';
                            overlay.style.display = 'none';
                        }
                    };
                    
                    var handleDrop = function(event) {
                        event.preventDefault();
                        event.stopPropagation();
                        dragEnterCount = 0;
                        container.style.border = '';
                        container.style.background = '';
                        container.style.boxShadow = '';
                        overlay.style.display = 'none';
                        
                        var dataTransfer = event.dataTransfer;
                        if (!dataTransfer || !dataTransfer.files || dataTransfer.files.length === 0) {
                            return;
                        }
                        
                        var file = dataTransfer.files[0];
                        console.log('📁 拖放文件: ' + file.name + ', 大小: ' + file.size);
                        
                        var formData = new FormData();
                        formData.append('sessionId', sessionId);
                        formData.append('userId', userId);
                        formData.append('file', file);
                        
                        var xhr = new XMLHttpRequest();
                        xhr.open('POST', uploadUrl, true);
                        
                        xhr.onload = function() {
                            if (xhr.status === 200) {
                                var response = JSON.parse(xhr.responseText);
                                console.log('✅ 上传成功: ' + response.fileName);
                                window.alert('文件上传成功: ' + response.fileName);
                            } else {
                                console.log('❌ 上传失败: ' + xhr.statusText);
                                window.alert('文件上传失败: ' + xhr.statusText);
                            }
                        };
                        
                        xhr.onerror = function() {
                            console.log('❌ 上传错误');
                            window.alert('文件上传失败，请检查网络连接');
                        };
                        
                        xhr.send(formData);
                    };
                    
                    container.addEventListener('dragenter', handleDragEnter);
                    container.addEventListener('dragover', handleDragOver);
                    container.addEventListener('dragleave', handleDragLeave);
                    container.addEventListener('drop', handleDrop);
                    
                    // Store handlers for cleanup
                    container._dragHandlers = {
                        dragenter: handleDragEnter,
                        dragover: handleDragOver,
                        dragleave: handleDragLeave,
                        drop: handleDrop,
                        overlay: overlay
                    };
                    console.log('✅ Drag-and-drop: handlers attached');
                }, 200);
            """)
            
            onDispose {
                js("""
                    (function() {
                        var container = document.getElementById('messages');
                        if (container && container._dragHandlers) {
                            container.removeEventListener('dragenter', container._dragHandlers.dragenter);
                            container.removeEventListener('dragover', container._dragHandlers.dragover);
                            container.removeEventListener('dragleave', container._dragHandlers.dragleave);
                            container.removeEventListener('drop', container._dragHandlers.drop);
                            if (container._dragHandlers.overlay && container._dragHandlers.overlay.parentNode) {
                                container._dragHandlers.overlay.parentNode.removeChild(container._dragHandlers.overlay);
                            }
                            delete container._dragHandlers;
                        }
                    })();
                """)
                window.asDynamic().tempDragDropSessionId = undefined
                window.asDynamic().tempDragDropUserId = undefined
                window.asDynamic().tempDragDropUploadUrl = undefined
                window.asDynamic().tempDragDropPrimaryColor = undefined
            }
        }
        
        // Input区域（添加诊断按钮）- 丝滑风格
        if (connectionState == ConnectionState.CONNECTED) {
            Div({ 
                classes(SilkStylesheet.inputContainer)
                style {
                    display(DisplayStyle.Flex)
                    property("flex-direction", "column")
                    property("gap", "12px")
                }
            }) {
                // @Silk 快捷按钮（在 Silk 私聊中隐藏）
                val isSilkPrivateChat = group.name.startsWith("[Silk]")
                if (!isSilkPrivateChat) {
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        property("justify-content", "flex-start")
                        property("gap", "8px")
                        alignItems(AlignItems.Center)
                    }
                }) {
                    Button({
                        style {
                            padding(6.px, 12.px)
                            backgroundColor(Color("rgba(201, 168, 108, 0.15)"))
                            color(Color(SilkColors.primary))
                            border {
                                width(1.px)
                                style(LineStyle.Solid)
                                color(Color(SilkColors.primary))
                            }
                            borderRadius(16.px)
                            property("cursor", "pointer")
                            fontSize(13.px)
                            property("font-weight", "500")
                            property("transition", "all 0.2s ease")
                            property("white-space", "nowrap")
                        }
                        onClick {
                            // 在输入框中插入 @Silk
                            val input = document.getElementById("chat-input") as? org.w3c.dom.HTMLTextAreaElement
                            if (input != null) {
                                val currentText = messageText
                                val cursorPos = input.selectionStart ?: currentText.length
                                val beforeCursor = currentText.substring(0, cursorPos)
                                val afterCursor = currentText.substring(cursorPos)
                                messageText = "$beforeCursor@Silk $afterCursor"
                                // 移动光标到插入文本之后
                                window.setTimeout({
                                    val newPos = cursorPos + 6 // "@Silk " 的长度
                                    input.setSelectionRange(newPos, newPos)
                                    input.focus()
                                }, 0)
                            } else {
                                // 如果无法获取输入框，直接追加
                                messageText = if (messageText.isEmpty() || messageText.endsWith(" ")) {
                                    "${messageText}@Silk "
                                } else {
                                    "${messageText} @Silk "
                                }
                            }
                        }
                    }) {
                        Text("@Silk")
                    }
                }
                }
                
                // 第一行：输入框占据整行
                // 发送消息的函数
                val sendMessage: () -> Unit = {
                    if (messageText.isNotBlank()) {
                        val msg = messageText
                        messageText = ""
                        scope.launch {
                            chatClient.sendMessage(user.id, user.fullName, msg)
                        }
                    }
                }
                
                // 输入框容器（用于定位 mention 菜单）
                Div({
                    style {
                        property("position", "relative")
                        width(100.percent)
                    }
                }) {
                    TextArea {
                        classes(SilkStylesheet.input)
                        value(messageText)
                        onInput { event ->
                            val newValue = event.value
                            val oldValue = messageText
                            messageText = newValue
                            
                            // 检测 @ 符号
                            if (newValue.length > oldValue.length) {
                                val lastChar = newValue.lastOrNull()
                                if (lastChar == '@') {
                                    // 计算输入框位置用于 fixed 定位菜单
                                    val input = document.getElementById("chat-input") as? org.w3c.dom.HTMLElement
                                    if (input != null) {
                                        val rect = input.getBoundingClientRect()
                                        mentionMenuPosition = Pair(rect.left, window.innerHeight - rect.top + 4)
                                    }
                                    showMentionMenu = true
                                    mentionStartIndex = newValue.length - 1
                                    mentionSearchText = ""
                                }
                            }
                            
                            // 如果在 mention 模式，更新搜索文本
                            if (showMentionMenu && mentionStartIndex >= 0) {
                                val textAfterAt = newValue.substring(mentionStartIndex + 1)
                                val spaceIndex = textAfterAt.indexOf(' ')
                                if (spaceIndex >= 0) {
                                    // 用户输入了空格，关闭菜单
                                    showMentionMenu = false
                                } else {
                                    mentionSearchText = textAfterAt
                                }
                            }
                        }
                        attr("placeholder", if (group.name.startsWith("[Silk]")) strings.silkChatInputPlaceholder else strings.messageInputPlaceholder)
                        attr("rows", "2")
                        attr("id", "chat-input")
                        style {
                            width(100.percent)
                            property("box-sizing", "border-box")
                            property("resize", "none")
                        }
                    }
                    
                    // @ Mention 下拉菜单 - 使用 fixed 定位避免被 overflow:hidden 裁剪
                    if (showMentionMenu) {
                        Div({
                            style {
                                property("position", "fixed")
                                property("left", "${mentionMenuPosition.first}px")
                                property("bottom", "${mentionMenuPosition.second}px")
                                backgroundColor(Color(SilkColors.surface))
                                border {
                                    width(1.px)
                                    style(LineStyle.Solid)
                                    color(Color(SilkColors.border))
                                }
                                borderRadius(8.px)
                                property("box-shadow", "0 4px 12px rgba(0,0,0,0.15)")
                                property("z-index", "9999")
                                property("max-height", "200px")
                                property("overflow-y", "auto")
                                property("min-width", "200px")
                            }
                        }) {
                            // 过滤用户列表
                            val filteredUsers = sessionUsers.filter { (_, name) ->
                                mentionSearchText.isEmpty() || 
                                name.lowercase().contains(mentionSearchText.lowercase())
                            }
                            
                            if (filteredUsers.isEmpty()) {
                                Div({
                                    style {
                                        padding(12.px, 16.px)
                                        color(Color(SilkColors.textSecondary))
                                        fontSize(14.px)
                                    }
                                }) {
                                    Text(strings.noMatchingUsers)
                                }
                            } else {
                                filteredUsers.forEach { (userId, userName) ->
                                    Div({
                                        style {
                                            padding(10.px, 16.px)
                                            property("cursor", "pointer")
                                            property("transition", "background-color 0.15s ease")
                                        }
                                        onClick {
                                            // 插入 @用户名
                                            val beforeAt = messageText.substring(0, mentionStartIndex)
                                            val displayName = if (userId == "silk_ai_agent") "Silk" else userName
                                            messageText = "$beforeAt@$displayName "
                                            showMentionMenu = false
                                            mentionStartIndex = -1
                                            
                                            // 聚焦输入框 (使用 window.setTimeout 确保在下一个事件循环执行)
                                            window.setTimeout({
                                                val input = document.getElementById("chat-input")
                                                input?.asDynamic()?.focus()
                                            }, 0)
                                        }
                                        onMouseEnter {
                                            (it.target as? org.w3c.dom.HTMLElement)?.style?.backgroundColor = SilkColors.secondary
                                        }
                                        onMouseLeave {
                                            (it.target as? org.w3c.dom.HTMLElement)?.style?.backgroundColor = "transparent"
                                        }
                                    }) {
                                        Span({
                                            style {
                                                fontSize(14.px)
                                                color(Color(SilkColors.textPrimary))
                                                if (userId == "silk_ai_agent") {
                                                    property("font-weight", "600")
                                                }
                                            }
                                        }) {
                                            Text(userName)
                                        }
                                        if (userId == "silk_ai_agent") {
                                            Span({
                                                style {
                                                    fontSize(12.px)
                                                    color(Color(SilkColors.textSecondary))
                                                    marginLeft(8.px)
                                                }
                                            }) {
                                                Text("(设置AI角色)")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                // 添加键盘事件监听
                DisposableEffect(Unit) {
                    val handler: (dynamic) -> Unit = { event: dynamic ->
                        val key = event.key as? String
                        val shiftKey = event.shiftKey as? Boolean ?: false
                        
                        if (key == "Enter" && !shiftKey) {
                            event.preventDefault()
                            sendMessage()
                        }
                    }
                    
                    val input = js("document.getElementById('chat-input')")
                    input?.addEventListener("keydown", handler)
                    
                    onDispose {
                        input?.removeEventListener("keydown", handler)
                    }
                }
                
                // 第二行：按钮组靠右对齐
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        property("justify-content", "flex-end")
                        property("gap", "10px")
                        alignItems(AlignItems.Center)
                    }
                }) {
                    // 📁 上传目录按钮
                    Button({
                        style {
                            padding(12.px, 14.px)
                            backgroundColor(Color(SilkColors.secondary))
                            color(Color(SilkColors.textPrimary))
                            border { width(0.px) }
                            borderRadius(8.px)
                            property("cursor", if (isUploading) "not-allowed" else "pointer")
                            fontSize(18.px)
                            property("transition", "all 0.2s ease")
                            property("opacity", if (isUploading) "0.6" else "1")
                        }
                        attr("title", "上传整个目录")
                        onClick {
                            if (!isUploading) {
                                js("""
                                    var input = document.getElementById('folder-upload-input');
                                    if (input) input.click();
                                """)
                            }
                        }
                    }) {
                        Text(if (isUploading) "⏳" else "📁")
                    }
                    
                    // 📎 上传单文件按钮
                    Button({
                        style {
                            padding(12.px, 14.px)
                            backgroundColor(Color(SilkColors.secondary))
                            color(Color(SilkColors.textPrimary))
                            border { width(0.px) }
                            borderRadius(8.px)
                            property("cursor", if (isUploading) "not-allowed" else "pointer")
                            fontSize(18.px)
                            property("transition", "all 0.2s ease")
                            property("opacity", if (isUploading) "0.6" else "1")
                        }
                        attr("title", "上传单个文件")
                        onClick {
                            if (!isUploading) {
                                js("""
                                    var input = document.getElementById('file-upload-input');
                                    if (input) input.click();
                                """)
                            }
                        }
                    }) {
                        Text(if (isUploading) "⏳" else "📎")
                    }
                    
                    // 发送按钮
                    Button({
                        classes(SilkStylesheet.button)
                        onClick { sendMessage() }
                    }) {
                        Text(strings.sendButton)
                    }
                }
            }
        }
    }
    
    // 转发对话框
    if (showForwardDialog && messageToForward != null) {
        Div({
            style {
                position(Position.Fixed)
                top(0.px)
                left(0.px)
                width(100.percent)
                height(100.vh)
                backgroundColor(Color("rgba(74, 64, 56, 0.6)"))
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.Center)
                alignItems(AlignItems.Center)
                property("z-index", "1100")
                property("backdrop-filter", "blur(4px)")
            }
            onClick { 
                showForwardDialog = false
                messageToForward = null
                forwardResult = null
            }
        }) {
            Div({
                style {
                    backgroundColor(Color(SilkColors.surfaceElevated))
                    borderRadius(16.px)
                    padding(28.px)
                    width(400.px)
                    maxWidth(90.vw)
                    property("max-height", "70vh")
                    property("overflow-y", "auto")
                    property("box-shadow", "0 8px 32px rgba(169, 137, 77, 0.2)")
                }
                onClick { it.stopPropagation() }
            }) {
                // 标题
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        justifyContent(JustifyContent.SpaceBetween)
                        alignItems(AlignItems.Center)
                        marginBottom(16.px)
                    }
                }) {
                    Span({
                        style {
                            fontSize(18.px)
                            property("font-weight", "bold")
                            color(Color(SilkColors.primary))
                        }
                    }) { Text("💬 转发到对话") }
                    Span({
                        style {
                            fontSize(13.px)
                            color(Color(SilkColors.textSecondary))
                        }
                    }) { Text("1 条消息") }
                }
                
                // 消息预览
                Div({
                    style {
                        backgroundColor(Color("#F5F5F5"))
                        borderRadius(8.px)
                        padding(12.px)
                        marginBottom(16.px)
                        fontSize(13.px)
                        color(Color(SilkColors.textSecondary))
                        property("max-height", "60px")
                        property("overflow", "hidden")
                    }
                }) {
                    Text("${messageToForward!!.userName}: ${messageToForward!!.content.take(80)}${if (messageToForward!!.content.length > 80) "..." else ""}")
                }
                
                // 结果提示
                forwardResult?.let { result ->
                    Div({
                        style {
                            textAlign("center")
                            marginBottom(12.px)
                            fontSize(14.px)
                            color(if (result.contains("✅")) Color("#10B981") else Color("#EF4444"))
                        }
                    }) { Text(result) }
                }
                
                // 群组列表
                if (isLoadingGroups) {
                    Div({
                        style {
                            textAlign("center")
                            padding(20.px)
                            color(Color(SilkColors.textSecondary))
                        }
                    }) { Text("加载中...") }
                } else if (userGroups.isEmpty()) {
                    Div({
                        style {
                            textAlign("center")
                            padding(20.px)
                            color(Color(SilkColors.textSecondary))
                        }
                    }) { Text("没有其他对话可转发") }
                } else {
                    userGroups.forEach { targetGroup ->
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                alignItems(AlignItems.Center)
                                padding(12.px)
                                borderRadius(8.px)
                                property("cursor", "pointer")
                                property("transition", "background-color 0.2s")
                            }
                            onClick {
                                val msg = messageToForward ?: return@onClick
                                scope.launch {
                                    forwardResult = null
                                    val forwardContent = "📨 转发自【${group.name}】:\n\n${msg.userName}: ${msg.content}"
                                    val success = ApiClient.sendMessageToGroup(
                                        groupId = targetGroup.id,
                                        userId = user.id,
                                        userName = user.fullName,
                                        content = forwardContent
                                    )
                                    if (success) {
                                        forwardResult = "✅ 已转发到 ${targetGroup.name}"
                                        kotlinx.coroutines.delay(1000)
                                        showForwardDialog = false
                                        messageToForward = null
                                        forwardResult = null
                                    } else {
                                        forwardResult = "❌ 转发失败"
                                    }
                                }
                            }
                        }) {
                            // 群头像
                            Div({
                                style {
                                    property("width", "40px")
                                    property("height", "40px")
                                    borderRadius(50.percent)
                                    backgroundColor(Color(SilkColors.primary))
                                    display(DisplayStyle.Flex)
                                    justifyContent(JustifyContent.Center)
                                    alignItems(AlignItems.Center)
                                    property("margin-right", "12px")
                                    property("flex-shrink", "0")
                                }
                            }) {
                                Span({
                                    style {
                                        color(Color("#FFFFFF"))
                                        fontSize(16.px)
                                        property("font-weight", "bold")
                                    }
                                }) { Text(targetGroup.name.take(1)) }
                            }
                            // 群名
                            Span({
                                style {
                                    fontSize(15.px)
                                    color(Color(SilkColors.textPrimary))
                                }
                            }) { Text(targetGroup.name) }
                        }
                    }
                }
                
                // 取消按钮
                Div({
                    style {
                        marginTop(16.px)
                        textAlign("center")
                    }
                }) {
                    Span({
                        style {
                            fontSize(14.px)
                            color(Color(SilkColors.textSecondary))
                            property("cursor", "pointer")
                            padding(8.px, 24.px)
                            borderRadius(8.px)
                            backgroundColor(Color("#F5F5F5"))
                        }
                        onClick {
                            showForwardDialog = false
                            messageToForward = null
                            forwardResult = null
                        }
                    }) { Text("取消") }
                }
            }
        }
    }
    
    // 邀请对话框
    if (showInvitationDialog) {
        InvitationDialog(
            group = group,
            strings = strings,
            onDismiss = { showInvitationDialog = false }
        )
    }
    
    // 添加成员对话框
    if (showAddMemberDialog) {
        AddMemberDialog(
            contacts = contacts,
            groupMembers = groupMembers,
            isLoading = isLoadingContacts,
            result = addMemberResult,
            strings = strings,
            onAddMember = { contact ->
                scope.launch {
                    val response = ApiClient.addMemberToGroup(group.id, contact.contactId)
                    addMemberResult = if (response.success) {
                        // 刷新成员列表
                        val membersResponse = ApiClient.getGroupMembers(group.id)
                        // 将群主排在第一位
                        groupMembers = membersResponse.members.sortedByDescending { it.id == group.hostId }
                        strings.memberAdded.replace("{name}", contact.contactName)
                    } else {
                        "❌ ${response.message}"
                    }
                }
            },
            onDismiss = { 
                showAddMemberDialog = false
                addMemberResult = null
            }
        )
    }
    
    // 查看成员对话框
    if (showMembersDialog) {
        MembersDialog(
            members = groupMembers,
            contacts = contacts,
            currentUserId = user.id,
            isLoading = isLoadingContacts,
            strings = strings,
            onMemberClick = { member ->
                // 检查是否是联系人
                val isContact = contacts.any { it.contactId == member.id }
                if (isContact) {
                    // 是联系人，跳转到与该联系人的对话
                    scope.launch {
                        showMembersDialog = false
                        // 先断开当前WebSocket
                        try {
                            chatClient.disconnect()
                        } catch (e: dynamic) { }
                        
                        // 调用API获取或创建与该联系人的对话
                        val response = ApiClient.startPrivateChat(user.id, member.id)
                        if (response.success && response.group != null) {
                            // 导航到新的对话
                            appState.selectGroup(response.group!!)
                        } else {
                            console.log("❌ 无法创建对话: ${response.message}")
                        }
                    }
                } else {
                    // 不是联系人，弹出邀请确认
                    selectedMemberForInvite = member
                }
            },
            onDismiss = { 
                showMembersDialog = false
                selectedMemberForInvite = null
                inviteMemberResult = null
            }
        )
    }
    
    // 邀请成员加入联系人确认对话框
    selectedMemberForInvite?.let { member ->
        Div({
            style {
                position(Position.Fixed)
                top(0.px)
                left(0.px)
                width(100.percent)
                height(100.vh)
                backgroundColor(Color("rgba(74, 64, 56, 0.6)"))
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.Center)
                alignItems(AlignItems.Center)
                property("z-index", "1100")
                property("backdrop-filter", "blur(4px)")
            }
            onClick { 
                selectedMemberForInvite = null
                inviteMemberResult = null
            }
        }) {
            Div({
                style {
                    backgroundColor(Color(SilkColors.surfaceElevated))
                    borderRadius(16.px)
                    padding(28.px)
                    width(380.px)
                    maxWidth(90.vw)
                    property("box-shadow", "0 8px 32px rgba(169, 137, 77, 0.2)")
                    property("border", "1px solid ${SilkColors.border}")
                }
                onClick { it.stopPropagation() }
            }) {
                H3({
                    style {
                        color(Color(SilkColors.primary))
                        marginBottom(20.px)
                        fontSize(18.px)
                        property("font-weight", "600")
                        textAlign("center")
                    }
                }) {
                    Text(strings.addContact)
                }
                
                Div({
                    style {
                        textAlign("center")
                        marginBottom(20.px)
                        color(Color(SilkColors.textPrimary))
                    }
                }) {
                                Text(strings.memberNotInContacts.replace("{name}", member.fullName))
                    Br()
                    Text(strings.sendContactRequestQuestion)
                }
                
                // 显示结果消息
                inviteMemberResult?.let { result ->
                    Div({
                        style {
                            textAlign("center")
                            marginBottom(16.px)
                            color(if (result.contains(strings.contactRequestSent) || result.contains("✅")) 
                                Color("#10B981") else Color("#EF4444"))
                            fontSize(14.px)
                        }
                    }) {
                        Text(result)
                    }
                }
                
                // 按钮区域
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        justifyContent(JustifyContent.Center)
                        gap(12.px)
                    }
                }) {
                    Button({
                        style {
                            backgroundColor(Color(SilkColors.background))
                            color(Color(SilkColors.textSecondary))
                            border {
                                width(1.px)
                                style(LineStyle.Solid)
                                color(Color(SilkColors.border))
                            }
                            padding(10.px, 20.px)
                            borderRadius(8.px)
                            property("cursor", "pointer")
                            fontSize(14.px)
                        }
                        onClick { 
                            selectedMemberForInvite = null
                            inviteMemberResult = null
                        }
                    }) {
                        Text(strings.cancelButton)
                    }
                    
                    Button({
                        style {
                            backgroundColor(Color(SilkColors.primary))
                            color(Color.white)
                            border { style(LineStyle.None) }
                            padding(10.px, 20.px)
                            borderRadius(8.px)
                            property("cursor", if (isInvitingMember) "not-allowed" else "pointer")
                            property("opacity", if (isInvitingMember) "0.6" else "1")
                            fontSize(14.px)
                            property("font-weight", "500")
                        }
                        onClick {
                            if (!isInvitingMember && inviteMemberResult == null) {
                                scope.launch {
                                    isInvitingMember = true
                                    val response = ApiClient.sendContactRequestById(user.id, member.id)
                                    inviteMemberResult = if (response.success) {
                                        "✅ ${strings.contactRequestSent}"
                                    } else {
                                        "❌ ${response.message}"
                                    }
                                    isInvitingMember = false
                                    
                                    // 成功后延迟关闭
                                    if (response.success) {
                                        kotlinx.coroutines.delay(1500)
                                        selectedMemberForInvite = null
                                        inviteMemberResult = null
                                    }
                                }
                            }
                        }
                    }) {
                        Text(if (isInvitingMember) strings.sendingRequest else strings.sendRequest)
                    }
                }
            }
        }
    }
    
    // 隐藏的单文件上传输入
    org.jetbrains.compose.web.dom.Input(org.jetbrains.compose.web.attributes.InputType.File) {
        id("file-upload-input")
        style {
            display(DisplayStyle.None)
        }
        attr("accept", "*/*")
        attr("multiple", "false")
        onChange {
            val sessionId = group.id
            val userId = user.id
            val protocol = window.location.protocol
            val host = window.location.hostname
            // 始终使用后端端口 8901
            val uploadUrl = "$protocol//$host:${BuildConfig.BACKEND_HTTP_PORT}/api/files/upload"
            
            js("""
                (function() {
                    var input = document.getElementById('file-upload-input');
                    if (input && input.files && input.files.length > 0) {
                        var file = input.files[0];
                        console.log('📁 选择文件: ' + file.name + ', 大小: ' + file.size);
                        
                        var formData = new FormData();
                        formData.append('sessionId', sessionId);
                        formData.append('userId', userId);
                        formData.append('file', file);
                        
                        var xhr = new XMLHttpRequest();
                        xhr.open('POST', uploadUrl, true);
                        
                        xhr.onload = function() {
                            if (xhr.status === 200) {
                                var response = JSON.parse(xhr.responseText);
                                console.log('✅ 上传成功: ' + response.fileName);
                                window.alert('文件上传成功: ' + response.fileName);
                            } else {
                                console.log('❌ 上传失败: ' + xhr.statusText);
                                window.alert('文件上传失败: ' + xhr.statusText);
                            }
                        };
                        
                        xhr.onerror = function() {
                            console.log('❌ 上传错误');
                            window.alert('文件上传失败，请检查网络连接');
                        };
                        
                        xhr.send(formData);
                        input.value = '';
                    }
                })();
            """)
        }
    }
    
    // 隐藏的目录上传输入
    org.jetbrains.compose.web.dom.Input(org.jetbrains.compose.web.attributes.InputType.File) {
        id("folder-upload-input")
        style {
            display(DisplayStyle.None)
        }
        attr("webkitdirectory", "true")
        attr("directory", "true")
        attr("multiple", "true")
        onChange {
            val sessionId = group.id
            val userId = user.id
            val protocol = window.location.protocol
            val host = window.location.hostname
            // 始终使用后端端口 8901
            val uploadUrl = "$protocol//$host:${BuildConfig.BACKEND_HTTP_PORT}/api/files/upload"
            
            js("""
                (function() {
                    var input = document.getElementById('folder-upload-input');
                    if (!input || !input.files || input.files.length === 0) return;
                    
                    // 支持的文件扩展名
                    var supportedExtensions = [
                        // 文本文件
                        '.txt', '.md', '.markdown', '.json', '.xml', '.html', '.htm', '.css',
                        '.yaml', '.yml', '.csv', '.log', '.ini', '.conf', '.cfg',
                        // 源代码
                        '.js', '.ts', '.jsx', '.tsx', '.kt', '.kts', '.java', '.py', '.pyw',
                        '.c', '.cpp', '.cc', '.h', '.hpp', '.cs', '.go', '.rs', '.rb',
                        '.php', '.swift', '.scala', '.groovy', '.lua', '.r', '.m', '.mm',
                        '.sh', '.bash', '.zsh', '.ps1', '.bat', '.cmd',
                        '.sql', '.graphql', '.proto',
                        // 文档
                        '.pdf'
                    ];
                    
                    var files = input.files;
                    var filesToUpload = [];
                    
                    // 筛选支持的文件
                    for (var i = 0; i < files.length; i++) {
                        var file = files[i];
                        var ext = '.' + file.name.split('.').pop().toLowerCase();
                        if (supportedExtensions.indexOf(ext) !== -1) {
                            filesToUpload.push(file);
                        }
                    }
                    
                    if (filesToUpload.length === 0) {
                        window.alert('所选目录中没有支持的文件类型');
                        input.value = '';
                        return;
                    }
                    
                    console.log('📁 准备上传 ' + filesToUpload.length + ' 个文件（共 ' + files.length + ' 个文件）');
                    window.alert('准备上传 ' + filesToUpload.length + ' 个文件...');
                    
                    var uploaded = 0;
                    var failed = 0;
                    
                    // 逐一上传文件
                    function uploadNext(index) {
                        if (index >= filesToUpload.length) {
                            window.alert('上传完成！成功: ' + uploaded + ', 失败: ' + failed);
                            input.value = '';
                            return;
                        }
                        
                        var file = filesToUpload[index];
                        console.log('📤 上传 (' + (index + 1) + '/' + filesToUpload.length + '): ' + file.name);
                        
                        var formData = new FormData();
                        formData.append('sessionId', sessionId);
                        formData.append('userId', userId);
                        formData.append('file', file);
                        
                        var xhr = new XMLHttpRequest();
                        xhr.open('POST', uploadUrl, true);
                        
                        xhr.onload = function() {
                            if (xhr.status === 200) {
                                uploaded++;
                                console.log('✅ (' + uploaded + ') ' + file.name);
                            } else {
                                failed++;
                                console.log('❌ ' + file.name + ': ' + xhr.statusText);
                            }
                            uploadNext(index + 1);
                        };
                        
                        xhr.onerror = function() {
                            failed++;
                            console.log('❌ 网络错误: ' + file.name);
                            uploadNext(index + 1);
                        };
                        
                        xhr.send(formData);
                    }
                    
                    uploadNext(0);
                })();
            """)
        }
    }
    
    // 文件夹浏览对话框
    if (showFolderExplorer) {
        FolderExplorerDialog(
            groupId = group.id,
            strings = strings,
            onDismiss = { showFolderExplorer = false }
        )
    }
}

@Composable
fun FolderExplorerDialog(
    groupId: String,
    strings: com.silk.shared.i18n.Strings,
    onDismiss: () -> Unit
) {
    var files by remember { mutableStateOf<List<dynamic>>(emptyList()) }
    var processedUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // 直接使用 fetch 并更新状态（简化逻辑，避免事件时序问题）
    LaunchedEffect(groupId) {
        val protocol = window.location.protocol
        val host = window.location.hostname
        val apiUrl = "$protocol//$host:${BuildConfig.BACKEND_HTTP_PORT}/api/files/list/$groupId"
        
        window.asDynamic().tempApiUrl = apiUrl
        window.asDynamic().folderLoadCallback = { data: dynamic ->
            val filesArray = data.files as? Array<dynamic>
            files = filesArray?.toList() ?: emptyList()
            val urlsArray = data.processedUrls as? Array<dynamic>
            processedUrls = urlsArray?.map { it.toString() } ?: emptyList()
            isLoading = false
            println("📁 加载完成: ${files.size} 文件, ${processedUrls.size} URL")
        }
        window.asDynamic().folderErrorCallback = { error: dynamic ->
            errorMessage = error.toString()
            isLoading = false
        }
        
        js("""
            (function() {
                var url = window.tempApiUrl;
                console.log('📁 请求文件列表:', url);
                fetch(url)
                    .then(function(response) { return response.json(); })
                    .then(function(data) {
                        console.log('📁 文件列表响应:', data);
                        console.log('📁 processedUrls:', data.processedUrls);
                        window.folderLoadCallback(data);
                    })
                    .catch(function(error) {
                        console.error('❌ 获取文件列表失败:', error);
                        window.folderErrorCallback(error.message || '获取失败');
                    });
            })();
        """)
    }
    
    // 对话框背景遮罩
    Div({
        style {
            property("position", "fixed")
            property("top", "0")
            property("left", "0")
            property("right", "0")
            property("bottom", "0")
            backgroundColor(Color("rgba(0,0,0,0.5)"))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            property("z-index", "1000")
        }
        onClick { onDismiss() }
    }) {
        // 对话框内容
        Div({
            style {
                backgroundColor(Color(SilkColors.surface))
                borderRadius(16.px)
                padding(24.px)
                property("min-width", "400px")
                property("max-width", "600px")
                property("max-height", "70vh")
                property("box-shadow", "0 8px 32px rgba(0,0,0,0.2)")
                display(DisplayStyle.Flex)
                property("flex-direction", "column")
            }
            onClick { it.stopPropagation() }
        }) {
            // 标题栏
            Div({
                style {
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.SpaceBetween)
                    alignItems(AlignItems.Center)
                    marginBottom(16.px)
                    paddingBottom(12.px)
                    property("border-bottom", "1px solid ${SilkColors.border}")
                }
            }) {
                H3({
                    style {
                        margin(0.px)
                        color(Color(SilkColors.textPrimary))
                        fontSize(18.px)
                    }
                }) {
                    Text(strings.sessionFiles)
                }
                
                // 关闭按钮
                Button({
                    style {
                        backgroundColor(Color("transparent"))
                        border { width(0.px) }
                        fontSize(20.px)
                        property("cursor", "pointer")
                        color(Color(SilkColors.textSecondary))
                    }
                    onClick { onDismiss() }
                }) {
                    Text("✕")
                }
            }
            
            // 文件列表区域
            Div({
                style {
                    property("flex", "1")
                    property("overflow-y", "auto")
                    property("min-height", "200px")
                }
            }) {
                if (isLoading) {
                    Div({
                        style {
                            property("text-align", "center")
                            padding(40.px)
                            color(Color(SilkColors.textSecondary))
                        }
                    }) {
                        Text("⏳ ${strings.loading}")
                    }
                } else if (errorMessage != null) {
                    Div({
                        style {
                            property("text-align", "center")
                            padding(40.px)
                            color(Color(SilkColors.error))
                        }
                    }) {
                        Text("❌ $errorMessage")
                    }
                } else if (files.isEmpty() && processedUrls.isEmpty()) {
                    Div({
                        style {
                            property("text-align", "center")
                            padding(40.px)
                            color(Color(SilkColors.textSecondary))
                        }
                    }) {
                        Text(strings.noFilesYet)
                        Br()
                        Span({
                            style {
                                fontSize(13.px)
                                marginTop(8.px)
                                display(DisplayStyle.Block)
                            }
                        }) {
                            Text(strings.useBottomButtonToUpload)
                        }
                    }
                } else {
                    // 1️⃣ 首先显示已下载的 URL 清单
                    if (processedUrls.isNotEmpty()) {
                        Div({
                            style {
                                marginBottom(16.px)
                            }
                        }) {
                            // URL 清单标题
                            Div({
                                style {
                                    fontSize(14.px)
                                    fontWeight("600")
                                    color(Color(SilkColors.textSecondary))
                                    marginBottom(8.px)
                                    display(DisplayStyle.Flex)
                                    alignItems(AlignItems.Center)
                                    property("gap", "6px")
                                }
                            }) {
                                Text("🔗 已下载的网页 (${processedUrls.size})")
                            }
                            
                            processedUrls.forEach { url ->
                                Div({
                                    style {
                                        display(DisplayStyle.Flex)
                                        justifyContent(JustifyContent.SpaceBetween)
                                        alignItems(AlignItems.Center)
                                        padding(10.px, 14.px)
                                        marginBottom(6.px)
                                        backgroundColor(Color("#F0FFF4"))  // 淡绿色背景
                                        borderRadius(8.px)
                                        property("border", "1px solid #C6F6D5")
                                    }
                                }) {
                                    // URL 信息
                                    Div({
                                        style {
                                            display(DisplayStyle.Flex)
                                            alignItems(AlignItems.Center)
                                            property("gap", "10px")
                                            property("flex", "1")
                                            property("overflow", "hidden")
                                        }
                                    }) {
                                        Span({ style { fontSize(16.px) } }) { Text("🌐") }
                                        A(href = url, {
                                            attr("target", "_blank")
                                            style {
                                                color(Color(SilkColors.primary))
                                                fontSize(13.px)
                                                property("text-decoration", "none")
                                                property("overflow", "hidden")
                                                property("text-overflow", "ellipsis")
                                                property("white-space", "nowrap")
                                            }
                                        }) {
                                            Text(url)
                                        }
                                    }
                                    // 状态标记
                                    Span({
                                        style {
                                            backgroundColor(Color("#48BB78"))
                                            color(Color("white"))
                                            padding(2.px, 8.px)
                                            borderRadius(10.px)
                                            fontSize(11.px)
                                        }
                                    }) {
                                        Text("✓ 已索引")
                                    }
                                }
                            }
                        }
                    }
                    
                    // 2️⃣ 然后显示上传的文件列表
                    if (files.isNotEmpty()) {
                        // 文件列表标题
                        if (processedUrls.isNotEmpty()) {
                            Div({
                                style {
                                    fontSize(14.px)
                                    fontWeight("600")
                                    color(Color(SilkColors.textSecondary))
                                    marginBottom(8.px)
                                    display(DisplayStyle.Flex)
                                    alignItems(AlignItems.Center)
                                    property("gap", "6px")
                                }
                            }) {
                                Text("📁 上传的文件 (${files.size})")
                            }
                        }
                    }
                    
                    // 显示文件列表
                    files.forEach { file ->
                        val fileName = (file.fileName ?: file.name) as? String ?: strings.unknownFile
                        val fileSize = (file.size as? Number)?.toLong() ?: 0L
                        val downloadUrl = file.downloadUrl as? String ?: ""
                        
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                justifyContent(JustifyContent.SpaceBetween)
                                alignItems(AlignItems.Center)
                                padding(12.px, 16.px)
                                marginBottom(8.px)
                                backgroundColor(Color(SilkColors.surfaceElevated))
                                borderRadius(8.px)
                                property("border", "1px solid ${SilkColors.border}")
                                property("transition", "all 0.2s ease")
                            }
                        }) {
                            // 文件信息
                            Div({
                                style {
                                    display(DisplayStyle.Flex)
                                    alignItems(AlignItems.Center)
                                    property("gap", "12px")
                                }
                            }) {
                                // 文件图标
                                Span({
                                    style {
                                        fontSize(24.px)
                                    }
                                }) {
                                    val icon = when {
                                        fileName.endsWith(".pdf") -> "📄"
                                        fileName.endsWith(".doc") || fileName.endsWith(".docx") -> "📝"
                                        fileName.endsWith(".xls") || fileName.endsWith(".xlsx") -> "📊"
                                        fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") || fileName.endsWith(".gif") -> "🖼️"
                                        fileName.endsWith(".zip") || fileName.endsWith(".rar") -> "📦"
                                        fileName.endsWith(".txt") || fileName.endsWith(".md") -> "📃"
                                        else -> "📎"
                                    }
                                    Text(icon)
                                }
                                
                                Div {
                                    Div({
                                        style {
                                            color(Color(SilkColors.textPrimary))
                                            property("font-weight", "500")
                                        }
                                    }) {
                                        Text(fileName)
                                    }
                                    Div({
                                        style {
                                            fontSize(12.px)
                                            color(Color(SilkColors.textSecondary))
                                            marginTop(2.px)
                                        }
                                    }) {
                                        val sizeStr = when {
                                            fileSize < 1024 -> "${fileSize} B"
                                            fileSize < 1024 * 1024 -> "${fileSize / 1024} KB"
                                            else -> "${fileSize / (1024 * 1024)} MB"
                                        }
                                        Text(sizeStr)
                                    }
                                }
                            }
                            
                            // 下载按钮
                            Button({
                                style {
                                    padding(8.px, 16.px)
                                    backgroundColor(Color(SilkColors.primary))
                                    color(Color.white)
                                    border { width(0.px) }
                                    borderRadius(6.px)
                                    property("cursor", "pointer")
                                    fontSize(13.px)
                                    property("font-weight", "500")
                                    property("transition", "all 0.2s ease")
                                }
                                onClick {
                                    val protocol = window.location.protocol
                                    val host = window.location.hostname
                                    // 始终使用后端端口 8901
                                    val fullUrl = "$protocol//$host:${BuildConfig.BACKEND_HTTP_PORT}$downloadUrl"
                                    window.open(fullUrl, "_blank")
                                }
                            }) {
                                Text("⬇️ ${strings.download}")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== Markdown 渲染组件 ====================

/**
 * 简单的 Markdown 解析和渲染
 * 支持：标题、粗体、斜体、代码块、行内代码、列表、链接
 */
@Composable
fun MarkdownContent(content: String) {
    val lines = content.split("\n")
    var inCodeBlock = false
    var codeBlockContent = StringBuilder()
    var codeLanguage = ""
    var inList = false
    
    Div({
        style {
            property("white-space", "pre-wrap")
            property("word-wrap", "break-word")
            property("line-height", "1.7")
            property("color", SilkColors.textPrimary)
        }
    }) {
        lines.forEachIndexed { index, line ->
            // 处理代码块
            if (line.trim().startsWith("```")) {
                if (inCodeBlock) {
                    // 结束代码块
                    inCodeBlock = false
                    // 渲染代码块
                    Pre({
                        style {
                            backgroundColor(Color("#F5F5F5"))
                            padding(12.px)
                            borderRadius(8.px)
                            property("overflow-x", "auto")
                            marginBottom(12.px)
                            fontSize(13.px)
                            property("font-family", "'Fira Code', 'Consolas', monospace")
                            property("border-left", "3px solid ${SilkColors.primary}")
                        }
                    }) {
                        Code({
                            style {
                                property("white-space", "pre")
                                property("font-family", "'Fira Code', 'Consolas', monospace")
                            }
                        }) {
                            Text(codeBlockContent.toString().trimEnd())
                        }
                    }
                    codeBlockContent = StringBuilder()
                } else {
                    // 开始代码块
                    inCodeBlock = true
                    codeLanguage = line.trim().removePrefix("```").trim()
                }
                return@forEachIndexed
            }
            
            if (inCodeBlock) {
                codeBlockContent.append(line).append("\n")
                return@forEachIndexed
            }
            
            // 处理标题
            when {
                line.startsWith("### ") -> {
                    H3({
                        style {
                            color(Color(SilkColors.textPrimary))
                            fontSize(16.px)
                            fontWeight("600")
                            marginTop(12.px)
                            marginBottom(8.px)
                            property("border-bottom", "1px solid ${SilkColors.border}")
                            paddingBottom(4.px)
                        }
                    }) {
                        renderInlineMarkdown(line.removePrefix("### "))
                    }
                }
                line.startsWith("## ") -> {
                    H2({
                        style {
                            color(Color(SilkColors.textPrimary))
                            fontSize(18.px)
                            fontWeight("600")
                            marginTop(14.px)
                            marginBottom(10.px)
                            property("border-bottom", "1px solid ${SilkColors.border}")
                            paddingBottom(6.px)
                        }
                    }) {
                        renderInlineMarkdown(line.removePrefix("## "))
                    }
                }
                line.startsWith("# ") -> {
                    H1({
                        style {
                            color(Color(SilkColors.primary))
                            fontSize(20.px)
                            fontWeight("700")
                            marginTop(16.px)
                            marginBottom(12.px)
                            property("border-bottom", "2px solid ${SilkColors.primary}")
                            paddingBottom(8.px)
                        }
                    }) {
                        renderInlineMarkdown(line.removePrefix("# "))
                    }
                }
                // 处理无序列表
                line.trim().startsWith("- ") || line.trim().startsWith("* ") -> {
                    val content = line.trim().removePrefix("- ").removePrefix("* ")
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            paddingLeft(16.px)
                            marginBottom(4.px)
                            property("gap", "8px")
                        }
                    }) {
                        Span({ style { color(Color(SilkColors.primary)) } }) { Text("•") }
                        Span({ style { property("flex", "1") } }) { renderInlineMarkdown(content) }
                    }
                }
                // 处理有序列表
                line.trim().matches(Regex("^\\d+\\.\\s.*")) -> {
                    val parts = line.trim().split(".", limit = 2)
                    if (parts.size == 2) {
                        val num = parts[0].trim()
                        val content = parts[1].trim()
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                paddingLeft(16.px)
                                marginBottom(4.px)
                                property("gap", "8px")
                            }
                        }) {
                            Span({ style { color(Color(SilkColors.primary)); fontWeight("600") } }) { Text("$num.") }
                            Span({ style { property("flex", "1") } }) { renderInlineMarkdown(content) }
                        }
                    }
                }
                // 处理分隔线
                line.trim() == "---" || line.trim() == "***" -> {
                    Hr({
                        style {
                            property("border", "none")
                            property("border-top", "1px solid ${SilkColors.border}")
                            marginTop(16.px)
                            marginBottom(16.px)
                        }
                    })
                }
                // 处理引用块
                line.startsWith("> ") -> {
                    val content = line.removePrefix("> ")
                    Div({
                        style {
                            paddingLeft(12.px)
                            property("border-left", "3px solid ${SilkColors.info}")
                            marginBottom(8.px)
                            color(Color(SilkColors.textSecondary))
                            property("font-style", "italic")
                            backgroundColor(Color("#F8F8F8"))
                            padding(8.px, 12.px)
                            borderRadius(0.px, 8.px, 8.px, 0.px)
                        }
                    }) {
                        renderInlineMarkdown(content)
                    }
                }
                // 普通文本
                line.isNotBlank() -> {
                    P({
                        style {
                            marginBottom(8.px)
                            marginTop(0.px)
                        }
                    }) {
                        renderInlineMarkdown(line)
                    }
                }
                // 空行
                else -> {
                    if (index > 0 && lines.getOrNull(index - 1)?.isNotBlank() == true) {
                        Br()
                    }
                }
            }
        }
    }
}

/**
 * 渲染行内 Markdown（粗体、斜体、行内代码、链接）
 */
@Composable
fun renderInlineMarkdown(text: String) {
    // 简化的行内渲染：处理粗体、斜体、行内代码
    val segments = mutableListOf<InlineSegment>()
    var remaining = text
    var currentPos = 0
    
    while (remaining.isNotEmpty()) {
        // 查找下一个特殊标记
        val boldIndex = remaining.indexOf("**")
        val italicIndex = remaining.indexOf("*")
        val codeIndex = remaining.indexOf("`")
        val linkIndex = remaining.indexOf("[")
        
        val nextIndex = listOfNotNull(
            if (boldIndex >= 0) boldIndex else null,
            if (italicIndex >= 0 && italicIndex != boldIndex) italicIndex else null,
            if (codeIndex >= 0) codeIndex else null,
            if (linkIndex >= 0) linkIndex else null
        ).minOrNull()
        
        if (nextIndex == null || nextIndex > 0) {
            // 输出普通文本
            val plainText = if (nextIndex != null) remaining.substring(0, nextIndex) else remaining
            Text(plainText)
            remaining = if (nextIndex != null) remaining.substring(nextIndex) else ""
            continue
        }
        
        when {
            // 行内代码
            codeIndex == 0 -> {
                val endCode = remaining.indexOf("`", 1)
                if (endCode > 0) {
                    val code = remaining.substring(1, endCode)
                    Code({
                        style {
                            backgroundColor(Color("#F0F0F0"))
                            padding(2.px, 6.px)
                            borderRadius(4.px)
                            fontSize(13.px)
                            property("font-family", "'Fira Code', 'Consolas', monospace")
                            color(Color(SilkColors.error))
                        }
                    }) {
                        Text(code)
                    }
                    remaining = remaining.substring(endCode + 1)
                } else {
                    Text("`")
                    remaining = remaining.substring(1)
                }
            }
            // 粗体
            boldIndex == 0 -> {
                val endBold = remaining.indexOf("**", 2)
                if (endBold > 0) {
                    val boldText = remaining.substring(2, endBold)
                    B({
                        style {
                            fontWeight("700")
                            color(Color(SilkColors.textPrimary))
                        }
                    }) {
                        Text(boldText)
                    }
                    remaining = remaining.substring(endBold + 2)
                } else {
                    Text("**")
                    remaining = remaining.substring(2)
                }
            }
            // 斜体（单星号）
            italicIndex == 0 -> {
                val endItalic = remaining.indexOf("*", 1)
                if (endItalic > 0) {
                    val italicText = remaining.substring(1, endItalic)
                    Em({
                        style {
                            property("font-style", "italic")
                            color(Color(SilkColors.textSecondary))
                        }
                    }) {
                        Text(italicText)
                    }
                    remaining = remaining.substring(endItalic + 1)
                } else {
                    Text("*")
                    remaining = remaining.substring(1)
                }
            }
            // 链接
            linkIndex == 0 -> {
                val endBracket = remaining.indexOf("]")
                if (endBracket > 0 && remaining.getOrNull(endBracket + 1) == '(') {
                    val endParen = remaining.indexOf(")", endBracket + 2)
                    if (endParen > 0) {
                        val linkText = remaining.substring(1, endBracket)
                        val linkUrl = remaining.substring(endBracket + 2, endParen)
                        A(href = linkUrl, {
                            style {
                                color(Color(SilkColors.info))
                                property("text-decoration", "underline")
                            }
                            attr("target", "_blank")
                        }) {
                            Text(linkText)
                        }
                        remaining = remaining.substring(endParen + 1)
                    } else {
                        Text("[")
                        remaining = remaining.substring(1)
                    }
                } else {
                    Text("[")
                    remaining = remaining.substring(1)
                }
            }
            else -> {
                Text(remaining.substring(0, 1))
                remaining = remaining.substring(1)
            }
        }
    }
}

data class InlineSegment(val type: String, val content: String, val extra: String = "")

// ==================== AI 消息卡片组件 ====================

/**
 * AI 消息卡片 - 用于 @silk 的回复
 * 特点：
 * 1. 左侧有 AI 图标和标识
 * 2. 渐变背景色
 * 3. Markdown 内容优化渲染
 * 4. 可折叠的长内容
 */
@Composable
fun AIMessageCard(
    message: Message,
    timeString: String,
    isTransient: Boolean = false,
    onCopy: (String) -> Unit = {},
    onForward: (Message) -> Unit = {}
) {
    var isExpanded by remember { mutableStateOf(false) }  // 默认收起
    val isLongContent = message.content.length > 500
    val effectiveExpanded = if (isTransient) true else isExpanded
    val collapsedPreview = remember(message.content) {
        message.content.trimStart().take(200).ifBlank { "（内容已折叠，点击展开）" }
    }
    
    Div({
        classes(SilkStylesheet.aiMessageCard)
    }) {
        // AI 头部标识
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                property("gap", "10px")
                marginBottom(12.px)
            }
        }) {
            // AI 图标
            Div({
                classes(SilkStylesheet.aiBadge)
            }) {
                Text("🤖")
            }
            
            // AI 名称和时间
            Div({
                style {
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    property("gap", "2px")
                }
            }) {
                Span({
                    style {
                        fontWeight("600")
                        fontSize(14.px)
                        color(Color(SilkColors.primary))
                        property("letter-spacing", "0.5px")
                    }
                }) {
                    Text("Silk AI")
                }
                Span({
                    style {
                        fontSize(11.px)
                        color(Color(SilkColors.textLight))
                    }
                }) {
                    Text(timeString)
                }
            }
            
            // 展开/折叠按钮（长内容时显示）
            if (isLongContent && !isTransient) {
                Div({ style { property("flex", "1") } }) { }
                Span({
                    style {
                        fontSize(12.px)
                        color(Color(SilkColors.textSecondary))
                        property("cursor", "pointer")
                        padding(4.px, 8.px)
                        borderRadius(4.px)
                        property("transition", "all 0.2s")
                        property("user-select", "none")
                        if (!effectiveExpanded) {
                            property("background", "rgba(201, 168, 108, 0.1)")
                        }
                    }
                    onClick {
                        isExpanded = !effectiveExpanded
                    }
                }) {
                    Text(if (effectiveExpanded) "▼ 收起" else "▶ 展开")
                }
            }
        }
        
        // 内容区域
        if (effectiveExpanded || !isLongContent) {
            if (isLongContent && effectiveExpanded && !isTransient) {
                Div({
                    classes(SilkStylesheet.aiMessageContent)
                    style {
                        maxHeight(360.px)
                        property("overflow-y", "auto")
                    }
                }) {
                    MarkdownContent(message.content)
                }
            } else {
                Div({
                    classes(SilkStylesheet.aiMessageContent)
                }) {
                    // 使用 Markdown 渲染
                    MarkdownContent(message.content)
                }
            }
        } else {
            // 折叠时显示摘要
            Div({
                style {
                    fontSize(13.px)
                    color(Color(SilkColors.textSecondary))
                    property("font-style", "italic")
                }
            }) {
                Text("$collapsedPreview...")
            }
        }
        
        // 底部操作栏
        if (!isTransient) {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    property("justify-content", "flex-end")
                    property("gap", "12px")
                    marginTop(12.px)
                    paddingTop(8.px)
                    property("border-top", "1px solid rgba(232, 224, 212, 0.5)")
                }
            }) {
                // 复制按钮
                Span({
                    style {
                        fontSize(11.px)
                        color(Color(SilkColors.textSecondary))
                        property("cursor", "pointer")
                        padding(4.px, 10.px)
                        borderRadius(4.px)
                        property("transition", "all 0.2s")
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        property("gap", "4px")
                    }
                    onClick { onCopy(message.content) }
                }) {
                    Text("📋")
                    Text("复制")
                }
                
                // 转发按钮
                Span({
                    style {
                        fontSize(11.px)
                        color(Color(SilkColors.textSecondary))
                        property("cursor", "pointer")
                        padding(4.px, 10.px)
                        borderRadius(4.px)
                        property("transition", "all 0.2s")
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        property("gap", "4px")
                    }
                    onClick { onForward(message) }
                }) {
                    Text("↗")
                    Text("转发")
                }
            }
        }
        
        // 临时消息状态指示
        if (isTransient) {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    property("gap", "6px")
                    marginTop(10.px)
                    fontSize(12.px)
                    color(Color(SilkColors.warning))
                }
            }) {
                Text("⏳")
                Text("生成中...")
            }
        }
    }
}

@Composable
fun MessageItem(
    message: Message, 
    isTransient: Boolean = false,
    currentUserId: String = "",
    groupId: String = "",
    isRecalling: Boolean = false,
    onRecall: (String) -> Unit = {},
    onCopy: (String) -> Unit = {},
    onForward: (Message) -> Unit = {}
) {
    val timeString = remember(message.timestamp) {
        formatTime(message.timestamp)
    }
    
    // 是否是 AI 消息
    val isAIMessage = message.userId == "silk_ai_agent"
    
    // AI 消息使用专用卡片
    if (isAIMessage && message.type == MessageType.TEXT && message.category != com.silk.shared.models.MessageCategory.AGENT_STATUS) {
        AIMessageCard(
            message = message,
            timeString = timeString,
            isTransient = isTransient,
            onCopy = onCopy,
            onForward = onForward
        )
        return
    }
    
    // 是否可以撤回：只能撤回自己发送的消息，且不是 Silk 的消息
    val canRecall = message.userId == currentUserId && 
                    message.userId != "silk_ai_agent" && 
                    message.type == MessageType.TEXT &&
                    !isTransient
    
    // 是否显示操作按钮：文本消息且不是临时消息
    val showActions = message.type == MessageType.TEXT && !isTransient && 
                      message.category != com.silk.shared.models.MessageCategory.AGENT_STATUS
    
    // Agent 状态消息 - 灰色样式
    if (message.category == com.silk.shared.models.MessageCategory.AGENT_STATUS) {
        Div({
            style {
                padding(8.px, 16.px)
                marginBottom(6.px)
                backgroundColor(Color("#F5F5F5"))
                borderRadius(8.px)
                property("border-left", "3px solid #BDBDBD")
                fontSize(13.px)
                color(Color("#757575"))
                property("font-style", "italic")
            }
        }) {
            Text(message.content)
        }
        return
    }
    
    when (message.type) {
        MessageType.TEXT -> {
            // 检测PDF下载链接
            val isPdfMessage = message.content.contains("/download/report/") && message.content.contains(".pdf")
            
            Div({ classes(SilkStylesheet.messageCard) }) {
                Div({ classes(SilkStylesheet.messageHeader) }) {
                    Span({ classes(SilkStylesheet.userName) }) {
                        Text(message.userName)
                    }
                    Span({ classes(SilkStylesheet.timestamp) }) {
                        Text(timeString)
                    }
                    if (canRecall) {
                        Span({
                            style {
                                marginLeft(8.px)
                                fontSize(11.px)
                                color(Color(if (isRecalling) "#BDBDBD" else SilkColors.textLight))
                                property("cursor", if (isRecalling) "default" else "pointer")
                                property("opacity", if (isRecalling) "0.4" else "0.6")
                                property("transition", "opacity 0.2s")
                                if (!isRecalling) property("text-decoration", "underline")
                            }
                            if (!isRecalling) {
                                onClick {
                                    if (window.confirm("确定要撤回这条消息吗？")) {
                                        onRecall(message.id)
                                    }
                                }
                            }
                        }) {
                            Text(if (isRecalling) "撤回中..." else "撤回")
                        }
                    }
                }
                Div({
                    style {
                        property("white-space", "pre-wrap")
                        property("word-wrap", "break-word")
                        property("line-height", "1.7")
                        property("color", SilkColors.textPrimary)
                    }
                }) {
                    if (isPdfMessage) {
                        // PDF下载消息特殊处理
                        val lines = message.content.split("\n")
                        var pdfUrl: String? = null
                        var fileName: String? = null
                        
                        // 查找PDF路径和文件名
                        lines.forEach { line ->
                            val trimmedLine = line.trim()
                            if (trimmedLine.startsWith("/download/report/") && trimmedLine.contains(".pdf")) {
                                pdfUrl = trimmedLine
                                // 提取文件名（去除路径中的编码字符）
                                fileName = trimmedLine.substringAfterLast("/").replace("%20", " ").replace("%27", "'")
                            }
                        }
                        
                        // 显示消息内容（过滤掉路径行）
                        lines.forEach { line ->
                            val trimmedLine = line.trim()
                            if (!trimmedLine.startsWith("/download/report/") && trimmedLine.isNotEmpty()) {
                                Text(line)
                                Br()
                            }
                        }
                        
                        // 显示下载按钮 - 丝滑绿色
                        if (pdfUrl != null) {
                            val baseUrl = js("window.location.protocol + '//' + window.location.hostname") as String
                            val port = BuildConfig.BACKEND_HTTP_PORT
                            val fullUrl = "$baseUrl:$port$pdfUrl"
                            
                            Div({
                                style {
                                    marginTop(14.px)
                                }
                            }) {
                                Button({
                                    style {
                                        property("background", "linear-gradient(135deg, ${SilkColors.success} 0%, #6A9D5B 100%)")
                                        color(Color.white)
                                        padding(12.px, 20.px)
                                        border {
                                            width(0.px)
                                        }
                                        borderRadius(8.px)
                                        fontSize(14.px)
                                        property("cursor", "pointer")
                                        property("font-weight", "600")
                                        property("display", "inline-flex")
                                        property("align-items", "center")
                                        property("gap", "8px")
                                        property("box-shadow", "0 2px 8px rgba(125, 174, 108, 0.3)")
                                        property("transition", "all 0.2s ease")
                                    }
                                    onClick { event ->
                                        event.preventDefault()
                                        // ✅ 使用 fetch + Blob 方式下载PDF，触发浏览器保存对话框
                                        val downloadFileName = fileName ?: "diagnosis_report.pdf"
                                        console.log("开始下载PDF: $fullUrl, 文件名: $downloadFileName")
                                        
                                        // 获取window和document对象（js()返回的已经是dynamic类型）
                                        val window = js("window")
                                        val document = js("document")
                                        
                                        // 使用fetch下载PDF
                                        window.fetch(fullUrl)
                                            .then({ response: dynamic ->
                                                // response已经是JavaScript对象，直接使用
                                                console.log("获取响应:", response)
                                                if (!response.ok) {
                                                    throw js("Error('下载失败: ' + response.status)")
                                                }
                                                response.blob()  // 返回Promise<Blob>
                                            })
                                            .then({ blob: dynamic ->
                                                // blob已经是JavaScript Blob对象，直接使用
                                                console.log("创建Blob对象")
                                                val url = window.URL.createObjectURL(blob)
                                                val a = document.createElement("a")
                                                a.style.display = "none"
                                                a.href = url
                                                a.download = downloadFileName
                                                document.body.appendChild(a)
                                                a.click()
                                                window.URL.revokeObjectURL(url)
                                                document.body.removeChild(a)
                                                console.log("PDF下载成功")
                                            })
                                            .catch({ error: dynamic ->
                                                console.error("下载PDF失败:", error)
                                                window.alert("下载失败: " + error.message)
                                            })
                                    }
                                }) {
                                    Text("📥 下载PDF报告")
                                }
                                
                                // 显示文件名
                                if (fileName != null) {
                                    Div({
                                        style {
                                            fontSize(11.px)
                                            color(Color(SilkColors.textLight))
                                            marginTop(8.px)
                                            property("font-style", "italic")
                                        }
                                    }) {
                                        Text("文件名：$fileName")
                                    }
                                }
                            }
                        }
                    } else {
                        // 普通文本消息
                        Text(message.content)
                    }
                }
                
                // 消息操作按钮行（复制、转发等）
                if (!isTransient && message.type == MessageType.TEXT) {
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            property("justify-content", "flex-end")
                            property("gap", "8px")
                            marginTop(8.px)
                            property("opacity", "0.6")
                            property("transition", "opacity 0.2s")
                        }
                    }) {
                        // 复制按钮
                        Span({
                            style {
                                fontSize(11.px)
                                color(Color(SilkColors.textSecondary))
                                property("cursor", "pointer")
                                property("padding", "2px 6px")
                                property("border-radius", "4px")
                                property("transition", "all 0.2s")
                            }
                            onClick {
                                // 复制消息内容到剪贴板
                                copyTextToClipboard(message.content)
                            }
                        }) {
                            Text("📋复制")
                        }
                        
                        // 转发按钮
                        Span({
                            style {
                                fontSize(11.px)
                                color(Color(SilkColors.textSecondary))
                                property("cursor", "pointer")
                                property("padding", "2px 6px")
                                property("border-radius", "4px")
                                property("transition", "all 0.2s")
                            }
                            onClick {
                                onForward(message)
                            }
                        }) {
                            Text("↗转发")
                        }
                    }
                }
            }
        }
        MessageType.FILE -> {
            // 文件消息 - 显示文件卡片，点击可下载
            // 解析文件信息 JSON
            val fileInfoJson = message.content
            var fileName by remember { mutableStateOf("") }
            var fileSize by remember { mutableStateOf(0L) }
            var downloadUrl by remember { mutableStateOf("") }
            var fileExt by remember { mutableStateOf("") }
            
            // 解析 JSON
            try {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val obj = json.parseToJsonElement(fileInfoJson) as kotlinx.serialization.json.JsonObject
                fileName = obj["fileName"]?.jsonPrimitive?.content ?: "未知文件"
                fileSize = obj["fileSize"]?.jsonPrimitive?.longOrNull ?: 0L
                downloadUrl = obj["downloadUrl"]?.jsonPrimitive?.content ?: ""
                fileExt = fileName.substringAfterLast(".", "").uppercase()
            } catch (e: Exception) {
                console.error("解析文件信息失败:", e)
                fileName = "文件"
                downloadUrl = ""
            }
            
            // 文件图标
            val fileIcon = when (fileExt.lowercase()) {
                "pdf" -> "📄"
                "doc", "docx" -> "📝"
                "xls", "xlsx" -> "📊"
                "ppt", "pptx" -> "📽"
                "jpg", "jpeg", "png", "gif", "webp" -> "🖼"
                "mp3", "wav", "ogg" -> "🎵"
                "mp4", "avi", "mov", "mkv" -> "🎬"
                "zip", "rar", "7z", "tar", "gz" -> "📦"
                "txt", "md", "log" -> "📃"
                "json", "xml", "yaml", "yml" -> "⚙"
                else -> "📎"
            }
            
            // 格式化文件大小
            fun formatFileSize(size: Long): String {
                val kb = 1024.0
                val mb = kb * 1024
                val gb = mb * 1024
                return when {
                    size < 1024 -> "$size B"
                    size < mb -> "${(size / kb * 10).toInt() / 10.0} KB"
                    size < gb -> "${(size / mb * 10).toInt() / 10.0} MB"
                    else -> "${(size / gb * 10).toInt() / 10.0} GB"
                }
            }
            val fileSizeStr = formatFileSize(fileSize)
            
            Div({ classes(SilkStylesheet.messageCard) }) {
                Div({ classes(SilkStylesheet.messageHeader) }) {
                    Span({ classes(SilkStylesheet.userName) }) {
                        Text(message.userName)
                    }
                    Span({ classes(SilkStylesheet.timestamp) }) {
                        Text(timeString)
                    }
                }
                
                // 文件卡片
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        property("gap", "12px")
                        padding(12.px)
                        backgroundColor(Color(SilkColors.surfaceElevated))
                        borderRadius(8.px)
                        property("border", "1px solid ${SilkColors.border}")
                        property("cursor", "pointer")
                        property("transition", "all 0.2s ease")
                    }
                    onClick {
                        if (downloadUrl.isNotEmpty()) {
                            val baseUrl = js("window.location.protocol + '//' + window.location.hostname") as String
                            val port = BuildConfig.BACKEND_HTTP_PORT
                            val fullUrl = "$baseUrl:$port$downloadUrl"
                            console.log("打开文件下载: $fullUrl")
                            
                            // 使用 fetch 下载文件
                            val window = js("window")
                            val document = js("document")
                            
                            window.fetch(fullUrl)
                                .then({ response: dynamic ->
                                    if (!response.ok) {
                                        throw js("Error('下载失败: ' + response.status)")
                                    }
                                    response.blob()
                                })
                                .then({ blob: dynamic ->
                                    val url = window.URL.createObjectURL(blob)
                                    val a = document.createElement("a")
                                    a.style.display = "none"
                                    a.href = url
                                    a.download = fileName
                                    document.body.appendChild(a)
                                    a.click()
                                    window.URL.revokeObjectURL(url)
                                    document.body.removeChild(a)
                                    console.log("文件下载成功")
                                })
                                .catch({ error: dynamic ->
                                    console.error("下载文件失败:", error)
                                    window.alert("下载失败: " + error.message)
                                })
                        }
                    }
                }) {
                    // 文件图标
                    Div({
                        style {
                            fontSize(32.px)
                            padding(8.px)
                            backgroundColor(Color(SilkColors.secondary))
                            borderRadius(8.px)
                        }
                    }) {
                        Text(fileIcon)
                    }
                    
                    // 文件信息
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            flexDirection(FlexDirection.Column)
                            property("gap", "4px")
                        }
                    }) {
                        Div({
                            style {
                                fontSize(14.px)
                                fontWeight("600")
                                color(Color(SilkColors.textPrimary))
                                property("max-width", "200px")
                                property("overflow", "hidden")
                                property("text-overflow", "ellipsis")
                                property("white-space", "nowrap")
                            }
                        }) {
                            Text(fileName)
                        }
                        Div({
                            style {
                                fontSize(12.px)
                                color(Color(SilkColors.textSecondary))
                            }
                        }) {
                            Text("$fileSizeStr • $fileExt")
                        }
                    }
                    
                    // 下载按钮
                    Div({
                        style {
                            marginLeft(8.px)
                            fontSize(18.px)
                            color(Color(SilkColors.primary))
                        }
                    }) {
                        Text("⬇")
                    }
                }
            }
        }
        MessageType.JOIN, MessageType.LEAVE, MessageType.SYSTEM -> {
            Div({ classes(SilkStylesheet.systemMessage) }) {
                Text("• ${message.content} ($timeString)")
            }
        }
        MessageType.RECALL -> {
            // 撤回消息通知 - 不显示在消息列表中
            // 客户端通过 ChatClient 自动处理撤回，这里不需要显示
        }
    }
}

@Composable
fun TransientMessageItem(message: Message) {
    // 临时消息：丝滑风格 + 进度条动画
    val timeString = remember(message.timestamp) {
        formatTime(message.timestamp)
    }
    
    // 循环进度动画状态
    var progress by remember { mutableStateOf(0) }
    
    LaunchedEffect(Unit) {
        // 循环动画：0 → 100 → 0 不断循环
        while (true) {
            for (i in 0..100) {
                progress = i
                kotlinx.coroutines.delay(20)  // 2秒完成一次循环（100步 * 20ms = 2000ms）
            }
        }
    }
    
    Div({ classes(SilkStylesheet.transientMessageCard) }) {
        Div({ 
            style {
                display(DisplayStyle.Flex)
                property("justify-content", "space-between")
                marginBottom(6.px)
            }
        }) {
            Span({ 
                style {
                    property("font-weight", "600")
                    color(Color(SilkColors.primaryDark))
                }
            }) {
                Text("${message.userName} (处理中...)")
            }
            Span({ 
                style {
                    fontSize(11.px)
                    color(Color(SilkColors.textLight))
                }
            }) {
                Text(timeString)
            }
        }
        
        // 如果有步骤信息，显示进度条
        if (message.currentStep != null && message.totalSteps != null) {
            Div({ classes(SilkStylesheet.progressBarContainer) }) {
                // 步骤指示
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        property("justify-content", "space-between")
                        fontSize(11.px)
                        color(Color(SilkColors.primary))
                        marginBottom(6.px)
                        property("letter-spacing", "0.5px")
                    }
                }) {
                    Span { Text("步骤 ${message.currentStep}/${message.totalSteps}") }
                    Span { Text("处理中...") }
                }
                
                // 进度条
                Div({ classes(SilkStylesheet.progressBar) }) {
                    Div({ 
                        classes(SilkStylesheet.progressFill)
                        style {
                            val totalProgress = ((message.currentStep!! - 1) * 100 + progress) / message.totalSteps!!
                            width(totalProgress.percent)
                        }
                    }) {}
                }
            }
        }
        
        Div({
            style {
                color(Color(SilkColors.textSecondary))
                marginTop(6.px)
                property("white-space", "pre-wrap")
                property("word-wrap", "break-word")
                property("line-height", "1.7")
            }
        }) {
            Text(message.content)
        }
    }
}

// 工具函数
fun generateRandomId(): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    return (1..16)
        .map { chars[Random.nextInt(chars.length)] }
        .joinToString("")
}

/**
 * 格式化时间戳为 HH:mm:ss 格式（上海时区 UTC+8）
 * 时间戳是UTC毫秒数，转换为上海时区需要加8小时偏移
 * @param timestamp 毫秒级时间戳
 * @return 格式化后的时间字符串
 */
fun formatTime(timestamp: Long): String {
    // 时间戳是UTC时间，转换为上海时区（UTC+8）
    val shanghaiOffsetMs = 8 * 60 * 60 * 1000L // 8小时的毫秒数
    val shanghaiTime = timestamp + shanghaiOffsetMs
    
    val totalSeconds = (shanghaiTime / 1000).toInt()
    val hours = (totalSeconds / 3600) % 24
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}

private fun isLikelyAgentStatusContent(content: String): Boolean {
    val text = content.trim()
    if (text.isBlank()) return false

    val statusHints = listOf(
        "正在处理",
        "思考中",
        "使用工具",
        "执行:",
        "处理中",
        "检索",
        "搜索",
        "🤔",
        "🔧",
        "⏳"
    )
    return statusHints.any { hint -> text.contains(hint) }
}

/**
 * 添加成员对话框
 */
@Composable
fun AddMemberDialog(
    contacts: List<Contact>,
    groupMembers: List<GroupMember>,
    isLoading: Boolean,
    result: String?,
    strings: com.silk.shared.i18n.Strings,
    onAddMember: (Contact) -> Unit,
    onDismiss: () -> Unit
) {
    // 过滤出不在群组中的联系人
    val memberIds = groupMembers.map { it.id }.toSet()
    val availableContacts = contacts.filter { it.contactId !in memberIds }
    
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
                maxHeight(70.vh)
                property("overflow-y", "auto")
                property("box-shadow", "0 8px 32px rgba(169, 137, 77, 0.15)")
            }
            onClick { it.stopPropagation() }
        }) {
            // 标题
            H3({
                style {
                    margin(0.px, 0.px, 20.px, 0.px)
                    color(Color(SilkColors.textPrimary))
                    fontSize(20.px)
                    property("font-weight", "600")
                }
            }) {
                Text(strings.addMembersToGroup)
            }
            
            // 结果提示
            result?.let {
                Div({
                    style {
                        backgroundColor(
                            if (it.startsWith("✅")) Color("#F0F7EE") else Color("#FFF5F5")
                        )
                        color(if (it.startsWith("✅")) Color(SilkColors.success) else Color(SilkColors.error))
                        padding(14.px)
                        borderRadius(8.px)
                        marginBottom(16.px)
                        fontSize(13.px)
                        property("border", "1px solid ${if (it.startsWith("✅")) SilkColors.success else SilkColors.error}")
                    }
                }) {
                    Text(it)
                }
            }
            
            if (isLoading) {
                Div({
                    style {
                        property("text-align", "center")
                        padding(40.px)
                        color(Color(SilkColors.textSecondary))
                    }
                }) {
                    Text(strings.loading)
                }
            } else if (availableContacts.isEmpty()) {
                Div({
                    style {
                        property("text-align", "center")
                        padding(40.px)
                        color(Color(SilkColors.textSecondary))
                    }
                }) {
                    Text(strings.noContactsToAdd)
                }
            } else {
                // 联系人列表
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Column)
                        property("gap", "12px")
                        maxHeight(400.px)
                        property("overflow-y", "auto")
                    }
                }) {
                    availableContacts.forEach { contact ->
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                justifyContent(JustifyContent.SpaceBetween)
                                alignItems(AlignItems.Center)
                                padding(16.px, 20.px)
                                backgroundColor(Color(SilkColors.surface))
                                borderRadius(10.px)
                                property("box-shadow", "0 2px 4px rgba(0,0,0,0.05)")
                                property("border", "1px solid ${SilkColors.border}")
                            }
                        }) {
                            // 联系人信息
                            Div({
                                style {
                                    display(DisplayStyle.Flex)
                                    flexDirection(FlexDirection.Column)
                                    property("gap", "4px")
                                }
                            }) {
                                Div({
                                    style {
                                        fontSize(15.px)
                                        color(Color(SilkColors.textPrimary))
                                        property("font-weight", "500")
                                    }
                                }) {
                                    Text(contact.contactName)
                                }
                                Div({
                                    style {
                                        fontSize(13.px)
                                        color(Color(SilkColors.textSecondary))
                                    }
                                }) {
                                    Text(contact.contactPhone)
                                }
                            }
                            
                            // 添加按钮
                            Button({
                                style {
                                    padding(10.px, 20.px)
                                    property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                                    color(Color.white)
                                    border { width(0.px) }
                                    borderRadius(8.px)
                                    fontSize(14.px)
                                    property("cursor", "pointer")
                                    property("font-weight", "500")
                                    property("transition", "all 0.2s ease")
                                }
                                onClick { onAddMember(contact) }
                            }) {
                                Text("添加")
                            }
                        }
                    }
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
 * 群组成员列表对话框
 */
@Composable
fun MembersDialog(
    members: List<GroupMember>,
    contacts: List<Contact>,
    currentUserId: String,
    isLoading: Boolean,
    strings: com.silk.shared.i18n.Strings,
    onMemberClick: (GroupMember) -> Unit,
    onDismiss: () -> Unit
) {
    val contactIds = contacts.map { it.contactId }.toSet()
    
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
                width(420.px)
                maxWidth(90.vw)
                maxHeight(70.vh)
                property("overflow-y", "auto")
                property("box-shadow", "0 8px 32px rgba(169, 137, 77, 0.15)")
            }
            onClick { it.stopPropagation() }
        }) {
            // 标题
            H3({
                style {
                    margin(0.px, 0.px, 20.px, 0.px)
                    color(Color(SilkColors.textPrimary))
                    fontSize(20.px)
                    property("font-weight", "600")
                    property("text-align", "center")
                }
            }) {
                Text(strings.groupMembersTitleWithCount.replace("{count}", members.size.toString()))
            }
            
            if (isLoading) {
                Div({
                    style {
                        property("text-align", "center")
                        padding(20.px)
                        color(Color(SilkColors.textSecondary))
                    }
                }) {
                    Text(strings.loading)
                }
            } else if (members.isEmpty()) {
                Div({
                    style {
                        property("text-align", "center")
                        padding(20.px)
                        color(Color(SilkColors.textSecondary))
                    }
                }) {
                    Text(strings.noMembers)
                }
            } else {
                // 成员列表
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Column)
                        property("gap", "10px")
                    }
                }) {
                    members.forEach { member ->
                        val isCurrentUser = member.id == currentUserId
                        val isContact = member.id in contactIds
                        val isSilkAI = member.id == "silk_ai_agent"
                        
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                justifyContent(JustifyContent.SpaceBetween)
                                alignItems(AlignItems.Center)
                                padding(12.px, 16.px)
                                backgroundColor(Color(SilkColors.surface))
                                borderRadius(10.px)
                                property("box-shadow", "0 2px 4px rgba(0,0,0,0.05)")
                                if (!isCurrentUser && !isSilkAI) {
                                    property("cursor", "pointer")
                                    property("transition", "all 0.2s ease")
                                }
                            }
                            if (!isCurrentUser && !isSilkAI) {
                                onClick { onMemberClick(member) }
                            }
                        }) {
                            // 成员信息
                            Div({
                                style {
                                    display(DisplayStyle.Flex)
                                    alignItems(AlignItems.Center)
                                    property("gap", "12px")
                                }
                            }) {
                                // 头像/图标
                                Div({
                                    style {
                                        width(40.px)
                                        height(40.px)
                                        borderRadius(20.px)
                                        backgroundColor(
                                            when {
                                                isSilkAI -> Color(SilkColors.info)
                                                isCurrentUser -> Color(SilkColors.primary)
                                                isContact -> Color(SilkColors.success)
                                                else -> Color(SilkColors.textSecondary)
                                            }
                                        )
                                        display(DisplayStyle.Flex)
                                        justifyContent(JustifyContent.Center)
                                        alignItems(AlignItems.Center)
                                        color(Color.white)
                                        fontSize(18.px)
                                    }
                                }) {
                                    Text(
                                        when {
                                            isSilkAI -> "🤖"
                                            isCurrentUser -> "👤"
                                            isContact -> "✓"
                                            else -> member.fullName.firstOrNull()?.toString() ?: "?"
                                        }
                                    )
                                }
                                
                                // 名字和状态
                                Div {
                                    Div({
                                        style {
                                            fontSize(15.px)
                                            color(Color(SilkColors.textPrimary))
                                            property("font-weight", "500")
                                        }
                                    }) {
                                        Text(member.fullName)
                                        if (isCurrentUser) {
                                            Span({
                                                style {
                                                    fontSize(12.px)
                                                    color(Color(SilkColors.textSecondary))
                                                    marginLeft(8.px)
                                                }
                                            }) {
                                                Text(strings.me)
                                            }
                                        }
                                    }
                                    Div({
                                        style {
                                            fontSize(12.px)
                                            color(Color(SilkColors.textSecondary))
                                            marginTop(2.px)
                                        }
                                    }) {
                                        Text(
                                            when {
                                                isSilkAI -> strings.aiAssistant
                                                isCurrentUser -> strings.currentUser
                                                isContact -> strings.contactClickToChat
                                                else -> strings.clickToAddContact
                                            }
                                        )
                                    }
                                }
                            }
                            
                            // 右侧操作提示
                            if (!isCurrentUser && !isSilkAI) {
                                Div({
                                    style {
                                        fontSize(20.px)
                                        color(Color(SilkColors.textLight))
                                    }
                                }) {
                                    Text(if (isContact) "💬" else "➕")
                                }
                            }
                        }
                    }
                }
            }
            
            // 关闭按钮
            Button({
                style {
                    width(100.percent)
                    marginTop(20.px)
                    backgroundColor(Color(SilkColors.textSecondary))
                    color(Color.white)
                    border { width(0.px) }
                    borderRadius(10.px)
                    padding(12.px)
                    property("cursor", "pointer")
                    fontSize(14.px)
                    property("font-weight", "500")
                }
                onClick { onDismiss() }
            }) {
                Text("关闭")
            }
        }
    }
}

/**
 * 复制文本到剪贴板（Web版）
 */
fun copyTextToClipboard(text: String) {
    val clipboard = kotlinx.browser.window.navigator.asDynamic().clipboard
    if (clipboard != null) {
        clipboard.writeText(text).then(
            { console.log("✅ 已复制到剪贴板") },
            { _: dynamic -> fallbackCopyToClipboard(text) }
        )
    } else {
        fallbackCopyToClipboard(text)
    }
}

private fun fallbackCopyToClipboard(text: String) {
    val document = kotlinx.browser.document
    val textarea = document.createElement("textarea") as org.w3c.dom.HTMLTextAreaElement
    textarea.value = text
    textarea.style.position = "fixed"
    textarea.style.left = "-9999px"
    document.body?.appendChild(textarea)
    textarea.select()
    try {
        document.execCommand("copy")
        console.log("✅ 使用备用方案复制成功")
    } catch (e: dynamic) {
        console.error("❌ 备用方案复制失败:", e)
    }
    document.body?.removeChild(textarea)
}
