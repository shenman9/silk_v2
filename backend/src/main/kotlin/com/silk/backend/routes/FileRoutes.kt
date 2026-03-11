package com.silk.backend.routes

import com.silk.backend.ai.AIConfig
import com.silk.backend.broadcastSystemStatus
import com.silk.backend.search.WeaviateClient
import com.silk.backend.search.IndexDocument
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

private val logger = LoggerFactory.getLogger("FileRoutes")
private val json = Json { ignoreUnknownKeys = true }

// Weaviate 客户端（延迟初始化）
private val weaviateClient by lazy { 
    WeaviateClient(AIConfig.requireWeaviateUrl()) 
}

/**
 * APK 版本信息响应
 */
@Serializable
data class AppVersionResponse(
    val versionCode: Int,
    val versionName: String,
    val lastModified: Long,
    val fileSize: Long,
    val downloadUrl: String
)

/**
 * 文件上传/下载路由
 */
fun Route.fileRoutes() {
    
    route("/api/files") {
        
        /**
         * 上传文件到指定会话
         * POST /api/files/upload
         * 
         * Form data:
         * - sessionId: 会话 ID
         * - userId: 用户 ID
         * - file: 文件
         */
        post("/upload") {
            try {
                val multipart = call.receiveMultipart()
                
                var sessionId: String? = null
                var userId: String? = null
                var fileName: String? = null
                var fileBytes: ByteArray? = null
                var contentType: String? = null
                
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> {
                            when (part.name) {
                                "sessionId" -> sessionId = part.value
                                "userId" -> userId = part.value
                            }
                        }
                        is PartData.FileItem -> {
                            fileName = part.originalFileName
                            contentType = part.contentType?.toString()
                            fileBytes = part.streamProvider().readBytes()
                        }
                        else -> {}
                    }
                    part.dispose()
                }
                
                // 验证参数
                if (sessionId.isNullOrBlank() || userId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing sessionId or userId"))
                    return@post
                }
                
                if (fileName.isNullOrBlank() || fileBytes == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No file provided"))
                    return@post
                }
                
                // 创建会话文件夹 - 确保使用 group_ 前缀保持一致
                val normalizedSessionDir = if (sessionId!!.startsWith("group_")) sessionId else "group_$sessionId"
                val sessionDir = File("chat_history/$normalizedSessionDir/uploads")
                if (!sessionDir.exists()) {
                    sessionDir.mkdirs()
                }
                
                // 保留原始文件名，如有重名则添加(数字)后缀
                val timestamp = System.currentTimeMillis()
                val originalName = fileName!!
                val baseName = if (originalName.contains(".")) {
                    originalName.substringBeforeLast(".")
                } else {
                    originalName
                }
                val extension = if (originalName.contains(".")) {
                    "." + originalName.substringAfterLast(".")
                } else {
                    ""
                }
                
                // 检查重名并生成唯一文件名
                var safeFileName = originalName
                var targetFile = File(sessionDir, safeFileName)
                var counter = 1
                while (targetFile.exists()) {
                    safeFileName = "$baseName($counter)$extension"
                    targetFile = File(sessionDir, safeFileName)
                    counter++
                }
                targetFile.writeBytes(fileBytes!!)
                
                logger.info("📁 文件已保存: ${targetFile.absolutePath}")
                
                // 先返回上传成功响应（不阻塞用户）
                call.respond(HttpStatusCode.OK, FileUploadResponse(
                    success = true,
                    fileId = safeFileName,
                    fileName = fileName!!,
                    filePath = targetFile.absolutePath,
                    downloadUrl = "/api/files/download/$sessionId/$safeFileName",
                    timestamp = timestamp,
                    indexed = false, // 先返回 false，异步索引完成后通过 WebSocket 通知
                    message = "文件已上传，正在索引..."
                ))
                
                // 异步索引到搜索系统（不阻塞用户对话）
                val finalSessionId = sessionId!!
                val finalUserId = userId!!
                val finalFileName = fileName!!
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // 发送开始索引状态
                        broadcastSystemStatus(finalSessionId, "🔄 正在索引文件: $finalFileName ...")
                        
                        val indexed = indexFileToWeaviate(
                            file = targetFile,
                            sessionId = finalSessionId,
                            userId = finalUserId,
                            originalFileName = finalFileName,
                            contentType = contentType
                        )
                        
                        // 发送索引完成状态
                        if (indexed) {
                            broadcastSystemStatus(finalSessionId, "✅ 文件索引完成: $finalFileName")
                        } else {
                            broadcastSystemStatus(finalSessionId, "⚠️ 文件索引失败: $finalFileName")
                        }
                    } catch (e: Exception) {
                        logger.error("❌ 异步索引失败: ${e.message}", e)
                        broadcastSystemStatus(finalSessionId, "❌ 文件索引异常: $finalFileName - ${e.message}")
                    }
                }
                
            } catch (e: Exception) {
                logger.error("❌ 文件上传失败: ${e.message}", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf(
                    "error" to "Upload failed: ${e.message}"
                ))
            }
        }
        
        /**
         * 下载文件
         * GET /api/files/download/{sessionId}/{fileId}
         */
        get("/download/{sessionId}/{fileId}") {
            val sessionId = call.parameters["sessionId"]
            val fileId = call.parameters["fileId"]
            
            if (sessionId.isNullOrBlank() || fileId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing parameters"))
                return@get
            }
            
            // 处理 sessionId - 确保使用正确的目录格式
            val downloadSessionId = if (sessionId.startsWith("group_")) sessionId else "group_$sessionId"
            val file = File("chat_history/$downloadSessionId/uploads/$fileId")
            
            if (!file.exists()) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "File not found"))
                return@get
            }
            
            // 确定 Content-Type
            val contentType = Files.probeContentType(file.toPath()) 
                ?: ContentType.Application.OctetStream.toString()
            
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName, 
                    fileId
                ).toString()
            )
            
            call.respondFile(file)
        }
        
        /**
         * 获取最新 APK 版本信息
         * GET /api/files/app-version
         * 
         * 版本号基于 APK 文件的修改时间自动生成：
         * - versionCode: (year-2020)*100000000 + month*1000000 + day*10000 + hour*100 + minute
         * - versionName: yyyy.MMdd.HHmm
         */
        get("/app-version") {
            // APK 文件路径 - 按优先级查找
            val possiblePaths = listOf(
                "static/files/androidApp-debug.apk",           // silk.sh 复制的位置
                "static/downloads/Silk-Android.apk",           // 已部署的稳定版本
                "../frontend/androidApp/build/outputs/apk/debug/androidApp-debug.apk",  // 构建目录
                "frontend/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
            )
            
            val apkFile = possiblePaths
                .map { File(it) }
                .firstOrNull { it.exists() && it.length() > 0 }
            
            val lastModified = if (apkFile != null && apkFile.exists()) apkFile.lastModified() else System.currentTimeMillis()
            val fileSize = if (apkFile != null && apkFile.exists()) apkFile.length() else 0L
            
            // ✅ 基于 APK 文件修改时间生成版本号
            val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai")).apply {
                timeInMillis = lastModified
            }
            
            val year = calendar.get(java.util.Calendar.YEAR) - 2020
            val month = calendar.get(java.util.Calendar.MONTH) + 1
            val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
            val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
            val minute = calendar.get(java.util.Calendar.MINUTE)
            
            val versionCode = year * 100000000 + month * 1000000 + day * 10000 + hour * 100 + minute
            val versionName = String.format("%04d.%02d%02d.%02d%02d", 
                calendar.get(java.util.Calendar.YEAR), month, day, hour, minute)
            
            logger.info("📱 APK 版本: $versionName (code=$versionCode) - 文件时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(lastModified))}")
            
            call.respond(AppVersionResponse(
                versionCode = versionCode,
                versionName = versionName,
                lastModified = lastModified,
                fileSize = fileSize,
                downloadUrl = "/api/files/download-apk"
            ))
        }
        
        /**
         * 下载最新 Android APK
         * GET /api/files/download-apk
         * 优先从 static/downloads 目录读取稳定版本
         */
        get("/download-apk") {
            // APK 文件路径 - 优先使用已部署的稳定版本
            val possiblePaths = listOf(
                "static/downloads/Silk-Android.apk",  // 已部署的稳定版本（优先）
                "/root/Silk/backend/static/downloads/Silk-Android.apk",  // 绝对路径
                "../frontend/androidApp/build/outputs/apk/debug/androidApp-debug.apk",  // 构建目录（备用）
                "frontend/androidApp/build/outputs/apk/debug/androidApp-debug.apk",
                "/root/Silk/frontend/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
            )
            
            val apkFile = possiblePaths
                .map { File(it) }
                .firstOrNull { it.exists() && it.length() > 0 }  // 确保文件存在且不为空
            
            if (apkFile == null || !apkFile.exists()) {
                logger.warn("APK 文件不存在，已检查路径: $possiblePaths")
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "APK 文件不存在，请先构建 Android 应用"))
                return@get
            }
            
            // 检查文件是否正在被写入（最后修改时间在 5 秒内）
            val lastModified = apkFile.lastModified()
            val now = System.currentTimeMillis()
            if (now - lastModified < 5000) {
                logger.warn("⚠️ APK 文件可能正在更新中，请稍后再试")
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "文件正在更新中，请稍后再试"))
                return@get
            }
            
            logger.info("📱 提供 APK 下载: ${apkFile.name} (${apkFile.length()} bytes)")
            
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName, 
                    "Silk-Android.apk"
                ).toString()
            )
            
            call.respondFile(apkFile)
        }
        
        /**
         * 获取会话文件列表
         * GET /api/files/list/{sessionId}
         */
        get("/list/{sessionId}") {
            val sessionId = call.parameters["sessionId"]
            
            if (sessionId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing sessionId"))
                return@get
            }
            
            // 处理 sessionId - 确保使用正确的目录格式
            val dirSessionId = if (sessionId.startsWith("group_")) sessionId else "group_$sessionId"
            val uploadsDir = File("chat_history/$dirSessionId/uploads")
            
            // 读取已处理的 URL 清单
            val processedUrlsFile = File(uploadsDir, "processed_urls.txt")
            val processedUrls = if (processedUrlsFile.exists()) {
                processedUrlsFile.readLines()
                    .filter { it.isNotBlank() }
                    .map { it.trim() }
            } else {
                emptyList()
            }
            
            if (!uploadsDir.exists()) {
                call.respond(HttpStatusCode.OK, FileListResponse(
                    sessionId = sessionId,
                    files = emptyList(),
                    totalCount = 0,
                    processedUrls = processedUrls
                ))
                return@get
            }
            
            val files = uploadsDir.listFiles()
                ?.filter { it.name != "processed_urls.txt" }  // 排除 URL 清单文件
                ?.map { file ->
                    FileInfo(
                        fileId = file.name,
                        fileName = file.name,
                        size = file.length(),
                        contentType = Files.probeContentType(file.toPath()) ?: "application/octet-stream",
                        uploadTime = file.lastModified(),
                        downloadUrl = "/api/files/download/$sessionId/${file.name}"
                    )
                }?.sortedByDescending { it.uploadTime } ?: emptyList()
            
            call.respond(HttpStatusCode.OK, FileListResponse(
                sessionId = sessionId,
                files = files,
                totalCount = files.size,
                processedUrls = processedUrls
            ))
        }
        
        /**
         * 删除文件
         * DELETE /api/files/{sessionId}/{fileId}
         */
        delete("/{sessionId}/{fileId}") {
            val sessionId = call.parameters["sessionId"]
            val fileId = call.parameters["fileId"]
            
            if (sessionId.isNullOrBlank() || fileId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing parameters"))
                return@delete
            }
            
            val file = File("chat_history/$sessionId/uploads/$fileId")
            
            if (!file.exists()) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "File not found"))
                return@delete
            }
            
            val deleted = file.delete()
            
            if (deleted) {
                // 从搜索索引中删除
                try {
                    // TODO: 调用 weaviateClient.deleteDocument()
                } catch (e: Exception) {
                    logger.warn("从索引删除文件失败: ${e.message}")
                }
                
                call.respond(HttpStatusCode.OK, mapOf(
                    "success" to true,
                    "message" to "File deleted"
                ))
            } else {
                call.respond(HttpStatusCode.InternalServerError, mapOf(
                    "error" to "Failed to delete file"
                ))
            }
        }
    }
}

