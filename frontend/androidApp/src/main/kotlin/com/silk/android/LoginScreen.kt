package com.silk.android

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(appState: AppState) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    
    // 检查是否是意外到达登录页（用户没有明确退出登录）
    // 如果是，自动恢复到群组列表页面
    LaunchedEffect(Unit) {
        println("🔍 [LoginScreen] 检查是否需要恢复会话...")
        val restored = appState.checkAndRestoreSession()
        if (restored) {
            println("✅ [LoginScreen] 会话已恢复，跳转到群组列表")
        }
    }
    
    var isLogin by remember { mutableStateOf(true) }
    var loginName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
    // 升级相关状态
    var showUpgradeDialog by remember { mutableStateOf(false) }
    var downloadState by remember { mutableStateOf<ApkDownloader.DownloadState>(ApkDownloader.DownloadState.Idle) }
    
    // Silk 渐变背景
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        SilkColors.background,
                        SilkColors.secondary.copy(alpha = 0.3f),
                        SilkColors.background
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo 区域
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 32.dp)
            ) {
                Text(
                    text = "SILK",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 8.sp
                    ),
                    color = SilkColors.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "智能协作平台",
                    style = MaterialTheme.typography.bodyLarge,
                    color = SilkColors.textSecondary
                )
            }
            
            // 登录卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 8.dp,
                        shape = MaterialTheme.shapes.large,
                        ambientColor = SilkColors.primary.copy(alpha = 0.1f),
                        spotColor = SilkColors.primary.copy(alpha = 0.2f)
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = SilkColors.surfaceElevated
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 标题
                    Text(
                        text = if (isLogin) "欢迎回来" else "创建账户",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SilkColors.textPrimary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    
                    Divider(
                        color = SilkColors.divider,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    
                    // 登录名
                    OutlinedTextField(
                        value = loginName,
                        onValueChange = { loginName = it; errorMessage = "" },
                        label = { Text("登录名") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        )
                    )
                    
                    // 密码
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; errorMessage = "" },
                        label = { Text("密码") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = if (isLogin) ImeAction.Done else ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) },
                            onDone = {
                                if (isLogin) {
                                    focusManager.clearFocus()
                                }
                            }
                        )
                    )
                    
                    // 注册时的额外字段
                    if (!isLogin) {
                        OutlinedTextField(
                            value = fullName,
                            onValueChange = { fullName = it; errorMessage = "" },
                            label = { Text("姓名") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isLoading,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                            )
                        )
                        
                        OutlinedTextField(
                            value = phoneNumber,
                            onValueChange = { phoneNumber = it; errorMessage = "" },
                            label = { Text("手机号") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isLoading,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Phone,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { focusManager.clearFocus() }
                            )
                        )
                    }
                    
                    // 错误提示
                    if (errorMessage.isNotEmpty()) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // 登录/注册按钮 - Silk 金色风格
                    Button(
                        onClick = {
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
                                        println("${if (isLogin) "登录" else "注册"}成功: ${response.user.fullName}")
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
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        enabled = !isLoading && loginName.isNotBlank() && password.isNotBlank() &&
                                (isLogin || (fullName.isNotBlank() && phoneNumber.isNotBlank())),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SilkColors.primary,
                            contentColor = androidx.compose.ui.graphics.Color.White,
                            disabledContainerColor = SilkColors.primary.copy(alpha = 0.5f),
                            disabledContentColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f)
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = androidx.compose.ui.graphics.Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = if (isLoading) "处理中..." else if (isLogin) "登录" else "注册",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    
                    // 切换登录/注册
                    TextButton(
                        onClick = {
                            if (!isLoading) {
                                isLogin = !isLogin
                                errorMessage = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = SilkColors.primary
                        )
                    ) {
                        Text(
                            text = if (isLogin) "没有账号？点击注册" else "已有账号？点击登录",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            
            // 升级按钮
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(
                onClick = { showUpgradeDialog = true },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = SilkColors.success
                ),
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                Icon(
                    Icons.Default.SystemUpdate, 
                    contentDescription = null, 
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("检查更新")
            }
            
            // 底部版权信息
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Silk © 2026",
                style = MaterialTheme.typography.bodySmall,
                color = SilkColors.textLight
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
    }
}

