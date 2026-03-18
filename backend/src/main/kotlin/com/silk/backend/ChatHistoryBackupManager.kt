package com.silk.backend

import com.silk.backend.models.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 聊天历史备份管理器
 * 
 * 在以下情况自动备份聊天历史：
 * 1. 群组被删除时
 * 2. 消息被撤回时
 * 3. 用户退出群组时
 * 
 * 备份存储在 chat_history_backup/ 目录下，按日期和类型分类
 */
object ChatHistoryBackupManager {
    
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    
    private val backupBaseDir = File("chat_history_backup")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    
    init {
        backupBaseDir.mkdirs()
        println("📁 备份目录已创建: ${backupBaseDir.absolutePath}")
    }
    
    /**
     * 备份类型
     */
    enum class BackupType {
        GROUP_DELETED,      // 群组被删除
        MESSAGE_RECALLED,   // 消息被撤回
        USER_LEFT,          // 用户退出群组
        MANUAL              // 手动备份
    }
    
    /**
     * 备份整个群组的聊天历史
     * @param groupId 群组ID
     * @param backupType 备份原因
     * @param reason 详细原因（可选）
     */
    fun backupGroupHistory(groupId: String, backupType: BackupType, reason: String = ""): Boolean {
        return try {
            val sourceDir = File("chat_history/group_$groupId")
            
            if (!sourceDir.exists()) {
                println("⚠️ 源目录不存在，无需备份: ${sourceDir.path}")
                return true
            }
            
            // 创建备份目录：backup/{type}/{date}/group_{id}
            val timestamp = LocalDateTime.now().format(dateFormatter)
            val typeDir = File(backupBaseDir, backupType.name.lowercase())
            val dateDir = File(typeDir, timestamp.substring(0, 10)) // 按日期分组
            val backupDir = File(dateDir, "group_$groupId")
            backupDir.mkdirs()
            
            // 复制所有文件
            sourceDir.copyRecursively(backupDir, overwrite = true)
            
            // 写入备份元数据
            val metadata = BackupMetadata(
                groupId = groupId,
                backupType = backupType.name,
                timestamp = timestamp,
                reason = reason.ifEmpty { backupType.name },
                originalPath = sourceDir.absolutePath
            )
            File(backupDir, "_backup_metadata.json").writeText(json.encodeToString(metadata))
            
            println("✅ 群组历史已备份: $groupId -> ${backupDir.absolutePath}")
            println("   原因: ${backupType.name} - $reason")
            true
        } catch (e: Exception) {
            println("❌ 备份群组历史失败: $groupId - ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 备份单条被撤回的消息
     * @param groupId 群组ID
     * @param message 被撤回的消息
     * @param userId 撤回者ID
     */
    fun backupRecalledMessage(groupId: String, message: ChatHistoryEntry, userId: String): Boolean {
        return try {
            val timestamp = LocalDateTime.now().format(dateFormatter)
            val typeDir = File(backupBaseDir, BackupType.MESSAGE_RECALLED.name.lowercase())
            val dateDir = File(typeDir, timestamp.substring(0, 10))
            dateDir.mkdirs()
            
            // 备份文件名：{timestamp}_group_{groupId}_msg_{messageId}.json
            val backupFile = File(dateDir, "${timestamp}_group_${groupId}_msg_${message.messageId}.json")
            
            // 创建撤回备份记录
            val record = RecalledMessageBackup(
                groupId = groupId,
                message = message,
                recalledBy = userId,
                recalledAt = timestamp,
                originalTimestamp = message.timestamp
            )
            backupFile.writeText(json.encodeToString(record))
            
            println("✅ 撤回消息已备份: ${message.messageId} -> ${backupFile.name}")
            true
        } catch (e: Exception) {
            println("❌ 备份撤回消息失败: ${message.messageId} - ${e.message}")
            false
        }
    }
    
    /**
     * 备份多条被撤回的消息
     */
    fun backupRecalledMessages(groupId: String, messages: List<ChatHistoryEntry>, userId: String): Int {
        var count = 0
        messages.forEach { message ->
            if (backupRecalledMessage(groupId, message, userId)) {
                count++
            }
        }
        return count
    }
    
    /**
     * 用户退出时备份（如果该用户是最后一个成员，即将删除群组）
     */
    fun backupOnUserLeave(groupId: String, userId: String, userName: String, isLastMember: Boolean): Boolean {
        val reason = if (isLastMember) {
            "用户 $userName($userId) 退出，群组将删除"
        } else {
            "用户 $userName($userId) 退出群组"
        }
        return backupGroupHistory(groupId, BackupType.USER_LEFT, reason)
    }
    
    /**
     * 获取备份列表
     * @param backupType 备份类型（可选，null表示所有类型）
     * @return 备份元数据列表
     */
    fun listBackups(backupType: BackupType? = null): List<BackupMetadata> {
        val backups = mutableListOf<BackupMetadata>()
        
        val searchDirs = if (backupType != null) {
            listOf(File(backupBaseDir, backupType.name.lowercase()))
        } else {
            backupBaseDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
        }
        
        searchDirs.forEach { typeDir ->
            if (!typeDir.exists()) return@forEach
            
            typeDir.walkTopDown()
                .filter { it.name == "_backup_metadata.json" }
                .forEach { metadataFile ->
                    try {
                        val metadata = json.decodeFromString<BackupMetadata>(metadataFile.readText())
                        backups.add(metadata)
                    } catch (e: Exception) {
                        println("⚠️ 解析备份元数据失败: ${metadataFile.path}")
                    }
                }
        }
        
        return backups.sortedByDescending { it.timestamp }
    }
    
    /**
     * 恢复群组历史（从备份）
     * @param backupPath 备份路径
     * @param targetGroupId 目标群组ID（可选，默认使用备份中的groupId）
     */
    fun restoreFromBackup(backupPath: String, targetGroupId: String? = null): Boolean {
        return try {
            val backupDir = File(backupPath)
            if (!backupDir.exists() || !backupDir.isDirectory) {
                println("❌ 备份目录不存在: $backupPath")
                return false
            }
            
            // 读取元数据
            val metadataFile = File(backupDir, "_backup_metadata.json")
            if (!metadataFile.exists()) {
                println("❌ 备份元数据不存在")
                return false
            }
            
            val metadata = json.decodeFromString<BackupMetadata>(metadataFile.readText())
            val groupId = targetGroupId ?: metadata.groupId
            
            // 恢复到目标目录
            val targetDir = File("chat_history/group_$groupId")
            if (targetDir.exists()) {
                // 如果目标已存在，先备份当前版本
                backupGroupHistory(groupId, BackupType.MANUAL, "恢复前自动备份")
            }
            
            targetDir.mkdirs()
            backupDir.copyRecursively(targetDir, overwrite = true)
            
            // 删除恢复的元数据文件（避免混淆）
            File(targetDir, "_backup_metadata.json").delete()
            
            println("✅ 已从备份恢复: $groupId <- ${backupDir.absolutePath}")
            true
        } catch (e: Exception) {
            println("❌ 恢复备份失败: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 清理过期备份（保留最近N天）
     * @param retentionDays 保留天数
     */
    fun cleanupOldBackups(retentionDays: Int = 30): Int {
        var deletedCount = 0
        val cutoffDate = LocalDateTime.now().minusDays(retentionDays.toLong())
        val cutoffDateStr = cutoffDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        
        backupBaseDir.walkTopDown()
            .filter { it.isDirectory && it.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
            .forEach { dateDir ->
                if (dateDir.name < cutoffDateStr) {
                    try {
                        dateDir.deleteRecursively()
                        deletedCount++
                        println("🗑️ 已删除过期备份: ${dateDir.path}")
                    } catch (e: Exception) {
                        println("⚠️ 删除过期备份失败: ${dateDir.path}")
                    }
                }
            }
        
        if (deletedCount > 0) {
            println("✅ 已清理 $deletedCount 个过期备份目录（保留最近 $retentionDays 天）")
        }
        return deletedCount
    }
}