/**
 * 索引文件到 Weaviate
 */
private suspend fun indexFileToWeaviate(
    file: File,
    sessionId: String,
    userId: String,
    originalFileName: String,
    contentType: String?
): Boolean = withContext(Dispatchers.IO) {
    try {
        logger.info("🔍 开始索引文件到 Weaviate: $originalFileName (type: $contentType)")
        
        // 检查 Weaviate 是否可用
        val isReady = weaviateClient.isReady()
        logger.info("🔍 Weaviate 状态: ${if (isReady) "✅ 可用" else "❌ 不可用"}")
        
        if (!isReady) {
            logger.warn("⚠️ Weaviate 不可用，跳过索引")
            return@withContext false
        }
        
        // 读取文件内容（对于文本文件）
        val content = when {
            // 文本文件（按扩展名判断）
            originalFileName.endsWith(".txt", ignoreCase = true) ||
            originalFileName.endsWith(".md", ignoreCase = true) ||
            originalFileName.endsWith(".markdown", ignoreCase = true) ||
            originalFileName.endsWith(".json", ignoreCase = true) ||
            originalFileName.endsWith(".xml", ignoreCase = true) ||
            originalFileName.endsWith(".html", ignoreCase = true) ||
            originalFileName.endsWith(".htm", ignoreCase = true) ||
            originalFileName.endsWith(".css", ignoreCase = true) ||
            originalFileName.endsWith(".js", ignoreCase = true) ||
            originalFileName.endsWith(".kt", ignoreCase = true) ||
            originalFileName.endsWith(".java", ignoreCase = true) ||
            originalFileName.endsWith(".py", ignoreCase = true) ||
            originalFileName.endsWith(".yaml", ignoreCase = true) ||
            originalFileName.endsWith(".yml", ignoreCase = true) ||
            contentType?.startsWith("text/") == true -> {
                logger.info("📝 读取文本文件: $originalFileName")
                file.readText()
            }
            // PDF 文件
            contentType == "application/pdf" || originalFileName.endsWith(".pdf", ignoreCase = true) -> {
                logger.info("📄 提取 PDF 文本...")
                extractTextFromPdf(file) ?: "[PDF 文件: $originalFileName, 无法提取文本]"
            }
            else -> {
                logger.info("📦 二进制文件，仅索引元数据: $originalFileName (contentType: $contentType)")
                "[二进制文件: $originalFileName, 大小: ${file.length()} bytes]"
            }
        }
        
        logger.info("📊 内容长度: ${content.length} 字符")
        
        // 清理无效字符（移除 \x00 等控制字符，保留换行和制表符）
        val cleanedContent = content
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")  // 移除控制字符
            .replace("\uFFFD", "")  // 移除 Unicode 替换字符
            .replace("\uFEFF", "")  // 移除 BOM
        
        logger.info("📊 清理后内容长度: ${cleanedContent.length} 字符")
        
        // 获取会话参与者（简化：只用当前用户）
        val participants = listOf(userId)
        
        // 确保 sessionId 有 group_ 前缀（与聊天消息一致）
        val normalizedSessionId = if (sessionId.startsWith("group_")) sessionId else "group_$sessionId"
        
        // 对长文本进行分块（特别是PDF文件）
        val chunks = if (cleanedContent.length > 10000) {
            logger.info("📦 内容较长 (${cleanedContent.length} 字符)，进行分块处理...")
            chunkText(cleanedContent, chunkSize = 10000, overlap = 500)
        } else {
            listOf(ChunkInfo(cleanedContent, 0, 1))
        }
        
        logger.info("📦 共生成 ${chunks.size} 个块")
        
        // 提取文件名关键词（用于所有块）
        val filenameKeywords = extractKeywordsFromFilename(originalFileName)
        
        // 索引每个块到 Weaviate
        var successCount = 0
        for (chunk in chunks) {
            // 为每个块生成关键词和摘要
            val chunkKeywords = extractKeywordsFromContent(chunk.content)
            val allKeywords = (filenameKeywords + chunkKeywords).distinct().take(20) // 限制关键词数量
            val summary = generateChunkSummary(
                chunk = chunk,
                fileName = originalFileName,
                fileType = file.extension.uppercase(),
                keywords = allKeywords
            )
            
            val indexed = weaviateClient.indexDocument(
                document = IndexDocument(
                    content = chunk.content,
                    title = originalFileName,
                    summary = summary,
                    sourceType = "FILE",
                    fileType = file.extension.uppercase(),
                    sessionId = normalizedSessionId,
                    authorId = userId,  // 记录上传者，但不用于权限过滤
                    filePath = file.absolutePath,
                    timestamp = Instant.now().toString(),
                    tags = allKeywords, // 将关键词也添加到 tags（虽然不搜索，但可用于过滤）
                    chunkIndex = chunk.chunkIndex,
                    totalChunks = chunk.totalChunks
                ),
                participants = participants
            )
            
            if (indexed) {
                successCount++
            }
        }
        
        if (successCount == chunks.size) {
            logger.info("✅ 文件已索引到 Weaviate: $originalFileName (${chunks.size} 个块)")
        } else {
            logger.warn("⚠️ 文件索引部分失败: $originalFileName (成功: $successCount/${chunks.size})")
        }
        
        successCount > 0
    } catch (e: Exception) {
        logger.error("❌ 索引文件失败: ${e.message}", e)
        false
    }
}

