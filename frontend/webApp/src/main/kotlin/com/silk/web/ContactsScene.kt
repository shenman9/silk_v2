package com.silk.web

import androidx.compose.runtime.*
import com.silk.shared.i18n.*
import com.silk.shared.models.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

@Composable
fun ContactsScene(appState: WebAppState) {
    val scope = rememberCoroutineScope()
    
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var pendingRequests by remember { mutableStateOf<List<ContactRequest>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showAddContactDialog by remember { mutableStateOf(false) }
    var showRequestDetailDialog by remember { mutableStateOf<ContactRequest?>(null) }
    
    // Language and strings
    var userLanguage by remember { mutableStateOf<Language>(Language.CHINESE) }
    
    // Load user language preference - load immediately when component mounts
    LaunchedEffect(appState.currentUser?.id) {
        appState.currentUser?.let { user ->
            scope.launch {
                try {
                    val response = ApiClient.getUserSettings(user.id)
                    if (response.success && response.settings != null) {
                        userLanguage = response.settings!!.language
                        console.log("✅ ContactsScene: Loaded language preference:", response.settings!!.language)
                    } else {
                        console.log("⚠️ ContactsScene: No user settings found, using default CHINESE")
                    }
                } catch (e: Exception) {
                    console.error("❌ ContactsScene: Failed to load user settings:", e)
                }
            }
        }
    }
    
    val strings = getStrings(userLanguage)
    
    // 加载联系人列表
    fun loadContacts() {
        scope.launch {
            isLoading = true
            try {
                val response = appState.currentUser?.let { user ->
                    ApiClient.getContacts(user.id)
                }
                
                if (response != null && response.success) {
                    contacts = response.contacts ?: emptyList()
                    pendingRequests = response.pendingRequests ?: emptyList()
                    console.log("✅ 加载了${contacts.size}个联系人，${pendingRequests.size}个待处理请求")
                }
            } catch (e: Exception) {
                console.error("❌ 加载联系人失败:", e.message)
            } finally {
                isLoading = false
            }
        }
    }
    
    LaunchedEffect(Unit) {
        loadContacts()
    }
    
    // 主容器
    Div({
        style {
            minHeight(100.vh)
            background("linear-gradient(135deg, ${SilkColors.background} 0%, ${SilkColors.surfaceElevated} 100%)")
            padding(0.px)
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
        }
    }) {
        // 顶部导航栏
        Div({
            style {
                background("linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryLight} 100%)")
                padding(16.px, 24.px)
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                justifyContent(JustifyContent.SpaceBetween)
                property("box-shadow", "0 2px 12px rgba(169, 137, 77, 0.2)")
            }
        }) {
            // 左侧：返回和标题
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    gap(16.px)
                }
            }) {
                // 返回按钮（切换到群组列表）
                Button({
                    style {
                        backgroundColor(Color.transparent)
                        color(Color.white)
                        border { style(LineStyle.None) }
                        padding(8.px, 12.px)
                        borderRadius(8.px)
                        property("cursor", "pointer")
                        fontSize(14.px)
                        property("transition", "background-color 0.2s")
                    }
                    onClick { appState.navigateTo(Scene.GROUP_LIST) }
                }) {
                    Text(strings.backToGroups)
                }
                
                Span({
                    style {
                        color(Color.white)
                        fontSize(20.px)
                        property("font-weight", "600")
                        property("letter-spacing", "1px")
                    }
                }) {
                    Text(strings.contactsTitle)
                }
            }
            
            // 右侧：用户信息和退出
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    gap(16.px)
                }
            }) {
                Span({
                    style {
                        color(Color.white)
                        fontSize(14.px)
                        property("opacity", "0.9")
                    }
                }) {
                    Text(appState.currentUser?.fullName ?: "")
                }
                
                Button({
                    style {
                        backgroundColor(Color.transparent)
                        color(Color.white)
                        border {
                            width(1.px)
                            style(LineStyle.Solid)
                            color(Color.white)
                        }
                        padding(6.px, 12.px)
                        borderRadius(6.px)
                        property("cursor", "pointer")
                        fontSize(12.px)
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
                flex(1)
                padding(24.px)
                maxWidth(800.px)
                width(100.percent)
                property("margin", "0 auto")
            }
        }) {
            when {
                isLoading -> {
                    // 加载中
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            justifyContent(JustifyContent.Center)
                            alignItems(AlignItems.Center)
                            height(200.px)
                        }
                    }) {
                        Span({
                            style {
                                color(Color(SilkColors.textSecondary))
                                fontSize(16.px)
                            }
                        }) {
                            Text(strings.loading)
                        }
                    }
                }
                else -> {
                    // 待处理的联系人请求
                    if (pendingRequests.isNotEmpty()) {
                        Div({
                            style {
                                marginBottom(24.px)
                            }
                        }) {
                            H3({
                                style {
                                    color(Color(SilkColors.textPrimary))
                                    fontSize(16.px)
                                    marginBottom(12.px)
                                    property("font-weight", "600")
                                }
                            }) {
                                Text(strings.pendingRequestsTitle.replace("{count}", pendingRequests.size.toString()))
                            }
                            
                            pendingRequests.forEach { request ->
                                PendingRequestCard(
                                    request = request,
                                    strings = strings,
                                    onClick = { showRequestDetailDialog = request }
                                )
                            }
                        }
                    }
                    
                    // 联系人列表标题
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            justifyContent(JustifyContent.SpaceBetween)
                            alignItems(AlignItems.Center)
                            marginBottom(16.px)
                        }
                    }) {
                        H3({
                            style {
                                color(Color(SilkColors.textPrimary))
                                fontSize(16.px)
                                property("font-weight", "600")
                            }
                        }) {
                            Text(strings.myContactsWithCount.replace("{count}", contacts.size.toString()))
                        }
                        
                        Button({
                            style {
                                backgroundColor(Color(SilkColors.primary))
                                color(Color.white)
                                border { style(LineStyle.None) }
                                padding(10.px, 20.px)
                                borderRadius(8.px)
                                property("cursor", "pointer")
                                fontSize(14.px)
                                property("font-weight", "500")
                            }
                            onClick { showAddContactDialog = true }
                        }) {
                            Text(strings.addContactButton)
                        }
                    }
                    
                    // 联系人列表
                    if (contacts.isEmpty()) {
                        // 空状态
                        Div({
                            style {
                                backgroundColor(Color(SilkColors.surfaceElevated))
                                borderRadius(12.px)
                                padding(40.px)
                                textAlign("center")
                                property("border", "1px solid ${SilkColors.border}")
                            }
                        }) {
                            Div({
                                style {
                                    fontSize(48.px)
                                    marginBottom(16.px)
                                }
                            }) {
                                Text("👤")
                            }
                            
                            Div({
                                style {
                                    color(Color(SilkColors.textSecondary))
                                    fontSize(16.px)
                                    marginBottom(20.px)
                                }
                            }) {
                                Text(strings.noContactsYet)
                            }
                            
                            Button({
                                style {
                                    backgroundColor(Color(SilkColors.primary))
                                    color(Color.white)
                                    border { style(LineStyle.None) }
                                    padding(12.px, 24.px)
                                    borderRadius(8.px)
                                    property("cursor", "pointer")
                                    fontSize(14.px)
                                    property("font-weight", "500")
                                }
                                onClick { showAddContactDialog = true }
                            }) {
                                Text(strings.addFirstContact)
                            }
                        }
                    } else {
                        // 联系人卡片列表
                        contacts.forEach { contact ->
                            ContactCard(
                                contact = contact,
                                onClick = {
                                    // 点击联系人，开始私聊
                                    scope.launch {
                                        val response = appState.currentUser?.let { user ->
                                            ApiClient.startPrivateChat(user.id, contact.contactId)
                                        }
                                        
                                        if (response != null && response.success && response.group != null) {
                                            appState.selectGroup(response.group)
                                        } else {
                                            console.error("创建私聊失败:", response?.message)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    
    // 添加联系人对话框
    if (showAddContactDialog) {
        AddContactDialog(
            appState = appState,
            strings = strings,
            onDismiss = { showAddContactDialog = false },
            onContactAdded = {
                showAddContactDialog = false
                loadContacts()
            }
        )
    }
    
    // 请求详情对话框
    showRequestDetailDialog?.let { request ->
        RequestDetailDialog(
            request = request,
            appState = appState,
            strings = strings,
            onDismiss = { showRequestDetailDialog = null },
            onHandled = {
                showRequestDetailDialog = null
                loadContacts()
            }
        )
    }
}

@Composable
fun ContactCard(contact: Contact, onClick: () -> Unit) {
    Div({
        style {
            backgroundColor(Color(SilkColors.surfaceElevated))
            borderRadius(12.px)
            padding(16.px, 20.px)
            marginBottom(12.px)
            property("box-shadow", "0 2px 8px rgba(169, 137, 77, 0.08)")
            property("cursor", "pointer")
            property("transition", "all 0.2s ease")
            property("border", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            gap(16.px)
        }
        onClick { onClick() }
    }) {
        // 头像
        Div({
            style {
                width(48.px)
                height(48.px)
                borderRadius(50.percent)
                backgroundColor(Color(SilkColors.primaryLight))
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.Center)
                alignItems(AlignItems.Center)
                color(Color.white)
                fontSize(20.px)
                property("font-weight", "600")
            }
        }) {
            Text(contact.contactName.firstOrNull()?.toString() ?: "?")
        }
        
        // 信息
        Div({
            style {
                flex(1)
            }
        }) {
            Div({
                style {
                    fontSize(16.px)
                    property("font-weight", "600")
                    color(Color(SilkColors.textPrimary))
                    marginBottom(4.px)
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
        
        // 箭头
        Span({
            style {
                color(Color(SilkColors.primary))
                fontSize(20.px)
            }
        }) {
            Text("💬")
        }
    }
}

@Composable
fun PendingRequestCard(request: ContactRequest, strings: Strings, onClick: () -> Unit) {
    Div({
        style {
            backgroundColor(Color(SilkColors.surfaceElevated))
            borderRadius(12.px)
            padding(16.px, 20.px)
            marginBottom(12.px)
            property("box-shadow", "0 2px 8px rgba(169, 137, 77, 0.08)")
            property("cursor", "pointer")
            property("border", "2px dashed ${SilkColors.textSecondary}")
            property("opacity", "0.8")
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            gap(16.px)
        }
        onClick { onClick() }
    }) {
        // 头像（灰色表示待处理）
        Div({
            style {
                width(48.px)
                height(48.px)
                borderRadius(50.percent)
                backgroundColor(Color("#9CA3AF"))
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.Center)
                alignItems(AlignItems.Center)
                color(Color.white)
                fontSize(20.px)
                property("font-weight", "600")
            }
        }) {
            Text(request.fromUserName.firstOrNull()?.toString() ?: "?")
        }
        
        // 信息
        Div({
            style {
                flex(1)
            }
        }) {
            Div({
                style {
                    fontSize(16.px)
                    property("font-weight", "600")
                    color(Color(SilkColors.textSecondary))
                    marginBottom(4.px)
                }
            }) {
                Text(request.fromUserName)
            }
            
            Div({
                style {
                    fontSize(13.px)
                    color(Color(SilkColors.textSecondary))
                }
            }) {
                Text(strings.wantsToAddYouAsContact)
            }
        }
        
        // 提示
        Span({
            style {
                backgroundColor(Color(SilkColors.primary))
                color(Color.white)
                padding(4.px, 12.px)
                borderRadius(12.px)
                fontSize(12.px)
            }
        }) {
            Text(strings.pendingStatus)
        }
    }
}

@Composable
fun AddContactDialog(
    appState: WebAppState,
    strings: Strings,
    onDismiss: () -> Unit,
    onContactAdded: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var phoneNumber by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var successMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var foundUser by remember { mutableStateOf<User?>(null) }
    
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
                    color(Color(SilkColors.textPrimary))
                    marginBottom(20.px)
                    fontSize(18.px)
                    property("font-weight", "600")
                }
            }) {
                Text(strings.addContact)
            }
            
            // 电话号码输入
            Div({
                style {
                    marginBottom(16.px)
                }
            }) {
                Div({
                    style {
                        display(DisplayStyle.Block)
                        color(Color(SilkColors.textSecondary))
                        fontSize(14.px)
                        marginBottom(8.px)
                    }
                }) {
                    Text(strings.phoneNumberLabel)
                }
                
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        gap(8.px)
                    }
                }) {
                    Input(InputType.Tel) {
                        value(phoneNumber)
                        onInput { phoneNumber = it.value }
                        style {
                            flex(1)
                            padding(12.px, 16.px)
                            borderRadius(8.px)
                            border {
                                width(1.px)
                                style(LineStyle.Solid)
                                color(Color(SilkColors.border))
                            }
                            fontSize(14.px)
                            property("outline", "none")
                        }
                        attr("placeholder", strings.phoneNumberPlaceholder)
                    }
                    
                    Button({
                        style {
                            backgroundColor(Color(SilkColors.primary))
                            color(Color.white)
                            border { style(LineStyle.None) }
                            padding(12.px, 20.px)
                            borderRadius(8.px)
                            property("cursor", "pointer")
                            fontSize(14.px)
                        }
                        onClick {
                            if (phoneNumber.isNotBlank()) {
                                scope.launch {
                                    isLoading = true
                                    errorMessage = ""
                                    successMessage = ""
                                    foundUser = null
                                    
                                    val result = ApiClient.searchUserByPhone(phoneNumber)
                                    if (result.found && result.user != null) {
                                        foundUser = result.user
                                    } else {
                                        errorMessage = result.message.ifEmpty { "未找到用户" }
                                    }
                                    isLoading = false
                                }
                            }
                        }
                    }) {
                        Text(if (isLoading) strings.loading else strings.searchButton)
                    }
                }
            }
            
            // 搜索结果
            foundUser?.let { user ->
                Div({
                    style {
                        backgroundColor(Color(SilkColors.background))
                        borderRadius(8.px)
                        padding(16.px)
                        marginBottom(16.px)
                        property("border", "1px solid ${SilkColors.primary}")
                    }
                }) {
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            alignItems(AlignItems.Center)
                            gap(12.px)
                            marginBottom(12.px)
                        }
                    }) {
                        Div({
                            style {
                                width(40.px)
                                height(40.px)
                                borderRadius(50.percent)
                                backgroundColor(Color(SilkColors.primary))
                                display(DisplayStyle.Flex)
                                justifyContent(JustifyContent.Center)
                                alignItems(AlignItems.Center)
                                color(Color.white)
                                fontSize(18.px)
                            }
                        }) {
                            Text(user.fullName.firstOrNull()?.toString() ?: "?")
                        }
                        
                        Div {
                            Div({
                                style {
                                    fontSize(16.px)
                                    property("font-weight", "600")
                                    color(Color(SilkColors.textPrimary))
                                }
                            }) {
                                Text(user.fullName)
                            }
                            Div({
                                style {
                                    fontSize(13.px)
                                    color(Color(SilkColors.textSecondary))
                                }
                            }) {
                                Text(user.phoneNumber)
                            }
                        }
                    }
                    
                    Button({
                        style {
                            width(100.percent)
                            backgroundColor(Color(SilkColors.primary))
                            color(Color.white)
                            border { style(LineStyle.None) }
                            padding(10.px)
                            borderRadius(8.px)
                            property("cursor", "pointer")
                            fontSize(14.px)
                        }
                        onClick {
                            scope.launch {
                                isLoading = true
                                val response = appState.currentUser?.let { currentUser ->
                                    ApiClient.sendContactRequest(currentUser.id, user.phoneNumber)
                                }
                                
                                if (response != null && response.success) {
                                    successMessage = strings.contactRequestSent
                                    foundUser = null
                                } else {
                                    errorMessage = response?.message ?: "发送失败"
                                }
                                isLoading = false
                            }
                        }
                    }) {
                        Text(if (isLoading) strings.sendingRequest else strings.sendAddRequestButton)
                    }
                }
            }
            
            // 错误消息
            if (errorMessage.isNotEmpty()) {
                Div({
                    style {
                        color(Color("#EF4444"))
                        fontSize(14.px)
                        marginBottom(16.px)
                        textAlign("center")
                    }
                }) {
                    Text(errorMessage)
                }
            }
            
            // 成功消息
            if (successMessage.isNotEmpty()) {
                Div({
                    style {
                        color(Color("#10B981"))
                        fontSize(14.px)
                        marginBottom(16.px)
                        textAlign("center")
                    }
                }) {
                    Text(successMessage)
                }
            }
            
            // 关闭按钮
            Div({
                style {
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.FlexEnd)
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
                    onClick { onDismiss() }
                }) {
                    Text(strings.closeButton)
                }
            }
        }
    }
}

