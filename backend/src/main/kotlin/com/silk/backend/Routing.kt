package com.silk.backend

import com.silk.backend.auth.AuthService
import com.silk.backend.auth.GroupService
import com.silk.backend.database.*
import com.silk.backend.routes.fileRoutes
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.http.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.LocalDate
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

// 群组聊天服务器映射（每个群组一个ChatServer实例）
private val groupChatServers = ConcurrentHashMap<String, ChatServer>()
private val logger = LoggerFactory.getLogger("Routing")

/**
 * 获取或创建指定群组的ChatServer
 */
private fun getGroupChatServer(groupId: String): ChatServer {
    return groupChatServers.getOrPut(groupId) {
        val sessionName = "group_$groupId"
        ChatServer(sessionName).also {
            logger.info("🆕 创建新的群组聊天服务器: {}", sessionName)
        }
    }
}

/**
 * 全局状态广播 - 供其他模块（如 FileRoutes）使用
 * 广播系统状态消息到指定群组
 */
suspend fun broadcastSystemStatus(groupId: String, status: String) {
    val chatServer = groupChatServers[groupId]
    if (chatServer != null) {
        chatServer.broadcastSystemStatus(status)
    } else {
        logger.warn("⚠️ [broadcastSystemStatus] 群组 {} 不存在", groupId)
    }
}

/**
 * 广播文件消息到指定群组 - 供 FileRoutes 使用
 * 当用户上传文件后，在聊天中显示文件消息
 */
suspend fun broadcastFileMessage(
    groupId: String,
    userId: String,
    userName: String,
    fileName: String,
    fileSize: Long,
    downloadUrl: String
) {
    val chatServer = groupChatServers[groupId]
    if (chatServer != null) {
        val message = Message(
            id = System.currentTimeMillis().toString() + (0..999).random(),
            userId = userId,
            userName = userName,
            content = """{"fileName":"$fileName","fileSize":$fileSize,"downloadUrl":"$downloadUrl"}""",
            timestamp = System.currentTimeMillis(),
            type = MessageType.FILE
        )
        chatServer.broadcast(message)
        logger.debug("📎 [broadcastFileMessage] 文件消息已广播到群组 {}: {}", groupId, fileName)
    } else {
        logger.warn("⚠️ [broadcastFileMessage] 群组 {} 不存在", groupId)
    }
}

