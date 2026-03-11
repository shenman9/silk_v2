package com.silk.shared

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*

actual class Platform {
    actual val name: String = "Android ${android.os.Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = Platform()

actual fun createPlatformHttpClient(): HttpClient {
    return HttpClient(OkHttp) {
        install(WebSockets) {
            pingInterval = 15_000
        }
        expectSuccess = false
    }
}
