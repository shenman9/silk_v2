package com.silk.backend

import com.silk.backend.database.DatabaseFactory
import com.silk.backend.utils.WebPageDownloader
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun main() {
    // 优先加载 .env（避免用 gradlew 直接启动时读不到配置）
    EnvLoader.load()

    // 在 logger 初始化之前设置日志级别系统属性
    val logLevel = System.getProperty("log.level")
        ?: EnvLoader.get("LOG_LEVEL")
        ?: "INFO"
    System.setProperty("log.level", logLevel)

    val logger = LoggerFactory.getLogger("Application")
    logger.info("日志级别: {}, 日志文件: logs/silk-backend.log", logLevel)

    // 初始化数据库
    DatabaseFactory.init()

    // 添加关闭钩子，清理 Playwright 资源
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("🔄 正在关闭服务...")
        WebPageDownloader.shutdown()
        logger.info("✅ 服务已关闭")
    })

    val port = EnvLoader.get("BACKEND_HTTP_PORT")?.toIntOrNull() ?: 8003
    logger.info("🚀 启动后端服务，端口: {}", port)
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(CallLogging)
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