/**
 * 从 PDF 提取文本（使用 Apache PDFBox）
 * 确保提取所有页面
 */
private fun extractTextFromPdf(file: File): String? {
    return try {
        logger.info("📄 正在提取 PDF 文本: ${file.name}")
        val document = org.apache.pdfbox.pdmodel.PDDocument.load(file)
        val stripper = org.apache.pdfbox.text.PDFTextStripper()
        
        // 明确设置提取所有页面（从第1页到最后1页）
        stripper.startPage = 1
        stripper.endPage = document.numberOfPages
        
        val text = stripper.getText(document)
        val pageCount = document.numberOfPages
        document.close()
        logger.info("✅ PDF 文本提取成功: ${text.length} 字符, 共 $pageCount 页")
        text // 不再限制长度，由分块处理
    } catch (e: Exception) {
        logger.error("❌ PDF 文本提取失败: ${e.message}", e)
        null
    }
}

/**
 * 将长文本分块（用于大文档索引）
 * @param text 要分块的文本
 * @param chunkSize 每块的最大字符数
 * @param overlap 块之间的重叠字符数
 * @return 分块列表，每个块包含 content, chunkIndex, totalChunks
 */
private fun chunkText(text: String, chunkSize: Int = 10000, overlap: Int = 500): List<ChunkInfo> {
    if (text.length <= chunkSize) {
        return listOf(ChunkInfo(text, 0, 1))
    }
    
    val chunks = mutableListOf<ChunkInfo>()
    var start = 0
    var chunkIndex = 0
    
    while (start < text.length) {
        var end = (start + chunkSize).coerceAtMost(text.length)
        
        // 尝试在句子边界分割（避免在单词中间分割）
        if (end < text.length) {
            // 查找最近的句号、换行符等分隔符
            val searchStart = (end - chunkSize / 2).coerceAtLeast(start)
            val separators = listOf("\n\n", "\n", "。", ". ", "! ", "? ")
            for (sep in separators) {
                // 在 searchStart 到 end 范围内查找分隔符
                val sepPos = text.substring(searchStart, end).lastIndexOf(sep)
                if (sepPos >= 0) {
                    end = searchStart + sepPos + sep.length
                    break
                }
            }
        }
        
        val chunkText = text.substring(start, end).trim()
        if (chunkText.isNotEmpty()) {
            chunks.add(ChunkInfo(chunkText, chunkIndex, -1)) // totalChunks 稍后更新
            chunkIndex++
        }
        
        start = (end - overlap).coerceAtLeast(start + 1) // 确保前进
    }
    
    // 更新所有块的 totalChunks
    val totalChunks = chunks.size
    return chunks.map { it.copy(totalChunks = totalChunks) }
}

