package com.silk.shared

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*

actual class Platform {
    actual val name: String = "Desktop (${System.getProperty("os.name")})"
}

actual fun getPlatform(): Platform = Platform()

actual fun createPlatformHttpClient(): HttpClient {
    return HttpClient(CIO) {
        install(WebSockets) {
            pingInterval = 15_000
        }
        expectSuccess = false
    }
}
