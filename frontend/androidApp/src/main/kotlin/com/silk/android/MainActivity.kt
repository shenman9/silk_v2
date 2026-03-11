package com.silk.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope

/**
 * Silk 颜色定义 - 与 Web 前端保持一致
 * 温暖的香槟金色调，优雅的丝绸质感
 */
object SilkColors {
    // 主色调 - 温暖的香槟金
    val primary = Color(0xFFC9A86C)
    val primaryDark = Color(0xFFA8894D)
    val primaryLight = Color(0xFFE0CDA0)
    
    // 次要色调 - 奶油丝绸
    val secondary = Color(0xFFE8D5B5)
    val secondaryDark = Color(0xFFD4C4A0)
    
    // 背景色 - 温暖的奶白色
    val background = Color(0xFFFDF8F0)
    val surface = Color(0xFFFFFBF5)
    val surfaceElevated = Color(0xFFFFFFFF)
    val cardBackground = Color(0xFFFFFFFF)
    
    // 文字颜色
    val textPrimary = Color(0xFF4A4038)
    val textSecondary = Color(0xFF8A7B6A)
    val textLight = Color(0xFFB8A890)
    
    // 功能色
    val success = Color(0xFF7DAE6C)
    val warning = Color(0xFFE8B86C)
    val error = Color(0xFFD97B7B)
    val info = Color(0xFF7BA8C9)
    
    // 边框和分隔线
    val border = Color(0xFFE8E0D4)
    val divider = Color(0xFFF0E8DC)
}

/**
 * Silk 主题色彩方案
 */
private val SilkColorScheme = lightColorScheme(
    // 主色调
    primary = SilkColors.primary,
    onPrimary = Color.White,
    primaryContainer = SilkColors.primaryLight,
    onPrimaryContainer = SilkColors.textPrimary,
    
    // 次要色调
    secondary = SilkColors.secondary,
    onSecondary = SilkColors.textPrimary,
    secondaryContainer = SilkColors.secondaryDark,
    onSecondaryContainer = SilkColors.textPrimary,
    
    // 第三色调
    tertiary = SilkColors.warning,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF3E0),
    onTertiaryContainer = SilkColors.textPrimary,
    
    // 背景和表面
    background = SilkColors.background,
    onBackground = SilkColors.textPrimary,
    surface = SilkColors.surface,
    onSurface = SilkColors.textPrimary,
    surfaceVariant = SilkColors.secondary,
    onSurfaceVariant = SilkColors.textSecondary,
    
    // 轮廓
    outline = SilkColors.border,
    outlineVariant = SilkColors.divider,
    
    // 错误色
    error = SilkColors.error,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = SilkColors.textPrimary,
    
    // 其他
    inverseSurface = SilkColors.textPrimary,
    inverseOnSurface = SilkColors.background,
    inversePrimary = SilkColors.primaryLight,
    scrim = Color(0x994A4038)
)

class MainActivity : ComponentActivity() {
    // 用于检查当前场景的引用
    private var currentAppState: AppState? = null
    
    // 返回键处理回调
    private lateinit var backCallback: OnBackPressedCallback
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 注册返回键回调 - 这是最可靠的方式，优先于所有其他返回处理
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val appState = currentAppState
                println("🔙 [OnBackPressedCallback] 返回键被按下，当前场景: ${appState?.currentScene}")
                
                // 在群组列表页面时，完全阻止返回操作
                if (appState != null && appState.currentScene == Scene.GROUP_LIST && appState.currentUser != null) {
                    println("🚫 [OnBackPressedCallback] 在群组列表页面，忽略返回")
                    // 什么都不做，阻止返回
                    return
                }
                
                // 在登录页面也阻止返回（防止退出应用）
                if (appState != null && appState.currentScene == Scene.LOGIN) {
                    println("🚫 [OnBackPressedCallback] 在登录页面，忽略返回")
                    return
                }
                
