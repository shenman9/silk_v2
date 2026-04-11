package com.silk.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * APK 下载和安装工具
 */
object ApkDownloader {
    
    // APK 下载地址（与 ApiClient 一致，使用应用内配置或构建时默认）
    private val apkUrl: String get() = "${BackendUrlHolder.getBaseUrl()}/api/files/download-apk"
    private const val APK_FILENAME = "Silk-Update.apk"
    
    /**
     * 下载状态
     */
    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val progress: Int, val message: String) : DownloadState()
        data class Success(val file: File) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }
    
    /**
     * 下载 APK 文件
     */
    suspend fun downloadApk(
        context: Context,
        onProgress: (DownloadState) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            onProgress(DownloadState.Downloading(0, "正在连接服务器..."))
            
            val url = URL(apkUrl)
            val connection = AndroidHttpCompat.openConnection(url)
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.connect()
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                onProgress(DownloadState.Error("服务器错误: $responseCode"))
                return@withContext null
            }
            
            val fileLength = connection.contentLength
            val inputStream = connection.inputStream
            
            // 保存到缓存目录
            val cacheDir = context.cacheDir
            val apkFile = File(cacheDir, APK_FILENAME)
            
            onProgress(DownloadState.Downloading(0, "开始下载..."))
            
            FileOutputStream(apkFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    
                    val progress = if (fileLength > 0) {
                        ((totalBytesRead * 100) / fileLength).toInt()
                    } else {
                        -1
                    }
                    
                    val sizeText = formatFileSize(totalBytesRead)
                    val totalText = if (fileLength > 0) formatFileSize(fileLength.toLong()) else "?"
                    onProgress(DownloadState.Downloading(progress, "下载中: $sizeText / $totalText"))
                }
            }
            
            inputStream.close()
            connection.disconnect()
            
            onProgress(DownloadState.Success(apkFile))
            apkFile
            
        } catch (e: Exception) {
            e.printStackTrace()
            onProgress(DownloadState.Error("下载失败: ${e.message}"))
            null
        }
    }
    
    /**
     * 安装 APK
     */
    fun installApk(context: Context, apkFile: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            
            val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7.0+ 需要使用 FileProvider
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }
            
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            context.startActivity(intent)
            
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException("启动安装失败: ${e.message}")
        }
    }
    
    /**
     * 格式化文件大小
     */
    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> String.format("%.1f MB", size / (1024.0 * 1024.0))
        }
    }
}

