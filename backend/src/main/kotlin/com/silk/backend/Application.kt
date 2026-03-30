package com.silk.backend

import com.silk.backend.database.DatabaseFactory
import com.silk.backend.utils.WebPageDownloader
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
// CallLogging plugin removed temporarily - can be re-added later
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.http.*
import kotlinx.serialization.json.Json

fun main() {
    // 优先加载 .env（避免用 gradlew 直接启动时读不到配置）
    EnvLoader.load()
    // 初始化数据库
    DatabaseFactory.init()
    
    // 添加关闭钩子，清理 Playwright 资源
    Runtime.getRuntime().addShutdownHook(Thread {
        println("🔄 正在关闭服务...")
        WebPageDownloader.shutdown()
        println("✅ 服务已关闭")
    })
    
    val port = EnvLoader.get("BACKEND_HTTP_PORT")?.toIntOrNull() ?: 8003
    println("🚀 启动后端服务，端口: $port")
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    // install(CallLoging) // Temporarily disabled
    install(Compression)
    
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
    
    install(CORS) {
        // 允许所有 HTTP 方法
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        
        // 允许标准头部
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.AccessControlAllowOrigin)
        allowHeader(HttpHeaders.AccessControlAllowHeaders)
        
        // WebSocket 专用头部支持
        allowHeader(HttpHeaders.Upgrade)
        allowHeader(HttpHeaders.Connection)
        allowHeader("Sec-WebSocket-Key")
        allowHeader("Sec-WebSocket-Version")
        allowHeader("Sec-WebSocket-Extensions")
        allowHeader("Sec-WebSocket-Protocol")
        
        // 暴露自定义响应头
        exposeHeader(HttpHeaders.ContentDisposition)
        
        // 允许凭据（cookies）
        allowCredentials = true
        
        // 允许所有来源（生产环境建议限制为特定域名）
        anyHost()
        
        // 设置预检请求缓存时间（24小时）
        maxAgeInSeconds = 86400
    }
    
    configureWebSockets()
    configureRouting()
}

