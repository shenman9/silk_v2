plugins {
    kotlin("js")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
            }
            runTask {
                devServer = devServer?.copy(
                    port = 8005
                )
            }
        }
        binaries.executable()
        
        // 编译选项
        compilations.all {
            kotlinOptions {
                sourceMap = true
                moduleKind = "plain"  // 切换到 plain 以避免 UMD 兼容问题
                freeCompilerArgs += listOf(
                    "-Xir-minimized-member-names=false"  // 防止函数名混淆导致 iterator 缺失
                )
            }
        }
    }
}

dependencies {
    implementation(project(":frontend:shared"))
    implementation(compose.html.core)
    implementation(compose.runtime)
    
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-js:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}