/**
 * 文本块信息
 */
private data class ChunkInfo(
    val content: String,
    val chunkIndex: Int,
    val totalChunks: Int
)

/**
 * 从文件名提取关键词
 * 例如: "User_Manual_2024.pdf" -> ["User", "Manual", "2024"]
 */
private fun extractKeywordsFromFilename(fileName: String): List<String> {
    val nameWithoutExt = fileName.substringBeforeLast(".")
    
    // 分割方式：下划线、连字符、空格、驼峰命名
    val keywords = mutableListOf<String>()
    
    // 按常见分隔符分割
    val parts = nameWithoutExt.split(Regex("[_\\-\\s.]+"))
    keywords.addAll(parts.filter { it.length > 1 && it.isNotBlank() })
    
    // 处理驼峰命名 (例如: "UserManual" -> ["User", "Manual"])
    val camelCaseParts = nameWithoutExt.split(Regex("(?<!^)(?=[A-Z])"))
    keywords.addAll(camelCaseParts.filter { it.length > 2 && !keywords.any { existing -> existing.equals(it, ignoreCase = true) } })
    
    // 过滤掉太短或纯数字的单词（除非是年份）
    return keywords
        .map { it.trim() }
        .filter { 
            it.length >= 2 && (
                it.any { char -> char.isLetter() } || // 包含字母
                (it.length == 4 && it.all { char -> char.isDigit() }) // 或4位数字（可能是年份）
            )
        }
        .distinctBy { it.lowercase() }
        .take(10) // 限制数量
}

