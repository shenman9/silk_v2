// backend/src/main/kotlin/com/silk/backend/claudecode/ClaudeCodeRunner.kt
package com.silk.backend.claudecode

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 启动 claude CLI 子进程，流式读取 stream-json 输出并回调。
 */
class ClaudeCodeRunner {

    private val logger = LoggerFactory.getLogger(ClaudeCodeRunner::class.java)

    companion object {
        private const val DEFAULT_CLAUDE_PATH = "claude"
        private const val DEFAULT_MAX_TURNS = 100
        private const val DEFAULT_TIMEOUT_SEC = 36000L
        private const val DEFAULT_MAX_OUTPUT_CHARS = 30000

        /** 流式推送节流参数 */
        const val STREAM_MIN_INTERVAL_MS = 500L
        const val STREAM_MIN_CHARS = 50

        private fun env(key: String): String? =
            com.silk.backend.EnvLoader.get(key) ?: System.getenv(key)?.takeIf { it.isNotBlank() }

        val claudePath: String get() = env("CLAUDE_CODE_PATH") ?: DEFAULT_CLAUDE_PATH
        val maxTurns: Int get() = env("CLAUDE_CODE_MAX_TURNS")?.toIntOrNull() ?: DEFAULT_MAX_TURNS
        val timeoutSec: Long get() = env("CLAUDE_CODE_TIMEOUT")?.toLongOrNull() ?: DEFAULT_TIMEOUT_SEC
        val maxOutputChars: Int get() = env("CLAUDE_CODE_MAX_OUTPUT_CHARS")?.toIntOrNull() ?: DEFAULT_MAX_OUTPUT_CHARS
        val defaultWorkingDir: String get() = env("CLAUDE_CODE_DEFAULT_DIR") ?: System.getProperty("user.dir") ?: "/"
    }

