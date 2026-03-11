package com.silk.shared

import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.plugins.websocket.*
import platform.UIKit.UIDevice

actual class Platform {
    actual val name: String = 
        UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = Platform()

actual fun createPlatformHttpClient(): HttpClient {
    return HttpClient(Darwin) {
        install(WebSockets) {
            pingInterval = 15_000
        }
        expectSuccess = false
    }
}
