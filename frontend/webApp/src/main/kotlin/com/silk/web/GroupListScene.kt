package com.silk.web

import androidx.compose.runtime.*
import com.silk.shared.i18n.*
import com.silk.shared.models.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

@Composable
fun GroupListScene(appState: WebAppState) {
    val scope = rememberCoroutineScope()
    
    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    
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
    val strings = getStrings(userLanguage)
    
    // Load user language preference
    // Reload when user changes OR when navigating to this scene
    LaunchedEffect(appState.currentUser?.id, appState.currentScene) {
        if (appState.currentScene == Scene.GROUP_LIST) {
            appState.currentUser?.let { user ->
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
    }
    
    // 加载群组列表和未读数
    LaunchedEffect(Unit) {
        console.log("📋 GroupListScene - 开始加载群组...")
        console.log("   当前用户:", appState.currentUser?.fullName)
        
        scope.launch {
            isLoading = true
            try {
                val response = appState.currentUser?.let { user ->
                    console.log("   发送API请求获取群组...")
                    ApiClient.getUserGroups(user.id)
                }
                
                if (response != null && response.success) {
                    groups = response.groups ?: emptyList()
                    console.log("✅ 加载了${groups.size}个群组")
                    groups.forEach { group ->
                        console.log("   - ${group.name} (${group.invitationCode})")
                    }
                    
                    // 加载未读消息数
                    appState.currentUser?.let { user ->
                        val unreadResponse = ApiClient.getUnreadCounts(user.id)
                        if (unreadResponse.success) {
                            unreadCounts = unreadResponse.unreadCounts
                            console.log("✅ 未读消息: ", unreadCounts)
                        }
                    }
                } else {
                    console.log("⚠️ 加载群组失败:", response?.message)
                }
            } catch (e: Exception) {
                console.error("❌ 加载群组异常:", e.message)
                console.error("   详细错误:", e)
            } finally {
                isLoading = false
                console.log("📋 群组加载完成，isLoading =", isLoading)
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
            flexDirection(FlexDirection.Column)
            height(100.vh)
            width(100.vw)
            property("overflow", "hidden")
            property("background", SilkColors.backgroundGradient)
            fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
        }
    }) {
        // 顶部导航 - 丝滑金色渐变
        Div({
            style {
                property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                color(Color.white)
                padding(16.px, 20.px)
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.SpaceBetween)
                alignItems(AlignItems.Center)
                property("box-shadow", "0 2px 12px rgba(169, 137, 77, 0.25)")
            }
        }) {
            Div {
                // Logo
                Div({
                    style {
                        fontSize(24.px)
                        property("font-weight", "700")
                        property("letter-spacing", "4px")
                        property("text-transform", "uppercase")
                        marginBottom(4.px)
                    }
                }) {
                    Text("Silk")
                }
                Div({ 
                    style { 
                        fontSize(13.px)
                        property("opacity", "0.9")
                        property("letter-spacing", "1px")
                    } 
                }) {
                    Text(appState.currentUser?.fullName ?: "")
                }
            }
            
            // 右侧按钮组
            Div({
                style {
                    display(DisplayStyle.Flex)
                    gap(12.px)
                    alignItems(AlignItems.Center)
                }
            }) {
                // ➖ 删除/退出模式按钮
                if (isDeleteMode) {
                    // 取消按钮
                    Button({
                        style {
                            padding(10.px, 18.px)
                            backgroundColor(Color("rgba(255,255,255,0.3)"))
                            color(Color.white)
                            border { width(0.px) }
                            borderRadius(8.px)
                            property("cursor", "pointer")
                            property("backdrop-filter", "blur(4px)")
                            property("transition", "all 0.2s ease")
                            fontSize(14.px)
                            property("font-weight", "500")
                        }
                        onClick { 
                            isDeleteMode = false
                            selectedGroups = emptySet()
                        }
                    }) {
                        Text(strings.cancelButton)
                    }
                    
                    // 确认退出按钮
                    if (selectedGroups.isNotEmpty()) {
                        Button({
                            style {
                                padding(10.px, 18.px)
                                backgroundColor(Color("#e74c3c"))
                                color(Color.white)
                                border { width(0.px) }
                                borderRadius(8.px)
                                property("cursor", if (isDeleting) "not-allowed" else "pointer")
                                property("backdrop-filter", "blur(4px)")
                                property("transition", "all 0.2s ease")
                                fontSize(14.px)
                                property("font-weight", "500")
                                property("opacity", if (isDeleting) "0.6" else "1")
                            }
                            onClick { 
                                if (!isDeleting) {
                                    scope.launch {
                                        isDeleting = true
                                        val userId = appState.currentUser?.id ?: return@launch
                                        
                                        selectedGroups.forEach { groupId ->
                                            // 查找群组，判断是否是群主
                                            val group = groups.find { it.id == groupId }
                                            if (group != null && group.hostId == userId) {
                                                // 群主删除群组
                                                val response = ApiClient.deleteGroup(groupId, userId)
                                                console.log("删除群组 $groupId: ${response.message}")
                                            } else {
                                                // 普通成员退出群组
                                                val response = ApiClient.leaveGroup(groupId, userId)
                                                console.log("退出群组 $groupId: ${response.message}")
                                            }
                                        }
                                        
                                        // 刷新群组列表
                                        val response = ApiClient.getUserGroups(userId)
                                        if (response.success) {
                                            groups = response.groups ?: emptyList()
                                        }
                                        
                                        isDeleting = false
                                        isDeleteMode = false
                                        selectedGroups = emptySet()
                                    }
                                }
                            }
                        }) {
                            // 检查选中的群组中是否有群主身份的群
                            val userId = appState.currentUser?.id ?: ""
                            val hasHostGroups = selectedGroups.any { groupId -> 
                                groups.find { it.id == groupId }?.hostId == userId 
                            }
                            val hasMemberGroups = selectedGroups.any { groupId -> 
                                groups.find { it.id == groupId }?.hostId != userId 
                            }
                            
                            val actionText = when {
                                hasHostGroups && hasMemberGroups -> "删除/退出"
                                hasHostGroups -> "删除"
                                else -> "退出"
                            }
                            
                            Text(if (isDeleting) strings.exiting else "${actionText} (${selectedGroups.size})")
                        }
                    }
                } else {
                    // ➖ 进入删除模式
                    Button({
                        style {
                            padding(10.px, 18.px)
                            backgroundColor(Color("rgba(255,255,255,0.2)"))
                            color(Color.white)
                            border { width(0.px) }
                            borderRadius(8.px)
                            property("cursor", "pointer")
                            property("backdrop-filter", "blur(4px)")
                            property("transition", "all 0.2s ease")
                            fontSize(14.px)
                            property("font-weight", "500")
                        }
                        onClick { isDeleteMode = true }
                    }) {
                        Text("➖ ${strings.exitButton}")
                    }
                    
                    // 创建群组按钮
                    Button({
                        style {
                            padding(10.px, 18.px)
                            backgroundColor(Color("rgba(255,255,255,0.2)"))
                            color(Color.white)
                            border { width(0.px) }
                            borderRadius(8.px)
                            property("cursor", "pointer")
                            property("backdrop-filter", "blur(4px)")
                            property("transition", "all 0.2s ease")
                            fontSize(14.px)
                            property("font-weight", "500")
                        }
                        onClick { showCreateDialog = true }
                    }) {
                        Text("➕ ${strings.createButton}")
                    }
                }
                
                // 加入群组按钮
                Button({
                    style {
                        padding(10.px, 18.px)
                        backgroundColor(Color("rgba(255,255,255,0.2)"))
                        color(Color.white)
                        border { width(0.px) }
                        borderRadius(8.px)
                        property("cursor", "pointer")
                        property("backdrop-filter", "blur(4px)")
                        property("transition", "all 0.2s ease")
                        fontSize(14.px)
                        property("font-weight", "500")
                    }
                    onClick { showJoinDialog = true }
                }) {
                    Text("🔗 ${strings.joinButton}")
                }
                
                // 联系人按钮
                Button({
                    style {
                        padding(10.px, 18.px)
                        backgroundColor(Color("rgba(255,255,255,0.2)"))
                        color(Color.white)
                        border { width(0.px) }
                        borderRadius(8.px)
                        property("cursor", "pointer")
                        property("backdrop-filter", "blur(4px)")
                        property("transition", "all 0.2s ease")
                        fontSize(14.px)
                        property("font-weight", "500")
                    }
                    onClick { appState.navigateTo(Scene.CONTACTS) }
                }) {
                    Text("👤 ${strings.contactsButton}")
                }
                
                // 🤖 与 Silk 对话按钮
                Button({
                    style {
                        padding(10.px, 18.px)
                        backgroundColor(Color("#7BA8C9"))
                        color(Color.white)
                        border { width(0.px) }
                        borderRadius(8.px)
                        property("cursor", "pointer")
                        property("box-shadow", "0 2px 8px rgba(123, 168, 201, 0.4)")
                        property("transition", "all 0.2s ease")
                        fontSize(14.px)
                        property("font-weight", "600")
                    }
                    onClick { 
                        scope.launch {
                            val userId = appState.currentUser?.id ?: return@launch
                            val response = ApiClient.startSilkPrivateChat(userId)
                            if (response.success && response.group != null) {
                                console.log("✅ 打开与 Silk 的对话: ${response.group!!.name}")
                                appState.selectGroup(response.group!!)
                            } else {
                                console.log("❌ 打开 Silk 对话失败: ${response.message}")
                            }
                        }
                    }
                }) {
                    Text("🤖 ${strings.chatWithSilk}")
                }
                
                // 设置按钮
                Button({
                    style {
                        padding(10.px, 18.px)
                        backgroundColor(Color("rgba(255,255,255,0.2)"))
                        color(Color.white)
                        border { width(0.px) }
                        borderRadius(8.px)
                        property("cursor", "pointer")
                        property("backdrop-filter", "blur(4px)")
                        property("transition", "all 0.2s ease")
                        fontSize(14.px)
                        property("font-weight", "500")
                    }
                    onClick { appState.navigateTo(Scene.SETTINGS) }
                }) {
                    Text("⚙️ ${strings.settingsButton}")
                }
                
                // 登出按钮
                Button({
                    style {
                        padding(10.px, 18.px)
                        backgroundColor(Color("rgba(255,255,255,0.15)"))
                        color(Color.white)
                        border { width(0.px) }
                        borderRadius(8.px)
                        property("cursor", "pointer")
                        property("backdrop-filter", "blur(4px)")
                        property("transition", "all 0.2s ease")
                        fontSize(14.px)
                        property("font-weight", "500")
                    }
                    onClick { appState.logout() }
                }) {
                    Text(strings.logoutButton)
                }
            }
        }
        
        // 内容区域
        Div({
            style {
                property("flex", "1")
                property("overflow-y", "auto")
                padding(24.px)
            }
        }) {
            when {
                isLoading -> {
                    Div({
                        style {
                            textAlign("center")
                            padding(60.px)
                            color(Color(SilkColors.textSecondary))
                            fontSize(15.px)
                            property("letter-spacing", "1px")
                        }
                    }) {
                        Text(strings.loading)
                    }
                }
                groups.isEmpty() -> {
                    // 空状态 - 丝滑风格
                    Div({
                        style {
                            textAlign("center")
                            padding(60.px, 40.px)
                        }
                    }) {
                        H3({ 
                            style { 
                                color(Color(SilkColors.textPrimary))
                                marginBottom(12.px)
                                property("font-weight", "600")
                                property("letter-spacing", "1px")
                            } 
                        }) { 
                            Text(strings.noGroupsMessage) 
                        }
                        P({ 
                            style { 
                                color(Color(SilkColors.textSecondary))
                                fontSize(14.px)
                                property("letter-spacing", "0.5px")
                            } 
                        }) { 
                            Text(strings.noGroupsSubmessage) 
                        }
                        
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                justifyContent(JustifyContent.Center)
                                property("gap", "16px")
                                marginTop(32.px)
                            }
                        }) {
                            Button({
                                style {
                                    padding(14.px, 28.px)
                                    property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                                    color(Color.white)
                                    border { width(0.px) }
                                    borderRadius(8.px)
                                    property("cursor", "pointer")
                                    fontSize(14.px)
                                    property("font-weight", "600")
                                    property("letter-spacing", "1px")
                                    property("box-shadow", "0 4px 12px rgba(169, 137, 77, 0.3)")
                                    property("transition", "all 0.2s ease")
                                }
                                onClick { showCreateDialog = true }
                            }) {
                                Text(strings.createGroup)
                            }
                            
                            Button({
                                style {
                                    padding(14.px, 28.px)
                                    backgroundColor(Color(SilkColors.surfaceElevated))
                                    color(Color(SilkColors.primary))
                                    border {
                                        width(2.px)
                                        style(LineStyle.Solid)
                                        color(Color(SilkColors.primary))
                                    }
                                    borderRadius(8.px)
                                    property("cursor", "pointer")
                                    fontSize(14.px)
                                    property("font-weight", "600")
                                    property("letter-spacing", "1px")
                                    property("transition", "all 0.2s ease")
                                }
                                onClick { showJoinDialog = true }
                            }) {
                                Text(strings.joinGroup)
                            }
                        }
                    }
                }
                else -> {
                    // 删除模式提示
                    if (isDeleteMode) {
                        Div({
                            style {
                                backgroundColor(Color("#FFF3E0"))
                                padding(12.px, 16.px)
                                borderRadius(8.px)
                                marginBottom(16.px)
                                color(Color("#E65100"))
                                fontSize(14.px)
                                property("text-align", "center")
                            }
                        }) {
                            val currentUserId = appState.currentUser?.id ?: ""
                            val hostGroups = selectedGroups.count { gid -> groups.find { it.id == gid }?.hostId == currentUserId }
                            val memberGroups = selectedGroups.size - hostGroups
                            if (hostGroups > 0 && memberGroups > 0) {
                                Text("选中 ${hostGroups} 个群组将删除（作为群主），${memberGroups} 个群组将退出（作为成员）")
                            } else if (hostGroups > 0) {
                                Text("选中 ${hostGroups} 个群组将被删除（您是群主）")
                            } else if (memberGroups > 0) {
                                Text(strings.selectGroupsToExit)
                            } else {
                                Text(strings.selectGroupsToExit)
                            }
                        }
                    }
                    
                    // 群组列表
                    groups.forEach { group ->
                        val isSelected = group.id in selectedGroups
                        val unreadCount = unreadCounts[group.id] ?: 0
                        GroupCard(
                            group = group,
                            isHost = group.hostId == appState.currentUser?.id,
                            isDeleteMode = isDeleteMode,
                            isSelected = isSelected,
                            unreadCount = unreadCount,
                            strings = strings,
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
                }
            }
        }
        
        // FAB按钮（创建群组）- 丝滑风格
        if (groups.isNotEmpty()) {
            Button({
                style {
                    position(Position.Fixed)
                    bottom(28.px)
                    right(28.px)
                    width(60.px)
                    height(60.px)
                    borderRadius(50.percent)
                    property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                    color(Color.white)
                    border { width(0.px) }
                    fontSize(28.px)
                    property("cursor", "pointer")
                    property("box-shadow", "0 4px 16px rgba(169, 137, 77, 0.4)")
                    property("transition", "all 0.2s ease")
                }
                onClick { showCreateDialog = true }
            }) {
                Text("+")
            }
        }
        
        // 对话框
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
        
        // 成员列表对话框
        if (showMembersDialog && selectedGroupForMembers != null) {
            GroupMembersListDialog(
                group = selectedGroupForMembers!!,
                members = groupMembers,
                contacts = contacts,
                currentUserId = appState.currentUser?.id ?: "",
                isLoading = isLoadingMembers,
                strings = strings,
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
                                console.log("无法创建对话: ${response.message}")
                            }
                        }
                    } else {
                        // 不是联系人，发送联系人请求
                        scope.launch {
                            val userId = appState.currentUser?.id ?: return@launch
                            val response = ApiClient.sendContactRequestById(userId, member.id)
                            if (response.success) {
                                console.log("联系人请求已发送给 ${member.fullName}")
                            } else {
                                console.log("发送失败: ${response.message}")
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
    strings: Strings,
    onClick: () -> Unit,
    onMembersClick: (() -> Unit)? = null
) {
    val hasUnread = unreadCount > 0
    
    Div({
        style {
            backgroundColor(
                when {
                    isSelected -> Color("#FFEBEE")
                    hasUnread -> Color("#FFF8E1")  // 淡黄色背景表示有未读
                    else -> Color(SilkColors.surfaceElevated)
                }
            )
            borderRadius(8.px)
            padding(10.px, 14.px)
            marginBottom(8.px)
            property("box-shadow", 
                if (hasUnread) "0 2px 8px rgba(255, 152, 0, 0.3)" else "0 1px 4px rgba(169, 137, 77, 0.06)"
            )
            property("cursor", "pointer")
            property("transition", "all 0.2s ease")
            property("border", 
                when {
                    isSelected -> "2px solid #e74c3c"
                    hasUnread -> "2px solid #FF9800"  // 橙色边框
                    else -> "1px solid ${SilkColors.border}"
                }
            )
        }
        onClick { onClick() }
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.SpaceBetween)
                alignItems(AlignItems.Center)
            }
        }) {
            // 左侧：未读指示器 + 群名 + 邀请码（紧凑布局）
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    property("gap", "10px")
                    property("flex", "1")
                }
            }) {
                // ✅ 未读指示器（红点 + 数字）
                if (hasUnread && !isDeleteMode) {
                    Span({
                        style {
                            display(DisplayStyle.Flex)
                            alignItems(AlignItems.Center)
                            justifyContent(JustifyContent.Center)
                            width(24.px)
                            height(24.px)
                            backgroundColor(Color("#FF5722"))
                            borderRadius(50.percent)
                            color(Color.white)
                            fontSize(10.px)
                            property("font-weight", "bold")
                        }
                    }) {
                        Text(if (unreadCount > 99) "99+" else unreadCount.toString())
                    }
                }
                
                // 群名
                Span({
                    style {
                        fontSize(14.px)
                        property("font-weight", if (hasUnread) "700" else "600")
                        color(Color(if (hasUnread) "#E65100" else SilkColors.textPrimary))
                    }
                }) {
                    Text(group.name)
                }
                
                // 邀请码（小字体）
                Span({
                    style {
                        fontSize(11.px)
                        color(Color(SilkColors.textSecondary))
                        property("letter-spacing", "1px")
                    }
                }) {
                    Text("[${group.invitationCode}]")
                }
            }
            
            // 右侧按钮区域
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    property("gap", "8px")
                }
            }) {
                // ✅ 未读提示文字
                if (hasUnread && !isDeleteMode) {
                    Span({
                        style {
                            fontSize(11.px)
                            color(Color("#FF5722"))
                            property("font-weight", "bold")
                        }
                    }) {
                        Text(strings.newMessage)
                    }
                }
                
                // 成员按钮（非删除模式下显示）
                if (!isDeleteMode && onMembersClick != null) {
                    Button({
                        style {
                            padding(4.px, 10.px)
                            backgroundColor(Color(SilkColors.secondary))
                            color(Color(SilkColors.textPrimary))
                            border { width(0.px) }
                            borderRadius(4.px)
                            property("cursor", "pointer")
                            fontSize(12.px)
                        }
                        onClick { 
                            it.stopPropagation()
                            onMembersClick() 
                        }
                    }) {
                        Text("👥")
                    }
                }
                
                // 删除模式下显示选择指示器
                if (isDeleteMode) {
                    Div({
                        style {
                            width(22.px)
                            height(22.px)
                            borderRadius(50.percent)
                            backgroundColor(if (isSelected) Color("#e74c3c") else Color("#ddd"))
                            display(DisplayStyle.Flex)
                            justifyContent(JustifyContent.Center)
                            alignItems(AlignItems.Center)
                            color(Color.white)
                            fontSize(14.px)
                            property("font-weight", "bold")
                        }
                    }) {
                        if (isSelected) {
                            Text("✓")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CreateGroupDialog(
    appState: WebAppState,
    strings: Strings,
    onDismiss: () -> Unit,
    onGroupCreated: (Group) -> Unit
) {
    val scope = rememberCoroutineScope()
    var groupName by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
    val userName = appState.currentUser?.fullName ?: ""
    val previewName = if (groupName.isNotBlank()) "$userName's $groupName" else ""
    
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
                width(420.px)
                maxWidth(90.vw)
                property("box-shadow", "0 8px 32px rgba(169, 137, 77, 0.2)")
                property("border", "1px solid ${SilkColors.border}")
            }
            onClick { it.stopPropagation() }
        }) {
            H3({ 
                style { 
                    marginTop(0.px)
                    marginBottom(24.px)
                    color(Color(SilkColors.textPrimary))
                    property("font-weight", "600")
                    property("letter-spacing", "1px")
                } 
            }) {
                Text(strings.createGroupTitle)
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
                        Text(strings.groupName)
                    }
                }
                Input(InputType.Text) {
                    value(groupName)
                    onInput { groupName = it.value; errorMessage = "" }
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
                }
            }
            
            if (previewName.isNotEmpty()) {
                Div({
                    style {
                        fontSize(13.px)
                        color(Color(SilkColors.textSecondary))
                        marginBottom(20.px)
                        property("font-style", "italic")
                    }
                }) {
                    Text("${strings.fullName}: $previewName")
                }
            }
            
            if (errorMessage.isNotEmpty()) {
                Div({ 
                    style { 
                        color(Color(SilkColors.error))
                        fontSize(13.px)
                        marginBottom(20.px)
                        padding(12.px)
                        backgroundColor(Color("#FDF5F5"))
                        borderRadius(8.px)
                    } 
                }) {
                    Text(errorMessage)
                }
            }
            
            Div({
                style {
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.FlexEnd)
                    property("gap", "12px")
                }
            }) {
                Button({
                    style {
                        padding(12.px, 20.px)
                        backgroundColor(Color(SilkColors.secondary))
                        color(Color(SilkColors.textPrimary))
                        border { width(0.px) }
                        borderRadius(8.px)
                        property("cursor", "pointer")
                        fontSize(14.px)
                        property("font-weight", "500")
                    }
                    onClick { onDismiss() }
                }) {
                    Text(strings.cancelButton)
                }
                
                Button({
                    style {
                        padding(12.px, 20.px)
                        property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                        color(Color.white)
                        border { width(0.px) }
                        borderRadius(8.px)
                        property("cursor", if (isLoading || groupName.isBlank()) "not-allowed" else "pointer")
                        property("opacity", if (isLoading || groupName.isBlank()) "0.6" else "1")
                        fontSize(14.px)
                        property("font-weight", "600")
                        property("box-shadow", "0 2px 8px rgba(169, 137, 77, 0.25)")
                    }
                    onClick {
                        if (!isLoading && groupName.isNotBlank()) {
                            scope.launch {
                                isLoading = true
                                try {
                                    val response = appState.currentUser?.let { user ->
                                        ApiClient.createGroup(user.id, groupName)
                                    }
                                    
                                    if (response != null && response.success && response.group != null) {
                                        console.log("群组创建成功:", response.group.name)
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
                        }
                    }
                }) {
                    Text(if (isLoading) strings.creating else strings.createButton)
                }
            }
        }
    }
}

