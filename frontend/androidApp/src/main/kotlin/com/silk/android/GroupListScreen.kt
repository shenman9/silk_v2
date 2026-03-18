package com.silk.android

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silk.shared.i18n.*
import com.silk.shared.models.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupListScreen(appState: AppState) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    
    // 升级相关状态
    var showUpgradeDialog by remember { mutableStateOf(false) }
    var downloadState by remember { mutableStateOf<ApkDownloader.DownloadState>(ApkDownloader.DownloadState.Idle) }
    
    // 删除模式相关状态
    var isDeleteMode by remember { mutableStateOf(false) }
    var selectedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isDeleting by remember { mutableStateOf(false) }
    
    // 成员列表相关状态
    var showMembersDialog by remember { mutableStateOf(false) }
    var selectedGroupForMembers by remember { mutableStateOf<Group?>(null) }
    var groupMembers by remember { mutableStateOf<List<GroupMember>>(emptyList()) }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var isLoadingMembers by remember { mutableStateOf(false) }
    
    // ✅ 未读消息计数
    var unreadCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    
    // Language and strings
    var userLanguage by remember { mutableStateOf<Language>(Language.CHINESE) }
    
    // Load user language preference
    LaunchedEffect(appState.currentUser?.id) {
        appState.currentUser?.let { user ->
            scope.launch {
                try {
                    val response = ApiClient.getUserSettings(user.id)
                    if (response.success && response.settings != null) {
                        userLanguage = response.settings!!.language
                    }
                } catch (e: Exception) {
                    println("Failed to load user settings: $e")
                }
            }
        }
    }
    
    val strings = getStrings(userLanguage)
    
    // 加载群组列表和未读数
    LaunchedEffect(Unit) {
        scope.launch {
            isLoading = true
            try {
                val response = appState.currentUser?.let { user ->
                    ApiClient.getUserGroups(user.id)
                }
                
                if (response != null && response.success) {
                    groups = response.groups ?: emptyList()
                    println("✅ 加载了 ${groups.size} 个群组")
                    
                    // 加载未读消息数
                    appState.currentUser?.let { user ->
                        val unreadResponse = ApiClient.getUnreadCounts(user.id)
                        if (unreadResponse.success) {
                            unreadCounts = unreadResponse.unreadCounts
                            println("✅ 未读消息: $unreadCounts")
                        }
                    }
                }
            } catch (e: Exception) {
                println("❌ 加载群组异常: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }
    
    // 定期刷新未读数（每30秒）
    LaunchedEffect(groups) {
        if (groups.isNotEmpty()) {
            while (true) {
                kotlinx.coroutines.delay(30000)
                appState.currentUser?.let { user ->
                    val unreadResponse = ApiClient.getUnreadCounts(user.id)
                    if (unreadResponse.success) {
                        unreadCounts = unreadResponse.unreadCounts
                    }
                }
            }
        }
    }
    
    Scaffold(
        topBar = {
            // Silk 风格顶部导航 - 金色渐变
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.Transparent
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    SilkColors.primary,
                                    SilkColors.primaryDark
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
                        Column {
                            Text(
                                text = "SILK",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 4.sp
                                ),
                                color = Color.White
                            )
                            Text(
                                text = appState.currentUser?.fullName ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isDeleteMode) {
                                // 取消按钮
                                IconButton(
                                    onClick = { 
                                        isDeleteMode = false
                                        selectedGroups = emptySet()
                                    },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Close, 
                                        contentDescription = "取消",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                
                                // 确认退出按钮
                                if (selectedGroups.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            if (!isDeleting) {
                                                scope.launch {
                                                    isDeleting = true
                                                    val userId = appState.currentUser?.id ?: return@launch
                                                    
                                                    selectedGroups.forEach { groupId ->
                                                        val response = ApiClient.leaveGroup(groupId, userId)
                                                        println("退出群组 $groupId: ${response.message}")
                                                    }
                                                    
                                                    // 刷新群组列表
                                                    val response = ApiClient.getUserGroups(userId)
                                                    if (response.success) {
                                                        groups = response.groups ?: emptyList()
                                                    }
                                                    
                                                    isDeleting = false
                                                    isDeleteMode = false
                                                    selectedGroups = emptySet()
                                                    
                                                    Toast.makeText(context, "已退出 ${selectedGroups.size} 个群组", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        colors = IconButtonDefaults.iconButtonColors(
                                            contentColor = if (isDeleting) Color.Gray else Color(0xFFe74c3c)
                                        )
                                    ) {
                                        if (isDeleting) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                color = Color.White,
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(
                                                Icons.Default.Check, 
                                                contentDescription = "确认退出",
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                }
                                
                                // 显示选中数量
                                Text(
                                    text = "已选${selectedGroups.size}个",
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            } else {
                                // 退出模式按钮
                                IconButton(
                                    onClick = { isDeleteMode = true },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Remove, 
                                        contentDescription = "退出群组",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                
                                // 升级按钮 - 图标按钮
                                IconButton(
                                    onClick = { showUpgradeDialog = true },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.SystemUpdate, 
                                        contentDescription = "升级",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                
                                // 创建群组按钮 - 图标按钮
                                IconButton(
                                    onClick = { showCreateDialog = true },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Add, 
                                        contentDescription = "创建",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                
                                // 加入群组按钮 - 图标按钮
                                IconButton(
                                    onClick = { showJoinDialog = true },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.GroupAdd, 
                                        contentDescription = "加入",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                
                                // 🤖 与 Silk 对话按钮
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            val userId = appState.currentUser?.id ?: return@launch
                                            val response = ApiClient.startSilkPrivateChat(userId)
                                            if (response.success && response.group != null) {
                                                println("✅ 打开与 Silk 的对话: ${response.group!!.name}")
                                                appState.selectGroup(response.group!!)
                                            } else {
                                                println("❌ 打开 Silk 对话失败: ${response.message}")
                                                Toast.makeText(context, response.message, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        contentColor = Color(0xFF7BA8C9) // 蓝色，区别于其他按钮
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.SmartToy,
                                        contentDescription = "与 Silk 对话",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                
                                // 联系人按钮 - 图标按钮
                                IconButton(
                                    onClick = { appState.navigateTo(Scene.CONTACTS) },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Contacts, 
                                        contentDescription = "联系人",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                
                                // 设置按钮 - 图标按钮
                                IconButton(
                                    onClick = { appState.navigateTo(Scene.SETTINGS) },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Settings, 
                                        contentDescription = "设置",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                
                                // 登出按钮 - 图标按钮
                                IconButton(
                                    onClick = { appState.logout() },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        contentColor = Color.White.copy(alpha = 0.8f)
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Logout, 
                                        contentDescription = "登出",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        // Silk 风格背景
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            SilkColors.background,
                            SilkColors.secondary.copy(alpha = 0.2f),
                            SilkColors.background
                        )
                    )
                )
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = SilkColors.primary
                    )
                }
                groups.isEmpty() -> {
                    // Silk 风格空状态
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "🧵",
                            style = MaterialTheme.typography.displayLarge
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "您还没有加入任何群组",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = SilkColors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "创建一个新群组或加入现有群组",
                            style = MaterialTheme.typography.bodyLarge,
                            color = SilkColors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(40.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Button(
                                onClick = { showCreateDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SilkColors.primary
                                )
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("创建群组")
                            }
                            OutlinedButton(
                                onClick = { showJoinDialog = true },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = SilkColors.primary
                                )
                            ) {
                                Icon(Icons.Default.GroupAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("加入群组")
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 删除模式提示
                        if (isDeleteMode) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color(0xFFFFF3E0)
                                    )
                                ) {
                                    Text(
                                        text = "点击选择要退出的群组",
                                        modifier = Modifier.padding(12.dp),
                                        color = Color(0xFFE65100),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                        
                        items(groups) { group ->
                            val isSelected = group.id in selectedGroups
                            val unreadCount = unreadCounts[group.id] ?: 0
                            GroupCard(
                                group = group,
                                isHost = group.hostId == appState.currentUser?.id,
                                isDeleteMode = isDeleteMode,
                                isSelected = isSelected,
                                unreadCount = unreadCount,
                                onClick = { 
                                    if (isDeleteMode) {
                                        selectedGroups = if (isSelected) {
                                            selectedGroups - group.id
                                        } else {
                                            selectedGroups + group.id
                                        }
                                    } else {
                                        // 标记为已读并清除本地未读计数
                                        scope.launch {
                                            appState.currentUser?.let { user ->
                                                ApiClient.markGroupAsRead(user.id, group.id)
                                                unreadCounts = unreadCounts - group.id
                                            }
                                        }
                                        appState.selectGroup(group)
                                    }
                                },
                                onMembersClick = {
                                    selectedGroupForMembers = group
                                    scope.launch {
                                        isLoadingMembers = true
                                        val userId = appState.currentUser?.id ?: return@launch
                                        val contactsResponse = ApiClient.getContacts(userId)
                                        contacts = contactsResponse.contacts ?: emptyList()
                                        val membersResponse = ApiClient.getGroupMembers(group.id)
                                        // 将群主排在第一位
                                        val sortedMembers = membersResponse.members.sortedByDescending { it.id == group.hostId }
                                        groupMembers = sortedMembers
                                        isLoadingMembers = false
                                        showMembersDialog = true
                                    }
                                }
                            )
                        }
                        
                        // 添加一个加入群组的按钮（非删除模式才显示）
                        if (!isDeleteMode) {
                            item {
                                OutlinedCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showJoinDialog = true }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "+ 加入其他群组",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // 创建群组对话框
        if (showCreateDialog) {
            CreateGroupDialog(
                appState = appState,
                strings = strings,
                onDismiss = { showCreateDialog = false },
                onGroupCreated = { newGroup ->
                    groups = groups + newGroup
                    showCreateDialog = false
                }
            )
        }
        
        // 加入群组对话框
        if (showJoinDialog) {
            JoinGroupDialog(
                appState = appState,
                strings = strings,
                onDismiss = { showJoinDialog = false },
                onGroupJoined = { newGroup ->
                    groups = groups + newGroup
                    showJoinDialog = false
                }
            )
        }
        
        // 升级对话框
        if (showUpgradeDialog) {
            UpgradeDialog(
                downloadState = downloadState,
                onDismiss = { 
                    if (downloadState !is ApkDownloader.DownloadState.Downloading) {
                        showUpgradeDialog = false
                        downloadState = ApkDownloader.DownloadState.Idle
                    }
                },
                onStartDownload = {
                    scope.launch {
                        ApkDownloader.downloadApk(context) { state ->
                            downloadState = state
                            
                            // 下载成功后自动安装
                            if (state is ApkDownloader.DownloadState.Success) {
                                try {
                                    ApkDownloader.installApk(context, state.file)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "启动安装失败: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
            )
        }
        
        // 成员列表对话框
        if (showMembersDialog && selectedGroupForMembers != null) {
            GroupMembersListDialog(
                group = selectedGroupForMembers!!,
                members = groupMembers,
                contacts = contacts,
                currentUserId = appState.currentUser?.id ?: "",
                isLoading = isLoadingMembers,
                onMemberClick = { member ->
                    // 检查是否是联系人
                    val isContact = contacts.any { it.contactId == member.id }
                    if (isContact) {
                        // 是联系人，跳转到与该联系人的对话
                        scope.launch {
                            showMembersDialog = false
                            val userId = appState.currentUser?.id ?: return@launch
                            val response = ApiClient.startPrivateChat(userId, member.id)
                            if (response.success && response.group != null) {
                                appState.selectGroup(response.group!!)
                            } else {
                                Toast.makeText(context, "无法创建对话: ${response.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        // 不是联系人，发送联系人请求
                        scope.launch {
                            val userId = appState.currentUser?.id ?: return@launch
                            val response = ApiClient.sendContactRequestById(userId, member.id)
                            if (response.success) {
                                Toast.makeText(context, "联系人请求已发送给 ${member.fullName}", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "发送失败: ${response.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                onDismiss = { 
                    showMembersDialog = false
                    selectedGroupForMembers = null
                }
            )
        }
    }
}

@Composable
fun GroupCard(
    group: Group,
    isHost: Boolean,
    isDeleteMode: Boolean = false,
    isSelected: Boolean = false,
    unreadCount: Int = 0,
    onClick: () -> Unit,
    onMembersClick: (() -> Unit)? = null
) {
    val hasUnread = unreadCount > 0
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = if (hasUnread) 6.dp else 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> Color(0xFFFFEBEE)
                hasUnread -> Color(0xFFFFF8E1)  // 淡黄色背景表示有未读
                else -> SilkColors.surfaceElevated
            }
        ),
        shape = MaterialTheme.shapes.small,
        border = when {
            isSelected -> androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFe74c3c))
            hasUnread -> androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFFF9800))  // 橙色边框
            else -> null
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：群名 + 邀请码（紧凑布局）
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ✅ 未读指示器（红点 + 数字）
                if (hasUnread && !isDeleteMode) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(
                                color = Color(0xFFFF5722),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                
                // 群名
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (hasUnread) Color(0xFFE65100) else SilkColors.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                // 邀请码（小字体）
                Text(
                    text = "[${group.invitationCode}]",
                    style = MaterialTheme.typography.labelSmall,
                    color = SilkColors.textSecondary,
                    letterSpacing = 1.sp
                )
            }
            
            // 右侧按钮区域
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ✅ 未读提示文字
                if (hasUnread && !isDeleteMode) {
                    Text(
                        text = "新消息",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF5722),
                        fontWeight = FontWeight.Bold
                    )
                }
                
                // 成员按钮（非删除模式下显示）
                if (!isDeleteMode && onMembersClick != null) {
                    Surface(
                        onClick = { onMembersClick() },
                        color = SilkColors.secondary.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "👥",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                
                // 删除模式下显示选择指示器
                if (isDeleteMode) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(
                                color = if (isSelected) Color(0xFFe74c3c) else Color.LightGray,
                                shape = androidx.compose.foundation.shape.CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "已选择",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CreateGroupDialog(
    appState: AppState,
    strings: com.silk.shared.i18n.Strings,
    onDismiss: () -> Unit,
    onGroupCreated: (Group) -> Unit
) {
    val scope = rememberCoroutineScope()
    var groupName by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
    val userName = appState.currentUser?.fullName ?: ""
    val previewName = if (groupName.isNotBlank()) "$userName's $groupName" else ""
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.createGroupTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it; errorMessage = "" },
                    label = { Text(strings.groupName) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                    singleLine = true
                )
                
                if (previewName.isNotEmpty()) {
                    Text(
                        text = "${strings.fullName}: $previewName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        try {
                            val response = appState.currentUser?.let { user ->
                                ApiClient.createGroup(user.id, groupName)
                            }
                            
                            if (response != null && response.success && response.group != null) {
                                println("群组创建成功: ${response.group.name}")
                                onGroupCreated(response.group)
                            } else {
                                errorMessage = response?.message ?: "创建失败"
                            }
                        } catch (e: Exception) {
                            errorMessage = "创建失败: ${e.message}"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading && groupName.isNotBlank()
            ) {
                Text(if (isLoading) strings.creating else strings.createButton)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text(strings.cancelButton)
            }
        }
    )
}

@Composable
fun JoinGroupDialog(
    appState: AppState,
    strings: com.silk.shared.i18n.Strings,
    onDismiss: () -> Unit,
    onGroupJoined: (Group) -> Unit
) {
    val scope = rememberCoroutineScope()
    var invitationCode by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.joinGroupTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = invitationCode,
                    onValueChange = {
                        invitationCode = it.uppercase().take(6)
                        errorMessage = ""
                    },
                    label = { Text(strings.invitationCode) },
                    placeholder = { Text(strings.invitationCodePlaceholder) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                    singleLine = true
                )
                
                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        try {
                            val response = appState.currentUser?.let { user ->
                                ApiClient.joinGroup(user.id, invitationCode)
                            }
                            
                            if (response != null && response.success && response.group != null) {
                                println("加入群组成功: ${response.group.name}")
                                onGroupJoined(response.group)
                            } else {
                                errorMessage = response?.message ?: "加入失败"
                            }
                        } catch (e: Exception) {
                            errorMessage = "加入失败: ${e.message}"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading && invitationCode.length == 6
            ) {
                Text(if (isLoading) strings.joining else strings.joinButton)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text(strings.cancelButton)
            }
        }
    )
}

/**
 * 升级对话框
 */
@Composable
fun UpgradeDialog(
    downloadState: ApkDownloader.DownloadState,
    onDismiss: () -> Unit,
    onStartDownload: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                "应用升级",
                fontWeight = FontWeight.Bold,
                color = SilkColors.primary
            ) 
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (downloadState) {
                    is ApkDownloader.DownloadState.Idle -> {
                        Text("点击下载按钮获取最新版本的 Silk 应用")
                        Text(
                            "下载完成后将自动启动安装程序",
                            style = MaterialTheme.typography.bodySmall,
                            color = SilkColors.textSecondary
                        )
                    }
                    is ApkDownloader.DownloadState.Downloading -> {
                        Text(downloadState.message)
                        if (downloadState.progress >= 0) {
                            LinearProgressIndicator(
                                progress = downloadState.progress / 100f,
                                modifier = Modifier.fillMaxWidth(),
                                color = SilkColors.primary
                            )
                            Text(
                                "${downloadState.progress}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = SilkColors.textSecondary
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = SilkColors.primary
                            )
                        }
                    }
                    is ApkDownloader.DownloadState.Success -> {
                        Text("✅ 下载完成！")
                        Text(
                            "正在启动安装程序...",
                            style = MaterialTheme.typography.bodySmall,
                            color = SilkColors.success
                        )
                    }
                    is ApkDownloader.DownloadState.Error -> {
                        Text(
                            "❌ ${downloadState.message}",
                            color = SilkColors.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (downloadState) {
                is ApkDownloader.DownloadState.Idle,
                is ApkDownloader.DownloadState.Error -> {
                    Button(
                        onClick = onStartDownload,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SilkColors.primary
                        )
                    ) {
                        Text("下载更新")
                    }
                }
                is ApkDownloader.DownloadState.Downloading -> {
                    // 下载中不显示确认按钮
                }
                is ApkDownloader.DownloadState.Success -> {
                    Button(onClick = onDismiss) {
                        Text("完成")
                    }
                }
            }
        },
        dismissButton = {
            if (downloadState !is ApkDownloader.DownloadState.Downloading) {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}

/**
 * 群组成员列表对话框（在群列表页面使用）
 */
@Composable
fun GroupMembersListDialog(
    group: Group,
    members: List<GroupMember>,
    contacts: List<Contact>,
    currentUserId: String,
    isLoading: Boolean,
    onMemberClick: (GroupMember) -> Unit,
    onDismiss: () -> Unit
) {
    val contactIds = contacts.map { it.contactId }.toSet()
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                "👥 ${group.name}",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            ) 
        },
        text = {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = SilkColors.primary)
                }
            } else if (members.isEmpty()) {
                Text("暂无成员", color = SilkColors.textSecondary)
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(members) { member ->
                        val isHost = member.id == group.hostId
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
                                containerColor = if (isHost) SilkColors.primary.copy(alpha = 0.1f) 
                                    else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // 头像
                                    Surface(
                                        modifier = Modifier.size(32.dp),
                                        shape = MaterialTheme.shapes.small,
                                        color = when {
                                            isSilkAI -> SilkColors.info
                                            isHost -> SilkColors.primary
                                            isContact -> SilkColors.success
                                            else -> SilkColors.textSecondary
                                        }
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            Text(
                                                text = when {
                                                    isSilkAI -> "🤖"
                                                    isHost -> "👑"
                                                    isContact -> "✓"
                                                    else -> member.fullName.firstOrNull()?.toString() ?: "?"
                                                },
                                                color = Color.White,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                    
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = member.fullName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isHost) FontWeight.Bold else FontWeight.Normal
                                            )
                                            if (isHost) {
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "(群主)",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = SilkColors.primary
                                                )
                                            }
                                            if (isCurrentUser) {
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "(我)",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = SilkColors.textSecondary
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
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SilkColors.textSecondary
                                        )
                                    }
                                }
                                
                                if (!isCurrentUser && !isSilkAI) {
                                    Text(
                                        text = if (isContact) "💬" else "➕",
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
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

