package com.silk.shared

import io.ktor.client.*
import io.ktor.client.engine.js.*
import io.ktor.client.plugins.websocket.*

actual class Platform {
    actual val name: String = "Web Browser"
}

actual fun getPlatform(): Platform = Platform()

actual fun createPlatformHttpClient(): HttpClient {
    return HttpClient(Js) {
        install(WebSockets) {
            pingInterval = 15_000
            maxFrameSize = Long.MAX_VALUE
        }
        expectSuccess = false
    }
}
