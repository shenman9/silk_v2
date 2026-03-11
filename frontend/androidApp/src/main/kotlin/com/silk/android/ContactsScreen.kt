package com.silk.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silk.shared.i18n.*
import com.silk.shared.models.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(appState: AppState) {
    val scope = rememberCoroutineScope()
    val user = appState.currentUser ?: return
    
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var pendingRequests by remember { mutableStateOf<List<ContactRequest>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showAddContactDialog by remember { mutableStateOf(false) }
    var showRequestDetailDialog by remember { mutableStateOf<ContactRequest?>(null) }
    
    // Language and strings
    var userLanguage by remember { mutableStateOf<Language>(Language.CHINESE) }
    
    // Load user language preference
    LaunchedEffect(Unit) {
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
                    println("✅ 加载了${contacts.size}个联系人，${pendingRequests.size}个待处理请求")
                }
            } catch (e: Exception) {
                println("❌ 加载联系人失败: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }
    
    LaunchedEffect(Unit) {
        loadContacts()
    }
    
    Scaffold(
        topBar = {
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // 返回按钮
                            IconButton(
                                onClick = { appState.navigateTo(Scene.GROUP_LIST) },
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                            }
                            
                            Text(
                                text = strings.contactsTitle,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp
                                ),
                                color = Color.White
                            )
                        }
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 添加联系人按钮
                            FilledTonalButton(
                                onClick = { showAddContactDialog = true },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = Color.White.copy(alpha = 0.2f),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(strings.addContactButton)
                            }
                            
                            // 登出按钮
                            TextButton(
                                onClick = { appState.logout() },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = Color.White.copy(alpha = 0.9f)
                                )
                            ) {
                                Text(strings.logoutButton)
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
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
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 待处理请求标题
                        if (pendingRequests.isNotEmpty()) {
                            item {
                                Text(
                                    text = strings.pendingRequestsTitle.replace("{count}", pendingRequests.size.toString()),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = SilkColors.textPrimary,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                            
                            items(pendingRequests) { request ->
                                PendingRequestCard(
                                    request = request,
                                    strings = strings,
                                    onClick = { showRequestDetailDialog = request }
                                )
                            }
                            
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                        
                        // 联系人列表标题
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = strings.myContactsWithCount.replace("{count}", contacts.size.toString()),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = SilkColors.textPrimary
                                )
                            }
                        }
                        
                        // 联系人列表或空状态
                        if (contacts.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = SilkColors.surfaceElevated
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(40.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "👤",
                                            style = MaterialTheme.typography.displayMedium
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            text = strings.noContactsYet,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = SilkColors.textSecondary
                                        )
                                        Spacer(modifier = Modifier.height(20.dp))
                                        Button(
                                            onClick = { showAddContactDialog = true },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = SilkColors.primary
                                            )
                                        ) {
                                            Icon(Icons.Default.PersonAdd, contentDescription = null)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(strings.addFirstContact)
                                        }
                                    }
                                }
                            }
                        } else {
                            items(contacts) { contact ->
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
                                                println("创建私聊失败: ${response?.message}")
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
}

@Composable
fun ContactCard(contact: Contact, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = SilkColors.surfaceElevated
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 头像
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(SilkColors.primaryLight),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact.contactName.firstOrNull()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            // 信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.contactName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SilkColors.textPrimary
                )
                Text(
                    text = contact.contactPhone,
                    style = MaterialTheme.typography.bodySmall,
                    color = SilkColors.textSecondary
                )
            }
            
            // 聊天图标
            Icon(
                imageVector = Icons.Default.Chat,
                contentDescription = "私聊",
                tint = SilkColors.primary
            )
        }
    }
}

@Composable
fun PendingRequestCard(request: ContactRequest, strings: com.silk.shared.i18n.Strings, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = SilkColors.surfaceElevated.copy(alpha = 0.8f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = SilkColors.textSecondary.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 头像（灰色表示待处理）
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Gray),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = request.fromUserName.firstOrNull()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            // 信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = request.fromUserName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SilkColors.textSecondary
                )
                Text(
                    text = strings.wantsToAddYouAsContact,
                    style = MaterialTheme.typography.bodySmall,
                    color = SilkColors.textSecondary
                )
            }
            
            // 待处理标签
            Surface(
                color = SilkColors.primary,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = strings.pendingStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun AddContactDialog(
    appState: AppState,
    strings: com.silk.shared.i18n.Strings,
    onDismiss: () -> Unit,
    onContactAdded: () -> Unit  // 保留以便将来刷新列表
) {
    val scope = rememberCoroutineScope()
    var phoneNumber by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var successMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var foundUser by remember { mutableStateOf<User?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                strings.addContact,
                fontWeight = FontWeight.Bold,
                color = SilkColors.primary
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 电话号码输入
                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = { 
                        phoneNumber = it
                        errorMessage = ""
                        successMessage = ""
                        foundUser = null
                    },
                    label = { Text(strings.phoneNumberLabel) },
                    placeholder = { Text(strings.phoneNumberPlaceholder) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    enabled = !isLoading
                )
                
                // 搜索按钮
                Button(
                    onClick = {
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
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading && phoneNumber.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SilkColors.primary
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isLoading) strings.loading else strings.searchButton)
                }
                
                // 搜索结果
                foundUser?.let { user ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = SilkColors.primary.copy(alpha = 0.1f)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = SilkColors.primary
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // 头像
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(SilkColors.primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = user.fullName.firstOrNull()?.toString() ?: "?",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                                
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = user.fullName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = SilkColors.textPrimary
                                    )
                                    Text(
                                        text = user.phoneNumber,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SilkColors.textSecondary
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Button(
                                onClick = {
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
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isLoading,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SilkColors.primary
                                )
                            ) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (isLoading) strings.sendingRequest else strings.sendAddRequestButton)
                            }
                        }
                    }
                }
                
                // 错误消息
                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        color = SilkColors.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                // 成功消息
                if (successMessage.isNotEmpty()) {
                    Text(
                        text = successMessage,
                        color = SilkColors.success,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.closeButton)
            }
        }
    )
}

@Composable
fun RequestDetailDialog(
    request: ContactRequest,
    appState: AppState,
    strings: com.silk.shared.i18n.Strings,
    onDismiss: () -> Unit,
    onHandled: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                strings.contactRequestTitle,
                fontWeight = FontWeight.Bold,
                color = SilkColors.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 用户头像
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(SilkColors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = request.fromUserName.firstOrNull()?.toString() ?: "?",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                
                // 用户信息
                Text(
                    text = request.fromUserName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = SilkColors.textPrimary
                )
                
                Text(
                    text = request.fromUserPhone,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SilkColors.textSecondary
                )
                
                Text(
                    text = strings.wantsToAddYouAsContact,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SilkColors.textSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
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
                },
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SilkColors.primary
                )
            ) {
                Text(if (isLoading) strings.loading else strings.acceptButton)
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
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
                    },
                    enabled = !isLoading
                ) {
                    Text(if (isLoading) strings.loading else strings.rejectButton, color = SilkColors.error)
                }
                
                TextButton(onClick = onDismiss, enabled = !isLoading) {
                    Text(strings.cancelButton)
                }
            }
        }
    )
}

