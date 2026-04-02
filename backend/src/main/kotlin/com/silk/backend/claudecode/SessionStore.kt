// backend/src/main/kotlin/com/silk/backend/claudecode/SessionStore.kt
package com.silk.backend.claudecode

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * CC 会话元数据持久化。
 * 存储到 dataDir/claude_code_sessions.json，线程安全。
 */
class SessionStore(private val dataDir: File) {

    private val logger = LoggerFactory.getLogger(SessionStore::class.java)
    private val lock = Any()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    companion object {
        private const val FILE_NAME = "claude_code_sessions.json"
        private const val EXPIRE_DAYS = 7L
    }

    @Serializable
    data class SessionRecord(
        val sessionId: String,
        val workingDir: String,
        var title: String,
        val createdAt: String,
        var lastActivity: String,
        var starred: Boolean = false,
    )

    fun loadUserSessions(userId: String): List<SessionRecord> {
        return synchronized(lock) {
            loadAll()[userId] ?: emptyList()
        }
    }

    fun upsertSession(userId: String, sessionId: String, workingDir: String, title: String) {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        synchronized(lock) {
            val data = loadAll().toMutableMap()
            val sessions = (data[userId] ?: emptyList()).toMutableList()

            val existing = sessions.find { it.sessionId == sessionId }
            if (existing != null) {
                existing.lastActivity = now
                existing.title = title
            } else {
                sessions.add(0, SessionRecord(sessionId, workingDir, title, now, now))
            }

            // 按最近活跃倒序，清理过期（7天未活跃且未收藏）
            sessions.sortByDescending { it.lastActivity }
            val cutoff = Instant.now().minus(EXPIRE_DAYS, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString()
            data[userId] = sessions.filter { it.starred || it.lastActivity >= cutoff }

            writeAll(data)
        }
    }

    private fun filePath(): File = File(dataDir, FILE_NAME)

    private fun loadAll(): Map<String, List<SessionRecord>> {
        val file = filePath()
        if (!file.exists()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, List<SessionRecord>>>(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            logger.warn("[CC] 读取会话文件失败: {}", e.message)
            emptyMap()
        }
    }

    private fun writeAll(data: Map<String, List<SessionRecord>>) {
        try {
            dataDir.mkdirs()
            val file = filePath()
            val tmp = File(dataDir, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(data), Charsets.UTF_8)
            tmp.renameTo(file)
        } catch (e: Exception) {
            logger.error("[CC] 写入会话文件失败: {}", e.message)
        }
    }
}
