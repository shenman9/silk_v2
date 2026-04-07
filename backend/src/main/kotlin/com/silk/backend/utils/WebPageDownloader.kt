package com.silk.backend.utils

import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.HttpURLConnection
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import com.microsoft.playwright.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

/**
 * 网页下载和内容提取工具
 * 
 * 支持两种模式：
 * 1. Playwright 无头浏览器（支持 JavaScript 渲染，可绕过部分反爬虫）
 * 2. 简单 HTTP 请求（作为降级方案）
 */
object WebPageDownloader {
    
    private val logger = LoggerFactory.getLogger(WebPageDownloader::class.java)

    // URL 匹配正则表达式
    private val URL_PATTERN = Pattern.compile(
        """https?://[^\s<>"']+""",
        Pattern.CASE_INSENSITIVE
    )
    
    // 常见的网页文件扩展名
    private val WEB_PAGE_EXTENSIONS = setOf(
        "", "html", "htm", "php", "asp", "aspx", "jsp", "shtml"
    )
    
    // 支持的文档类型（可以提取文本）
    private val SUPPORTED_DOC_EXTENSIONS = setOf("pdf")
    
    // 非网页文件扩展名（如图片、视频等）- 不包括可处理的文档
    private val NON_WEB_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg",
        "mp4", "mp3", "avi", "mov", "wmv", "flv", "mkv",
        "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "zip", "rar", "7z", "tar", "gz",
        "exe", "msi", "dmg", "apk"
    )
    
    // Playwright 实例（延迟初始化）
    @Volatile
    private var playwright: Playwright? = null
    
    @Volatile
    private var browser: Browser? = null
    
    @Volatile
    private var playwrightInitialized = false
    
    @Volatile
    private var playwrightAvailable = true  // 标记 Playwright 是否可用
    
    private val initLock = Any()
    
    /**
     * 初始化 Playwright（首次使用时自动调用）
     */
    private fun initPlaywright(): Boolean {
        if (playwrightInitialized) return playwrightAvailable
        
        synchronized(initLock) {
            if (playwrightInitialized) return playwrightAvailable
            
            try {
                logger.debug("🎭 正在初始化 Playwright 无头浏览器...")
                playwright = Playwright.create()
                browser = playwright?.chromium()?.launch(
                    BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setArgs(listOf(
                            "--no-sandbox",
                            "--disable-setuid-sandbox",
                            "--disable-dev-shm-usage",
                            "--disable-gpu"
                        ))
                )
                playwrightAvailable = browser != null
                if (playwrightAvailable) {
                    logger.info("✅ Playwright 初始化成功")
                } else {
                    logger.warn("⚠️ Playwright 浏览器启动失败")
                }
            } catch (e: Exception) {
                logger.warn("⚠️ Playwright 初始化失败: {}，将使用简单 HTTP 模式", e.message)
                playwrightAvailable = false
            }
            playwrightInitialized = true
        }
        
        return playwrightAvailable
    }
    
    /**
     * 关闭 Playwright（应用退出时调用）
     */
    fun shutdown() {
        try {
            browser?.close()
            playwright?.close()
            logger.debug("🎭 Playwright 已关闭")
        } catch (e: Exception) {
            logger.warn("⚠️ 关闭 Playwright 出错: {}", e.message)
        }
    }
    
    /**
     * 从文本中提取所有URL
     */
    fun extractUrls(text: String): List<String> {
        val urls = mutableListOf<String>()
        val matcher = URL_PATTERN.matcher(text)
        while (matcher.find()) {
            val url = matcher.group()
            // 清理URL末尾的标点符号
            val cleanUrl = url.trimEnd('.', ',', '!', '?', ')', ']', '}', ':', ';')
            if (isWebPageUrl(cleanUrl)) {
                urls.add(cleanUrl)
            }
        }
        return urls.distinct()
    }
    
    /**
     * 判断URL是否可以处理（网页或支持的文档）
     */
    private fun isWebPageUrl(url: String): Boolean {
        return try {
            val urlObj = URL(url)
            val path = urlObj.path.lowercase()
            val extension = path.substringAfterLast('.', "").lowercase()
            
            // 支持的文档类型
            if (extension in SUPPORTED_DOC_EXTENSIONS) {
                return true
            }
            
            // 如果是已知的非网页扩展名，返回false
            if (extension in NON_WEB_EXTENSIONS) {
                return false
            }
            
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 判断URL是否是PDF文件
     */
    private fun isPdfUrl(url: String): Boolean {
        return try {
            val urlObj = URL(url)
            val path = urlObj.path.lowercase()
            path.endsWith(".pdf") || path.contains("/pdf/")
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 下载网页或PDF并提取文本内容
     * 
     * 策略：
     * 1. PDF 文件直接使用 HTTP 下载
     * 2. 网页优先使用 Playwright（支持 JS 渲染）
     * 3. Playwright 失败时降级到简单 HTTP
     * 
     * @param url 网页或PDF URL
     * @return WebPageContent 或 null（如果下载失败）
     */
    fun downloadAndExtract(url: String): WebPageContent? {
        logger.debug("🌐 开始下载: {}", url)
        
        // PDF 文件直接使用 HTTP 下载
        if (isPdfUrl(url)) {
            return downloadPdfWithHttp(url)
        }
        
        // 网页：优先使用 Playwright
        if (initPlaywright() && playwrightAvailable) {
            val result = downloadWithPlaywright(url)
            if (result != null && result.textContent.length > 100) {
                return result
            }
            logger.warn("⚠️ Playwright 抓取内容不足，尝试简单 HTTP...")
        }
        
        // 降级：使用简单 HTTP
        return downloadWithSimpleHttp(url)
    }
    
    /**
     * 使用 Playwright 无头浏览器下载网页
     * 支持 JavaScript 渲染，可处理 SPA 和部分反爬虫
     */
    private fun downloadWithPlaywright(url: String): WebPageContent? {
        return try {
            logger.debug("🎭 使用 Playwright 下载: {}", url)
            
            val context = browser?.newContext(
                Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                    .setViewportSize(1920, 1080)
                    .setLocale("zh-CN")
                    .setExtraHTTPHeaders(mapOf(
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
                        "Accept-Encoding" to "gzip, deflate, br",
                        "Connection" to "keep-alive",
                        "Upgrade-Insecure-Requests" to "1",
                        "Sec-Fetch-Dest" to "document",
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-Site" to "none",
                        "Sec-Fetch-User" to "?1"
                    ))
            ) ?: return null
            
            val page = context.newPage()
            
            try {
                // 设置超时
                page.setDefaultTimeout(45000.0)
                
                // 导航到页面 - 使用 DOMCONTENTLOADED
                val response = page.navigate(url, Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(60000.0)
                )
                
                // 处理 Cloudflare 等 JS 挑战
                // 即使初始响应是 403，也继续等待，因为 JS 可能会自动重定向
                val statusCode = response?.status() ?: 0
                logger.debug("📊 初始响应状态: {}", statusCode)
                
                if (statusCode == 403 || statusCode == 503) {
                    logger.debug("🔒 检测到安全挑战（可能是 Cloudflare），等待 JS 处理...")
                    // 等待 Cloudflare JS 挑战完成
                    try {
                        page.waitForTimeout(8000.0)  // 等待8秒让挑战完成
                        // 检查页面是否已经变化
                        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE, 
                            Page.WaitForLoadStateOptions().setTimeout(15000.0))
                    } catch (e: Exception) {
                        logger.debug("⏱️ 等待超时，继续尝试获取内容...")
                    }
                } else if (response == null || (!response.ok() && statusCode != 0)) {
                    logger.warn("⚠️ 页面加载失败: {}", statusCode)
                    return null
                }
                
                // 等待页面内容加载完成
                try {
                    page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED)
                } catch (e: Exception) {
                    // 忽略
                }
                
                // 额外等待动态内容（针对 SPA）
                try {
                    page.waitForTimeout(3000.0)  // 等待3秒让 JS 完成渲染
                } catch (e: Exception) {
                    // 忽略超时
                }
                
                // 获取页面标题
                val title = page.title().ifBlank { extractTitleFromUrl(url) }
                
                // 获取页面内容
                val htmlContent = page.content()
                
                // 使用 Jsoup 解析并提取文本
                val document = Jsoup.parse(htmlContent)
                document.select("script, style, nav, header, footer, aside, noscript, iframe").remove()
                val textContent = document.body()?.text() ?: ""
                
                // 清理文本
                val cleanedText = cleanText(textContent)
                
                // 生成文件名
                val fileName = generateFileName(url, title, "html")
                
                logger.info("✅ Playwright 下载成功: {} ({} 字符)", title, cleanedText.length)
                
                WebPageContent(
                    url = url,
                    title = title,
                    textContent = cleanedText,
                    fileName = fileName,
                    htmlContent = htmlContent,
                    isPdf = false
                )
            } finally {
                page.close()
                context.close()
            }
        } catch (e: Exception) {
            logger.error("❌ Playwright 下载失败: {}", e.message)
            null
        }
    }
    
    /**
     * 使用简单 HTTP 下载网页（降级方案）
     */
    private fun downloadWithSimpleHttp(url: String): WebPageContent? {
        return try {
            logger.debug("📡 使用简单 HTTP 下载: {}", url)
            
            // 配置SSL（允许所有证书，用于开发环境）
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate>? = null
            })
            
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
            
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 20000
                readTimeout = 60000
                // 模拟真实浏览器请求
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                setRequestProperty("Accept-Encoding", "gzip, deflate, br")
                setRequestProperty("Connection", "keep-alive")
                setRequestProperty("Upgrade-Insecure-Requests", "1")
                setRequestProperty("Sec-Fetch-Dest", "document")
                setRequestProperty("Sec-Fetch-Mode", "navigate")
                setRequestProperty("Sec-Fetch-Site", "none")
                setRequestProperty("Sec-Fetch-User", "?1")
                setRequestProperty("Cache-Control", "max-age=0")
                instanceFollowRedirects = true
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                logger.warn("⚠️ HTTP 下载失败，状态码: {}, URL: {}", responseCode, url)
                return null
            }
            
            // 检查Content-Type
            val contentType = connection.contentType ?: ""
            
            // 如果是 PDF，使用专门的方法
            if (contentType.contains("application/pdf") || isPdfUrl(url)) {
                return downloadPdfFromConnection(url, connection)
            }
            
            // 检查是否是 HTML
            if (!contentType.contains("text/html") && !contentType.contains("text/plain") && !contentType.contains("application/xhtml")) {
                logger.warn("⚠️ 不支持的内容类型: {}", contentType)
                return null
            }
            
            // 根据 Content-Encoding 处理压缩响应
            val contentEncoding = connection.contentEncoding?.lowercase() ?: ""
            val inputStream: InputStream = when {
                contentEncoding.contains("gzip") -> GZIPInputStream(connection.inputStream)
                contentEncoding.contains("deflate") -> InflaterInputStream(connection.inputStream)
                else -> connection.inputStream
            }
            
            // 读取HTML内容
            val charset = extractCharset(contentType) ?: "UTF-8"
            val htmlContent = inputStream.bufferedReader(charset = java.nio.charset.Charset.forName(charset)).use { it.readText() }
            inputStream.close()
            connection.disconnect()
            
            // 使用Jsoup解析HTML
            val document = Jsoup.parse(htmlContent)
            val title = document.title().ifBlank { extractTitleFromUrl(url) }
            document.select("script, style, nav, header, footer, aside, noscript, iframe").remove()
            val textContent = document.body()?.text() ?: ""
            val cleanedText = cleanText(textContent)
            val fileName = generateFileName(url, title, "html")
            
            logger.info("✅ HTTP 下载成功: {} ({} 字符)", title, cleanedText.length)
            
            WebPageContent(
                url = url,
                title = title,
                textContent = cleanedText,
                fileName = fileName,
                htmlContent = htmlContent,
                isPdf = false
            )
        } catch (e: Exception) {
            logger.error("❌ HTTP 下载失败: {}", e.message)
            null
        }
    }
    
    /**
     * 使用 HTTP 下载 PDF 文件
     */
    private fun downloadPdfWithHttp(url: String): WebPageContent? {
        return try {
            logger.debug("📕 下载 PDF: {}", url)
            
            // 配置SSL
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate>? = null
            })
            
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
            
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 120000  // PDF 可能较大
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                instanceFollowRedirects = true
            }
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                logger.warn("⚠️ PDF 下载失败，状态码: {}", responseCode)
                return null
            }
            
            downloadPdfFromConnection(url, connection)
        } catch (e: Exception) {
            logger.error("❌ PDF 下载失败: {}", e.message)
            null
        }
    }
    
    /**
     * 从 HTTP 连接下载 PDF 并提取文本
     */
    private fun downloadPdfFromConnection(url: String, connection: HttpURLConnection): WebPageContent? {
        return try {
            // 读取PDF二进制内容
            val inputStream = connection.inputStream
            val buffer = ByteArrayOutputStream()
            val data = ByteArray(8192)
            var bytesRead: Int
            var totalBytes = 0L
            
            while (inputStream.read(data, 0, data.size).also { bytesRead = it } != -1) {
                buffer.write(data, 0, bytesRead)
                totalBytes += bytesRead
            }
            buffer.flush()
            inputStream.close()
            connection.disconnect()
            
            val pdfBytes = buffer.toByteArray()
            logger.debug("📕 PDF 下载完成: {} KB", pdfBytes.size / 1024)
            
            // 使用 PDFBox 提取文本
            val document = PDDocument.load(pdfBytes)
            val stripper = PDFTextStripper()
            val textContent = stripper.getText(document)
            
            val info = document.documentInformation
            val title = info?.title?.takeIf { it.isNotBlank() } ?: extractTitleFromUrl(url)
            val pageCount = document.numberOfPages
            
            document.close()
            
            val cleanedText = cleanText(textContent)
            val fileName = generateFileName(url, title, "pdf")
            
            logger.info("✅ PDF 提取成功: {} ({} 页, {} 字符)", title, pageCount, cleanedText.length)
            
            WebPageContent(
                url = url,
                title = title,
                textContent = cleanedText,
                fileName = fileName,
                htmlContent = "",
                isPdf = true,
                pdfBytes = pdfBytes
            )
        } catch (e: Exception) {
            logger.error("❌ PDF 处理失败: {}", e.message)
            null
        }
    }
    
    /**
     * 从 Content-Type 中提取字符编码
     */
    private fun extractCharset(contentType: String): String? {
        val charsetRegex = """charset\s*=\s*([^\s;]+)""".toRegex(RegexOption.IGNORE_CASE)
        val match = charsetRegex.find(contentType)
        return match?.groupValues?.get(1)?.trim('"', '\'')
    }
    
    /**
     * 从URL提取标题
     */
    private fun extractTitleFromUrl(url: String): String {
        return try {
            val urlObj = URL(url)
            val path = urlObj.path
            if (path.isNotBlank() && path != "/") {
                path.substringAfterLast('/').substringBeforeLast('.')
            } else {
                urlObj.host
            }
        } catch (e: Exception) {
            "webpage"
        }
    }
    
    /**
     * 清理文本内容
     */
    private fun cleanText(text: String): String {
        return text
            .replace(Regex("\\s+"), " ")  // 合并多个空白字符
            .replace(Regex("\\n{3,}"), "\n\n")  // 限制连续换行
            .trim()
            .take(50000)  // 限制最大长度
    }
    
    /**
     * 生成保存的文件名
     */
    private fun generateFileName(url: String, title: String, extension: String = "html"): String {
        val timestamp = System.currentTimeMillis()
        val safeName = title
            .replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5]"), "_")
            .take(50)
        return "webpage_${safeName}_$timestamp.$extension"
    }
    
    /**
     * 保存内容到文件
     */
    fun saveToFile(content: WebPageContent, uploadDir: File): File {
        if (!uploadDir.exists()) {
            uploadDir.mkdirs()
        }
        
        val file = File(uploadDir, content.fileName)
        
        if (content.isPdf && content.pdfBytes != null) {
            // 保存原始 PDF 文件
            file.writeBytes(content.pdfBytes)
            logger.debug("💾 PDF 已保存: {}", file.absolutePath)
            
            // 同时保存一个包含提取文本的 txt 文件
            val txtFile = File(uploadDir, content.fileName.replace(".pdf", "_text.txt"))
            txtFile.writeText("""
                来源: ${content.url}
                标题: ${content.title}
                下载时间: ${System.currentTimeMillis()}
                
                ======== 提取的文本内容 ========
                
                ${content.textContent}
            """.trimIndent())
            logger.debug("💾 PDF文本已保存: {}", txtFile.absolutePath)
        } else {
            // 创建一个包含元数据的HTML文件
            val htmlWithMeta = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>${content.title}</title>
                    <meta name="source-url" content="${content.url}">
                    <meta name="download-time" content="${System.currentTimeMillis()}">
                </head>
                <body>
                    <h1>${content.title}</h1>
                    <p><em>来源: <a href="${content.url}">${content.url}</a></em></p>
                    <hr>
                    <div class="content">
                        ${content.textContent}
                    </div>
                </body>
                </html>
            """.trimIndent()
            
            file.writeText(htmlWithMeta)
            logger.debug("💾 网页已保存: {}", file.absolutePath)
        }
        
        return file
    }
}

/**
 * 网页/PDF内容数据类
 */
data class WebPageContent(
    val url: String,
    val title: String,
    val textContent: String,
    val fileName: String,
    val htmlContent: String,
    val isPdf: Boolean = false,
    val pdfBytes: ByteArray? = null
)
