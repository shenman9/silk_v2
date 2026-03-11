package com.silk.backend

import java.nio.file.Files
import java.nio.file.Paths

/**
 * 从项目根目录的 .env 文件加载配置，供后端在未通过 shell 加载 .env 时使用。
 * 在 Application.main() 开头调用 load()，AIConfig 会优先使用此处加载的值。
 */
object EnvLoader {
    private var env: Map<String, String> = emptyMap()

    fun load() {
        val cwd = System.getProperty("user.dir") ?: return
        val candidates = listOf(
            Paths.get(cwd, ".env"),
            Paths.get(cwd).parent?.resolve(".env"),
            Paths.get(cwd).parent?.parent?.resolve(".env")
        ).filterNotNull().distinct()
        for (path in candidates) {
            if (Files.isRegularFile(path)) {
                env = parseEnvFile(path)
                println("✅ [EnvLoader] 已加载 .env: ${path.toAbsolutePath()}")
                return
            }
        }
        println("⚠️ [EnvLoader] 未找到 .env 文件，将使用系统环境变量")
    }

    fun get(key: String): String? = env[key]?.trim()?.takeIf { it.isNotBlank() }

    private fun parseEnvFile(path: java.nio.file.Path): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            Files.readAllLines(path).forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                val eq = trimmed.indexOf('=')
                if (eq <= 0) return@forEach
                var key = trimmed.substring(0, eq).trim()
                var value = trimmed.substring(eq + 1).trim()
                if (key.startsWith("export ")) key = key.removePrefix("export ").trim()
                if (value.startsWith("\"") && value.endsWith("\"")) value = value.drop(1).dropLast(1)
                if (value.startsWith("'") && value.endsWith("'")) value = value.drop(1).dropLast(1)
                map[key] = value
            }
        } catch (e: Exception) {
            println("⚠️ [EnvLoader] 读取 .env 失败: ${e.message}")
        }
        return map
    }
}