@Composable
fun RequestDetailDialog(
    request: ContactRequest,
    appState: WebAppState,
    strings: Strings,
    onDismiss: () -> Unit,
    onHandled: () -> Unit
) {
    val scope = rememberCoroutineScope()
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
                width(380.px)
                maxWidth(90.vw)
                property("box-shadow", "0 8px 32px rgba(169, 137, 77, 0.2)")
                property("border", "1px solid ${SilkColors.border}")
            }
            onClick { it.stopPropagation() }
        }) {
            H3({
                style {
                    color(Color(SilkColors.textPrimary))
                    marginBottom(20.px)
                    fontSize(18.px)
                    property("font-weight", "600")
                    textAlign("center")
                }
            }) {
                Text(strings.contactRequestTitle)
            }
            
            // 用户信息
            Div({
                style {
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    alignItems(AlignItems.Center)
                    marginBottom(24.px)
                }
            }) {
                Div({
                    style {
                        width(64.px)
                        height(64.px)
                        borderRadius(50.percent)
                        backgroundColor(Color(SilkColors.primary))
                        display(DisplayStyle.Flex)
                        justifyContent(JustifyContent.Center)
                        alignItems(AlignItems.Center)
                        color(Color.white)
                        fontSize(28.px)
                        property("font-weight", "600")
                        marginBottom(12.px)
                    }
                }) {
                    Text(request.fromUserName.firstOrNull()?.toString() ?: "?")
                }
                
                Div({
                    style {
                        fontSize(18.px)
                        property("font-weight", "600")
                        color(Color(SilkColors.textPrimary))
                        marginBottom(4.px)
                    }
                }) {
                    Text(request.fromUserName)
                }
                
                Div({
                    style {
                        fontSize(14.px)
                        color(Color(SilkColors.textSecondary))
                    }
                }) {
                    Text(request.fromUserPhone)
                }
            }
            
            Div({
                style {
                    textAlign("center")
                    color(Color(SilkColors.textSecondary))
                    fontSize(14.px)
                    marginBottom(24.px)
                }
            }) {
                Text(strings.wantsToAddYouAsContact)
            }
            
            // 操作按钮
            Div({
                style {
                    display(DisplayStyle.Flex)
                    gap(12.px)
                }
            }) {
                Button({
                    style {
                        flex(1)
                        backgroundColor(Color(SilkColors.background))
                        color(Color(SilkColors.textSecondary))
                        border {
                            width(1.px)
                            style(LineStyle.Solid)
                            color(Color(SilkColors.border))
                        }
                        padding(12.px)
                        borderRadius(8.px)
                        property("cursor", "pointer")
                        fontSize(14.px)
                    }
                    onClick {
                        scope.launch {
                            isLoading = true
                            val response = appState.currentUser?.let { user ->
                                ApiClient.handleContactRequest(request.id, user.id, false)
                            }
                            if (response?.success == true) {
                                onHandled()
                            }
                            isLoading = false
                        }
                    }
                }) {
                    Text(if (isLoading) strings.loading else strings.rejectButton)
                }
                
                Button({
                    style {
                        flex(1)
                        backgroundColor(Color(SilkColors.primary))
                        color(Color.white)
                        border { style(LineStyle.None) }
                        padding(12.px)
                        borderRadius(8.px)
                        property("cursor", "pointer")
                        fontSize(14.px)
                        property("font-weight", "500")
                    }
                    onClick {
                        scope.launch {
                            isLoading = true
                            val response = appState.currentUser?.let { user ->
                                ApiClient.handleContactRequest(request.id, user.id, true)
                            }
                            if (response?.success == true) {
                                onHandled()
                            }
                            isLoading = false
                        }
                    }
                }) {
                    Text(if (isLoading) strings.loading else strings.acceptButton)
                }
            }
        }
    }
}

