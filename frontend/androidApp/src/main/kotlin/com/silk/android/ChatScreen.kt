package com.silk.android

import android.content.Intent
import android.net.Uri
import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.silk.shared.ChatClient
import com.silk.shared.ConnectionState
import com.silk.shared.models.Message
import com.silk.shared.models.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONObject
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView

// Web版的 collectAsState 实现
@Composable
fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsState(): State<T> {
    val state = remember { mutableStateOf(value) }
    LaunchedEffect(this) {
        collect { state.value = it }
    }
    return state
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(appState: AppState) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    val user = appState.currentUser ?: return
    val group = appState.selectedGroup ?: return
    
    // WebSocket URL - 与 BackendUrlHolder 一致（应用内设置或构建时默认）
    val baseUrl = BackendUrlHolder.getBaseUrl()
    val wsUrl = baseUrl.replaceFirst("http", "ws")
    
    // 收集调试日志（只保留最后32行）
    val debugLogs = remember { mutableStateListOf<String>() }
    
    // 添加日志函数（自动限制到最后32行）
    fun addLog(message: String) {
        debugLogs.add(message)
        // 只保留最后32行
        while (debugLogs.size > 32) {
            debugLogs.removeAt(0)
        }
        println(message)
    }
    
    val chatClient = remember { 
        addLog("🔧 创建 ChatClient，URL: $wsUrl")
        ChatClient(wsUrl) { logMessage ->
            addLog(logMessage)
        }
    }
    
    val messages by chatClient.messages.collectAsState()
    val transientMessage by chatClient.transientMessage.collectAsState()
    val statusMessages by chatClient.statusMessages.collectAsState()
    val connectionState by chatClient.connectionState.collectAsState()
    
    // Track if we've sent the default instruction for this session
    var hasSentDefaultInstruction by remember { mutableStateOf(false) }
    
    var messageText by remember { mutableStateOf(TextFieldValue("")) }
    var showInvitationDialog by remember { mutableStateOf(false) }
    var isExiting by remember { mutableStateOf(false) }
    var showDebugInfo by remember { mutableStateOf(false) }
    
    // AI 响应等待状态 - 必须在 LaunchedEffect 之前定义
    var isWaitingForAI by remember { mutableStateOf(false) }
    
    // 监控消息列表变化
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val last = messages.last()
            addLog("📊 消息列表+1: 总共${messages.size}条")
            addLog("   最新: ${last.userName}: ${last.content.take(20)}...")
            
            // 收到 Silk 的响应时，清除等待状态
            if (last.userName == "Silk" && isWaitingForAI) {
                isWaitingForAI = false
                addLog("✅ AI 响应已收到，清除等待状态")
            }
        }
    }
    
    // 监控临时消息变化
    LaunchedEffect(transientMessage) {
        if (transientMessage != null) {
            addLog("⏳ 临时消息: ${transientMessage!!.content.take(20)}... (${transientMessage!!.content.length}字)")
        } else {
            addLog("🗑️ 临时消息已清除")
        }
    }
    
    // 文件上传相关
    var isUploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf("") }
    var showFolderExplorer by remember { mutableStateOf(false) }
    var folderFiles by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var processedUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingFiles by remember { mutableStateOf(false) }
    
    // 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            scope.launch {
                isUploading = true
                uploadProgress = "正在上传..."
                try {
                    val fileName = getFileName(context, selectedUri)
                    addLog("📎 开始上传文件: $fileName")
                    
                    val inputStream = context.contentResolver.openInputStream(selectedUri)
                    if (inputStream != null) {
                        val success = uploadFile(
                            inputStream = inputStream,
                            fileName = fileName,
                            sessionId = group.id,
                            userId = user.id,
                            onProgress = { progress -> uploadProgress = progress }
                        )
                        if (success) {
                            addLog("✅ 文件上传成功: $fileName")
                            // 发送上传成功消息
                            chatClient.sendMessage(user.id, user.fullName, "📎 已上传文件: $fileName")
                        } else {
                            addLog("❌ 文件上传失败")
                        }
                    }
                } catch (e: Exception) {
                    addLog("❌ 上传异常: ${e.message}")
                } finally {
                    isUploading = false
                    uploadProgress = ""
                }
            }
        }
    }
    
    // 消息选择模式
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedMessages = remember { mutableStateListOf<String>() }  // 存储选中消息的ID
    
    // ✅ 转发对话框状态
    var showForwardToGroupDialog by remember { mutableStateOf(false) }
    var showForwardToContactDialog by remember { mutableStateOf(false) }
    var userGroups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var isLoadingGroups by remember { mutableStateOf(false) }
    var forwardResult by remember { mutableStateOf<String?>(null) }
    // ✅ 单条消息转发状态（用于 AI 消息等直接点击转发按钮的情况）
    var messageToForward by remember { mutableStateOf<Message?>(null) }
    
    // 添加联系人对话框状态
    var showAddContactConfirm by remember { mutableStateOf<Message?>(null) }
    var isAddingContact by remember { mutableStateOf(false) }
    var addContactResult by remember { mutableStateOf<String?>(null) }
    
    // 添加成员到群组状态
    var showAddMemberDialog by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var groupMembers by remember { mutableStateOf<List<GroupMember>>(emptyList()) }
    var isLoadingContacts by remember { mutableStateOf(false) }
    var addMemberResult by remember { mutableStateOf<String?>(null) }
    
    // 消息撤回相关状态
    var recallingMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }  // 正在撤回中的消息ID集合
    var recallResult by remember { mutableStateOf<String?>(null) }  // 撤回结果提示
    
    // 查看成员列表状态
    var showMembersDialog by remember { mutableStateOf(false) }
    var selectedMemberForInvite by remember { mutableStateOf<GroupMember?>(null) }
    var isInvitingMember by remember { mutableStateOf(false) }
    var inviteMemberResult by remember { mutableStateOf<String?>(null) }
    
    // @ mention 功能状态（输入 @ 后弹出群成员/会话用户列表）
    var showMentionMenu by remember { mutableStateOf(false) }
    var mentionSearchText by remember { mutableStateOf("") }
    var mentionStartIndex by remember { mutableStateOf(-1) }
    
    // 从消息历史和群组成员列表中提取用户列表（去重），用于 @ 提及下拉
    // 优先使用 groupMembers（包含所有群组成员），同时合并消息历史中的用户
    val sessionUsers = remember(messages, groupMembers) {
        val users = mutableSetOf<Pair<String, String>>() // (id, name)
        // 始终添加 Silk AI
        users.add("silk_ai_agent" to "🤖 Silk")
        // 添加当前用户
        users.add(user.id to user.fullName)
        // 从群组成员列表添加所有成员
        groupMembers.forEach { member ->
            if (member.id != "silk_ai_agent" && member.id != user.id) {
                users.add(member.id to member.fullName)
            }
        }
        // 从消息中提取其他用户（作为补充，以防成员列表不完整）
        messages.forEach { msg ->
            if (msg.userId != "silk_ai_agent" && msg.userId != user.id) {
                users.add(msg.userId to msg.userName)
            }
        }
        users.toList()
    }
    
    LaunchedEffect(Unit) {
        addLog("📱 应用版本: 1.0.12-robust-reconnect (Build 12)")
        addLog("📱 Android版本: ${android.os.Build.VERSION.RELEASE}")
        addLog("📱 设备型号: ${android.os.Build.MODEL}")
        addLog("🔧 WebSocket URL: $wsUrl")
        addLog("🔧 HTTP BASE_URL: $baseUrl")
    }
    
    val listState = rememberLazyListState()
    
    // ✅ AI 消息展开状态管理 - 使用 Map 存储每个消息的展开状态
    val aiMessageExpandedStates = remember { mutableStateMapOf<String, Boolean>() }
    
    // ✅ 当展开/收起 AI 消息时，滚动到该消息位置
    val scopeForScroll = rememberCoroutineScope()
    fun onAIExpandChange(messageId: String, isExpanded: Boolean) {
        aiMessageExpandedStates[messageId] = isExpanded
        println("🤖 AI消息展开状态变化: messageId=$messageId, isExpanded=$isExpanded")
    }
    
    // 显示连接状态
    LaunchedEffect(connectionState) {
        addLog("📊 连接状态变化: $connectionState")
    }
    
    // 连接WebSocket（在后台协程中保持连接）
    LaunchedEffect(group.id) {
        // Reset flag when group changes
        hasSentDefaultInstruction = false
        
        addLog("━━━━━━━━━━━━━━━━━━━━━━━━")
        addLog("🔌 开始连接WebSocket...")
        addLog("   URL: $wsUrl")
        addLog("   用户: ${user.fullName}")
        addLog("   用户ID: ${user.id}")
        addLog("   群组: ${group.name}")
        addLog("   群组ID: ${group.id}")
        addLog("━━━━━━━━━━━━━━━━━━━━━━━━")
        
        // 加载群成员列表（用于 @ mention 功能）
        try {
            val membersResponse = ApiClient.getGroupMembers(group.id)
            groupMembers = membersResponse.members.sortedByDescending { it.id == group.hostId }
            addLog("✅ 群成员列表已加载，共 ${groupMembers.size} 人")
        } catch (e: Exception) {
            addLog("❌ 加载群成员列表失败: ${e.message}")
        }
        
        // 在单独的协程中保持连接
        launch {
            try {
                addLog("⏳ 启动 chatClient.connect()...")
                addLog("⚠️ 注意：此调用会持续运行直到断开连接")
                chatClient.connect(user.id, user.fullName, group.id)
                addLog("⚠️ connect() 返回了（连接已关闭）")
            } catch (e: Exception) {
                addLog("❌ connect() 抛出异常!")
                addLog("   错误: ${e.message}")
                addLog("   类型: ${e::class.simpleName}")
                addLog("   Cause: ${e.cause?.message}")
                e.printStackTrace()
            }
        }
        
        // 等待一下让日志有序
        kotlinx.coroutines.delay(100)
        addLog("━━━━━━━━━━━━━━━━━━━━━━━━")
        addLog("✅ WebSocket 连接协程已启动")
        addLog("━━━━━━━━━━━━━━━━━━━━━━━━")
    }
    
    // ✅ 跟踪是否是首次加载（历史消息加载中）
    var isHistoryLoading by remember { mutableStateOf(true) }
    var lastMessageCount by remember { mutableStateOf(0) }
    
    // ✅ 检测历史消息加载完成：10ms 后显示
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && isHistoryLoading) {
            lastMessageCount = messages.size
            kotlinx.coroutines.delay(10)  // 等待 10ms
            if (lastMessageCount == messages.size) {
                isHistoryLoading = false
                listState.scrollToItem(0)  // 直接跳转到底部，无动画
            }
        } else if (!isHistoryLoading && messages.size > lastMessageCount) {
            // 历史加载完成后的新消息，使用动画滚动
            lastMessageCount = messages.size
            listState.animateScrollToItem(0)
        }
    }
    
    // ✅ 首次出现临时消息时，确保显示在底部
    val hasTransient = transientMessage != null
    LaunchedEffect(hasTransient) {
        if (hasTransient && messages.isNotEmpty()) {
            kotlinx.coroutines.delay(100)  // 短暂延迟确保UI已渲染
            listState.scrollToItem(0)  // 直接跳转，无动画
        }
    }
    
    // 重新连接后显示最新消息
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.CONNECTED && messages.isNotEmpty()) {
            kotlinx.coroutines.delay(300)
            listState.scrollToItem(0)  // 直接跳转，无动画
        }
    }
    
    // 等待 AI 响应时确保显示等待状态
    LaunchedEffect(isWaitingForAI) {
        if (isWaitingForAI) {
            kotlinx.coroutines.delay(100)  // 等待 UI 更新
            listState.scrollToItem(0)  // 直接跳转，无动画
        }
    }
    
    // ✅ 使用前台服务保持 WebSocket 连接活跃
    // 前台服务会在应用切换到后台时保持连接，避免显示"连接已断开"
    val lifecycleOwner = LocalLifecycleOwner.current
    var connectionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // 跟踪是否需要重连
    var needReconnect by remember { mutableStateOf(false) }
    var lastConnectionTime by remember { mutableStateOf(0L) }
    
    // 启动前台服务
    LaunchedEffect(Unit) {
        WebSocketForegroundService.start(context, group.name)
        addLog("🚀 [前台服务] 已启动 WebSocket 保活服务")
    }
    
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    scope.launch {
                        addLog("🔄 [生命周期] 应用返回前台")
                        addLog("   当前连接状态: $connectionState")
                        
                        // ✅ 如果连接已断开或正在连接中，才尝试重连
                        if (connectionState != ConnectionState.CONNECTED) {
                            addLog("🔄 [生命周期] 连接未就绪，检查是否需要重连...")
                            
                            // 等待一小段时间，让连接状态稳定
                            kotlinx.coroutines.delay(500)
                            
                            if (connectionState == ConnectionState.DISCONNECTED) {
                                addLog("🔌 [生命周期] 连接已断开，尝试重新连接...")
                                needReconnect = true
                                
                                // 取消旧的连接协程
                                connectionJob?.cancel()
                                kotlinx.coroutines.delay(300)
                                
                                // 启动新的连接
                                connectionJob = scope.launch {
                                    try {
                                        addLog("🔌 建立新的WebSocket连接...")
                                        chatClient.connect(user.id, user.fullName, group.id)
                                        
                                        // 等待连接建立
                                        var attempts = 0
                                        while (connectionState != ConnectionState.CONNECTED && attempts < 10) {
                                            kotlinx.coroutines.delay(500)
                                            attempts++
                                            addLog("⏳ 等待连接建立... (${attempts}/10)")
                                        }
                                        
                                        if (connectionState == ConnectionState.CONNECTED) {
                                            addLog("✅ WebSocket重新连接成功！")
                                            lastConnectionTime = System.currentTimeMillis()
                                            
                                            // 连接成功后滚动到最新消息
                                            kotlinx.coroutines.delay(300)
                                            if (messages.isNotEmpty()) {
                                                listState.scrollToItem(0)
                                            }
                                        } else {
                                            addLog("❌ WebSocket重新连接超时")
                                        }
                                    } catch (e: Exception) {
                                        addLog("❌ 重新连接失败: ${e.message}")
                                    }
                                }
                            } else if (connectionState == ConnectionState.CONNECTING) {
                                addLog("⏳ [生命周期] 连接正在建立中，等待完成...")
                            }
                        } else {
                            addLog("✅ [生命周期] 连接正常，无需重连")
                            // 确保前台服务正在运行
                            WebSocketForegroundService.start(context, group.name)
                        }
                        
                        needReconnect = false
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    // ✅ 进入后台时保持连接不断开
                    // 前台服务会维持连接活跃
                    scope.launch {
                        addLog("⏸️ [生命周期] 应用进入后台 - 保持连接（前台服务保活）")
                        addLog("   当前连接状态: $connectionState")
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    // ✅ 即使在 onStop 也不断开连接
                    // 前台服务会确保连接保持活跃
                    scope.launch {
                        addLog("⏹️ [生命周期] 应用停止 - 前台服务保活中")
                        addLog("   当前连接状态: $connectionState")
                    }
                }
                else -> {}
            }
        }
        
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            scope.launch {
                addLog("🔌 [生命周期] 离开聊天界面，清理资源...")
                
                // 停止前台服务
                WebSocketForegroundService.stop(context)
                addLog("🛑 [前台服务] 已停止 WebSocket 保活服务")
                
                // 断开连接
                chatClient.disconnect()
                
                // 标记已读
                kotlinx.coroutines.delay(300)
                appState.currentUser?.let { user ->
                    try {
                        ApiClient.markGroupAsRead(user.id, group.id)
                        addLog("✅ 已标记群组为已读")
                    } catch (e: Exception) {
                        addLog("⚠️ 标记已读失败: ${e.message}")
                    }
                }
            }
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.fillMaxWidth()) {  // 不限制宽度
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.bodySmall,  // 缩小字体
                            maxLines = 1,  // 单行显示
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis  // 超出显示省略号
                        )
                        Text(
                            text = "${messages.size} 条消息",
                            style = MaterialTheme.typography.labelSmall,  // 更小的字体
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (!isExiting) {
                                isExiting = true
                                scope.launch {
                                    // 先断开连接，确保所有消息处理完成
                                    chatClient.disconnect()
                                    chatClient.clearMessages()
                                    
                                    // 等待服务器完成所有消息处理
                                    kotlinx.coroutines.delay(500)
                                    
                                    // 最后标记已读 - 在断开连接之后调用
                                    // 这样可以确保用户发送的消息已被服务器处理
                                    // 标记时间会晚于所有消息的时间戳
                                    appState.currentUser?.let { user ->
                                        try {
                                            ApiClient.markGroupAsRead(user.id, group.id)
                                            println("✅ 已标记群组 ${group.id} 为已读")
                                        } catch (e: Exception) {
                                            println("⚠️ 标记已读失败: ${e.message}")
                                        }
                                    }
                                    
                                    appState.navigateBack()
                                }
                            }
                        },
                        enabled = !isExiting
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 如果在选择模式，显示操作按钮
                    if (isSelectionMode) {
                        // 使用水平滚动以适应更多按钮 - 使用 TextButton 替代 IconButton 避免圆形裁切
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            // 📋 复制到剪贴板
                            TextButton(
                                onClick = {
                                    val selectedContent = messages
                                        .filter { selectedMessages.contains(it.id) }
                                        .sortedBy { it.timestamp }
                                        .joinToString("\n\n") { "${it.userName}:\n${it.content}" }
                                    
                                    if (selectedContent.isNotEmpty()) {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) 
                                            as android.content.ClipboardManager
                                        val clip = android.content.ClipData.newPlainText("消息", selectedContent)
                                        clipboard.setPrimaryClip(clip)
                                        android.widget.Toast.makeText(
                                            context,
                                            "已复制 ${selectedMessages.size} 条消息",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    isSelectionMode = false
                                    selectedMessages.clear()
                                },
                                enabled = selectedMessages.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("📋复制", fontSize = 12.sp, color = Color.White)
                            }
                            
                            // 💬 转发到其他 Silk 对话
                            TextButton(
                                onClick = {
                                    // 加载用户的群组列表
                                    scope.launch {
                                        isLoadingGroups = true
                                        val response = ApiClient.getUserGroups(user.id)
                                        userGroups = response.groups?.filter { it.id != group.id } ?: emptyList()
                                        isLoadingGroups = false
                                        showForwardToGroupDialog = true
                                    }
                                },
                                enabled = selectedMessages.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("💬转发", fontSize = 12.sp, color = Color.White)
                            }
                            
                            // 👤 转发到联系人
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        isLoadingContacts = true
                                        val contactsResponse = ApiClient.getContacts(user.id)
                                        contacts = contactsResponse.contacts ?: emptyList()
                                        isLoadingContacts = false
                                        showForwardToContactDialog = true
                                    }
                                },
                                enabled = selectedMessages.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("👤私聊", fontSize = 12.sp, color = Color.White)
                            }
                            
                            // 📤 分享到其他应用
                            TextButton(
                                onClick = {
                                    val selectedContent = messages
                                        .filter { selectedMessages.contains(it.id) }
                                        .sortedBy { it.timestamp }
                                        .joinToString("\n\n") { msg ->
                                            val time = SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).apply {
                                                timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
                                            }.format(java.util.Date(msg.timestamp))
                                            "[$time] ${msg.userName}:\n${msg.content}"
                                        }
                                    
                                    if (selectedContent.isNotEmpty()) {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, selectedContent)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "分享到"))
                                    }
                                    isSelectionMode = false
                                    selectedMessages.clear()
                                },
                                enabled = selectedMessages.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("📤分享", fontSize = 12.sp, color = Color.White)
                            }
                            
                            // ✕ 取消选择
                            TextButton(
                                onClick = {
                                    isSelectionMode = false
                                    selectedMessages.clear()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("✕取消", fontSize = 12.sp, color = Color.White)
                            }
                        }
                    } else {
                        // 正常模式的按钮
                        
                        // ☑️ 选择模式按钮 - 点击进入选择模式
                        IconButton(
                            onClick = {
                                isSelectionMode = true
                                selectedMessages.clear()
                                android.widget.Toast.makeText(
                                    context,
                                    "已进入选择模式，点击消息进行选择",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        ) {
                            Text("☑️", fontSize = 16.sp)
                        }
                        
                        // 📁 文件夹浏览器按钮
                        IconButton(
                            onClick = { 
                                showFolderExplorer = true
                                isLoadingFiles = true
                                // 加载文件列表和 URL 清单
                                scope.launch {
                                    try {
                                        val result = loadGroupFilesAndUrls(group.id)
                                        folderFiles = result.files
                                        processedUrls = result.processedUrls
                                    } catch (e: Exception) {
                                        addLog("❌ 加载文件列表失败: ${e.message}")
                                    } finally {
                                        isLoadingFiles = false
                                    }
                                }
                            }
                        ) {
                            Text("📁", fontSize = 16.sp)
                        }
                        
                        // 📎 上传文件按钮
                        IconButton(
                            onClick = { 
                                if (!isUploading) {
                                    filePickerLauncher.launch("*/*")
                                }
                            },
                            enabled = !isUploading
                        ) {
                            Text(
                                text = if (isUploading) "⏳" else "📎", 
                                fontSize = 16.sp
                            )
                        }
                        
                        // 邀请按钮
                        IconButton(onClick = { showInvitationDialog = true }) {
                            Icon(Icons.Default.Share, contentDescription = "邀请", modifier = Modifier.size(20.dp))
                        }
                        
                        // ➕ 添加成员按钮
                        IconButton(
                            onClick = {
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
                        ) {
                            Text("➕", fontSize = 16.sp)
                        }
                        
                        // 👥 查看成员按钮
                        IconButton(
                            onClick = {
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
                        ) {
                            Text("👥", fontSize = 16.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()  // ✅ 键盘弹出时自动调整内容，避免历史消息被遮挡
        ) {
            // 连接状态指示器
            if (connectionState == ConnectionState.CONNECTING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "正在连接...",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            
            // 上传状态显示（浅灰色）
            if (isUploading && uploadProgress.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.LightGray.copy(alpha = 0.3f)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.Gray
                        )
                        Text(
                            text = "📎 $uploadProgress",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }
            }
            
            // 消息列表 - 使用 reverseLayout 让最新消息贴近输入框
            // ✅ 底部对齐：使用 Box 包裹 LazyColumn，确保内容贴底显示
            // 这样当键盘弹出时，历史信息不会滚动到屏幕外
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter  // ✅ 内容底部对齐
            ) {
                // ✅ 使用 Crossfade 实现平滑过渡动画
                Crossfade(
                    targetState = isHistoryLoading && messages.isNotEmpty(),
                    animationSpec = tween(durationMillis = 300),
                    label = "history_loading"
                ) { loading ->
                    if (loading) {
                        // 加载中：显示加载指示器
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = SilkColors.primary)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("加载历史消息...", color = SilkColors.textSecondary)
                            }
                        }
                    } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                reverseLayout = true  // 从底部开始显示，最新消息贴近输入框
            ) {
                if (messages.isEmpty() && transientMessage == null && statusMessages.isEmpty() && !isWaitingForAI) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "暂无消息，开始聊天吧！",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // ✅ reverseLayout=true 时，第一个 item 显示在底部（靠近输入框）
                    // 所以状态消息要放在最前面，才能显示在最底部
                    
                    // 1️⃣ 状态消息（搜索、索引等过程状态）
                    // 仅在最终答案尚未开始流式生成前显示
                    val hasStartedFinalAnswer = transientMessage?.let { msg ->
                        msg.content.isNotBlank() && msg.currentStep == null && msg.totalSteps == null
                    } == true
                    if ((statusMessages.isNotEmpty() || isWaitingForAI) && !hasStartedFinalAnswer) {
                        item(key = "status_messages") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                // 如果有服务器状态消息，清除本地等待状态
                                if (statusMessages.isNotEmpty() && isWaitingForAI) {
                                    LaunchedEffect(statusMessages) {
                                        isWaitingForAI = false
                                    }
                                }

                                // 服务器状态消息（按时间倒序显示，最新的在上面）
                                statusMessages.reversed().forEach { statusMsg ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            strokeWidth = 1.5.dp,
                                            color = Color.Gray.copy(alpha = 0.6f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = statusMsg.content,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray.copy(alpha = 0.7f)
                                        )
                                    }
                                }

                                // 本地等待状态（服务器状态消息到达前显示）
                                if (isWaitingForAI && statusMessages.isEmpty()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = Color.Gray.copy(alpha = 0.7f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "🤔 Silk 正在思考...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 2️⃣ 临时消息（AI处理中）- 不支持选择
                    transientMessage?.let { message ->
                        item(key = "transient_message") {
                            MessageItem(
                                message = message,
                                currentUserId = user.id,
                                context = context,
                                isTransient = true,
                                isSelectionMode = false,
                                isSelected = false,
                                onToggleSelection = {},
                                onUserNameClick = null
                            )
                        }
                    }
                    
                    // 3️⃣ 普通消息列表（反转顺序，最新的在第0位 = 显示在底部）
                    items(messages.reversed(), key = { it.id }) { message ->
                        MessageItem(
                            message = message,
                            currentUserId = user.id,
                            context = context,
                            isTransient = false,
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedMessages.contains(message.id),
                            onToggleSelection = { messageId ->
                                if (selectedMessages.contains(messageId)) {
                                    selectedMessages.remove(messageId)
                                } else {
                                    selectedMessages.add(messageId)
                                }
                            },
                            onLongPress = { messageId ->
                                // ✅ 长按进入选择模式并选中该消息
                                println("🔴🔴🔴 [ChatScreen] onLongPress 被调用! messageId=$messageId, isSelectionMode=$isSelectionMode")
                                if (!isSelectionMode) {
                                    isSelectionMode = true
                                    selectedMessages.clear()
                                    selectedMessages.add(messageId)
                                    println("🔴🔴🔴 [ChatScreen] 已进入选择模式，选中消息: $messageId")
                                    // 触觉反馈
                                    android.os.Build.VERSION.SDK_INT.let {
                                        if (it >= android.os.Build.VERSION_CODES.O) {
                                            (context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator)
                                                ?.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                                        }
                                    }
                                }
                            },
                            onUserNameClick = { clickedMessage ->
                                // 点击用户名，弹出添加联系人确认对话框
                                if (clickedMessage.userId != user.id) {
                                    showAddContactConfirm = clickedMessage
                                }
                            },
                            // 撤回相关参数
                            isRecalling = message.id in recallingMessageIds,
                            onRecall = { messageId ->
                                if (messageId !in recallingMessageIds) {
                                    recallingMessageIds = recallingMessageIds + messageId
                                    scope.launch {
                                        try {
                                            val response = ApiClient.recallMessage(group.id, messageId, user.id)
                                            if (!response.success) {
                                                android.widget.Toast.makeText(context, "撤回失败: ${response.message}", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            println("❌ 撤回消息失败: ${e.message}")
                                            android.widget.Toast.makeText(context, "撤回失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        } finally {
                                            recallingMessageIds = recallingMessageIds - messageId
                                        }
                                    }
                                }
                            },
                            // AI 消息展开状态（默认收起，只有长内容才需要展开/收起功能）
                            isAIExpanded = aiMessageExpandedStates[message.id] ?: false,
                            onAIExpandChange = { messageId, isExpanded ->
                                // 只切换展开状态，不做任何列表滚动，避免视图跳动
                                aiMessageExpandedStates[messageId] = isExpanded
                            },
                            // 复制功能
                            onCopy = { content ->
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) 
                                    as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("消息", content)
                                clipboard.setPrimaryClip(clip)
                                android.widget.Toast.makeText(context, "已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            // 转发功能 - 单条消息转发
                            onForward = { msg ->
                                // 设置要转发的单条消息
                                messageToForward = msg
                                scope.launch {
                                    isLoadingGroups = true
                                    val response = ApiClient.getUserGroups(user.id)
                                    userGroups = response.groups?.filter { it.id != group.id } ?: emptyList()
                                    isLoadingGroups = false
                                    showForwardToGroupDialog = true
                                }
                            }
                        )
                    }
                }
            }
                    }  // ✅ else 结束 - 历史加载完成后的内容
                }  // ✅ Crossfade 结束
            }  // ✅ Box 结束 - 底部对齐容器
            
            // 输入框区域
            if (connectionState == ConnectionState.CONNECTED) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    ) {
                        // @Silk 快捷按钮（在 Silk 私聊中隐藏）
                        val isSilkPrivateChat = group.name.startsWith("[Silk]")
                        if (!isSilkPrivateChat) {
                            Surface(
                                onClick = {
                                    val prefix = "@Silk "
                                    if (!messageText.text.startsWith(prefix)) {
                                        val newText = prefix + messageText.text
                                        val newSelection = TextRange(
                                            start = (messageText.selection.start + prefix.length).coerceIn(0, newText.length),
                                            end = (messageText.selection.end + prefix.length).coerceIn(0, newText.length)
                                        )
                                        messageText = messageText.copy(
                                            text = newText,
                                            selection = newSelection
                                        )
                                    }
                                },
                                color = SilkColors.primary.copy(alpha = 0.15f),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = "@Silk",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SilkColors.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // 输入框容器（用于 @ 提及下拉）
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                // @ 提及下拉列表（输入 @ 后显示群成员/会话用户）
                                if (showMentionMenu) {
                                    val filteredUsers = sessionUsers.filter { (_, name) ->
                                        mentionSearchText.isEmpty() ||
                                            name.lowercase().contains(mentionSearchText.lowercase())
                                    }
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 200.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        shape = RoundedCornerShape(8.dp),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                                    ) {
                                        if (filteredUsers.isEmpty()) {
                                            Text(
                                                text = "无匹配用户",
                                                modifier = Modifier.padding(12.dp, 16.dp),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        } else {
                                            LazyColumn(
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                items(filteredUsers.size) { index ->
                                                    val (userId, userName) = filteredUsers[index]
                                                    val displayName = if (userId == "silk_ai_agent") "Silk" else userName
                                                    Surface(
                                                        onClick = {
                                                            val beforeAt = messageText.text.substring(0, mentionStartIndex.coerceAtLeast(0))
                                                            val newText = "$beforeAt@$displayName "
                                                            messageText = TextFieldValue(
                                                                text = newText,
                                                                selection = TextRange(newText.length)
                                                            )
                                                            showMentionMenu = false
                                                            mentionStartIndex = -1
                                                        },
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Text(
                                                            text = userName,
                                                            modifier = Modifier.padding(10.dp, 12.dp),
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = if (userId == "silk_ai_agent") FontWeight.SemiBold else FontWeight.Normal
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                
                                // 输入框和发送按钮在同一行
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // 输入框
                                    OutlinedTextField(
                                        value = messageText,
                                        onValueChange = { newValue ->
                                            val oldText = messageText.text
                                            messageText = newValue
                                            val newText = newValue.text
                                            // 检测刚输入的 @
                                            if (newText.length > oldText.length) {
                                                val lastChar = newText.getOrNull(newText.length - 1)
                                                if (lastChar == '@') {
                                                    showMentionMenu = true
                                                    mentionStartIndex = newText.length - 1
                                                    mentionSearchText = ""
                                                    return@OutlinedTextField
                                                }
                                            }
                                            // 处于 @ 提及模式时，更新搜索或遇空格/删除@时关闭
                                            if (showMentionMenu && mentionStartIndex >= 0) {
                                                if (mentionStartIndex >= newText.length || newText.getOrNull(mentionStartIndex) != '@') {
                                                    showMentionMenu = false
                                                    mentionStartIndex = -1
                                                } else {
                                                    val textAfterAt = newText.substring(mentionStartIndex + 1)
                                                    val spaceIndex = textAfterAt.indexOf(' ')
                                                    if (spaceIndex >= 0) {
                                                        showMentionMenu = false
                                                        mentionStartIndex = -1
                                                    } else {
                                                        mentionSearchText = textAfterAt
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        placeholder = { Text(if (group.name.startsWith("[Silk]")) "直接输入消息与 Silk 对话..." else "输入消息... @ 提及成员 / @silk 提问AI") },
                                        maxLines = 3
                                    )
                                    
                                    // 发送按钮
                                    Button(
                                        onClick = {
                                            if (messageText.text.isNotBlank()) {
                                                val msgContent = messageText.text
                                                addLog("📤 发送消息: ${msgContent.take(20)}...")
                                                
                                                // 如果是 @silk 消息，立即显示等待状态
                                                if (msgContent.lowercase().startsWith("@silk")) {
                                                    isWaitingForAI = true
                                                    addLog("⏳ 开始等待 AI 响应...")
                                                }
                                                
                                                scope.launch {
                                                    chatClient.sendMessage(user.id, user.fullName, msgContent)
                                                    addLog("✅ 消息已发送")
                                                    messageText = TextFieldValue("")
                                                }
                                            }
                                        },
                                        enabled = messageText.text.isNotBlank(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = SilkColors.primary
                                        ),
                                        modifier = Modifier.height(56.dp)  // 匹配输入框高度
                                    ) {
                                        Icon(Icons.Default.Send, contentDescription = "发送")
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = "连接已断开，正在重新连接...",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
    
    // 调试信息对话框
    if (showDebugInfo) {
        AlertDialog(
            onDismissRequest = { showDebugInfo = false },
            title = { Text("🐛 调试信息") },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    items(debugLogs.size) { index ->
                        Text(
                            text = debugLogs[index],
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                    item {
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = "当前连接状态: $connectionState",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = when (connectionState) {
                                ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                                ConnectionState.CONNECTING -> MaterialTheme.colorScheme.secondary
                                ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
                            }
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showDebugInfo = false }) {
                    Text("关闭")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        // 复制日志到剪贴板
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) 
                            as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText(
                            "调试日志",
                            debugLogs.joinToString("\n")
                        )
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(
                            context,
                            "日志已复制到剪贴板",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Text("复制日志")
                }
            }
        )
    }
    
    // 邀请对话框
    if (showInvitationDialog) {
        InvitationDialog(
            group = group,
            onDismiss = { showInvitationDialog = false }
        )
    }
    
    // 添加成员对话框
    if (showAddMemberDialog) {
        AddMemberDialog(
            contacts = contacts,
            groupMembers = groupMembers,
            groupId = group.id,
            isLoading = isLoadingContacts,
            result = addMemberResult,
            onAddMember = { contact ->
                scope.launch {
                    val response = ApiClient.addMemberToGroup(group.id, contact.contactId)
                    addMemberResult = if (response.success) {
                        // 刷新成员列表
                        val membersResponse = ApiClient.getGroupMembers(group.id)
                        // 将群主排在第一位
                        groupMembers = membersResponse.members.sortedByDescending { it.id == group.hostId }
                        "✅ 已添加 ${contact.contactName}"
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
                        } catch (e: Exception) { }
                        
                        // 调用API获取或创建与该联系人的对话
                        val response = ApiClient.startPrivateChat(user.id, member.id)
                        if (response.success && response.group != null) {
                            // 导航到新的对话
                            appState.selectGroup(response.group!!)
                        } else {
                            android.widget.Toast.makeText(
                                context,
                                "无法创建对话: ${response.message}",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
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
        AlertDialog(
            onDismissRequest = { 
                selectedMemberForInvite = null 
                inviteMemberResult = null
            },
            title = { 
                Text(
                    "添加联系人",
                    fontWeight = FontWeight.Bold,
                    color = SilkColors.primary
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("${member.fullName} 不在您的联系人列表中。")
                    Text("是否发送联系人请求？")
                    
                    if (inviteMemberResult != null) {
                        Text(
                            text = inviteMemberResult!!,
                            color = if (inviteMemberResult!!.contains("成功") || inviteMemberResult!!.contains("已发送")) 
                                SilkColors.success else SilkColors.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isInvitingMember = true
                            inviteMemberResult = null
                            
                            val response = ApiClient.sendContactRequestById(user.id, member.id)
                            inviteMemberResult = if (response.success) {
                                "✅ 联系人请求已发送"
                            } else {
                                "❌ ${response.message}"
                            }
                            
                            isInvitingMember = false
                            
                            // 成功后延迟关闭对话框
                            if (response.success) {
                                kotlinx.coroutines.delay(1500)
                                selectedMemberForInvite = null
                                inviteMemberResult = null
                            }
                        }
                    },
                    enabled = !isInvitingMember && inviteMemberResult == null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SilkColors.primary
                    )
                ) {
                    if (isInvitingMember) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("发送请求")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        selectedMemberForInvite = null 
                        inviteMemberResult = null
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }
    
    // 文件夹浏览对话框
    if (showFolderExplorer) {
        FolderExplorerDialog(
            groupId = group.id,
            files = folderFiles,
            processedUrls = processedUrls,
            isLoading = isLoadingFiles,
            onDismiss = { showFolderExplorer = false },
            onFileClick = { file ->
                // 打开文件下载链接
                val downloadUrl = "${BackendUrlHolder.getBaseUrl()}/api/files/download/${group.id}/${file.name}"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                context.startActivity(intent)
            },
            onUrlClick = { url ->
                // 打开原始 URL
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            }
        )
    }
    
    // 添加联系人确认对话框
    showAddContactConfirm?.let { clickedMessage ->
        AlertDialog(
            onDismissRequest = { 
                showAddContactConfirm = null 
                addContactResult = null
            },
            title = { 
                Text(
                    "添加联系人",
                    fontWeight = FontWeight.Bold,
                    color = SilkColors.primary
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("是否将 ${clickedMessage.userName} 添加为联系人？")
                    
                    if (addContactResult != null) {
                        Text(
                            text = addContactResult!!,
                            color = if (addContactResult!!.contains("成功") || addContactResult!!.contains("已发送")) 
                                SilkColors.success else SilkColors.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isAddingContact = true
                            addContactResult = null
                            
                            val response = ApiClient.sendContactRequestById(user.id, clickedMessage.userId)
                            addContactResult = if (response.success) {
                                "联系人请求已发送"
                            } else {
                                response.message
                            }
                            
                            isAddingContact = false
                            
                            // 成功后延迟关闭对话框
                            if (response.success) {
                                kotlinx.coroutines.delay(1500)
                                showAddContactConfirm = null
                                addContactResult = null
                            }
                        }
                    },
                    enabled = !isAddingContact && addContactResult == null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SilkColors.primary
                    )
                ) {
                    if (isAddingContact) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("发送请求")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        showAddContactConfirm = null 
                        addContactResult = null
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }
    
    // ==================== 转发到群组对话框 ====================
    if (showForwardToGroupDialog) {
        // 支持单条消息转发（AI消息）和多条消息转发（选择模式）
        val messagesToForward = if (messageToForward != null) {
            listOf(messageToForward!!)
        } else {
            messages.filter { selectedMessages.contains(it.id) }.sortedBy { it.timestamp }
        }
        
        ForwardToGroupDialog(
            groups = userGroups,
            isLoading = isLoadingGroups,
            selectedMessages = messagesToForward,
            currentUser = user,
            onForward = { targetGroup ->
                scope.launch {
                    forwardResult = null
                    val selectedContent = messagesToForward
                        .joinToString("\n\n") { msg ->
                            val time = SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).apply {
                                timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
                            }.format(java.util.Date(msg.timestamp))
                            "[$time] ${msg.userName}: ${msg.content}"
                        }
                    
                    // 发送转发消息到目标群组
                    val forwardMessage = "📨 转发自【${group.name}】:\n\n$selectedContent"
                    val success = ApiClient.sendMessageToGroup(
                        groupId = targetGroup.id,
                        userId = user.id,
                        userName = user.fullName,
                        content = forwardMessage
                    )
                    
                    if (success) {
                        forwardResult = "✅ 已转发到 ${targetGroup.name}"
                        android.widget.Toast.makeText(
                            context,
                            "已转发 ${messagesToForward.size} 条消息到 ${targetGroup.name}",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        kotlinx.coroutines.delay(1000)
                        showForwardToGroupDialog = false
                        isSelectionMode = false
                        selectedMessages.clear()
                        messageToForward = null  // 清除单条消息转发状态
                        forwardResult = null
                    } else {
                        forwardResult = "❌ 转发失败"
                    }
                }
            },
            onDismiss = {
                showForwardToGroupDialog = false
                messageToForward = null  // 清除单条消息转发状态
                forwardResult = null
            },
            result = forwardResult
        )
    }
    
    // ==================== 转发到联系人对话框 ====================
    if (showForwardToContactDialog) {
        ForwardToContactDialog(
            contacts = contacts,
            isLoading = isLoadingContacts,
            selectedMessages = messages.filter { selectedMessages.contains(it.id) }.sortedBy { it.timestamp },
            currentUser = user,
            onForward = { contact ->
                scope.launch {
                    forwardResult = null
                    
                    // 先获取或创建与联系人的私聊
                    val chatResponse = ApiClient.startPrivateChat(user.id, contact.contactId)
                    if (chatResponse.success && chatResponse.group != null) {
                        val selectedContent = messages
                            .filter { selectedMessages.contains(it.id) }
                            .sortedBy { it.timestamp }
                            .joinToString("\n\n") { msg ->
                                val time = SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).apply {
                                    timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
                                }.format(java.util.Date(msg.timestamp))
                                "[$time] ${msg.userName}: ${msg.content}"
                            }
                        
                        val forwardMessage = "📨 转发自【${group.name}】:\n\n$selectedContent"
                        val success = ApiClient.sendMessageToGroup(
                            groupId = chatResponse.group!!.id,
                            userId = user.id,
                            userName = user.fullName,
                            content = forwardMessage
                        )
                        
                        if (success) {
                            forwardResult = "✅ 已转发给 ${contact.contactName}"
                            android.widget.Toast.makeText(
                                context,
                                "已转发 ${selectedMessages.size} 条消息给 ${contact.contactName}",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            kotlinx.coroutines.delay(1000)
                            showForwardToContactDialog = false
                            isSelectionMode = false
                            selectedMessages.clear()
                            forwardResult = null
                        } else {
                            forwardResult = "❌ 转发失败"
                        }
                    } else {
                        forwardResult = "❌ 无法创建对话: ${chatResponse.message}"
                    }
                }
            },
            onDismiss = {
                showForwardToContactDialog = false
                forwardResult = null
            },
            result = forwardResult
        )
    }
}

// ==================== AI 消息卡片组件 ====================

/**
 * AI 消息卡片 - 专门用于 Silk AI 回复的卡片样式
 * 
 * @param isExpanded 外部控制的展开状态（由父组件管理）
 * @param onExpandChange 展开/收起状态变化回调
 */
@Composable
fun AIMessageCardAndroid(
    message: Message,
    timeString: String,
    isTransient: Boolean = false,
    isExpanded: Boolean = true,
    onExpandChange: (Boolean) -> Unit = {},
    onCopy: (String) -> Unit = {},
    onForward: (Message) -> Unit = {}
) {
    val isLongContent = message.content.length > 500
    val effectiveExpanded = if (isTransient) true else isExpanded
    
    // 调试日志
    LaunchedEffect(message.id, isExpanded) {
        println("🤖 AIMessageCardAndroid: messageId=${message.id}, contentLength=${message.content.length}, isLongContent=$isLongContent, isExpanded=$isExpanded")
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFF8F6F0)  // 温暖的奶白色背景
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 顶部标识栏
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // AI 头像
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFFC9A86C), Color(0xFFA8894D))
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🤖", fontSize = 16.sp)
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                // 名称和时间
                Text(
                    text = "Silk AI",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFC9A86C)  // 金色
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = timeString,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // 展开/折叠按钮
                if (isLongContent && !isTransient) {
                    Spacer(modifier = Modifier.weight(1f))
                    
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (effectiveExpanded) Color.Transparent else Color(0x1AC9A86C),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .clickable { 
                                onExpandChange(!effectiveExpanded)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (effectiveExpanded) "▼ 收起" else "▶ 展开",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 分隔线
            Divider(
                color = Color(0xFFE8E0D4),
                thickness = 1.dp
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 内容区域
            if (effectiveExpanded || !isLongContent) {
                if (isLongContent && effectiveExpanded && !isTransient) {
                    // 长内容展开时改为卡片内滚动，避免整条消息高度突变导致列表跳动
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        MarkdownWebView(message.content)
                    }
                } else {
                    MarkdownWebView(message.content)
                }
            } else {
                Text(
                    text = "${message.content.take(200)}...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // 底部操作栏
            if (!isTransient) {
                Spacer(modifier = Modifier.height(12.dp))
                
                Divider(
                    color = Color(0xFFE8E0D4).copy(alpha = 0.5f),
                    thickness = 0.5.dp
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    // 复制按钮
                    TextButton(
                        onClick = { onCopy(message.content) },
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text("📋", fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("复制", style = MaterialTheme.typography.bodySmall)
                    }
                    
                    // 转发按钮
                    TextButton(
                        onClick = { onForward(message) },
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text("↗", fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("转发", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            
            // 临时消息状态
            if (isTransient) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⏳",
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "生成中...",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE8B86C)
                    )
                }
            }
        }
    }
}

// ==================== 代码语法高亮颜色配置 ====================
private val codeColors = mapOf(
    // 关键字
    "keyword" to Color(0xFFCF8E6D),      // 橙色
    "string" to Color(0xFF6AAB73),       // 绿色
    "number" to Color(0xFFB8A965),       // 黄色
    "comment" to Color(0xFF6A7B8C),      // 灰色
    "function" to Color(0xFF79C0FF),     // 蓝色
    "operator" to Color(0xFFFF79C6),     // 粉色
    "punctuation" to Color(0xFFE8E0D4),  // 浅灰
    "variable" to Color(0xFFE0E0E0),     // 白色
    "type" to Color(0xFF9CDCFE),         // 青色
    "builtin" to Color(0xFFCE9178)       // 棕色
)

private val keywordSet = setOf(
    "fun", "val", "var", "if", "else", "when", "for", "while", "do", "return",
    "class", "interface", "object", "enum", "sealed", "data", "abstract",
    "override", "private", "public", "protected", "internal", "open", "final",
    "suspend", "inline", "reified", "crossinline", "noinline",
    "import", "package", "as", "is", "in", "out", "by", "lazy", "lateinit",
    "companion", "constructor", "init", "get", "set", "where", "typealias",
    "true", "false", "null", "this", "super", "it", "self",
    "def", "lambda", "pass", "raise", "try", "except", "finally", "with",
    "yield", "global", "nonlocal", "assert", "async", "await",
    "const", "let", "function", "typeof", "new", "delete", "void",
    "static", "struct", "union", "sizeof", "typedef", "extern",
    "break", "continue", "goto", "switch", "case", "default",
    "throw", "throws", "implements", "extends", "instanceof",
    "protocol", "extension", "guard", "defer", "vararg"
)

private val builtinSet = setOf(
    "print", "println", "log", "debug", "info", "warn", "error",
    "listOf", "setOf", "mapOf", "arrayOf", "mutableListOf", "mutableSetOf",
    "mutableMapOf", "intArrayOf", "doubleArrayOf", "booleanArrayOf",
    "size", "length", "isEmpty", "isNotEmpty", "contains", "get", "put",
    "add", "remove", "clear", "first", "last", "take", "drop", "filter",
    "map", "reduce", "fold", "forEach", "apply", "also", "let", "run", "with",
    "toInt", "toString", "toDouble", "toFloat", "toLong", "toBoolean",
    "split", "join", "joinToString", "trim", "substring", "replace",
    "range", "ranges", "until", "downTo", "step",
    "len", "str", "int", "float", "bool", "dict", "tuple", "set",
    "append", "extend", "insert", "pop", "keys", "values", "items",
    "console", "window", "document", "Math", "Array", "Object", "String", "Number",
    "require", "module", "exports", "define", "setTimeout", "setInterval"
)

/**
 * 简单的代码语法高亮
 */
private fun highlightCode(code: String, language: String): AnnotatedString {
    return buildAnnotatedString {
        val lines = code.lines()
        lines.forEachIndexed { lineIndex, line ->
            if (lineIndex > 0) append("\n")
            highlightLine(line, language)
        }
    }
}

private fun AnnotatedString.Builder.highlightLine(line: String, language: String) {
    var i = 0
    val lang = language.lowercase()
    
    while (i < line.length) {
        // 跳过空格
        if (line[i].isWhitespace()) {
            append(line[i])
            i++
            continue
        }
        
        // 注释
        if (line.substring(i).startsWith("//") || 
            (lang == "python" && line[i] == '#')) {
            withStyle(androidx.compose.ui.text.SpanStyle(color = codeColors["comment"]!!)) {
                append(line.substring(i))
            }
            return
        }
        
        // 字符串字面量
        if (line[i] == '"' || line[i] == '\'' || line[i] == '`') {
            val quote = line[i]
            val start = i
            i++
            while (i < line.length && line[i] != quote) {
                if (line[i] == '\\' && i + 1 < line.length) i++
                i++
            }
            if (i < line.length) i++
            withStyle(androidx.compose.ui.text.SpanStyle(color = codeColors["string"]!!)) {
                append(line.substring(start, i))
            }
            continue
        }
        
        // 数字
        if (line[i].isDigit() || (line[i] == '-' && i + 1 < line.length && line[i + 1].isDigit())) {
            val start = i
            if (line[i] == '-') i++
            while (i < line.length && (line[i].isDigit() || line[i] == '.' || line[i] == 'x' || line[i] == 'X')) {
                i++
            }
            withStyle(androidx.compose.ui.text.SpanStyle(color = codeColors["number"]!!)) {
                append(line.substring(start, i))
            }
            continue
        }
        
        // 标识符（关键字、内置函数、变量）
        if (line[i].isLetter() || line[i] == '_') {
            val start = i
            while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_')) {
                i++
            }
            val word = line.substring(start, i)
            when {
                keywordSet.contains(word) -> {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = codeColors["keyword"]!!)) {
                        append(word)
                    }
                }
                builtinSet.contains(word) -> {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = codeColors["builtin"]!!)) {
                        append(word)
                    }
                }
                i < line.length && line[i] == '(' -> {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = codeColors["function"]!!)) {
                        append(word)
                    }
                }
                word.first().isUpperCase() -> {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = codeColors["type"]!!)) {
                        append(word)
                    }
                }
                else -> {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = codeColors["variable"]!!)) {
                        append(word)
                    }
                }
            }
            continue
        }
        
        // 操作符
        if (line[i] in setOf('+', '-', '*', '/', '=', '!', '<', '>', '&', '|', '^', '~', '?', ':')) {
            val start = i
            while (i < line.length && line[i] in setOf('+', '-', '*', '/', '=', '!', '<', '>', '&', '|', '^', '~', '?', ':')) {
                i++
            }
            withStyle(androidx.compose.ui.text.SpanStyle(color = codeColors["operator"]!!)) {
                append(line.substring(start, i))
            }
            continue
        }
        
        // 标点符号
        if (line[i] in setOf('(', ')', '{', '}', '[', ']', ',', ';', '.')) {
            withStyle(androidx.compose.ui.text.SpanStyle(color = codeColors["punctuation"]!!)) {
                append(line[i])
            }
            i++
            continue
        }
        
        // 其他字符
        withStyle(androidx.compose.ui.text.SpanStyle(color = codeColors["variable"]!!)) {
            append(line[i])
        }
        i++
    }
}

// ==================== 数学公式解析 ====================

/**
 * 简化的数学公式渲染
 * 支持上下标、分数、根号、希腊字母等
 */
@Composable
fun MathFormulaAndroid(formula: String, isBlock: Boolean = false) {
    val processedFormula = remember(formula) { processMathFormula(formula) }
    
    if (isBlock) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F9FA)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = processedFormula,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = Color(0xFF1A1A2E),
                modifier = Modifier.padding(12.dp)
            )
        }
    } else {
        Text(
            text = processedFormula,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = Color(0xFF1A1A2E)
        )
    }
}

private fun processMathFormula(formula: String): String {
    var result = formula.trim()
    
    // 移除外层分隔符
    if (result.startsWith("$$") && result.endsWith("$$")) {
        result = result.removePrefix("$$").removeSuffix("$$").trim()
    } else if (result.startsWith("$") && result.endsWith("$")) {
        result = result.removePrefix("$").removeSuffix("$").trim()
    }
    
    // 处理 \sqrt[n]{x} -> ⁿ√x
    val sqrtNPattern = Regex("""\\sqrt\[(\d+)\]\{([^}]+)\}""")
    result = sqrtNPattern.replace(result) { match ->
        "${match.groupValues[1]}√(${match.groupValues[2]})"
    }
    
    // 处理 \sqrt{x} -> √x
    val sqrtPattern = Regex("""\\sqrt\{([^}]+)\}""")
    result = sqrtPattern.replace(result) { match ->
        "√(${match.groupValues[1]})"
    }
    
    // 处理分数 \frac{a}{b} -> (a)/(b)
    val fracPattern = Regex("""\\frac\{([^{}]+)\}\{([^{}]+)\}""")
    result = fracPattern.replace(result) { match ->
        val numerator = match.groupValues[1]
        val denominator = match.groupValues[2]
        "($numerator)/($denominator)"
    }
    
    // 直接替换 LaTeX 命令
    val replacements = listOf(
        // 希腊字母（小写）
        """\alpha""" to "α", """\beta""" to "β", """\gamma""" to "γ", """\delta""" to "δ",
        """\epsilon""" to "ε", """\varepsilon""" to "ε", """\zeta""" to "ζ", """\eta""" to "η",
        """\theta""" to "θ", """\vartheta""" to "θ", """\iota""" to "ι", """\kappa""" to "κ",
        """\lambda""" to "λ", """\mu""" to "μ", """\nu""" to "ν", """\xi""" to "ξ",
        """\pi""" to "π", """\varpi""" to "π", """\rho""" to "ρ", """\varrho""" to "ρ",
        """\sigma""" to "σ", """\varsigma""" to "σ", """\tau""" to "τ", """\upsilon""" to "υ",
        """\phi""" to "φ", """\varphi""" to "φ", """\chi""" to "χ", """\psi""" to "ψ", """\omega""" to "ω",
        // 希腊字母（大写）
        """\Gamma""" to "Γ", """\Delta""" to "Δ", """\Theta""" to "Θ",
        """\Lambda""" to "Λ", """\Xi""" to "Ξ", """\Pi""" to "Π",
        """\Sigma""" to "∑", """\Phi""" to "Φ", """\Psi""" to "Ψ", """\Omega""" to "Ω",
        // 数学运算符
        """\infty""" to "∞", """\partial""" to "∂", """\nabla""" to "∇",
        """\forall""" to "∀", """\exists""" to "∃", """\in""" to "∈", """\notin""" to "∉",
        """\subset""" to "⊂", """\supset""" to "⊃", """\cup""" to "∪", """\cap""" to "∩",
        """\leq""" to "≤", """\geq""" to "≥", """\neq""" to "≠", """\approx""" to "≈",
        """\equiv""" to "≡", """\sim""" to "∼", """\propto""" to "∝",
        """\pm""" to "±", """\mp""" to "∓", """\times""" to "×", """\div""" to "÷",
        """\cdot""" to "·", """\ast""" to "∗", """\star""" to "⋆",
        """\oplus""" to "⊕", """\ominus""" to "⊖", """\otimes""" to "⊗", """\odot""" to "⊙",
        // 箭头
        """\rightarrow""" to "→", """\leftarrow""" to "←", """\Rightarrow""" to "⇒",
        """\Leftarrow""" to "⇐", """\leftrightarrow""" to "↔", """\Leftrightarrow""" to "⇔",
        """\mapsto""" to "↦", """\to""" to "→",
        // 大运算符
        """\sum""" to "∑", """\prod""" to "∏", """\int""" to "∫",
        """\iint""" to "∬", """\iiint""" to "∭", """\oint""" to "∮",
        """\lim""" to "lim", """\log""" to "log", """\ln""" to "ln",
        """\exp""" to "exp", """\min""" to "min", """\max""" to "max",
        // 其他
        """\prime""" to "′", """\degree""" to "°", """\angle""" to "∠",
        """\triangle""" to "△", """\square""" to "□", """\circ""" to "∘",
        """\vert""" to "|", """\Vert""" to "‖", """\ldots""" to "…", """\cdots""" to "⋯",
        """\vdots""" to "⋮", """\ddots""" to "⋱",
        """\Re""" to "ℜ", """\Im""" to "ℑ", """\emptyset""" to "∅",
        // 括号
        """\left(""" to "(", """\right)""" to ")",
        """\left[""" to "[", """\right]""" to "]",
        """\left{""" to "{", """\right}""" to "}",
        """\left|""" to "|", """\right|""" to "|",
        // 环境和其他命令
        """\mathbf{""" to "", """\text{""" to "", """\mathrm{""" to "",
        """\begin{aligned}""" to "", """\end{aligned}""" to "",
        """\begin{pmatrix}""" to "[", """\end{pmatrix}""" to "]",
        """\begin{bmatrix}""" to "[", """\end{bmatrix}""" to "]",
        """\begin{vmatrix}""" to "|", """\end{vmatrix}""" to "|",
        """\begin{cases}""" to "", """\end{cases}""" to "",
        // 空格
        """\,""" to " ", """\;""" to " ", """\quad""" to "  ", """\qquad""" to "    ",
        """\!""" to "",
        // 换行（LaTeX的 \\）
        """\\""" to "; "
    )
    
    for ((latex, symbol) in replacements) {
        result = result.replace(latex, symbol)
    }
    
    // Unicode 上标字符映射
    val superscriptMap = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'n' to 'ⁿ', 'i' to 'ⁱ', 'x' to 'ˣ', 'a' to 'ᵃ', 'b' to 'ᵇ',
        'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ', 'f' to 'ᶠ', 'g' to 'ᵍ',
        'h' to 'ʰ', 'k' to 'ᵏ', 'l' to 'ˡ', 'm' to 'ᵐ', 'o' to 'ᵒ',
        'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ', 't' to 'ᵗ', 'u' to 'ᵘ',
        'v' to 'ᵛ', 'w' to 'ʷ'
    )
    // Unicode 下标字符映射
    val subscriptMap = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'i' to 'ᵢ', 'o' to 'ₒ', 'r' to 'ᵣ',
        'u' to 'ᵤ', 'v' to 'ᵥ', 'x' to 'ₓ', 'k' to 'ₖ', 'n' to 'ₙ',
        'p' to 'ₚ', 's' to 'ₛ', 't' to 'ₜ', 'j' to 'ⱼ'
    )
    
    // 处理上标 ^{...}
    val upperLimitPattern = Regex("""\^\{([^}]+)\}""")
    result = upperLimitPattern.replace(result) { match ->
        val content = match.groupValues[1]
        val superscript = content.map { c -> superscriptMap[c] ?: c }.joinToString("")
        superscript
    }
    
    // 处理下标 _{...}
    val lowerLimitPattern = Regex("""_\{([^}]+)\}""")
    result = lowerLimitPattern.replace(result) { match ->
        val content = match.groupValues[1]
        val subscript = content.map { c -> subscriptMap[c] ?: c }.joinToString("")
        subscript
    }
    
    // 处理单字符上标 x^n
    val superSinglePattern = Regex("""\^([0-9a-zA-Z+-])""")
    result = superSinglePattern.replace(result) { match ->
        val c = match.groupValues[1][0]
        (superscriptMap[c] ?: "^$c").toString()
    }
    
    // 处理单字符下标 x_n
    val subSinglePattern = Regex("""_([0-9a-zA-Z+-])""")
    result = subSinglePattern.replace(result) { match ->
        val c = match.groupValues[1][0]
        (subscriptMap[c] ?: "_$c").toString()
    }
    
    // 移除未配对的花括号
    result = result.replace("{", "").replace("}", "")
    
    // 清理多余的空格
    result = result.trim()
    result = result.replace(Regex("""\s+"""), " ")
    
    return result
}

// ==================== 表格解析 ====================

/**
 * Markdown 表格组件
 */
@Composable
fun MarkdownTableAndroid(lines: List<String>) {
    if (lines.isEmpty()) return
    
    val hasHeader = lines.size > 1 && lines[1].contains("|") && 
                    lines[1].all { it == '|' || it == '-' || it == ' ' || it == ':' }
    
    val headerLine = if (hasHeader) lines[0] else ""
    val dataLines = if (hasHeader) lines.drop(2) else lines
    
    val parseRow: (String) -> List<String> = { line ->
        line.split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    
    val headerCells = if (hasHeader) parseRow(headerLine) else emptyList()
    val rows = dataLines.map { parseRow(it) }
    
    if (headerCells.isEmpty() && rows.isEmpty()) return
    
    val maxCols = maxOf(headerCells.size, rows.maxOfOrNull { it.size } ?: 0)
    if (maxCols == 0) return
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 表头
            if (headerCells.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFEFF6FF))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    headerCells.forEach { cell ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        ) {
                            Text(
                                text = cell,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1E3A5F)
                            )
                        }
                    }
                    // 补齐空列
                    repeat(maxCols - headerCells.size) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
                Divider(color = Color(0xFFE2E8F0), thickness = 1.dp)
            }
            
            // 数据行
            rows.forEachIndexed { rowIndex, rowCells ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (rowIndex % 2 == 0) Color.White else Color(0xFFFAFAFA))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    rowCells.forEach { cell ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        ) {
                            InlineMarkdownAndroid(cell)
                        }
                    }
                    // 补齐空列
                    repeat(maxCols - rowCells.size) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
                if (rowIndex < rows.size - 1) {
                    Divider(color = Color(0xFFF0F0F0), thickness = 0.5.dp)
                }
            }
        }
    }
}

// ==================== 任务列表项 ====================

/**
 * 任务列表项组件
 */
@Composable
fun TaskListItemAndroid(content: String, isChecked: Boolean, onToggle: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, bottom = 4.dp)
            .then(if (onToggle != null) Modifier.clickable { onToggle() } else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Checkbox
        Box(
            modifier = Modifier
                .size(18.dp)
                .background(
                    if (isChecked) Color(0xFF4CAF50) else Color.Transparent,
                    RoundedCornerShape(3.dp)
                )
                .border(
                    1.5.dp,
                    if (isChecked) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                    RoundedCornerShape(3.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isChecked) {
                Text(
                    text = "✓",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        // 内容
        val textContent = content.trim()
        if (isChecked) {
            Text(
                text = textContent,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF9E9E9E),
                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
            )
        } else {
            InlineMarkdownAndroid(textContent)
        }
    }
}

/**
 * Markdown 内容渲染 - Android 端 (备用实现)
 * 
 * 注意: 主要渲染已改用 MarkdownWebView (WebView + KaTeX)，能正确显示数学公式
 * 此函数保留作为备用，使用简单的 Unicode 符号替换方式处理 LaTeX
 * 
 * 支持表格、数学公式、代码高亮、任务列表、链接
 */
@Composable
fun MarkdownContentAndroid(content: String) {
    val context = LocalContext.current
    val lines = content.split("\n")
    var inCodeBlock = false
    var codeBlockContent = StringBuilder()
    var codeLanguage = ""
    var inTable = false
    var tableLines = mutableListOf<String>()
    var inMathBlock = false
    var mathBlockContent = StringBuilder()
    
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        lines.forEachIndexed { index, line ->
            when {
                // 数学公式块 $$...$$ 或 \[...\]
                (line.trim().startsWith("$$") || line.trim().startsWith("\\[")) && !inCodeBlock -> {
                    if (inMathBlock) {
                        // 结束数学块
                        if (mathBlockContent.isNotEmpty()) {
                            MathFormulaAndroid(mathBlockContent.toString().trim(), isBlock = true)
                            mathBlockContent = StringBuilder()
                        }
                        inMathBlock = false
                    } else {
                        val trimmedLine = line.trim()
                        val isDollar = trimmedLine.startsWith("$$")
                        val endMarker = if (isDollar) "$$" else "\\]"
                        val startMarker = if (isDollar) "$$" else "\\["
                        
                        // 检查是否是单行公式 $$...$$ 或 \[...\]
                        if (trimmedLine.endsWith(endMarker) && trimmedLine.length > startMarker.length + endMarker.length) {
                            // 单行公式
                            val content = trimmedLine.removePrefix(startMarker).removeSuffix(endMarker).trim()
                            MathFormulaAndroid(content, isBlock = true)
                        } else {
                            // 多行公式开始
                            inMathBlock = true
                            // 如果当前行有内容（不是纯粹的起始标记），加入内容
                            val firstContent = trimmedLine.removePrefix(startMarker).trim()
                            if (firstContent.isNotEmpty()) {
                                mathBlockContent.append(firstContent).append("\n")
                            }
                        }
                    }
                }
                // 结束标记 \] 或 $$（当在数学块中）
                inMathBlock && (line.trim() == "$$" || line.trim() == "\\]") -> {
                    // 结束数学块
                    if (mathBlockContent.isNotEmpty()) {
                        MathFormulaAndroid(mathBlockContent.toString().trim(), isBlock = true)
                        mathBlockContent = StringBuilder()
                    }
                    inMathBlock = false
                }
                inMathBlock -> {
                    mathBlockContent.append(line).append("\n")
                }
                // 代码块开始/结束
                line.trim().startsWith("```") -> {
                    if (inCodeBlock) {
                        // 代码块结束
                        if (codeBlockContent.isNotEmpty()) {
                            CodeBlockAndroid(
                                code = codeBlockContent.toString().trimEnd(),
                                language = codeLanguage
                            )
                            codeBlockContent = StringBuilder()
                        }
                        inCodeBlock = false
                    } else {
                        // 代码块开始
                        inCodeBlock = true
                        codeLanguage = line.trim().removePrefix("```").trim()
                    }
                }
                // 代码块内容
                inCodeBlock -> {
                    codeBlockContent.append(line).append("\n")
                }
                // 表格检测
                line.trim().startsWith("|") && line.trim().endsWith("|") -> {
                    if (!inTable) {
                        inTable = true
                        tableLines = mutableListOf()
                    }
                    tableLines.add(line)
                }
                // 表格结束
                inTable && !line.trim().startsWith("|") -> {
                    if (tableLines.isNotEmpty()) {
                        MarkdownTableAndroid(tableLines)
                        tableLines = mutableListOf()
                    }
                    inTable = false
                    // 继续处理当前行
                    when {
                        line.startsWith("### ") -> {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = line.removePrefix("### "),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF4A4038)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        line.startsWith("## ") -> {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = line.removePrefix("## "),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF4A4038)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        line.startsWith("# ") -> {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = line.removePrefix("# "),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFC9A86C)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        line.isNotBlank() -> {
                            InlineMarkdownAndroid(line, context)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
                // 标题
                line.startsWith("### ") -> {
                    if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = line.removePrefix("### "),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF4A4038)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                line.startsWith("## ") -> {
                    if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = line.removePrefix("## "),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4A4038)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                line.startsWith("# ") -> {
                    if (index > 0) Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = line.removePrefix("# "),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFC9A86C)  // 金色大标题
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                // 任务列表 - [ ] 或 [x]
                line.trim().let { l ->
                    l.startsWith("- [ ] ") || l.startsWith("- [x] ") || 
                    l.startsWith("* [ ] ") || l.startsWith("* [x] ")
                } -> {
                    val isChecked = line.trim().contains("[x]")
                    val content = line.trim()
                        .removePrefix("- [ ] ").removePrefix("- [x] ")
                        .removePrefix("* [ ] ").removePrefix("* [x] ")
                    TaskListItemAndroid(content, isChecked)
                }
                // 无序列表
                line.trim().startsWith("- ") || line.trim().startsWith("* ") -> {
                    val content = line.trim().removePrefix("- ").removePrefix("* ")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, bottom = 4.dp)
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFC9A86C),
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        InlineMarkdownAndroid(content, context)
                    }
                }
                // 有序列表
                line.trim().matches(Regex("^\\d+\\.\\s.*")) -> {
                    val parts = line.trim().split(".", limit = 2)
                    if (parts.size == 2) {
                        val num = parts[0].trim()
                        val listContent = parts[1].trim()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, bottom = 4.dp)
                        ) {
                            Text(
                                text = "$num.",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFC9A86C),
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            InlineMarkdownAndroid(listContent, context)
                        }
                    }
                }
                // 分隔线
                line.trim() == "---" || line.trim() == "***" -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider(
                        color = Color(0xFFE8E0D4),
                        thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                // 引用块
                line.startsWith("> ") -> {
                    val quoteContent = line.removePrefix("> ")
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, bottom = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFF0F0F0)
                        ),
                        shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp, bottomStart = 8.dp)
                    ) {
                        Row(modifier = Modifier.padding(12.dp)) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(20.dp)
                                    .background(Color(0xFF7BA8C9))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            InlineMarkdownAndroid(quoteContent, context)
                        }
                    }
                }
                // 普通文本
                line.isNotBlank() -> {
                    InlineMarkdownAndroid(line, context)
                    Spacer(modifier = Modifier.height(4.dp))
                }
                // 空行
                else -> {
                    if (index > 0 && lines.getOrNull(index - 1)?.isNotBlank() == true) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
        
        // 处理末尾的表格
        if (inTable && tableLines.isNotEmpty()) {
            MarkdownTableAndroid(tableLines)
        }
    }
}

/**
 * 代码块组件 - 带语法高亮
 */
@Composable
fun CodeBlockAndroid(code: String, language: String) {
    val highlightedCode = remember(code, language) { highlightCode(code, language) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E1E1E)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 语言标签
            if (language.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2D2D2D))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = language,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF888888),
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // 复制按钮
                    val context = LocalContext.current
                    androidx.compose.material3.IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("code", code)
                            clipboard.setPrimaryClip(clip)
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.ContentCopy,
                            contentDescription = "复制代码",
                            tint = Color(0xFF888888),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            // 代码内容
            SelectionContainer {
                Text(
                    text = highlightedCode,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp),
                    color = Color(0xFFE0E0E0)
                )
            }
        }
    }
}