@Composable
fun JoinGroupDialog(
    appState: WebAppState,
    strings: Strings,
    onDismiss: () -> Unit,
    onGroupJoined: (Group) -> Unit
) {
    val scope = rememberCoroutineScope()
    var invitationCode by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
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
                property("box-shadow", "0 8px 32px rgba(169, 137, 77, 0.2)")
                property("border", "1px solid ${SilkColors.border}")
            }
            onClick { it.stopPropagation() }
        }) {
            H3({ 
                style { 
                    marginTop(0.px)
                    marginBottom(24.px)
                    color(Color(SilkColors.textPrimary))
                    property("font-weight", "600")
                    property("letter-spacing", "1px")
                } 
            }) {
                Text(strings.joinGroupTitle)
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
                        Text(strings.invitationCode)
                    }
                }
                Input(InputType.Text) {
                    value(invitationCode)
                    onInput { 
                        invitationCode = it.value.uppercase().take(6)
                        errorMessage = ""
                    }
                    style {
                        width(100.percent)
                        padding(14.px)
                        fontSize(16.px)
                        marginTop(8.px)
                        border { 
                            width(1.px)
                            style(LineStyle.Solid)
                            color(Color(SilkColors.border)) 
                        }
                        borderRadius(8.px)
                        property("box-sizing", "border-box")
                        property("text-transform", "uppercase")
                        property("letter-spacing", "4px")
                        property("text-align", "center")
                        property("background", SilkColors.surface)
                        property("color", SilkColors.textPrimary)
                        fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
                    }
                    attr("placeholder", strings.invitationCodePlaceholder)
                    attr("maxlength", "6")
                }
            }
            
            if (errorMessage.isNotEmpty()) {
                Div({ 
                    style { 
                        color(Color(SilkColors.error))
                        fontSize(13.px)
                        marginBottom(20.px)
                        padding(12.px)
                        backgroundColor(Color("#FDF5F5"))
                        borderRadius(8.px)
                    } 
                }) {
                    Text(errorMessage)
                }
            }
            
            Div({
                style {
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.FlexEnd)
                    property("gap", "12px")
                }
            }) {
                Button({
                    style {
                        padding(12.px, 20.px)
                        backgroundColor(Color(SilkColors.secondary))
                        color(Color(SilkColors.textPrimary))
                        border { width(0.px) }
                        borderRadius(8.px)
                        property("cursor", "pointer")
                        fontSize(14.px)
                        property("font-weight", "500")
                    }
                    onClick { onDismiss() }
                }) {
                    Text(strings.cancelButton)
                }
                
                Button({
                    style {
                        padding(12.px, 20.px)
                        property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                        color(Color.white)
                        border { width(0.px) }
                        borderRadius(8.px)
                        property("cursor", if (isLoading || invitationCode.length != 6) "not-allowed" else "pointer")
                        property("opacity", if (isLoading || invitationCode.length != 6) "0.6" else "1")
                        fontSize(14.px)
                        property("font-weight", "600")
                        property("box-shadow", "0 2px 8px rgba(169, 137, 77, 0.25)")
                    }
                    onClick {
                        if (!isLoading && invitationCode.length == 6) {
                            scope.launch {
                                isLoading = true
                                try {
                                    val response = appState.currentUser?.let { user ->
                                        ApiClient.joinGroup(user.id, invitationCode)
                                    }
                                    
                                    if (response != null && response.success && response.group != null) {
                                        console.log("加入群组成功:", response.group.name)
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
                        }
                    }
                }) {
                    Text(if (isLoading) strings.joining else strings.joinButton)
                }
            }
        }
    }
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
    strings: Strings,
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
                borderRadius(12.px)
                padding(20.px)
                width(400.px)
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
                    margin(0.px, 0.px, 16.px, 0.px)
                    color(Color(SilkColors.textPrimary))
                    fontSize(16.px)
                    property("font-weight", "600")
                }
            }) {
                Text("👥 ${strings.groupMembersTitle} ${group.name}")
            }
            
            if (isLoading) {
                Div({
                    style {
                        property("text-align", "center")
                        padding(20.px)
                        color(Color(SilkColors.textSecondary))
                    }
                }) {
                    Text("加载中...")
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
                        property("gap", "8px")
                    }
                }) {
                    members.forEach { member ->
                        val isHost = member.id == group.hostId
                        val isCurrentUser = member.id == currentUserId
                        val isContact = member.id in contactIds
                        val isSilkAI = member.id == "silk_ai_agent"
                        
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                justifyContent(JustifyContent.SpaceBetween)
                                alignItems(AlignItems.Center)
                                padding(10.px, 12.px)
                                backgroundColor(
                                    if (isHost) Color("rgba(201, 168, 108, 0.1)") 
                                    else Color(SilkColors.surface)
                                )
                                borderRadius(8.px)
                                if (!isCurrentUser && !isSilkAI) {
                                    property("cursor", "pointer")
                                }
                            }
                            if (!isCurrentUser && !isSilkAI) {
                                onClick { onMemberClick(member) }
                            }
                        }) {
                            // 左侧：头像 + 信息
                            Div({
                                style {
                                    display(DisplayStyle.Flex)
                                    alignItems(AlignItems.Center)
                                    property("gap", "10px")
                                }
                            }) {
                                // 头像
                                Div({
                                    style {
                                        width(32.px)
                                        height(32.px)
                                        borderRadius(6.px)
                                        backgroundColor(
                                            when {
                                                isSilkAI -> Color(SilkColors.info)
                                                isHost -> Color(SilkColors.primary)
                                                isContact -> Color(SilkColors.success)
                                                else -> Color(SilkColors.textSecondary)
                                            }
                                        )
                                        display(DisplayStyle.Flex)
                                        justifyContent(JustifyContent.Center)
                                        alignItems(AlignItems.Center)
                                        color(Color.white)
                                        fontSize(14.px)
                                    }
                                }) {
                                    Text(
                                        when {
                                            isSilkAI -> "🤖"
                                            isHost -> "👑"
                                            isContact -> "✓"
                                            else -> member.fullName.firstOrNull()?.toString() ?: "?"
                                        }
                                    )
                                }
                                
                                // 名字和状态
                                Div {
                                    Div({
                                        style {
                                            fontSize(13.px)
                                            color(Color(SilkColors.textPrimary))
                                            property("font-weight", if (isHost) "600" else "400")
                                        }
                                    }) {
                                        Text(member.fullName)
                                        if (isHost) {
                                            Span({
                                                style {
                                                    fontSize(11.px)
                                                    color(Color(SilkColors.primary))
                                                    marginLeft(4.px)
                                                }
                                            }) {
                                                Text(strings.host)
                                            }
                                        }
                                        if (isCurrentUser) {
                                            Span({
                                                style {
                                                    fontSize(11.px)
                                                    color(Color(SilkColors.textSecondary))
                                                    marginLeft(4.px)
                                                }
                                            }) {
                                                Text(strings.me)
                                            }
                                        }
                                    }
                                    Div({
                                        style {
                                            fontSize(11.px)
                                            color(Color(SilkColors.textSecondary))
                                            marginTop(2.px)
                                        }
                                    }) {
                                        Text(
                                            when {
                                                isSilkAI -> "AI 助手"
                                                isCurrentUser -> "当前用户"
                                                isContact -> "联系人 · 点击聊天"
                                                else -> "点击添加联系人"
                                            }
                                        )
                                    }
                                }
                            }
                            
                            // 右侧操作提示
                            if (!isCurrentUser && !isSilkAI) {
                                Div({
                                    style {
                                        fontSize(16.px)
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
                    marginTop(16.px)
                    backgroundColor(Color(SilkColors.textSecondary))
                    color(Color.white)
                    border { width(0.px) }
                    borderRadius(8.px)
                    padding(10.px)
                    property("cursor", "pointer")
                    fontSize(13.px)
                    property("font-weight", "500")
                }
                onClick { onDismiss() }
            }) {
                Text(strings.closeButton)
            }
        }
    }
}
