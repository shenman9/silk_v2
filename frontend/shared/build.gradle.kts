plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")  // 启用Android支持
}

kotlin {
    androidTarget {  // 启用Android目标
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }
    
    jvm("desktop")
    
    js(IR) {
        browser()
        binaries.executable()
    }
    
    // listOf(  // iOS 暂时禁用
    //     iosX64(),
    //     iosArm64(),
    //     iosSimulatorArm64()
    // ).forEach { iosTarget ->
    //     iosTarget.binaries.framework {
    //         baseName = "shared"
    //         isStatic = true
    //     }
    // }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Coroutines
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                
                // Serialization
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
                
                // Ktor Client
                implementation("io.ktor:ktor-client-core:2.3.6")
                implementation("io.ktor:ktor-client-websockets:2.3.6")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.6")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.6")
                
                // DateTime
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.1")
            }
        }
        
        val desktopMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-cio:2.3.6")
            }
        }
        
        val jsMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-js:2.3.6")
            }
        }
        
        val androidMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:2.3.6")  // OkHttp 支持 WebSocket
            }
        }
        
        // iOS 相关配置暂时禁用
    }
}

// Android 配置
android {
    namespace = "com.silk.shared"
    compileSdk = 34
    
    defaultConfig {
        minSdk = 26
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