/**
 * 行内 Markdown 渲染 - 支持链接点击和行内公式
 */
@Composable
fun InlineMarkdownAndroid(text: String, context: Context? = null) {
    val localContext = context ?: LocalContext.current
    
    // 处理行内数学公式
    val processedText = remember(text) { extractInlineMath(text) }
    
    val annotatedText = buildAnnotatedString {
        var remaining = processedText.first
        val mathSegments = processedText.second
        var offset = 0
        
        while (remaining.isNotEmpty()) {
            // 检查当前位置是否有数学公式
            val mathAtPos = mathSegments.find { it.first == offset }
            if (mathAtPos != null) {
                // 渲染数学公式
                withStyle(androidx.compose.ui.text.SpanStyle(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    background = Color(0xFFF5F5F5)
                )) {
                    append(mathAtPos.second)
                }
                offset += mathAtPos.second.length
                remaining = if (remaining.length > mathAtPos.second.length) remaining.substring(mathAtPos.second.length) else ""
                continue
            }
            
            // 处理链接 [text](url)
            val linkStart = remaining.indexOf("[")
            if (linkStart >= 0) {
                val linkEnd = remaining.indexOf("]", linkStart)
                if (linkEnd > linkStart) {
                    val urlStart = remaining.indexOf("(", linkEnd)
                    val urlEnd = remaining.indexOf(")", urlStart)
                    if (urlStart == linkEnd + 1 && urlEnd > urlStart) {
                        // 添加前面的普通文本
                        if (linkStart > 0) {
                            append(remaining.substring(0, linkStart))
                        }
                        
                        val linkText = remaining.substring(linkStart + 1, linkEnd)
                        val url = remaining.substring(urlStart + 1, urlEnd)
                        
                        // 添加可点击的链接
                        pushStringAnnotation(tag = "URL", annotation = url)
                        withStyle(androidx.compose.ui.text.SpanStyle(
                            color = Color(0xFF1565C0),
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                        )) {
                            append(linkText)
                        }
                        pop()
                        
                        remaining = remaining.substring(urlEnd + 1)
                        continue
                    }
                }
            }
            
            // 处理自动链接 URL
            val urlPattern = Regex("""(https?://[^\s<>\[\]()]+)""")
            val urlMatch = urlPattern.find(remaining)
            if (urlMatch != null) {
                val matchStart = urlMatch.range.first
                if (matchStart > 0) {
                    append(remaining.substring(0, matchStart))
                }
                
                val url = urlMatch.value
                pushStringAnnotation(tag = "URL", annotation = url)
                withStyle(androidx.compose.ui.text.SpanStyle(
                    color = Color(0xFF1565C0),
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                )) {
                    append(url)
                }
                pop()
                
                remaining = remaining.substring(urlMatch.range.last + 1)
                continue
            }
            
            // 处理粗体 **text**
            val boldStart = remaining.indexOf("**")
            if (boldStart >= 0) {
                // 添加前面的普通文本
                if (boldStart > 0) {
                    append(remaining.substring(0, boldStart))
                }
                
                val boldEnd = remaining.indexOf("**", boldStart + 2)
                if (boldEnd > boldStart) {
                    val boldText = remaining.substring(boldStart + 2, boldEnd)
                    withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(boldText)
                    }
                    remaining = remaining.substring(boldEnd + 2)
                    continue
                }
            }
            
            // 处理斜体 *text*
            val italicStart = remaining.indexOf("*")
            if (italicStart >= 0 && (italicStart == 0 || remaining[italicStart - 1] != '*')) {
                if (italicStart > 0) {
                    append(remaining.substring(0, italicStart))
                }
                
                val italicEnd = remaining.indexOf("*", italicStart + 1)
                if (italicEnd > italicStart && (italicEnd == remaining.length - 1 || remaining[italicEnd + 1] != '*')) {
                    val italicText = remaining.substring(italicStart + 1, italicEnd)
                    withStyle(androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                        append(italicText)
                    }
                    remaining = remaining.substring(italicEnd + 1)
                    continue
                }
            }
            
            // 处理行内代码 `code`
            val codeStart = remaining.indexOf("`")
            if (codeStart >= 0) {
                if (codeStart > 0) {
                    append(remaining.substring(0, codeStart))
                }
                
                val codeEnd = remaining.indexOf("`", codeStart + 1)
                if (codeEnd > codeStart) {
                    val codeText = remaining.substring(codeStart + 1, codeEnd)
                    withStyle(
                        androidx.compose.ui.text.SpanStyle(
                            background = Color(0xFFF0F0F0),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    ) {
                        append(" $codeText ")
                    }
                    remaining = remaining.substring(codeEnd + 1)
                    continue
                }
            }
            
            // 没有特殊标记，添加剩余文本
            append(remaining)
            break
        }
    }
    
    // 渲染可点击的文本
    Text(
        text = annotatedText,
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF4A4038),
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) {
            // 点击时检查是否点击了链接
            val annotations = annotatedText.getStringAnnotations("URL", 0, annotatedText.length)
            if (annotations.isNotEmpty()) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(annotations.first().item))
                    localContext.startActivity(intent)
                } catch (e: Exception) {
                    // 忽略无法打开的链接
                }
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    message: Message,
    currentUserId: String,
    context: android.content.Context,
    isTransient: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelection: (String) -> Unit = {},
    onLongPress: (String) -> Unit = {},
    onUserNameClick: ((Message) -> Unit)? = null,
    // 撤回功能相关参数
    isRecalling: Boolean = false,
    onRecall: (String) -> Unit = {},
    // AI 消息展开状态相关参数
    isAIExpanded: Boolean = true,
    onAIExpandChange: (String, Boolean) -> Unit = { _, _ -> },
    // 复制和转发功能相关参数
    onCopy: (String) -> Unit = {},
    onForward: (Message) -> Unit = {}
) {
    val isCurrentUser = message.userId == currentUserId
    val isSystemMessage = message.type == MessageType.SYSTEM
    val isFileMessage = message.type == MessageType.FILE
    
    // 是否可以撤回：只能撤回自己发送的消息，且不是 Silk 的消息，且不是系统消息
    val canRecall = isCurrentUser && 
                    message.userName != "Silk" && 
                    !isSystemMessage && 
                    !isTransient
    
    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
        timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
    }
    val timeString = dateFormat.format(Date(message.timestamp))
    
    // 检测PDF下载链接
    val isPdfMessage = message.content.contains("/download/report/") && message.content.contains(".pdf")
    
    // ✅ 是否显示上下文菜单（非临时消息、非系统消息、文本消息）
    val canShowContextMenu = !isTransient && !isSystemMessage && message.type == MessageType.TEXT
    
    // ✅ AI 消息特殊处理 - 使用专用卡片
    val isAIMessage = message.userId == "silk_ai_agent"
    if (isAIMessage && message.type == MessageType.TEXT && 
        message.category != com.silk.shared.models.MessageCategory.AGENT_STATUS) {
        AIMessageCardAndroid(
            message = message,
            timeString = timeString,
            isTransient = isTransient,
            isExpanded = isAIExpanded,
            onExpandChange = { newExpanded -> onAIExpandChange(message.id, newExpanded) },
            onCopy = onCopy,
            onForward = onForward
        )
        return
    }
    
    // ✅ 文件消息特殊处理
    if (isFileMessage) {
        // 解析文件信息：content 格式为 JSON {"fileName":"xxx","fileSize":123,"downloadUrl":"xxx"}
        // 兼容旧格式 "fileName|fileSize|downloadUrl"
        val fileName: String
        val fileSize: Long
        val downloadUrl: String
        
        if (message.content.startsWith("{")) {
            // JSON 格式
            val json = try {
                org.json.JSONObject(message.content)
            } catch (e: Exception) {
                println("⚠️ 解析文件消息JSON失败: ${e.message}")
                null
            }
            if (json != null) {
                fileName = json.optString("fileName", "未知文件")
                fileSize = json.optLong("fileSize", 0L)
                downloadUrl = json.optString("downloadUrl", "")
            } else {
                fileName = "解析失败"
                fileSize = 0L
                downloadUrl = ""
            }
        } else {
            // 兼容旧的 | 分隔符格式
            val parts = message.content.split("|")
            fileName = parts.getOrNull(0) ?: "未知文件"
            fileSize = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            downloadUrl = parts.getOrNull(2) ?: ""
        }
        
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isCurrentUser) Alignment.End else Alignment.Start
        ) {
            // 发送者名称和时间
            if (!isCurrentUser) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = message.userName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = " · $timeString",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
            
            // 文件卡片
            Surface(
                color = if (isCurrentUser) MaterialTheme.colorScheme.primary 
                        else MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.clickable {
                    if (downloadUrl.isNotEmpty()) {
                        val fullUrl = "${BackendUrlHolder.getBaseUrl()}$downloadUrl"
                        println("📥 点击打开文件: $fileName, URL: $fullUrl")
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl))
                        context.startActivity(intent)
                    }
                }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 文件图标
                    val icon = when (fileName.substringAfterLast(".").lowercase()) {
                        "pdf" -> "📄"
                        "doc", "docx" -> "📝"
                        "xls", "xlsx" -> "📊"
                        "jpg", "jpeg", "png", "gif" -> "🖼️"
                        "mp4", "avi", "mov" -> "🎬"
                        "mp3", "wav" -> "🎵"
                        "zip", "rar" -> "📦"
                        else -> "📎"
                    }
                    Text(
                        text = icon,
                        fontSize = 32.sp,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    
                    Column {
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (isCurrentUser) MaterialTheme.colorScheme.onPrimary 
                                    else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (fileSize > 0) formatFileSize(fileSize) else "点击查看",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isCurrentUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // 当前用户消息的时间
            if (isCurrentUser) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = timeString,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
        return
    }
    
    if (isSystemMessage) {
        // 系统消息
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        // 普通消息
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isCurrentUser) Alignment.End else Alignment.Start
        ) {
                // 发送者名称和时间
                if (!isCurrentUser) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        // 用户名 - 可点击添加联系人（不包括 Silk）
                        if (message.userName != "Silk" && onUserNameClick != null) {
                            Text(
                                text = message.userName,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = SilkColors.primary,
                                modifier = Modifier.clickable { onUserNameClick(message) }
                            )
                        } else {
                            Text(
                                text = message.userName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = " · $timeString",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
                
                var showContextMenu by remember { mutableStateOf(false) }
                
                if (showContextMenu) {
                    AlertDialog(
                        onDismissRequest = { showContextMenu = false },
                        title = { Text("消息操作", fontWeight = FontWeight.Bold) },
                        text = {
                            Column {
                                TextButton(
                                    onClick = {
                                        showContextMenu = false
                                        try {
                                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) 
                                                as android.content.ClipboardManager
                                            val clip = android.content.ClipData.newPlainText("消息", message.content)
                                            clipboard.setPrimaryClip(clip)
                                            android.widget.Toast.makeText(context, "已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                                        } catch (_: Exception) { }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("📋 复制", color = MaterialTheme.colorScheme.onSurface) }
                                
                                TextButton(
                                    onClick = {
                                        showContextMenu = false
                                        try { onLongPress(message.id) } catch (_: Exception) { }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("↗ 转发", color = MaterialTheme.colorScheme.onSurface) }
                                
                                TextButton(
                                    onClick = {
                                        showContextMenu = false
                                        try {
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, "${message.userName}: ${message.content}")
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, "分享到"))
                                        } catch (_: Exception) { }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("📤 分享", color = MaterialTheme.colorScheme.onSurface) }
                                
                                if (canRecall && !isRecalling) {
                                    TextButton(
                                        onClick = {
                                            showContextMenu = false
                                            try { onRecall(message.id) } catch (_: Exception) { }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("↩ 撤回", color = MaterialTheme.colorScheme.onSurface) }
                                }
                                
                                TextButton(
                                    onClick = {
                                        showContextMenu = false
                                        try { onLongPress(message.id) } catch (_: Exception) { }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("☑️ 多选", color = MaterialTheme.colorScheme.onSurface) }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { showContextMenu = false }) {
                                Text("取消")
                            }
                        }
                    )
                }
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (isCurrentUser) Arrangement.End else Arrangement.Start,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 选中勾选图标（左侧 - 对方消息）
                    if (isSelected && !isCurrentUser) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(Color(0xFF2196F3), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    
                    Box(
                        modifier = if (!isTransient) {
                            Modifier
                                .weight(1f, fill = false)
                                .background(
                                    if (isSelected) Color(0xFF4FC3F7).copy(alpha = 0.4f) else Color.Transparent,
                                    shape = MaterialTheme.shapes.medium
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (isSelectionMode) {
                                            onToggleSelection(message.id)
                                        }
                                    },
                                    onLongClick = {
                                        if (!isSelectionMode) {
                                            showContextMenu = true
                                            try {
                                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                                    (context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator)
                                                        ?.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                                                }
                                            } catch (_: Exception) { }
                                        }
                                    }
                                )
                        } else {
                            Modifier.weight(1f, fill = false)
                        }
                    ) {
                // ✅ 根据消息类别设置背景色和透明度
                Surface(
                    color = when {
                        isSelected -> Color(0xFF81D4FA)  // 选中时：明亮的浅蓝色
                        // ✅ 根据category设置背景
                        message.category == com.silk.shared.models.MessageCategory.FINAL_REPORT -> {
                            // 最终报告：正常颜色，高亮
                            if (isCurrentUser) MaterialTheme.colorScheme.primary 
                            else MaterialTheme.colorScheme.surfaceVariant
                        }
                        message.category == com.silk.shared.models.MessageCategory.STEP_PROCESS ||
                        message.category == com.silk.shared.models.MessageCategory.TODO_LIST -> {
                            // 步骤过程和TODO：浅色背景
                            if (isCurrentUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        }
                        isTransient -> {
                            // 临时消息：更浅
                            if (isCurrentUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        }
                        isCurrentUser -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = if (isTransient) 0.5.dp else 1.dp  // ✅ 统一阴影
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
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
                                // ✅ 使用 URLDecoder 完整解码文件名（处理中文和所有特殊字符）
                                val encodedFileName = trimmedLine.substringAfterLast("/")
                                fileName = try {
                                    java.net.URLDecoder.decode(encodedFileName, "UTF-8")
                                } catch (e: Exception) {
                                    encodedFileName  // 解码失败则使用原始文件名
                                }
                            }
                        }
                        
                        // 显示消息内容（过滤掉路径行）
                        lines.forEach { line ->
                            val trimmedLine = line.trim()
                            if (!trimmedLine.startsWith("/download/report/") && trimmedLine.isNotEmpty()) {
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,  // ✅ 统一字体大小
                                    // ✅ PDF消息是FINAL_REPORT，高亮显示
                                    color = if (isCurrentUser) MaterialTheme.colorScheme.onPrimary 
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        
                        // 显示下载按钮
                        if (pdfUrl != null) {
                            val fullUrl = "${BackendUrlHolder.getBaseUrl()}$pdfUrl"
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Button(
                                onClick = {
                                    println("📥 点击下载PDF: $fileName")
                                    // 打开浏览器下载PDF
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl))
                                    context.startActivity(intent)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary
                                )
                            ) {
                                Text("📥 下载PDF报告")
                            }
                            
                            if (fileName != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "文件名：$fileName",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isCurrentUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) 
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    } else {
                        // 普通文本消息
                        // ✅ 统一使用bodySmall（增大一号，不再缩小），根据category设置不同亮度
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodySmall,  // ✅ 增大一号：直接使用bodySmall，不缩小
                            color = when (message.category) {
                                com.silk.shared.models.MessageCategory.FINAL_REPORT -> {
                                    // 最终报告：高亮度
                                    if (isCurrentUser) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                com.silk.shared.models.MessageCategory.STEP_PROCESS,
                                com.silk.shared.models.MessageCategory.TODO_LIST -> {
                                    // 步骤过程和TODO：低亮度（50%透明）
                                    if (isCurrentUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                }
                                else -> {
                                    // 普通消息和临时消息
                                    if (isTransient) {
                                        if (isCurrentUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    } else {
                                        if (isCurrentUser) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                }
                            }
                        )
                    }
                    
                    // 显示步骤信息（临时消息）
                    if (isTransient && message.currentStep != null && message.totalSteps != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "步骤 ${message.currentStep}/${message.totalSteps}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isCurrentUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f) 
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
                }
            }  // ✅ Box 结束（长按手势容器）
                    
                    // 选中勾选图标（右侧 - 自己的消息）
                    if (isSelected && isCurrentUser) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(Color(0xFF2196F3), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }  // ✅ Row 结束（选中图标容器）
            
            if (isCurrentUser) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = timeString,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
fun InvitationDialog(
    group: Group,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("邀请成员") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("分享以下信息邀请其他人加入群组：")
                
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small
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
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Divider()
                        
                        Text(
                            text = "邀请码",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = group.invitationCode,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val shareText = """
                        🎀 加入我的 Silk 群组！
                        
                        群组名称：${group.name}
                        邀请码：${group.invitationCode}
                        
                        📱 下载/访问 Silk：
                        • Android APK: ${BackendUrlHolder.getBaseUrl()}/api/files/download-apk
                        • Web 网页版: ${BackendUrlHolder.getBaseUrl().substringBeforeLast(":")}:${BuildConfig.FRONTEND_PORT}
                        
                        打开 Silk，点击"加入群组"，输入邀请码即可加入！
                    """.trimIndent()
                    
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    context.startActivity(Intent.createChooser(intent, "分享邀请"))
                    onDismiss()
                }
            ) {
                Text("分享")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

/**
 * 文件项数据类
 */
data class FileItem(
    val name: String,
    val size: Long,
    val uploadTime: Long,
    val uploadedBy: String
)

/**
 * 文件夹浏览对话框
 */
@Composable
fun FolderExplorerDialog(
    groupId: String,
    files: List<FileItem>,
    processedUrls: List<String>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onFileClick: (FileItem) -> Unit,
    onUrlClick: (String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f),
            shape = MaterialTheme.shapes.large,
            color = SilkColors.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 标题栏 - Silk 风格
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    SilkColors.primary,
                                    SilkColors.primary.copy(alpha = 0.8f)
                                )
                            )
                        )
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "📁",
                                fontSize = 24.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "会话资源",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Text("✕", color = Color.White, fontSize = 20.sp)
                        }
                    }
                }
                
                // 内容列表
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = SilkColors.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("加载中...", color = SilkColors.textSecondary)
                        }
                    }
                } else if (files.isEmpty() && processedUrls.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📂", fontSize = 48.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "暂无资源",
                                color = SilkColors.textSecondary,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "上传文件或发送URL后将在这里显示",
                                color = SilkColors.textSecondary.copy(alpha = 0.7f),
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        // 1️⃣ 首先显示已下载的 URL 清单
                        if (processedUrls.isNotEmpty()) {
                            item {
                                Text(
                                    text = "🔗 已下载的网页 (${processedUrls.size})",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = SilkColors.textSecondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                                )
                            }
                            items(processedUrls) { url ->
                                UrlItemCard(
                                    url = url,
                                    onClick = { onUrlClick(url) }
                                )
                            }
                            
                            // 分隔线
                            if (files.isNotEmpty()) {
                                item {
                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                            }
                        }
                        
                        // 2️⃣ 然后显示文件列表
                        if (files.isNotEmpty()) {
                            item {
                                Text(
                                    text = "📁 上传的文件 (${files.size})",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = SilkColors.textSecondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                                )
                            }
                            items(files) { file ->
                                FileItemCard(
                                    file = file,
                                    onClick = { onFileClick(file) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * URL 项卡片
 */
@Composable
fun UrlItemCard(
    url: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFF0FFF4)  // 淡绿色背景
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🌐",
                fontSize = 24.sp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = url,
                    fontSize = 13.sp,
                    color = SilkColors.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            // 已索引标记
            Box(
                modifier = Modifier
                    .background(
                        color = Color(0xFF48BB78),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "✓ 已索引",
                    fontSize = 11.sp,
                    color = Color.White
                )
            }
        }
    }
}

/**
 * 文件项卡片
 */
@Composable
fun FileItemCard(
    file: FileItem,
    onClick: () -> Unit
) {
    val fileIcon = when {
        file.name.endsWith(".pdf", ignoreCase = true) -> "📄"
        file.name.endsWith(".doc", ignoreCase = true) || file.name.endsWith(".docx", ignoreCase = true) -> "📝"
        file.name.endsWith(".xls", ignoreCase = true) || file.name.endsWith(".xlsx", ignoreCase = true) -> "📊"
        file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".png", ignoreCase = true) || 
        file.name.endsWith(".gif", ignoreCase = true) -> "🖼️"
        file.name.endsWith(".mp3", ignoreCase = true) || file.name.endsWith(".wav", ignoreCase = true) -> "🎵"
        file.name.endsWith(".mp4", ignoreCase = true) || file.name.endsWith(".avi", ignoreCase = true) -> "🎬"
        file.name.endsWith(".zip", ignoreCase = true) || file.name.endsWith(".rar", ignoreCase = true) -> "📦"
        else -> "📎"
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = SilkColors.cardBackground
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = fileIcon,
                fontSize = 32.sp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    fontWeight = FontWeight.Medium,
                    color = SilkColors.textPrimary,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${formatFileSize(file.size)} · ${formatTime(file.uploadTime)}",
                    color = SilkColors.textSecondary,
                    fontSize = 12.sp
                )
            }
            Text(
                text = "⬇️",
                fontSize = 20.sp,
                color = SilkColors.primary
            )
        }
    }
}

/**
 * 从 Uri 获取文件名
 */
fun getFileName(context: android.content.Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    result = it.getString(index)
                }
            }
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result ?: "unknown_file"
}

/**
 * 上传文件
 * 后端 API: POST /api/files/upload
 * 表单字段: sessionId, userId, file
 */
suspend fun uploadFile(
    inputStream: InputStream,
    fileName: String,
    sessionId: String,
    userId: String,
    onProgress: (String) -> Unit
): Boolean = withContext(Dispatchers.IO) {
    try {
        val boundary = "===" + System.currentTimeMillis() + "==="
        val url = URL("${BackendUrlHolder.getBaseUrl()}/api/files/upload")
        val connection = url.openConnection() as HttpURLConnection
        
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.doInput = true
        connection.useCaches = false
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.setRequestProperty("Connection", "Keep-Alive")
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        
        val outputStream = connection.outputStream
        val writer = java.io.PrintWriter(java.io.OutputStreamWriter(outputStream, "UTF-8"), true)
        
        // 写入 sessionId 字段
        writer.append("--$boundary").append("\r\n")
        writer.append("Content-Disposition: form-data; name=\"sessionId\"").append("\r\n")
        writer.append("\r\n")
        writer.append(sessionId).append("\r\n")
        
        // 写入 userId 字段
        writer.append("--$boundary").append("\r\n")
        writer.append("Content-Disposition: form-data; name=\"userId\"").append("\r\n")
        writer.append("\r\n")
        writer.append(userId).append("\r\n")
        
        // 写入文件部分
        writer.append("--$boundary").append("\r\n")
        writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"").append("\r\n")
        writer.append("Content-Type: application/octet-stream").append("\r\n")
        writer.append("\r\n")
        writer.flush()
        
        // 写入文件内容
        val buffer = ByteArray(4096)
        var bytesRead: Int
        var totalBytesRead = 0L
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
            totalBytesRead += bytesRead
            onProgress("已上传 ${formatFileSize(totalBytesRead)}")
        }
        outputStream.flush()
        inputStream.close()
        
        // 写入结束边界
        writer.append("\r\n")
        writer.append("--$boundary--").append("\r\n")
        writer.flush()
        writer.close()
        
        val responseCode = connection.responseCode
        connection.disconnect()
        
        responseCode == 200 || responseCode == 201
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

/**
 * 文件列表和 URL 清单的响应数据
 */
data class FilesAndUrls(
    val files: List<FileItem>,
    val processedUrls: List<String>
)

/**
 * 加载群组文件列表和已处理的 URL
 */
suspend fun loadGroupFilesAndUrls(groupId: String): FilesAndUrls = withContext(Dispatchers.IO) {
    try {
        val url = URL("${BackendUrlHolder.getBaseUrl()}/api/files/list/$groupId")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        
        if (connection.responseCode == 200) {
            val response = connection.inputStream.bufferedReader().readText()
            // 解析 JSON
            parseFileListAndUrls(response)
        } else {
            FilesAndUrls(emptyList(), emptyList())
        }
    } catch (e: Exception) {
        e.printStackTrace()
        FilesAndUrls(emptyList(), emptyList())
    }
}

/**
 * 解析文件列表和 URL 清单 JSON
 * 后端返回格式: {"sessionId":"...", "files":[...], "totalCount":1, "processedUrls":["url1", "url2"]}
 */
fun parseFileListAndUrls(json: String): FilesAndUrls {
    val files = mutableListOf<FileItem>()
    val urls = mutableListOf<String>()
    try {
        // 使用简单正则提取文件信息 - 匹配后端 API 格式
        val fileNamePattern = """"fileName"\s*:\s*"([^"]+)"""".toRegex()
        val sizePattern = """"size"\s*:\s*(\d+)""".toRegex()
        val timePattern = """"uploadTime"\s*:\s*(\d+)""".toRegex()
        
        val names = fileNamePattern.findAll(json).map { it.groupValues[1] }.toList()
        val sizes = sizePattern.findAll(json).map { it.groupValues[1].toLongOrNull() ?: 0L }.toList()
        val times = timePattern.findAll(json).map { it.groupValues[1].toLongOrNull() ?: 0L }.toList()
        
        for (i in names.indices) {
            files.add(FileItem(
                name = names[i],
                size = sizes.getOrElse(i) { 0L },
                uploadTime = times.getOrElse(i) { 0L },
                uploadedBy = ""  // 后端不返回此字段
            ))
        }
        
        // 解析 processedUrls 数组
        val urlsPattern = """"processedUrls"\s*:\s*\[([^\]]*)\]""".toRegex()
        val urlsMatch = urlsPattern.find(json)
        if (urlsMatch != null) {
            val urlsContent = urlsMatch.groupValues[1]
            val singleUrlPattern = """"([^"]+)"""".toRegex()
            singleUrlPattern.findAll(urlsContent).forEach { match ->
                urls.add(match.groupValues[1])
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return FilesAndUrls(files, urls)
}

/**
 * 格式化文件大小
 */
fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
        else -> "${size / (1024 * 1024 * 1024)} GB"
    }
}

/**
 * 格式化时间 - 使用上海时区 (UTC+8)
 */
fun formatTime(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    return sdf.format(Date(timestamp))
}

/**
 * 格式化时间为 HH:mm 格式 - 使用上海时区 (UTC+8)
 */
fun formatTimeHM(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    return sdf.format(Date(timestamp))
}

/**
 * 格式化时间为 HH:mm:ss 格式 - 使用上海时区 (UTC+8)
 */
fun formatTimeHMS(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    return sdf.format(Date(timestamp))
}


/**
 * 添加成员对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMemberDialog(
    contacts: List<Contact>,
    groupMembers: List<GroupMember>,
    groupId: String,
    isLoading: Boolean,
    result: String?,
    onAddMember: (Contact) -> Unit,
    onDismiss: () -> Unit
) {
    // 过滤出不在群组中的联系人
    val memberIds = groupMembers.map { it.id }.toSet()
    val availableContacts = contacts.filter { it.contactId !in memberIds }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // 标题
                Text(
                    text = "➕ 添加成员到群组",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                // 结果提示
                result?.let {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        color = if (it.startsWith("✅")) 
                            Color(0xFFE8F5E9) 
                        else 
                            Color(0xFFFFEBEE),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = it,
                            modifier = Modifier.padding(12.dp),
                            color = if (it.startsWith("✅")) 
                                Color(0xFF2E7D32) 
                            else 
                                Color(0xFFC62828)
                        )
                    }
                }
                
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (availableContacts.isEmpty()) {
                    Text(
                        text = "没有可添加的联系人\n（所有联系人已在群组中）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp)
                    )
                } else {
                    // 联系人列表
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(availableContacts) { contact ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = contact.contactName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = contact.contactPhone,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    
                                    Button(
                                        onClick = { onAddMember(contact) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Text("添加")
                                    }
                                }
                            }
                        }
                    }
                }
                
                // 关闭按钮
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text("关闭")
                }
            }
        }
    }
}

/**
 * 群组成员列表对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersDialog(
    members: List<GroupMember>,
    contacts: List<Contact>,
    currentUserId: String,
    isLoading: Boolean,
    onMemberClick: (GroupMember) -> Unit,
    onDismiss: () -> Unit
) {
    val contactIds = contacts.map { it.contactId }.toSet()
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // 标题
                Text(
                    text = "👥 群组成员 (${members.size})",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (members.isEmpty()) {
                    Text(
                        text = "暂无成员",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp)
                    )
                } else {
                    // 成员列表
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(members) { member ->
                            val isCurrentUser = member.id == currentUserId
                            val isContact = member.id in contactIds
                            val isSilkAI = member.id == "silk_ai_agent"
                            
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (!isCurrentUser && !isSilkAI) {
                                            Modifier.clickable { onMemberClick(member) }
                                        } else {
                                            Modifier
                                        }
                                    ),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        // 头像/图标
                                        Surface(
                                            modifier = Modifier.size(40.dp),
                                            shape = MaterialTheme.shapes.medium,
                                            color = when {
                                                isSilkAI -> SilkColors.info
                                                isCurrentUser -> SilkColors.primary
                                                isContact -> SilkColors.success
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.fillMaxSize()
                                            ) {
                                                Text(
                                                    text = when {
                                                        isSilkAI -> "🤖"
                                                        isCurrentUser -> "👤"
                                                        isContact -> "✓"
                                                        else -> member.fullName.firstOrNull()?.toString() ?: "?"
                                                    },
                                                    color = Color.White,
                                                    fontSize = 18.sp
                                                )
                                            }
                                        }
                                        
                                        // 名字和状态
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = member.fullName,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                if (isCurrentUser) {
                                                    Text(
                                                        text = " (我)",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                            Text(
                                                text = when {
                                                    isSilkAI -> "AI 助手"
                                                    isCurrentUser -> "当前用户"
                                                    isContact -> "联系人 · 点击聊天"
                                                    else -> "点击添加联系人"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    
                                    // 右侧操作提示
                                    if (!isCurrentUser && !isSilkAI) {
                                        Text(
                                            text = if (isContact) "💬" else "➕",
                                            fontSize = 20.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                // 关闭按钮
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text("关闭")
                }
            }
        }
    }
}

/**
 * 转发到群组对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardToGroupDialog(
    groups: List<Group>,
    isLoading: Boolean,
    selectedMessages: List<Message>,
    currentUser: User,
    onForward: (Group) -> Unit,
    onDismiss: () -> Unit,
    result: String?
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = SilkColors.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // 标题
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "💬 转发到对话",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = SilkColors.primary
                    )
                    Text(
                        text = "${selectedMessages.size} 条消息",
                        style = MaterialTheme.typography.bodySmall,
                        color = SilkColors.textSecondary
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 预览选中的消息
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 100.dp),
                    color = SilkColors.cardBackground,
                    shape = MaterialTheme.shapes.small
                ) {
                    LazyColumn(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        items(selectedMessages.take(3)) { msg ->
                            Text(
                                text = "${msg.userName}: ${msg.content.take(50)}${if (msg.content.length > 50) "..." else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = SilkColors.textSecondary,
                                maxLines = 1
                            )
                        }
                        if (selectedMessages.size > 3) {
                            item {
                                Text(
                                    text = "... 还有 ${selectedMessages.size - 3} 条消息",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SilkColors.textSecondary
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 结果提示
                result?.let {
                    Text(
                        text = it,
                        color = if (it.startsWith("✅")) SilkColors.success else SilkColors.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                
                // 群组列表
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = SilkColors.primary)
                    }
                } else if (groups.isEmpty()) {
                    Text(
                        text = "没有其他对话可转发",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SilkColors.textSecondary,
                        modifier = Modifier.padding(20.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(groups) { targetGroup ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onForward(targetGroup) },
                                colors = CardDefaults.cardColors(
                                    containerColor = SilkColors.cardBackground
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Text("💬", fontSize = 24.sp)
                                        Text(
                                            text = targetGroup.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Text("➡️", fontSize = 18.sp)
                                }
                            }
                        }
                    }
                }
                
                // 取消按钮
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text("取消", color = SilkColors.textSecondary)
                }
            }
        }
    }
}

/**
 * 转发到联系人对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardToContactDialog(
    contacts: List<Contact>,
    isLoading: Boolean,
    selectedMessages: List<Message>,
    currentUser: User,
    onForward: (Contact) -> Unit,
    onDismiss: () -> Unit,
    result: String?
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = SilkColors.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // 标题
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "👤 转发给联系人",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = SilkColors.primary
                    )
                    Text(
                        text = "${selectedMessages.size} 条消息",
                        style = MaterialTheme.typography.bodySmall,
                        color = SilkColors.textSecondary
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 预览选中的消息
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 100.dp),
                    color = SilkColors.cardBackground,
                    shape = MaterialTheme.shapes.small
                ) {
                    LazyColumn(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        items(selectedMessages.take(3)) { msg ->
                            Text(
                                text = "${msg.userName}: ${msg.content.take(50)}${if (msg.content.length > 50) "..." else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = SilkColors.textSecondary,
                                maxLines = 1
                            )
                        }
                        if (selectedMessages.size > 3) {
                            item {
                                Text(
                                    text = "... 还有 ${selectedMessages.size - 3} 条消息",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SilkColors.textSecondary
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 结果提示
                result?.let {
                    Text(
                        text = it,
                        color = if (it.startsWith("✅")) SilkColors.success else SilkColors.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                
                // 联系人列表
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = SilkColors.primary)
                    }
                } else if (contacts.isEmpty()) {
                    Text(
                        text = "暂无联系人",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SilkColors.textSecondary,
                        modifier = Modifier.padding(20.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(contacts) { contact ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onForward(contact) },
                                colors = CardDefaults.cardColors(
                                    containerColor = SilkColors.cardBackground
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Surface(
                                            modifier = Modifier.size(40.dp),
                                            shape = MaterialTheme.shapes.medium,
                                            color = SilkColors.primary
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.fillMaxSize()
                                            ) {
                                                Text(
                                                    text = contact.contactName.firstOrNull()?.toString() ?: "?",
                                                    color = Color.White,
                                                    fontSize = 18.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Column {
                                            Text(
                                                text = contact.contactName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = contact.contactPhone,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = SilkColors.textSecondary
                                            )
                                        }
                                    }
                                    Text("➡️", fontSize = 18.sp)
                                }
                            }
                        }
                    }
                }
                
                // 取消按钮
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text("取消", color = SilkColors.textSecondary)
                }
            }
        }
    }
}

/**
 * 提取行内数学公式
 * 支持 $...$ 和 \(...\) 格式
 * 返回处理后的文本和数学公式的位置列表
 */
private fun extractInlineMath(text: String): Pair<String, List<Pair<Int, String>>> {
    val mathSegments = mutableListOf<Pair<Int, String>>()
    val result = StringBuilder()
    var i = 0
    var offset = 0
    
    while (i < text.length) {
        // 检查是否是 \(...\) 格式的行内公式
        if (i + 1 < text.length && text[i] == '\\' && text[i + 1] == '(') {
            val start = i
            i += 2  // 跳过 \(
            // 找到结束的 \)
            while (i + 1 < text.length && !(text[i] == '\\' && text[i + 1] == ')')) {
                i++
            }
            if (i + 1 < text.length) {
                val formula = text.substring(start + 2, i)
                val processed = processMathFormula(formula)
                mathSegments.add(Pair(offset, processed))
                result.append(processed)
                offset += processed.length
                i += 2  // 跳过 \)
            } else {
                // 没有结束符，作为普通文本
                result.append(text.substring(start))
                offset += text.length - start
            }
        }
        // 检查是否是行内公式 $...$ (不是 $$)
        else if (text[i] == '$' && (i == 0 || text[i - 1] != '$') && (i + 1 >= text.length || text[i + 1] != '$')) {
            val start = i
            i++
            // 找到结束的 $
            while (i < text.length && text[i] != '$') {
                i++
            }
            if (i < text.length) {
                val formula = text.substring(start + 1, i)
                val processed = processMathFormula(formula)
                mathSegments.add(Pair(offset, processed))
                result.append(processed)
                offset += processed.length
                i++
            } else {
                // 没有结束符，作为普通文本
                result.append(text.substring(start))
                offset += text.length - start
            }
        } else {
            result.append(text[i])
            offset++
            i++
        }
    }
    
    return Pair(result.toString(), mathSegments)
}