/**
 * 从内容中提取关键词
 * 提取：首句、重复出现的词、重要术语
 */
private fun extractKeywordsFromContent(content: String): List<String> {
    val keywords = mutableListOf<String>()
    
    // 提取第一句（通常包含重要信息）
    val firstSentence = content.split(Regex("[.!?。！？\n]")).firstOrNull()?.trim()
    if (firstSentence != null && firstSentence.length > 10 && firstSentence.length < 200) {
        // 从首句中提取重要单词（长度>=3的单词）
        val words = firstSentence.split(Regex("[\\s\\p{Punct}]+"))
            .filter { it.length >= 3 && it.any { char -> char.isLetter() } }
            .map { it.lowercase() }
            .take(5)
        keywords.addAll(words)
    }
    
    // 提取重复出现的词（可能是重要术语）
    val words = content.split(Regex("[\\s\\p{Punct}]+"))
        .filter { it.length >= 4 && it.any { char -> char.isLetter() } }
        .map { it.lowercase() }
        .filter { !it.matches(Regex("^(the|and|for|are|but|not|you|all|can|her|was|one|our|out|day|get|has|him|his|how|man|new|now|old|see|two|way|who|boy|did|its|let|put|say|she|too|use)$")) } // 过滤常见停用词
    
    val wordFreq = words.groupingBy { it }.eachCount()
    val frequentWords = wordFreq.filter { it.value >= 2 } // 出现至少2次
        .entries
        .sortedByDescending { it.value }
        .take(5)
        .map { it.key }
    
    keywords.addAll(frequentWords)
    
    return keywords.distinct().take(10)
}

