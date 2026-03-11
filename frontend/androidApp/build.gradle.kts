import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization") version "1.9.20"
    id("org.jetbrains.compose")
}

// ==================== 基于时间戳的自动版本管理 ====================
// 每次构建自动生成新版本号，无需手动管理

val buildTime = Date()
val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
    setTime(buildTime)
}

// versionCode: 使用紧凑格式确保在 Int 范围内
// 格式: (year-2020)*100000000 + month*1000000 + day*10000 + hour*100 + minute
// 例如: 2026年1月8日16:53 -> 6*100000000 + 1*1000000 + 8*10000 + 16*100 + 53 = 601081653
val year = calendar.get(Calendar.YEAR) - 2020  // 2026 -> 6
val month = calendar.get(Calendar.MONTH) + 1    // 0-based -> 1-based
val day = calendar.get(Calendar.DAY_OF_MONTH)
val hour = calendar.get(Calendar.HOUR_OF_DAY)
val minute = calendar.get(Calendar.MINUTE)

val currentVersionCode = year * 100000000 + month * 1000000 + day * 10000 + hour * 100 + minute

// versionName: 使用 yyyy.MMdd.HHmm 格式，更易读
// 例如: 2026.0108.1653
val dateFormatName = SimpleDateFormat("yyyy.MMdd.HHmm").apply {
    timeZone = TimeZone.getTimeZone("Asia/Shanghai")
}
val currentVersionName = dateFormatName.format(buildTime)

println("📱 构建版本: $currentVersionName (code=$currentVersionCode) - ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(buildTime)}")

android {
    namespace = "com.silk.android"
    compileSdk = 34
    
    defaultConfig {
        applicationId = "com.silk.android"
        minSdk = 26  // 支持自适应图标和更多现代特性
        targetSdk = 34
        versionCode = currentVersionCode
        versionName = currentVersionName
        // 构建时后端地址：优先 -PBACKEND_BASE_URL（silk.sh build-apk 会从 .env 传入），否则环境变量，最后默认模拟器地址
        val baseUrl = project.findProperty("BACKEND_BASE_URL")?.toString()
            ?: System.getenv("BACKEND_BASE_URL")
            ?: (System.getenv("BACKEND_HOST")?.let { host ->
                val port = System.getenv("BACKEND_HTTP_PORT") ?: "8006"
                "http://$host:$port"
            } ?: "http://10.0.2.2:8006")
        buildConfigField("String", "BACKEND_BASE_URL", "\"$baseUrl\"")
        println("📱 [Android] BACKEND_BASE_URL = $baseUrl")
    }
    
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/previous-compilation-data.bin"
        }
    }
    
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
}

// ==================== 自动递增版本号任务 ====================

// ==================== 版本管理说明 ====================
// 版本号现在基于构建时间自动生成，无需手动管理
// versionCode: yyMMddHHmm (例如 2601081652)
// versionName: yyyy.MMdd.HHmm (例如 2026.0108.1652)
// 每次构建都会生成新版本，自动触发更新检测

dependencies {
    implementation(project(":frontend:shared"))
    
    // Compose
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    
    // Ktor Client (使用 OkHttp 引擎支持 WebSocket)
    implementation("io.ktor:ktor-client-okhttp:2.3.5")
    implementation("io.ktor:ktor-client-websockets:2.3.5")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.5")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.5")
    
    // Kotlinx DateTime
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.1")
    
    debugImplementation("androidx.compose.ui:ui-tooling")
}