fun Application.configureRouting() {
    routing {
        get("/") {
            val html = """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Silk Chat Server</title>
                    <style>
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
                            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                            color: #333;
                            min-height: 100vh;
                            display: flex;
                            justify-content: center;
                            align-items: center;
                            padding: 20px;
                        }
                        .container {
                            background: white;
                            border-radius: 20px;
                            box-shadow: 0 20px 60px rgba(0,0,0,0.3);
                            padding: 40px;
                            max-width: 800px;
                            width: 100%;
                        }
                        h1 {
                            color: #667eea;
                            font-size: 2.5em;
                            margin-bottom: 10px;
                            display: flex;
                            align-items: center;
                            gap: 15px;
                        }
                        .status {
                            display: inline-block;
                            background: #10b981;
                            color: white;
                            padding: 5px 15px;
                            border-radius: 20px;
                            font-size: 0.4em;
                            font-weight: bold;
                        }
                        .subtitle {
                            color: #666;
                            font-size: 1.1em;
                            margin-bottom: 30px;
                        }
                        .endpoints {
                            background: #f7fafc;
                            border-radius: 10px;
                            padding: 20px;
                            margin: 20px 0;
                        }
                        .endpoints h2 {
                            color: #667eea;
                            font-size: 1.3em;
                            margin-bottom: 15px;
                        }
                        .endpoint {
                            display: flex;
                            align-items: flex-start;
                            margin-bottom: 15px;
                            padding: 10px;
                            background: white;
                            border-radius: 8px;
                            border-left: 4px solid #667eea;
                        }
                        .endpoint-method {
                            background: #667eea;
                            color: white;
                            padding: 3px 8px;
                            border-radius: 4px;
                            font-size: 0.8em;
                            font-weight: bold;
                            min-width: 60px;
                            text-align: center;
                            margin-right: 10px;
                        }
                        .endpoint-method.ws {
                            background: #f59e0b;
                        }
                        .endpoint-path {
                            font-family: 'Courier New', monospace;
                            color: #333;
                            flex: 1;
                        }
                        .endpoint-desc {
                            color: #666;
                            font-size: 0.9em;
                            margin-top: 5px;
                        }
                        .info-box {
                            background: #eff6ff;
                            border: 1px solid #3b82f6;
                            border-radius: 8px;
                            padding: 15px;
                            margin-top: 20px;
                        }
                        .info-box strong {
                            color: #1e40af;
                        }
                        a {
                            color: #667eea;
                            text-decoration: none;
                            font-weight: bold;
                        }
                        a:hover {
                            text-decoration: underline;
                        }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <h1>
                            🤍 Silk Chat Server
                            <span class="status">● RUNNING</span>
                        </h1>
                        <p class="subtitle">智能医疗问诊聊天系统 - 后端 API 服务</p>
                        
                        <div class="endpoints">
                            <h2>🔐 认证接口</h2>
                            <div class="endpoint">
                                <span class="endpoint-method">POST</span>
                                <div>
                                    <div class="endpoint-path">/auth/register</div>
                                    <div class="endpoint-desc">用户注册</div>
                                </div>
                            </div>
                            <div class="endpoint">
                                <span class="endpoint-method">POST</span>
                                <div>
                                    <div class="endpoint-path">/auth/login</div>
                                    <div class="endpoint-desc">用户登录</div>
                                </div>
                            </div>
                            <div class="endpoint">
                                <span class="endpoint-method">GET</span>
                                <div>
                                    <div class="endpoint-path">/auth/validate/{userId}</div>
                                    <div class="endpoint-desc">验证用户身份</div>
                                </div>
                            </div>
                        </div>
                        
                        <div class="endpoints">
                            <h2>👥 群组管理</h2>
                            <div class="endpoint">
                                <span class="endpoint-method">POST</span>
                                <div>
                                    <div class="endpoint-path">/groups/create</div>
                                    <div class="endpoint-desc">创建新群组</div>
                                </div>
                            </div>
                            <div class="endpoint">
                                <span class="endpoint-method">POST</span>
                                <div>
                                    <div class="endpoint-path">/groups/join</div>
                                    <div class="endpoint-desc">加入群组（使用邀请码）</div>
                                </div>
                            </div>
                            <div class="endpoint">
                                <span class="endpoint-method">GET</span>
                                <div>
                                    <div class="endpoint-path">/groups/user/{userId}</div>
                                    <div class="endpoint-desc">获取用户的所有群组</div>
                                </div>
                            </div>
                            <div class="endpoint">
                                <span class="endpoint-method">GET</span>
                                <div>
                                    <div class="endpoint-path">/groups/{groupId}</div>
                                    <div class="endpoint-desc">获取群组详情</div>
                                </div>
                            </div>
                            <div class="endpoint">
                                <span class="endpoint-method">GET</span>
                                <div>
                                    <div class="endpoint-path">/groups/{groupId}/members</div>
                                    <div class="endpoint-desc">获取群组成员列表</div>
                                </div>
                            </div>
                        </div>
                        
                        <div class="endpoints">
                            <h2>💬 聊天服务</h2>
                            <div class="endpoint">
                                <span class="endpoint-method ws">WS</span>
                                <div>
                                    <div class="endpoint-path">/chat?userId={userId}&userName={userName}&groupId={groupId}</div>
                                    <div class="endpoint-desc">WebSocket 实时聊天连接</div>
                                </div>
                            </div>
                            <div class="endpoint">
                                <span class="endpoint-method">GET</span>
                                <div>
                                    <div class="endpoint-path">/users</div>
                                    <div class="endpoint-desc">获取在线用户列表</div>
                                </div>
                            </div>
                        </div>
                        
                        <div class="endpoints">
                            <h2>📥 文件下载</h2>
                            <div class="endpoint">
                                <span class="endpoint-method">GET</span>
                                <div>
                                    <div class="endpoint-path">/download/report/{sessionName}/{fileName}</div>
                                    <div class="endpoint-desc">下载 PDF 诊断报告</div>
                                </div>
                            </div>
                        </div>
                        
                        <div class="endpoints">
                            <h2>🔧 系统监控</h2>
                            <div class="endpoint">
                                <span class="endpoint-method">GET</span>
                                <div>
                                    <div class="endpoint-path">/health</div>
                                    <div class="endpoint-desc">健康检查</div>
                                </div>
                            </div>
                            <div class="endpoint">
                                <span class="endpoint-method">GET</span>
                                <div>
                                    <div class="endpoint-path">/api/info</div>
                                    <div class="endpoint-desc">API 版本信息（JSON）</div>
                                </div>
                            </div>
                        </div>
                        
                        <div class="info-box">
                            <strong>💡 提示：</strong>
                            这是 Silk 的后端 API 服务器，不提供用户界面。
                            请使用 <strong>Desktop UI</strong> 或 <strong>Web UI</strong> 客户端连接到此服务器。
                        </div>
                        
                        <div class="info-box" style="background: #fef3c7; border-color: #f59e0b; margin-top: 15px;">
                            <strong>🌐 Web UI 部署：</strong>
                            如需在浏览器中使用，请部署 Web UI 前端应用并配置其连接到此后端地址。
                        </div>
                    </div>
                </body>
                </html>
            """.trimIndent()
            call.respondText(html, ContentType.Text.Html)
        }
        
        // API 信息端点（JSON 格式，方便程序化访问）
        get("/api/info") {
            call.respondText("""
                {
                    "service": "Silk Chat Server",
                    "version": "1.0.0",
                    "status": "running",
                    "endpoints": {
                        "auth": [
                            {"method": "POST", "path": "/auth/register", "description": "用户注册"},
                            {"method": "POST", "path": "/auth/login", "description": "用户登录"},
                            {"method": "GET", "path": "/auth/validate/{userId}", "description": "验证用户"}
                        ],
                        "groups": [
                            {"method": "POST", "path": "/groups/create", "description": "创建群组"},
                            {"method": "POST", "path": "/groups/join", "description": "加入群组"},
                            {"method": "GET", "path": "/groups/user/{userId}", "description": "获取用户群组"},
                            {"method": "GET", "path": "/groups/{groupId}", "description": "获取群组详情"},
                            {"method": "GET", "path": "/groups/{groupId}/members", "description": "获取群组成员"}
                        ],
                        "chat": [
                            {"method": "WS", "path": "/chat", "description": "WebSocket 聊天"},
                            {"method": "GET", "path": "/users", "description": "在线用户"}
                        ],
                        "files": [
                            {"method": "GET", "path": "/download/report/{sessionName}/{fileName}", "description": "下载报告"}
                        ]
                    },
                    "cors": {
                        "enabled": true,
                        "allowCredentials": true,
                        "allowedOrigins": "all"
                    },
                    "websocket": {
                        "enabled": true,
                        "endpoint": "/chat",
                        "protocol": "ws/wss"
                    }
                }
            """.trimIndent(), ContentType.Application.Json)
        }
        
        get("/health") {
            call.respondText(
                """{"status":"ok","service":"silk","timestamp":${System.currentTimeMillis()}}""",
                ContentType.Application.Json
            )
        }
        
        get("/users") {
            val users = chatServer.getOnlineUsers()
            call.respond(users)
        }
        
        // ==================== 用户设置 API ====================
        
        // 获取用户设置
        get("/users/{userId}/settings") {
            val userId = call.parameters["userId"] ?: ""
            
            if (userId.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    UserSettingsResponse(false, "用户ID不能为空")
                )
                return@get
            }
            
            try {
                val settings = UserSettingsRepository.getUserSettings(userId)
                call.respond(UserSettingsResponse(true, "获取设置成功", settings))
            } catch (e: Exception) {
                logger.error("❌ 获取用户设置失败: {}", e.message)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    UserSettingsResponse(false, "获取设置失败: ${e.message}")
                )
            }
        }
        
        // 更新用户设置
        put("/users/{userId}/settings") {
            val userId = call.parameters["userId"] ?: ""
            
            if (userId.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    UserSettingsResponse(false, "用户ID不能为空")
                )
                return@put
            }
            
            try {
                val request = call.receive<UpdateUserSettingsRequest>()
                
                // 验证userId匹配
                if (request.userId != userId) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        UserSettingsResponse(false, "用户ID不匹配")
                    )
                    return@put
                }
                
                val settings = UserSettingsRepository.updateUserSettings(
                    userId = userId,
                    language = request.language,
                    defaultAgentInstruction = request.defaultAgentInstruction
                )
                
                call.respond(UserSettingsResponse(true, "设置更新成功", settings))
            } catch (e: Exception) {
                logger.error("❌ 更新用户设置失败: {}", e.message)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    UserSettingsResponse(false, "更新设置失败: ${e.message}")
                )
            }
        }
        
        // PDF 报告下载端点
        get("/download/report/{sessionName}/{fileName...}") {
            val sessionName = call.parameters["sessionName"] ?: "default_room"
            val fileName = call.parameters.getAll("fileName")?.joinToString("/") ?: ""
            
            logger.debug("📥 PDF下载请求:")
            logger.debug("   sessionName: {}", sessionName)
            logger.debug("   fileName: {}", fileName)
            
            // 获取当前工作目录
            val workingDir = System.getProperty("user.dir")
            logger.debug("   当前工作目录: {}", workingDir)
            
            // 尝试多个可能的路径（适配服务器和本地）
            val possiblePaths = listOf(
                // 服务器路径（~/Silk 目录）
                "${System.getProperty("user.home")}/Silk/chat_history/$sessionName/reports/$fileName",
                // 相对路径（从JAR运行位置）
                "chat_history/$sessionName/reports/$fileName",
                // 当前目录下的backend子目录
                "backend/chat_history/$sessionName/reports/$fileName",
                // 上级目录
                "../chat_history/$sessionName/reports/$fileName",
                // Mac本地开发路径
                "/Users/mac/Documents/Silk/backend/chat_history/$sessionName/reports/$fileName"
            )
            
            logger.debug("   尝试的路径:")
            possiblePaths.forEachIndexed { index, path ->
                val file = File(path)
                logger.debug("     {}. {} (exists={}, isFile={})", index + 1, path, file.exists(), file.isFile)
            }
            
            val pdfFile = possiblePaths
                .map { File(it) }
                .firstOrNull { it.exists() && it.isFile }
            
            if (pdfFile != null) {
                logger.info("✅ 找到PDF文件: {}", pdfFile.absolutePath)
                logger.debug("   文件大小: {} bytes", pdfFile.length())
                
                // ✅ 验证文件是否为有效的PDF（检查文件头）
                try {
                    pdfFile.inputStream().use { input ->
                        val header = ByteArray(5)
                        val bytesRead = input.read(header)
                        val isPdf = bytesRead == 5 && 
                                   header[0] == 0x25.toByte() &&  // %
                                   header[1] == 0x50.toByte() &&  // P
                                   header[2] == 0x44.toByte() &&  // D
                                   header[3] == 0x46.toByte()     // F
                        
                        if (!isPdf) {
                            logger.warn("⚠️ 警告：文件不是有效的PDF格式")
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("⚠️ 无法验证PDF格式: {}", e.message)
                }
                
                // ✅ 设置正确的 Content-Type
                call.response.header(HttpHeaders.ContentType, "application/pdf")
                
                // ✅ 设置 Content-Disposition，使用RFC 2231标准编码中文文件名
                // 只使用 filename* 参数（RFC 2231），避免在 filename 中使用非ASCII字符
                val encodedFileName = java.net.URLEncoder.encode(fileName, "UTF-8")
                    .replace("+", "%20")  // 空格使用 %20
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename*=UTF-8''$encodedFileName"  // ✅ 只使用 filename*，不使用 filename
                )
                
                // ✅ 设置 Content-Length 以便浏览器显示下载进度
                call.response.header(HttpHeaders.ContentLength, pdfFile.length().toString())
                
                // ✅ 禁用缓存，确保每次都下载最新文件
                call.response.header(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
                call.response.header(HttpHeaders.Pragma, "no-cache")
                call.response.header(HttpHeaders.Expires, "0")
                
                call.respondFile(pdfFile)
            } else {
                val errorMsg = "PDF 文件未找到: $fileName\n\n尝试的路径:\n${possiblePaths.mapIndexed { i, p -> "${i+1}. $p" }.joinToString("\n")}"
                logger.error("❌ {}", errorMsg)
                call.respondText(errorMsg, status = HttpStatusCode.NotFound)
            }
        }
        
        // 用户认证API
        post("/auth/register") {
            try {
                val request = call.receive<RegisterRequest>()
                val response = AuthService.register(request)
                call.respond(response)
            } catch (e: Exception) {
                logger.error("❌ 注册失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, AuthResponse(false, "请求格式错误"))
            }
        }
        
        post("/auth/login") {
            try {
                val request = call.receive<LoginRequest>()
                val response = AuthService.login(request)
                call.respond(response)
            } catch (e: Exception) {
                logger.error("❌ 登录失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, AuthResponse(false, "请求格式错误"))
            }
        }
        
        // 验证用户（用于重新认证）
        get("/auth/validate/{userId}") {
            val userId = call.parameters["userId"] ?: ""
            val user = UserRepository.findUserById(userId)
            
            if (user != null) {
                call.respond(AuthResponse(true, "验证成功", user))
            } else {
                call.respond(AuthResponse(false, "用户不存在或已失效"))
            }
        }
        
        // 群组管理API
        post("/groups/create") {
            try {
                val request = call.receive<CreateGroupRequest>()
                val response = GroupService.createGroup(request)
                
                // 如果创建成功，发送欢迎消息到群组
                if (response.success && response.group != null) {
                    val welcomeMessage = buildString {
                        append("🎉 欢迎加入群组！\n\n")
                        append("群组名称：${response.group.name}\n")
                        append("邀请码：${response.group.invitationCode}\n\n")
                        append("分享邀请码，邀请朋友加入群组吧！")
                    }
                    // TODO: 发送欢迎消息到群组聊天室
                }
                
                call.respond(response)
            } catch (e: Exception) {
                logger.error("❌ 创建群组失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, GroupResponse(false, "请求格式错误"))
            }
        }
        
        post("/groups/join") {
            try {
                val request = call.receive<JoinGroupRequest>()
                val response = GroupService.joinGroup(request)
                call.respond(response)
            } catch (e: Exception) {
                logger.error("❌ 加入群组失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, GroupResponse(false, "请求格式错误"))
            }
        }
        
        get("/groups/user/{userId}") {
            val userId = call.parameters["userId"] ?: ""
            val response = GroupService.getUserGroups(userId)
            call.respond(response)
        }
        
        get("/groups/{groupId}") {
            val groupId = call.parameters["groupId"] ?: ""
            val response = GroupService.getGroupDetails(groupId)
            call.respond(response)
        }
        
        get("/groups/{groupId}/members") {
            val groupId = call.parameters["groupId"] ?: ""
            val members = GroupService.getGroupMembers(groupId)
            // 转换为前端期望的格式
            val apiMembers = members.map { member ->
                val user = UserRepository.findUserById(member.userId)
                GroupMemberApi(
                    id = member.userId,
                    fullName = user?.fullName ?: member.userName,
                    phone = user?.phoneNumber ?: ""
                )
            }
            call.respond(GroupMembersResponse(success = true, members = apiMembers))
        }
        
        // ==================== 联系人管理 API ====================
        
        // 获取联系人列表（包含待处理请求）
        get("/contacts/{userId}") {
            val userId = call.parameters["userId"] ?: ""
            val contacts = ContactRepository.getContacts(userId)
            val pendingRequests = ContactRepository.getPendingRequests(userId)
            call.respond(ContactResponse(
                success = true,
                message = "获取联系人列表成功",
                contacts = contacts,
                pendingRequests = pendingRequests
            ))
        }
        
        // 通过电话号码搜索用户
        get("/users/search") {
            val phoneNumber = call.request.queryParameters["phone"] ?: ""
            if (phoneNumber.isBlank()) {
                call.respond(UserSearchResult(false, message = "请输入电话号码"))
                return@get
            }
            
            val user = UserRepository.findUserByPhoneNumber(phoneNumber)
            if (user != null) {
                call.respond(UserSearchResult(true, user, "找到用户"))
            } else {
                call.respond(UserSearchResult(false, message = "未找到该电话号码对应的用户"))
            }
        }
        
        // 发送联系人请求（通过电话号码）
        post("/contacts/request") {
            try {
                val request = call.receive<SendContactRequestData>()
                
                // 查找目标用户
                val targetUser = UserRepository.findUserByPhoneNumber(request.toPhoneNumber)
                if (targetUser == null) {
                    call.respond(ContactResponse(false, "未找到该电话号码对应的用户"))
                    return@post
                }
                
                // 检查是否已经是联系人
                if (ContactRepository.areContacts(request.fromUserId, targetUser.id)) {
                    call.respond(ContactResponse(false, "该用户已经是您的联系人"))
                    return@post
                }
                
                // 检查是否是自己
                if (request.fromUserId == targetUser.id) {
                    call.respond(ContactResponse(false, "不能添加自己为联系人"))
                    return@post
                }
                
                // 创建联系人请求
                val contactRequest = ContactRepository.createContactRequest(request.fromUserId, targetUser.id)
                if (contactRequest != null) {
                    call.respond(ContactResponse(true, "联系人请求已发送"))
                } else {
                    call.respond(ContactResponse(false, "已有待处理的请求"))
                }
            } catch (e: Exception) {
                logger.error("❌ 发送联系人请求失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, ContactResponse(false, "请求格式错误"))
            }
        }

        // 发送联系人请求（通过用户ID，用于从聊天中添加）
        post("/contacts/request-by-id") {
            try {
                val request = call.receive<SendContactRequestByIdData>()
                
                // 检查目标用户是否存在
                val targetUser = UserRepository.findUserById(request.toUserId)
                if (targetUser == null) {
                    call.respond(ContactResponse(false, "用户不存在"))
                    return@post
                }
                
                // 检查是否已经是联系人
                if (ContactRepository.areContacts(request.fromUserId, request.toUserId)) {
                    call.respond(ContactResponse(false, "该用户已经是您的联系人"))
                    return@post
                }
                
                // 检查是否是自己
                if (request.fromUserId == request.toUserId) {
                    call.respond(ContactResponse(false, "不能添加自己为联系人"))
                    return@post
                }
                
                // 创建联系人请求
                val contactRequest = ContactRepository.createContactRequest(request.fromUserId, request.toUserId)
                if (contactRequest != null) {
                    call.respond(ContactResponse(true, "联系人请求已发送"))
                } else {
                    call.respond(ContactResponse(false, "已有待处理的请求"))
                }
            } catch (e: Exception) {
                logger.error("❌ 发送联系人请求失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, ContactResponse(false, "请求格式错误"))
            }
        }

        // 处理联系人请求（接受/拒绝）
        post("/contacts/handle-request") {
            try {
                val request = call.receive<HandleContactRequestData>()
                
                // 验证请求是否属于该用户
                val contactRequest = ContactRepository.getRequestById(request.requestId)
                if (contactRequest == null) {
                    call.respond(ContactResponse(false, "请求不存在"))
                    return@post
                }
                
                if (contactRequest.toUserId != request.userId) {
                    call.respond(ContactResponse(false, "无权处理此请求"))
                    return@post
                }
                
                val success = ContactRepository.handleContactRequest(request.requestId, request.accept)
                if (success) {
                    val message = if (request.accept) "已添加联系人" else "已拒绝请求"
                    call.respond(ContactResponse(true, message))
                } else {
                    call.respond(ContactResponse(false, "处理请求失败"))
                }
            } catch (e: Exception) {
                logger.error("❌ 处理联系人请求失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, ContactResponse(false, "请求格式错误"))
            }
        }
        
        // 开始/获取私聊会话（与联系人的双人+Silk对话）
        post("/contacts/private-chat") {
            try {
                val request = call.receive<StartPrivateChatRequest>()
                
                // 检查是否是联系人
                if (!ContactRepository.areContacts(request.userId, request.contactId)) {
                    call.respond(PrivateChatResponse(false, "该用户不是您的联系人"))
                    return@post
                }
                
                // 获取两个用户的信息
                val user = UserRepository.findUserById(request.userId)
                val contact = UserRepository.findUserById(request.contactId)
                
                if (user == null || contact == null) {
                    call.respond(PrivateChatResponse(false, "用户信息不完整"))
                    return@post
                }
                
                // 查找两个用户共同的任意群组（不限成员数，群组可扩展）
                val existingGroup = GroupRepository.findCommonGroup(request.userId, request.contactId)
                
                if (existingGroup != null) {
                    call.respond(PrivateChatResponse(true, "打开对话", existingGroup, isNew = false))
                } else {
                    // 创建新群组（用联系人名字命名，后续可扩展添加更多成员）
                    val groupName = "${contact.fullName} 的对话"
                    val newGroup = GroupRepository.createContactGroup(
                        user1Id = request.userId,
                        user2Id = request.contactId,
                        groupName = groupName
                    )
                    
                    if (newGroup != null) {
                        call.respond(PrivateChatResponse(true, "创建对话", newGroup, isNew = true))
                    } else {
                        call.respond(PrivateChatResponse(false, "创建会话失败"))
                    }
                }
            } catch (e: Exception) {
                logger.error("❌ 对话会话失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, PrivateChatResponse(false, "请求格式错误"))
            }
        }
        
        // 添加成员到群组（无需确认）
        post("/groups/{groupId}/add-member") {
            try {
                val groupId = call.parameters["groupId"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest, SimpleResponse(false, "缺少群组ID")
                )
                val request = call.receive<AddMemberRequest>()
                
                // 检查群组是否存在
                val group = GroupRepository.findGroupById(groupId)
                if (group == null) {
                    call.respond(SimpleResponse(false, "群组不存在"))
                    return@post
                }
                
                // 检查用户是否已在群组中
                if (GroupRepository.isUserInGroup(groupId, request.userId)) {
                    call.respond(SimpleResponse(false, "用户已在群组中"))
                    return@post
                }
                
                // 添加用户到群组
                val success = GroupRepository.addUserToGroup(groupId, request.userId)
                if (success) {
                    call.respond(SimpleResponse(true, "成功添加成员"))
                } else {
                    call.respond(SimpleResponse(false, "添加成员失败"))
                }
            } catch (e: Exception) {
                logger.error("❌ 添加成员失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "请求格式错误"))
            }
        }
        
        // 退出群组
        post("/groups/{groupId}/leave") {
            try {
                val groupId = call.parameters["groupId"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest, LeaveGroupResponse(false, "缺少群组ID")
                )
                val request = call.receive<LeaveGroupRequest>()
                
                // 检查群组是否存在
                val group = GroupRepository.findGroupById(groupId)
                if (group == null) {
                    call.respond(LeaveGroupResponse(false, "群组不存在"))
                    return@post
                }
                
                // 用户退出群组
                val (success, groupDeleted) = GroupRepository.leaveGroup(groupId, request.userId)
                
                if (success) {
                    val message = if (groupDeleted) {
                        "已退出群组，群组已被删除（无剩余成员）"
                    } else {
                        "已退出群组"
                    }
                    call.respond(LeaveGroupResponse(true, message, groupDeleted))
                } else {
                    call.respond(LeaveGroupResponse(false, "退出群组失败"))
                }
            } catch (e: Exception) {
                logger.error("❌ 退出群组失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, LeaveGroupResponse(false, "请求格式错误"))
            }
        }
        
        // 删除群组（仅群主可操作）
        delete("/groups/{groupId}") {
            try {
                val groupId = call.parameters["groupId"] ?: return@delete call.respond(
                    HttpStatusCode.BadRequest, SimpleResponse(false, "缺少群组ID")
                )
                val request = call.receive<DeleteGroupRequest>()
                
                // 检查群组是否存在
                val group = GroupRepository.findGroupById(groupId)
                if (group == null) {
                    call.respond(SimpleResponse(false, "群组不存在"))
                    return@delete
                }
                
                // 验证是否为群主
                if (group.hostId != request.userId) {
                    call.respond(SimpleResponse(false, "只有群主才能删除群组"))
                    return@delete
                }
                
                // 删除群组
                val (success, message, removedMembers) = GroupRepository.deleteGroupByHost(groupId, request.userId)
                
                if (success) {
                    // 清理群组的ChatServer实例
                    groupChatServers.remove(groupId)
                    logger.info("🗑️ 群组 {} 已被群主 {} 删除", groupId, request.userId)
                    call.respond(SimpleResponse(true, message))
                } else {
                    call.respond(SimpleResponse(false, message))
                }
            } catch (e: Exception) {
                logger.error("❌ 删除群组失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "请求格式错误"))
            }
        }
        
        // ==================== 未读消息 API ====================
        
        // 获取用户所有群组的未读消息数
        get("/api/unread/{userId}") {
            val userId = call.parameters["userId"] ?: ""
            
            // 获取用户的所有群组
            val groups = GroupRepository.getUserGroups(userId)
            val groupIds = groups.map { it.id }
            
            // 获取未读数
            val unreadCounts = UnreadRepository.getUnreadCounts(userId, groupIds)
            
            call.respond(UnreadCountResponse(true, unreadCounts))
        }
        
        // 标记群组已读
        post("/api/unread/mark-read") {
            try {
                val request = call.receive<MarkReadRequest>()
                UnreadRepository.markAsRead(request.userId, request.groupId)
                call.respond(SimpleResponse(true, "已标记为已读"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "请求格式错误"))
            }
        }

        // ==================== 日历/工作日 API ====================
        get("/api/calendar/workday/{date}") {
            val dateRaw = call.parameters["date"] ?: ""
            if (dateRaw.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf(
                        "success" to false,
                        "message" to "date 不能为空，格式应为 yyyy-MM-dd"
                    )
                )
                return@get
            }
            try {
                val date = LocalDate.parse(dateRaw)
                val isWorkday = com.silk.backend.todos.HolidayCalendarCn.isWorkday(date)
                call.respond(
                    mapOf(
                        "success" to true,
                        "message" to "ok",
                        "date" to dateRaw,
                        "isWorkday" to isWorkday
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf(
                        "success" to false,
                        "message" to "date 格式错误，应为 yyyy-MM-dd"
                    )
                )
            }
        }

        // ==================== 跨群待办（[Silk] 专属对话侧边能力） ====================
        get("/api/user-todos/{userId}") {
            val userId = call.parameters["userId"] ?: ""
            val items = com.silk.backend.todos.UserTodoStore.load(userId)
                .sortedByDescending { it.updatedAt }
            call.respond(UserTodosResponse(true, "ok", items))
        }

        /**
         * 触发跨群待办同步（GET，无请求体，避免部分客户端 POST JSON 序列化不兼容）。
         */
        get("/api/user-todos/{userId}/refresh") {
            val userId = call.parameters["userId"] ?: ""
            var syncDetail = "ok"
            try {
                kotlinx.coroutines.runBlocking {
                    com.silk.backend.todos.GroupTodoExtractionService.refreshTodosForUser(userId)
                }
            } catch (e: Exception) {
                println("❌ 待办同步异常 userId=${userId.take(8)}…: ${e.message}")
                e.printStackTrace()
                syncDetail = "同步异常，已返回已有列表: ${e.message?.take(120)}"
            }
            val items = com.silk.backend.todos.UserTodoStore.load(userId)
                .sortedByDescending { it.updatedAt }
            call.respond(UserTodosResponse(true, syncDetail, items))
        }

        /**
         * 后台异步刷新：快速返回任务状态，避免客户端长时间阻塞。
         */
        post("/api/user-todos/{userId}/refresh-async/start") {
            val userId = call.parameters["userId"] ?: ""
            val status = com.silk.backend.todos.UserTodoRefreshAsyncManager.start(userId)
            call.respond(status)
        }

        /**
         * 查询后台异步刷新状态。
         */
        get("/api/user-todos/{userId}/refresh-async/status") {
            val userId = call.parameters["userId"] ?: ""
            val status = com.silk.backend.todos.UserTodoRefreshAsyncManager.status(userId)
            call.respond(status)
        }

        /**
         * 查询最近一次待办抽取诊断信息（用于定位漏抽）。
         */
        get("/api/user-todos/{userId}/diagnostics") {
            val userId = call.parameters["userId"] ?: ""
            val d = com.silk.backend.todos.GroupTodoExtractionService.getDiagnostics(userId)
            call.respond(
                UserTodoExtractionDiagnosticsResponse(
                    success = true,
                    message = "ok",
                    userId = d.userId,
                    updatedAt = d.updatedAt,
                    source = d.source,
                    totalGroups = d.totalGroups,
                    transcriptChars = d.transcriptChars,
                    llmDraftCount = d.llmDraftCount,
                    heuristicDraftCount = d.heuristicDraftCount,
                    forcedRecurringCount = d.forcedRecurringCount,
                    finalDraftCount = d.finalDraftCount,
                    matchedRecurringLines = d.matchedRecurringLines,
                    note = d.note
                )
            )
        }

        put("/api/user-todos/item") {
            try {
                val request = call.receive<UpdateUserTodoRequest>()
                val ok = com.silk.backend.todos.UserTodoStore.updateItem(
                    userId = request.userId,
                    itemId = request.itemId,
                    done = request.done,
                    title = request.title,
                    actionType = request.actionType,
                    actionDetail = request.actionDetail,
                    executedAt = request.executedAt,
                    reminderId = request.reminderId,
                    clearReminderId = request.clearReminderId,
                    taskKind = request.taskKind,
                    repeatRule = request.repeatRule,
                    repeatAnchor = request.repeatAnchor,
                    activeFrom = request.activeFrom,
                    activeTo = request.activeTo,
                    templateId = request.templateId,
                    lifecycleState = request.lifecycleState,
                    closedAt = request.closedAt,
                    lastEvidenceAt = request.lastEvidenceAt,
                    explicitIntent = request.explicitIntent,
                    dateBucket = request.dateBucket,
                    reopenCount = request.reopenCount
                )
                val items = com.silk.backend.todos.UserTodoStore.load(request.userId)
                    .sortedByDescending { it.updatedAt }
                call.respond(
                    if (ok) UserTodosResponse(true, "已更新", items)
                    else UserTodosResponse(false, "待办不存在", items)
                )
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, UserTodosResponse(false, "请求格式错误"))
            }
        }

        delete("/api/user-todos/item") {
            try {
                val request = call.receive<DeleteUserTodoRequest>()
                val ok = com.silk.backend.todos.UserTodoStore.deleteItem(
                    request.userId, request.itemId
                )
                val items = com.silk.backend.todos.UserTodoStore.load(request.userId)
                    .sortedByDescending { it.updatedAt }
                call.respond(
                    if (ok) UserTodosResponse(true, "已删除", items)
                    else UserTodosResponse(false, "待办不存在", items)
                )
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, UserTodosResponse(false, "请求格式错误"))
            }
        }

        post("/api/user-todos/refresh") {
            val userId = try {
                call.receive<RefreshUserTodosRequest>().userId
            } catch (e: Exception) {
                println("❌ 刷新待办请求体解析失败: ${e.message}")
                call.respond(
                    HttpStatusCode.BadRequest,
                    UserTodosResponse(false, "请求格式错误: ${e.message}", emptyList())
                )
                return@post
            }
            var syncDetail = "已根据各群记录刷新待办"
            try {
                kotlinx.coroutines.runBlocking {
                    com.silk.backend.todos.GroupTodoExtractionService.refreshTodosForUser(userId)
                }
            } catch (e: Exception) {
                println("❌ 待办同步异常: ${e.message}")
                e.printStackTrace()
                syncDetail = "同步异常，已返回已有列表: ${e.message?.take(120)}"
            }
            val items = com.silk.backend.todos.UserTodoStore.load(userId)
                .sortedByDescending { it.updatedAt }
            call.respond(UserTodosResponse(true, syncDetail, items))
        }
        
        // 文件上传/下载 API
        fileRoutes()
        
        // ==================== 与 Silk 直接对话 API ====================
        post("/api/silk-private-chat") {
            try {
                val request = call.receive<StartSilkPrivateChatRequest>()
                
                // 获取用户信息
                val user = UserRepository.findUserById(request.userId)
                if (user == null) {
                    call.respond(PrivateChatResponse(false, "用户不存在"))
                    return@post
                }
                
                // Silk AI Agent ID
                val silkAgentId = "silk_ai_agent"
                
                // 查找用户与 Silk 的专属私聊群组
                // 使用特殊命名规则来区分 Silk 私聊：以 "[Silk] " 开头
                val existingGroup = transaction {
                    val userGroups = GroupMembers
                        .select { GroupMembers.userId eq request.userId }
                        .map { it[GroupMembers.groupId] }
                    
                    for (groupId in userGroups) {
                        val group = Groups.select { Groups.id eq groupId }.singleOrNull()
                        if (group != null && group[Groups.name].startsWith("[Silk] ")) {
                            // 检查 Silk AI 是否也在这个群组中
                            val silkInGroup = GroupMembers.select {
                                (GroupMembers.groupId eq groupId) and (GroupMembers.userId eq silkAgentId)
                            }.count() > 0
                            
                            if (silkInGroup) {
                                // 检查群组是否只有2个成员（用户 + Silk）
                                val memberCount = GroupMembers.select { GroupMembers.groupId eq groupId }.count()
                                if (memberCount == 2L) {
                                    val hostUser = UserRepository.findUserById(group[Groups.hostId])
                                    return@transaction com.silk.backend.database.Group(
                                        id = group[Groups.id],
                                        name = group[Groups.name],
                                        invitationCode = group[Groups.invitationCode],
                                        hostId = group[Groups.hostId],
                                        hostName = hostUser?.fullName ?: "",
                                        createdAt = group[Groups.createdAt].toString()
                                    )
                                }
                            }
                        }
                    }
                    null
                }
                
                if (existingGroup != null) {
                    logger.info("✅ 找到用户 {} 与 Silk 的私聊: {}", user.fullName, existingGroup.name)
                    call.respond(PrivateChatResponse(true, "打开 Silk 对话", existingGroup, isNew = false))
                } else {
                    // 创建新的 Silk 私聊群组
                    val groupName = "[Silk] ${user.fullName} 的专属对话"
                    val groupId = java.util.UUID.randomUUID().toString()
                    val invitationCode = java.util.UUID.randomUUID().toString().substring(0, 6).uppercase()
                    
                    val newGroup = transaction {
                        // 创建群组
                        Groups.insert {
                            it[id] = groupId
                            it[name] = groupName
                            it[Groups.invitationCode] = invitationCode
                            it[hostId] = request.userId // 用户作为群主
                        }
                        
                        // 添加用户作为成员
                        GroupMembers.insert {
                            it[GroupMembers.groupId] = groupId
                            it[GroupMembers.userId] = request.userId
                            it[GroupMembers.role] = MemberRole.HOST.name
                        }
                        
                        // 添加 Silk AI 作为成员
                        GroupMembers.insert {
                            it[GroupMembers.groupId] = groupId
                            it[GroupMembers.userId] = silkAgentId
                            it[GroupMembers.role] = MemberRole.GUEST.name
                        }
                        
                        // 创建聊天历史文件夹
                        val sessionDir = java.io.File("chat_history/group_$groupId")
                        sessionDir.mkdirs()
                        logger.debug("📁 Silk 私聊历史文件夹已创建: {}", sessionDir.path)
                        
                        com.silk.backend.database.Group(
                            id = groupId,
                            name = groupName,
                            invitationCode = invitationCode,
                            hostId = request.userId,
                            hostName = user.fullName,
                            createdAt = System.currentTimeMillis().toString()
                        )
                    }
                    
                    logger.info("🆕 创建用户 {} 与 Silk 的私聊: {}", user.fullName, groupName)
                    call.respond(PrivateChatResponse(true, "创建 Silk 对话", newGroup, isNew = true))
                }
            } catch (e: Exception) {
                logger.error("❌ 创建 Silk 私聊失败: {}", e.message)
                e.printStackTrace()
                call.respond(HttpStatusCode.BadRequest, PrivateChatResponse(false, "请求格式错误"))
            }
        }
        
        // ==================== 消息发送 API（用于转发等功能） ====================
        post("/api/messages/send") {
            try {
                val request = call.receive<SendMessageRequest>()
                
                // 检查群组是否存在
                val group = GroupRepository.findGroupById(request.groupId)
                if (group == null) {
                    call.respond(SimpleResponse(false, "群组不存在"))
                    return@post
                }
                
                // 检查用户是否在群组中
                if (!GroupRepository.isUserInGroup(request.groupId, request.userId)) {
                    call.respond(SimpleResponse(false, "您不是该群组成员"))
                    return@post
                }
                
                // 获取群组的 ChatServer 并发送消息
                val groupChatServer = getGroupChatServer(request.groupId)
                
                // 创建消息
                val message = Message(
                    id = UUID.randomUUID().toString(),
                    content = request.content,
                    userId = request.userId,
                    userName = request.userName,
                    timestamp = System.currentTimeMillis(),
                    type = MessageType.TEXT
                )
                
                // 广播消息到群组
                groupChatServer.broadcast(message)
                
                logger.debug("📨 转发消息到群组 {}: {}...", request.groupId, request.content.take(50))
                
                call.respond(SimpleResponse(true, "消息发送成功"))
            } catch (e: Exception) {
                logger.error("❌ 发送消息失败: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "发送失败: ${e.message}"))
            }
        }
        
        // ==================== 消息撤回 API ====================
        post("/api/messages/recall") {
            try {
                val request = call.receive<RecallMessageRequest>()
                
                // 检查群组是否存在
                val group = GroupRepository.findGroupById(request.groupId)
                if (group == null) {
                    call.respond(SimpleResponse(false, "群组不存在"))
                    return@post
                }
                
                // 获取群组的 ChatServer 并撤回消息
                val groupChatServer = getGroupChatServer(request.groupId)
                
                // 撤回消息
                val result = groupChatServer.recallMessage(
                    messageId = request.messageId,
                    userId = request.userId
                )
                
                if (result.success) {
                    logger.info("🗑️ 消息已撤回: {} by {}", request.messageId, request.userId)
                    call.respond(SimpleResponse(true, result.message))
                } else {
                    call.respond(SimpleResponse(false, result.message))
                }
            } catch (e: Exception) {
                logger.error("❌ 撤回消息失败: {}", e.message)
                e.printStackTrace()
                call.respond(HttpStatusCode.BadRequest, SimpleResponse(false, "撤回失败: ${e.message}"))
            }
        }
        
        webSocket("/chat") {
            val userId = call.parameters["userId"] ?: UUID.randomUUID().toString()
            val userName = call.parameters["userName"] ?: "User_${userId.take(6)}"
            val groupId = call.parameters["groupId"] ?: "default_room"
            
            logger.info("👤 用户连接: {} ({}) -> 群组: {}", userName, userId, groupId)
            
            // 为每个群组获取或创建独立的ChatServer
            val groupChatServer = getGroupChatServer(groupId)
            
            try {
                groupChatServer.join(userId, userName, this)
                
                incoming.consumeEach { frame ->
                    when (frame) {
                        is Frame.Text -> {
                            val receivedText = frame.readText()
                            try {
                                val message = Json.decodeFromString<Message>(receivedText)
                                groupChatServer.broadcast(message)
                            } catch (e: Exception) {
                                logger.warn("⚠️ 解析消息失败: {}", e.message)
                            }
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                logger.error("❌ WebSocket 错误: {}", e.localizedMessage)
            } finally {
                logger.info("👤 用户断开: {} ({})", userName, userId)
                groupChatServer.leave(userId, userName)
            }
        }
    }
}