/**
 * 为块生成摘要（包含关键词，用于搜索）
 */
private fun generateChunkSummary(
    chunk: ChunkInfo,
    fileName: String,
    fileType: String,
    keywords: List<String>
): String {
    val summary = StringBuilder()
    
    // 添加文件信息
    summary.append("文件: $fileName")
    if (fileType.isNotEmpty()) {
        summary.append(" (类型: $fileType)")
    }
    
    // 如果是多块文档，添加块信息
    if (chunk.totalChunks > 1) {
        summary.append(" [块 ${chunk.chunkIndex + 1}/${chunk.totalChunks}]")
    }
    
    // 添加关键词
    if (keywords.isNotEmpty()) {
        summary.append(" | 关键词: ${keywords.joinToString(", ")}")
    }
    
    // 添加内容摘要（前100个字符）
    val contentPreview = chunk.content
        .replace(Regex("\\s+"), " ") // 规范化空白
        .take(100)
        .trim()
    
    if (contentPreview.isNotEmpty()) {
        summary.append(" | $contentPreview")
        if (chunk.content.length > 100) {
            summary.append("...")
        }
    }
    
    return summary.toString()
}

// ===== 数据类 =====

@Serializable
data class FileUploadResponse(
    val success: Boolean,
    val fileId: String,
    val fileName: String,
    val filePath: String,
    val downloadUrl: String,
    val timestamp: Long,
    val indexed: Boolean,
    val message: String
)

@Serializable
data class FileListResponse(
    val sessionId: String,
    val files: List<FileInfo>,
    val totalCount: Int,
    val processedUrls: List<String> = emptyList()  // 已下载的 URL 清单
)

@Serializable
data class FileInfo(
    val fileId: String,
    val fileName: String,
    val size: Long,
    val contentType: String,
    val uploadTime: Long,
    val downloadUrl: String
)

