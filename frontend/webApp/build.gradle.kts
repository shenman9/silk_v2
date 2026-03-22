import java.io.File

// ==================== 读取 .env 文件 ====================
fun readEnvFile(): Map<String, String> {
    val env = mutableMapOf<String, String>()
    val cwd = File(System.getProperty("user.dir"))
    val candidates = listOf(
        File(cwd, ".env"),
        cwd.parentFile?.let { File(it, ".env") },
        cwd.parentFile?.parentFile?.let { File(it, ".env") }
    ).filterNotNull()

    for (file in candidates) {
        if (file.isFile) {
            file.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
                val eq = trimmed.indexOf('=')
                if (eq <= 0) return@forEachLine
                var key = trimmed.substring(0, eq).trim()
                var value = trimmed.substring(eq + 1).trim()
                if (key.startsWith("export ")) key = key.removePrefix("export ").trim()
                if (value.startsWith("\"") && value.endsWith("\"")) value = value.drop(1).dropLast(1)
                if (value.startsWith("'") && value.endsWith("'")) value = value.drop(1).dropLast(1)
                env[key] = value
            }
            println("📦 [webApp] 已加载 .env: ${file.absolutePath}")
            break
        }
    }
    return env
}
val envFile = readEnvFile()

val backendPort = envFile["BACKEND_HTTP_PORT"] ?: System.getenv("BACKEND_HTTP_PORT") ?: "8003"
val frontendPort = envFile["FRONTEND_PORT"] ?: System.getenv("FRONTEND_PORT") ?: "8004"
println("📦 [webApp] BACKEND_HTTP_PORT = $backendPort, FRONTEND_PORT = $frontendPort")

plugins {
    kotlin("js")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
}

// ==================== 生成 BuildConfig.kt ====================
val generateBuildConfig by tasks.registering {
    val outputDir = file("$buildDir/generated/buildconfig")
    outputs.dir(outputDir)
    doLast {
        val dir = File(outputDir, "com/silk/web")
        dir.mkdirs()
        File(dir, "BuildConfig.kt").writeText("""
            package com.silk.web

            object BuildConfig {
                const val BACKEND_HTTP_PORT = "$backendPort"
                const val FRONTEND_PORT = "$frontendPort"
            }
        """.trimIndent())
        println("📦 [webApp] 已生成 BuildConfig.kt (BACKEND_HTTP_PORT=$backendPort)")
    }
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
        
        compilations.all {
            kotlinOptions {
                sourceMap = true
                moduleKind = "plain"
                freeCompilerArgs += listOf(
                    "-Xir-minimized-member-names=false"
                )
            }
        }
    }

    sourceSets["main"].kotlin.srcDir("$buildDir/generated/buildconfig")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile>().configureEach {
    dependsOn(generateBuildConfig)
}

dependencies {
    implementation(project(":frontend:shared"))
    implementation(compose.html.core)
    implementation(compose.runtime)
    
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-js:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    implementation(npm("markdown-it", "^14.1.0"))
    implementation(npm("markdown-it-task-lists", "^2.1.1"))
    implementation(npm("dompurify", "^3.2.6"))
    implementation(npm("katex", "^0.16.22"))
    implementation(npm("highlight.js", "^11.11.1"))
    implementation(npm("github-markdown-css", "^5.8.1"))
}