                // 其他页面，执行正常返回
                if (appState != null && appState.navigateBack()) {
                    println("✅ [OnBackPressedCallback] 执行返回导航")
                } else {
                    println("🚫 [OnBackPressedCallback] 无法返回，保持当前页面")
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
        
        setContent {
            SilkTheme {
                SilkApp(this, lifecycleScope) { appState ->
                    currentAppState = appState
                    println("📱 [MainActivity] AppState 已设置，当前场景: ${appState.currentScene}")
                }
            }
        }
    }
}

@Composable
fun SilkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SilkColorScheme,
        typography = Typography(
            // 可以自定义字体，这里使用默认字体但调整字重
            displayLarge = MaterialTheme.typography.displayLarge.copy(
                fontWeight = FontWeight.Light,
                color = SilkColors.textPrimary
            ),
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.SemiBold,
                color = SilkColors.textPrimary
            ),
            titleLarge = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Medium,
                color = SilkColors.textPrimary
            ),
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(
                color = SilkColors.textPrimary
            ),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(
                color = SilkColors.textSecondary
            ),
            labelLarge = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold
            )
        ),
        content = content
    )
}

@Composable
fun SilkApp(
    activity: MainActivity,
    scope: kotlinx.coroutines.CoroutineScope,
    onAppStateReady: (AppState) -> Unit = {}
) {
    val appState = remember { AppState(activity, scope) }
    
    // 将 AppState 传递给 Activity
    LaunchedEffect(appState) {
        onAppStateReady(appState)
    }
    
    // 版本更新对话框状态
    val showUpdateDialog by appState.versionChecker.showUpdateDialog.collectAsState()
    val newVersion by appState.versionChecker.newVersionAvailable.collectAsState()
    
    // 下载进度对话框状态
    val showDownloadDialog by appState.versionChecker.showDownloadDialog.collectAsState()
    val downloadState by appState.versionChecker.downloadState.collectAsState()
    
    // ✅ 调试日志：监控更新对话框状态
    LaunchedEffect(showUpdateDialog, newVersion) {
        println("🔔 [MainActivity] 更新对话框状态: showUpdateDialog=$showUpdateDialog, newVersion=${newVersion?.versionName ?: "null"}")
    }
    
    // 在 Activity 销毁时清理资源
    DisposableEffect(Unit) {
        onDispose {
            appState.destroy()
        }
    }
    
    // 注意：返回键处理已移至 Activity 的 OnBackPressedCallback
    // 这里的 BackHandler 作为额外保险，始终启用并阻止默认行为
    BackHandler(enabled = true) {
        // 所有返回操作都由 Activity 的 OnBackPressedCallback 处理
        // 这里只是作为 Compose 层的额外保护
        println("🔙 [Compose BackHandler] 返回事件，场景: ${appState.currentScene}")
        
        when (appState.currentScene) {
            Scene.LOGIN -> {
                // 登录页不做任何操作
            }
            Scene.GROUP_LIST -> {
                // 群组列表页不做任何操作（防止退出登录）
            }
            else -> {
                // 其他页面执行返回
                appState.navigateBack()
            }
        }
    }
    
    // 版本更新对话框
    if (showUpdateDialog && newVersion != null) {
        UpdateDialog(
            versionInfo = newVersion!!,
            onDownload = { appState.versionChecker.startDownload() },
            onSkip = { appState.versionChecker.skipThisVersion() },
            onLater = { appState.versionChecker.remindLater() }
        )
    }
    
    // 下载进度对话框
    if (showDownloadDialog) {
        DownloadProgressDialog(
            downloadState = downloadState,
            onDismiss = { appState.versionChecker.dismissDownloadDialog() }
        )
    }
    
    // ✅ 调试信息：显示当前版本检查状态（可删除）
    var versionCheckStatus by remember { mutableStateOf("检查中...") }
    LaunchedEffect(Unit) {
        // 获取本地版本
        val packageInfo = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        } catch (e: Exception) { null }
        val localVersion = packageInfo?.let {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                it.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                it.versionCode
            }
        } ?: 0
        val localName = packageInfo?.versionName ?: "unknown"
        
        // 获取远程版本
        val remoteVersion = ApiClient.getAppVersion()
        versionCheckStatus = if (remoteVersion != null) {
            "本地: v$localName ($localVersion)\n远程: v${remoteVersion.versionName} (${remoteVersion.versionCode})\n${if (remoteVersion.versionCode > localVersion) "🆕 有新版本!" else "✅ 已是最新"}"
        } else {
            "本地: v$localName ($localVersion)\n远程: 获取失败"
        }
    }
    
    // 在屏幕底部显示版本信息（调试用）
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
            color = Color.Black.copy(alpha = 0.7f),
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = versionCheckStatus,
                modifier = Modifier.padding(8.dp),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
            
    // 显示验证中的加载界面
    if (appState.isValidating) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator()
                Text(
                    text = "正在验证用户信息...",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    } else {
        // 根据当前场景显示对应的界面
        when (appState.currentScene) {
            Scene.LOGIN -> LoginScreen(appState)
            Scene.GROUP_LIST -> GroupListScreen(appState)
            Scene.CONTACTS -> ContactsScreen(appState)
            Scene.CHAT_ROOM -> ChatScreen(appState)
            Scene.SETTINGS -> SettingsScreen(appState)
        }
    }
}