    /**
     * 启动 claude CLI 并流式处理输出。
     */
    suspend fun execute(
        prompt: String,
        sessionId: String,
        workingDir: String,
        resume: Boolean,
        onStreamText: suspend (accumulatedText: String) -> Unit,
        onToolLog: suspend (log: String) -> Unit,
        onComplete: suspend (fullText: String, meta: StreamParser.ResultMeta?) -> Unit,
        onError: suspend (error: String) -> Unit,
        processRef: (Process) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val cmd = buildCommand(prompt, sessionId, resume)
        logger.info("[CC] 启动子进程: cmd={}, cwd={}", cmd.joinToString(" ") { "\"$it\"" }, workingDir)

        val processBuilder = ProcessBuilder(cmd)
            .directory(java.io.File(workingDir))
            .redirectErrorStream(false)

        val process: Process
        try {
            process = processBuilder.start()
        } catch (e: Exception) {
            logger.error("[CC] 启动 claude 失败: {}", e.message)
            onError("启动 Claude Code 失败: ${e.message}")
            return@withContext
        }
        processRef(process)

        // 超时定时器
        val timeoutJob = launch {
            delay(timeoutSec * 1000)
            if (process.isAlive) {
                logger.warn("[CC] 子进程超时 ({}s)，强制终止", timeoutSec)
                process.destroyForcibly()
            }
        }

        val accumulatedText = StringBuilder()
        var lastMeta: StreamParser.ResultMeta? = null
        // 活跃的工具调用 ID → 日志行文本（用于追加 ✅/❌）
        val activeToolIds = mutableMapOf<String, String>()
        var lastPushTime = System.currentTimeMillis()
        var lastPushLen = 0

        try {
            logger.info("[CC] 开始读取子进程输出...")
            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            var line: String?
            var lineCount = 0

            while (reader.readLine().also { line = it } != null) {
                lineCount++
                val l = line!!.trim().replace("\r", "") // script PTY 可能引入 \r
                if (l.isEmpty()) continue
                // 跳过非 JSON 行（script 可能输出额外信息）
                if (!l.startsWith("{")) {
                    logger.debug("[CC] 跳过非JSON行: {}", l.take(80))
                    continue
                }

                logger.info("[CC] 收到第{}行输出 ({}字符), type={}", lineCount, l.length, l.take(50))
                val parsed = StreamParser.parseLine(l)

                // 处理工具日志
                for (toolLog in parsed.toolLogs) {
                    if (toolLog.toolUseId != null) {
                        activeToolIds[toolLog.toolUseId] = toolLog.line
                    }
                    onToolLog(toolLog.line)
                }

                // 处理工具结果（追加 ✅/❌）
                for (result in parsed.toolResults) {
                    val originalLine = activeToolIds.remove(result.toolUseId)
                    if (originalLine != null) {
                        val suffix = if (result.isError) {
                            if (result.summary.isNotBlank()) " → ❌ ${result.summary}" else " → ❌"
                        } else " → ✅"
                        onToolLog("$originalLine$suffix")
                    }
                }

                // 处理文本（result 的 fallback 文本仅在没有 assistant 文本时使用）
                val shouldAppendText = parsed.textChunk.isNotEmpty() &&
                    (parsed.meta == null || accumulatedText.isEmpty())
                if (shouldAppendText) {
                    accumulatedText.append(parsed.textChunk)

                    // 截断保护
                    if (accumulatedText.length > maxOutputChars) {
                        val truncated = accumulatedText.take(maxOutputChars).toString()
                        logger.warn("[CC] 输出超限 ({} chars)，终止进程", maxOutputChars)
                        process.destroyForcibly()
                        onStreamText(truncated)
                        onToolLog("⚠️ 输出已截断（超过 ${maxOutputChars} 字符上限）")
                        break
                    }

                    // 节流推送
                    val now = System.currentTimeMillis()
                    val newChars = accumulatedText.length - lastPushLen
                    if (now - lastPushTime >= STREAM_MIN_INTERVAL_MS || newChars >= STREAM_MIN_CHARS) {
                        onStreamText(accumulatedText.toString())
                        lastPushTime = now
                        lastPushLen = accumulatedText.length
                    }
                }

                // 元信息
                if (parsed.meta != null) {
                    lastMeta = parsed.meta
                }
            }

            // 推送剩余文本
            if (accumulatedText.length > lastPushLen) {
                onStreamText(accumulatedText.toString())
            }

            // 等待进程结束
            logger.info("[CC] 子进程输出读取完毕，共{}行，等待退出...", lineCount)
            val exitCode = process.waitFor()
            timeoutJob.cancel()
            logger.info("[CC] 子进程已退出, exitCode={}, 累积文本={}字符", exitCode, accumulatedText.length)

            // 读取 stderr
            val stderr = process.errorStream.bufferedReader().readText().trim()
            if (exitCode != 0 && stderr.isNotBlank()) {
                logger.warn("[CC] 进程退出码={}, stderr={}", exitCode, stderr.take(200))
            }

            if (exitCode != 0 && accumulatedText.isEmpty()) {
                val errMsg = stderr.ifBlank { "Claude Code 进程异常退出 (code=$exitCode)" }
                onError(errMsg)
            } else {
                onComplete(accumulatedText.toString(), lastMeta)
            }

        } catch (e: CancellationException) {
            timeoutJob.cancel()
            if (process.isAlive) process.destroyForcibly()
            throw e
        } catch (e: Exception) {
            timeoutJob.cancel()
            if (process.isAlive) process.destroyForcibly()
            logger.error("[CC] 读取子进程输出异常: {}", e.message)
            onError("处理 Claude Code 输出异常: ${e.message}")
        }
    }

    private fun buildCommand(prompt: String, sessionId: String, resume: Boolean): List<String> {
        val claudeCmd = mutableListOf(
            claudePath,
            "-p", prompt,
            "--output-format", "stream-json",
        )
        if (resume) {
            claudeCmd.addAll(listOf("--resume", sessionId))
        } else {
            claudeCmd.addAll(listOf("--session-id", sessionId))
        }
        claudeCmd.addAll(listOf(
            "--verbose",
            "--permission-mode", "bypassPermissions",
            "--max-turns", maxTurns.toString(),
        ))
        // claude CLI 需要 TTY 才能输出 stream-json，
        // 但 ProcessBuilder 的 stdout 是 pipe 而非 TTY。
        // 用 script -q -c 包装命令来分配伪终端（PTY）。
        val shellCmd = claudeCmd.joinToString(" ") {
            // 对含空格/特殊字符的参数加引号
            if (it.contains(" ") || it.contains("\"") || it.contains("'") || it.contains("\n")) {
                "'" + it.replace("'", "'\\''") + "'"
            } else it
        }
        return listOf("script", "-q", "-c", shellCmd, "/dev/null")
    }
}
