package com.silk.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 版本检查器 - 负责定期检查云端版本并提示更新
 */
class VersionChecker(
    private val context: Context,
    private val currentVersionCode: Int,
    private val currentVersionName: String
) {
    companion object {
        private const val PREFS_NAME = "silk_version_prefs"
        private const val KEY_SKIPPED_VERSION = "skipped_version_code"
        private const val KEY_REMIND_LATER_TIME = "remind_later_time"
        private const val KEY_REMIND_LATER_VERSION = "remind_later_version"
        private const val KEY_DOWNLOADING_VERSION = "downloading_version_code"  // 正在下载的版本
        private const val KEY_DOWNLOADING_TIME = "downloading_time"  // 下载时间
        private const val CHECK_INTERVAL_MS = 60_000L  // 1 分钟检查一次
        private const val REMIND_LATER_DELAY_MS = 3_600_000L  // "稍后提醒"延迟 1 小时
        private const val DOWNLOAD_SILENCE_MS = 86_400_000L  // 下载后静默 24 小时（而不是永久）
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var checkJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // 新版本信息状态
    private val _newVersionAvailable = MutableStateFlow<AppVersionInfo?>(null)
    val newVersionAvailable: StateFlow<AppVersionInfo?> = _newVersionAvailable.asStateFlow()
    
    // 是否显示更新对话框
    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()
    
    // 下载状态
    private val _downloadState = MutableStateFlow<ApkDownloader.DownloadState>(ApkDownloader.DownloadState.Idle)
    val downloadState: StateFlow<ApkDownloader.DownloadState> = _downloadState.asStateFlow()
    
    // 是否显示下载进度对话框
    private val _showDownloadDialog = MutableStateFlow(false)
    val showDownloadDialog: StateFlow<Boolean> = _showDownloadDialog.asStateFlow()
    
    /**
     * 开始定期版本检查
     */
    fun startChecking() {
        // 清理已完成的下载记录（如果当前版本 >= 之前下载的版本，说明已安装）
        val downloadingVersion = prefs.getInt(KEY_DOWNLOADING_VERSION, 0)
        val downloadingTime = prefs.getLong(KEY_DOWNLOADING_TIME, 0)
        val currentTime = System.currentTimeMillis()
        
        if (downloadingVersion > 0) {
            if (currentVersionCode >= downloadingVersion) {
                // 已安装新版本，清除记录
                prefs.edit()
                    .remove(KEY_DOWNLOADING_VERSION)
                    .remove(KEY_DOWNLOADING_TIME)
                    .apply()
                println("✅ 已安装版本 $currentVersionCode，清除下载记录")
            } else if (currentTime > downloadingTime + DOWNLOAD_SILENCE_MS) {
                // 超过24小时，清除过期的下载记录
                prefs.edit()
                    .remove(KEY_DOWNLOADING_VERSION)
                    .remove(KEY_DOWNLOADING_TIME)
                    .apply()
                println("⏰ 下载记录已过期（24小时），清除记录")
            }
        }
        
        stopChecking()
        checkJob = scope.launch {
            while (isActive) {
                checkForUpdate()
                delay(CHECK_INTERVAL_MS)
            }
        }
        println("📱 版本检查器已启动，当前版本: $currentVersionName ($currentVersionCode)")
    }
    
    /**
     * 停止版本检查
     */
    fun stopChecking() {
        checkJob?.cancel()
        checkJob = null
    }
    
    /**
     * 立即检查更新
     */
    suspend fun checkForUpdate() {
        println("🔄 [VersionChecker] 开始版本检查...")
        println("🔄 [VersionChecker] 本地版本: $currentVersionName (code=$currentVersionCode)")
        
        try {
            val remoteVersion = ApiClient.getAppVersion()
            
            if (remoteVersion == null) {
                println("⚠️ [VersionChecker] 无法获取远程版本信息 (API返回null)")
                return
            }
            
            println("🔍 [VersionChecker] 远程版本: ${remoteVersion.versionName} (code=${remoteVersion.versionCode})")
            println("🔍 [VersionChecker] 比较: 本地=$currentVersionCode vs 远程=${remoteVersion.versionCode}")
            
            // 获取用户已跳过的版本
            val skippedVersion = prefs.getInt(KEY_SKIPPED_VERSION, 0)
            println("🔍 [VersionChecker] 已跳过版本: $skippedVersion")
            
            // 检查是否正在下载此版本（用户已点击下载，24小时内不再弹窗）
            val downloadingVersion = prefs.getInt(KEY_DOWNLOADING_VERSION, 0)
            val downloadingTime = prefs.getLong(KEY_DOWNLOADING_TIME, 0)
            val currentTime = System.currentTimeMillis()
            val isInDownloadSilence = downloadingVersion == remoteVersion.versionCode && 
                                      currentTime < downloadingTime + DOWNLOAD_SILENCE_MS
            if (isInDownloadSilence) {
                val remainingHours = (downloadingTime + DOWNLOAD_SILENCE_MS - currentTime) / 3_600_000
                println("📥 [VersionChecker] 版本 ${remoteVersion.versionCode} 已点击下载，静默中（剩余 $remainingHours 小时）")
                return
            }
            
            // 检查"稍后提醒"的延迟时间
            val remindLaterTime = prefs.getLong(KEY_REMIND_LATER_TIME, 0)
            val remindLaterVersion = prefs.getInt(KEY_REMIND_LATER_VERSION, 0)
            // 复用上面的 currentTime
            val isInRemindLaterPeriod = remindLaterVersion == remoteVersion.versionCode && 
                                        currentTime < remindLaterTime + REMIND_LATER_DELAY_MS
            
            if (isInRemindLaterPeriod) {
                val remainingMinutes = (remindLaterTime + REMIND_LATER_DELAY_MS - currentTime) / 60_000
                println("⏰ [VersionChecker] 在稍后提醒期间，还剩 $remainingMinutes 分钟")
                return
            }
            
            // 如果远程版本更新，且不是用户已跳过的版本
            if (remoteVersion.versionCode > currentVersionCode && 
                remoteVersion.versionCode != skippedVersion) {
                println("🆕 [VersionChecker] ✅ 发现新版本! ${remoteVersion.versionName} (${remoteVersion.versionCode})")
                println("🆕 [VersionChecker] 准备显示更新对话框...")
                _newVersionAvailable.value = remoteVersion
                _showUpdateDialog.value = true
                println("🆕 [VersionChecker] showUpdateDialog 已设置为 true")
            } else if (remoteVersion.versionCode > skippedVersion && skippedVersion != 0) {
                // 如果有更新的版本（比跳过的版本还新），重新提示
                if (remoteVersion.versionCode > currentVersionCode) {
                    println("🆕 [VersionChecker] 发现比跳过版本更新的版本: ${remoteVersion.versionName}")
                    _newVersionAvailable.value = remoteVersion
                    _showUpdateDialog.value = true
                }
            } else {
                // 没有新版本的原因
                if (remoteVersion.versionCode <= currentVersionCode) {
                    println("ℹ️ [VersionChecker] 无需更新: 本地版本($currentVersionCode) >= 远程版本(${remoteVersion.versionCode})")
                } else if (remoteVersion.versionCode == skippedVersion) {
                    println("ℹ️ [VersionChecker] 用户已跳过此版本: ${remoteVersion.versionCode}")
                }
            }
        } catch (e: Exception) {
            println("❌ [VersionChecker] 版本检查失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 用户选择跳过此版本
     */
    fun skipThisVersion() {
        _newVersionAvailable.value?.let { version ->
            prefs.edit().putInt(KEY_SKIPPED_VERSION, version.versionCode).apply()
            println("⏭️ 用户选择跳过版本: ${version.versionCode}")
        }
        dismissDialog()
    }
    
    /**
     * 用户选择稍后提醒（延迟1小时后再次提示）
     */
    fun remindLater() {
        _newVersionAvailable.value?.let { version ->
            prefs.edit()
                .putLong(KEY_REMIND_LATER_TIME, System.currentTimeMillis())
                .putInt(KEY_REMIND_LATER_VERSION, version.versionCode)
                .apply()
            println("⏰ 用户选择稍后提醒版本: ${version.versionCode}，1小时后再次提示")
        }
        dismissDialog()
    }
    
    /**
     * 关闭更新对话框
     */
    fun dismissDialog() {
        _showUpdateDialog.value = false
    }
    
    /**
     * 开始下载更新
     */
    fun startDownload() {
        // 记录正在下载的版本和时间，24小时内不再弹窗
        _newVersionAvailable.value?.let { version ->
            prefs.edit()
                .putInt(KEY_DOWNLOADING_VERSION, version.versionCode)
                .putLong(KEY_DOWNLOADING_TIME, System.currentTimeMillis())
                .apply()
            println("📥 开始下载版本: ${version.versionCode}，24小时内不再提示")
        }
        
        // 关闭更新对话框，显示下载进度对话框
        dismissDialog()
        _showDownloadDialog.value = true
        _downloadState.value = ApkDownloader.DownloadState.Downloading(0, "准备下载...")
        
        // 使用 ApkDownloader 下载
        scope.launch {
            val apkFile = ApkDownloader.downloadApk(context) { state ->
                _downloadState.value = state
            }
            
            if (apkFile != null) {
                // 下载成功，自动安装
                try {
                    ApkDownloader.installApk(context, apkFile)
                } catch (e: Exception) {
                    println("❌ 安装失败: ${e.message}")
                    _downloadState.value = ApkDownloader.DownloadState.Error("安装失败: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 关闭下载对话框
     */
    fun dismissDownloadDialog() {
        _showDownloadDialog.value = false
        _downloadState.value = ApkDownloader.DownloadState.Idle
    }
    
    /**
     * 清理资源
     */
    fun destroy() {
        stopChecking()
        scope.cancel()
    }
}