/**
 * 版本更新提示对话框
 */
@Composable
fun UpdateDialog(
    versionInfo: AppVersionInfo,
    onDownload: () -> Unit,
    onSkip: () -> Unit,
    onLater: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onLater,
        containerColor = SilkColors.surface,
        titleContentColor = SilkColors.textPrimary,
        textContentColor = SilkColors.textSecondary,
        title = {
            Text(
                text = "🎉 发现新版本",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "新版本 ${versionInfo.versionName} 已发布！",
                    style = MaterialTheme.typography.bodyLarge
                )
                
                val fileSizeMB = versionInfo.fileSize / 1024.0 / 1024.0
                Text(
                    text = "文件大小: %.1f MB".format(fileSizeMB),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SilkColors.textSecondary
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "建议更新以获得最佳体验",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SilkColors.primary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDownload,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SilkColors.primary,
                    contentColor = Color.White
                )
            ) {
                Text("立即更新")
            }
        },
        dismissButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onSkip) {
                    Text(
                        text = "跳过此版本",
                        color = SilkColors.textSecondary
                    )
                }
                TextButton(onClick = onLater) {
                    Text(
                        text = "稍后提醒",
                        color = SilkColors.textSecondary
                    )
                }
            }
        }
    )
}

/**
 * 下载进度对话框
 */
@Composable
fun DownloadProgressDialog(
    downloadState: ApkDownloader.DownloadState,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            // 下载中不允许关闭
            if (downloadState !is ApkDownloader.DownloadState.Downloading) {
                onDismiss()
            }
        },
        containerColor = SilkColors.surface,
        titleContentColor = SilkColors.textPrimary,
        textContentColor = SilkColors.textSecondary,
        title = {
            Text(
                text = when (downloadState) {
                    is ApkDownloader.DownloadState.Downloading -> "📥 下载更新"
                    is ApkDownloader.DownloadState.Success -> "✅ 下载完成"
                    is ApkDownloader.DownloadState.Error -> "❌ 下载失败"
                    else -> "下载更新"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (downloadState) {
                    is ApkDownloader.DownloadState.Downloading -> {
                        Text(
                            text = downloadState.message,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (downloadState.progress >= 0) {
                            LinearProgressIndicator(
                                progress = downloadState.progress / 100f,
                                modifier = Modifier.fillMaxWidth(),
                                color = SilkColors.primary
                            )
                            Text(
                                text = "${downloadState.progress}%",
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
                        Text(
                            text = "下载完成，正在启动安装...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SilkColors.success
                        )
                    }
                    is ApkDownloader.DownloadState.Error -> {
                        Text(
                            text = downloadState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SilkColors.error
                        )
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            if (downloadState is ApkDownloader.DownloadState.Error || 
                downloadState is ApkDownloader.DownloadState.Success) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SilkColors.primary,
                        contentColor = Color.White
                    )
                ) {
                    Text("关闭")
                }
            }
        },
        dismissButton = {}
    )
}

