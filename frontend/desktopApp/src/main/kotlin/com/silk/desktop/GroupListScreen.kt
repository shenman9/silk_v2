package com.silk.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silk.shared.i18n.Strings
import com.silk.shared.i18n.getStrings
import com.silk.shared.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupListScreen(appState: AppState) {
    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    
    // 删除模式相关状态
    var isDeleteMode by remember { mutableStateOf(false) }
    var selectedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isDeleting by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    
    // Language and strings
    var userLanguage by remember { mutableStateOf<Language>(Language.CHINESE) }
    
    // Load user language preference
    LaunchedEffect(appState.currentUser?.id) {
        appState.currentUser?.let { user ->
            scope.launch {
                try {
                    val response = withContext(Dispatchers.IO) {
                        ApiClient.getUserSettings(user.id)
                    }
                    val settings = response.settings
                    if (response.success && settings != null) {
                        userLanguage = settings.language
                    }
                } catch (e: Exception) {
                    println("Failed to load user settings: $e")
                }
            }
        }
    }
    
    val strings = getStrings(userLanguage)
    
    // 加载群组列表
    LaunchedEffect(Unit) {
        scope.launch {
            isLoading = true
            try {
                val response = withContext(Dispatchers.IO) {
                    appState.currentUser?.let { user ->
                        ApiClient.getUserGroups(user.id)
                    }
                }
                
                if (response != null && response.success) {
                    groups = response.groups ?: emptyList()
                    println("✅ 加载了 ${groups.size} 个群组")
                } else {
                    println("❌ 加载群组失败: ${response?.message}")
                }
            } catch (e: Exception) {
                println("❌ 加载群组异常: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("群组列表")
                        Text(
                            text = appState.currentUser?.fullName ?: "",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                actions = {
                    // 设置按钮
                    IconButton(onClick = { appState.navigateTo(Scene.SETTINGS) }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                    // 登出按钮
                    IconButton(onClick = { appState.logout() }) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "登出")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            // 创建群组按钮
            FloatingActionButton(
                onClick = { showCreateDialog = true }
            ) {
                Icon(Icons.Default.Add, contentDescription = "创建群组")
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    // 加载中
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                groups.isEmpty() -> {
                    // 空状态
                    EmptyGroupState(
                        onCreateClick = { showCreateDialog = true },
                        onJoinClick = { showJoinDialog = true }
                    )
                }
                else -> {
                    // 群组列表
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(groups) { group ->
                            GroupCard(
                                group = group,
                                isHost = group.hostId == appState.currentUser?.id,
                                onClick = { appState.selectGroup(group) }
                            )
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
}

@Composable
fun EmptyGroupState(
    onCreateClick: () -> Unit,
    onJoinClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.secondary
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "您还没有加入任何群组",
            style = MaterialTheme.typography.titleMedium
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "创建一个新群组或加入现有群组",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = onCreateClick) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("创建群组")
            }
            
            OutlinedButton(onClick = onJoinClick) {
                Icon(Icons.Default.Person, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("加入群组")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupCard(
    group: Group,
    isHost: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 群组图标
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // 群组信息
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium
                )
                
                Text(
                    text = if (isHost) "群主" else "成员",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Text(
                    text = "邀请码: ${group.invitationCode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            
            // 进入箭头
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = "进入群组",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupDialog(
    appState: AppState,
    strings: Strings,
    onDismiss: () -> Unit,
    onGroupCreated: (Group) -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    val userName = appState.currentUser?.fullName ?: ""
    val previewName = if (groupName.isNotBlank()) "${userName}'s $groupName" else ""
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.createGroupTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it; errorMessage = "" },
                    label = { Text(strings.groupName) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isLoading
                )
                
                if (previewName.isNotEmpty()) {
                    Text(
                        text = "${strings.fullName}: $previewName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
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
                        errorMessage = ""
                        
                        try {
                            val response = withContext(Dispatchers.IO) {
                                appState.currentUser?.let { user ->
                                    ApiClient.createGroup(user.id, groupName)
                                }
                            }
                            
                            if (response != null && response.success && response.group != null) {
                                println("✅ 群组创建成功: ${response.group.name}")
                                onGroupCreated(response.group)
                            } else {
                                errorMessage = response?.message ?: "创建失败"
                            }
                        } catch (e: Exception) {
                            errorMessage = "创建失败: ${e.message}"
                            println("❌ 创建群组异常: ${e.message}")
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading && groupName.isNotBlank()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinGroupDialog(
    appState: AppState,
    strings: Strings,
    onDismiss: () -> Unit,
    onGroupJoined: (Group) -> Unit
) {
    var invitationCode by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.joinGroupTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = invitationCode,
                    onValueChange = { 
                        invitationCode = it.uppercase().take(6)
                        errorMessage = ""
                    },
                    label = { Text(strings.invitationCode) },
                    placeholder = { Text(strings.invitationCodePlaceholder) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isLoading
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
                        errorMessage = ""
                        
                        try {
                            val response = withContext(Dispatchers.IO) {
                                appState.currentUser?.let { user ->
                                    ApiClient.joinGroup(user.id, invitationCode)
                                }
                            }
                            
                            if (response != null && response.success && response.group != null) {
                                println("✅ 加入群组成功: ${response.group.name}")
                                onGroupJoined(response.group)
                            } else {
                                errorMessage = response?.message ?: "加入失败"
                            }
                        } catch (e: Exception) {
                            errorMessage = "加入失败: ${e.message}"
                            println("❌ 加入群组异常: ${e.message}")
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading && invitationCode.length == 6
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
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
