package com.silk.backend.models

import kotlinx.serialization.Serializable

/**
 * 群组历史备份元数据
 */
@Serializable
data class BackupMetadata(
    val groupId: String,
    val backupType: String,
    val timestamp: String,
    val reason: String,
    val originalPath: String
)

/**
 * 被撤回消息的备份记录
 */
@Serializable
data class RecalledMessageBackup(
    val groupId: String,
    val message: ChatHistoryEntry,
    val recalledBy: String,
    val recalledAt: String,
    val originalTimestamp: Long
)

