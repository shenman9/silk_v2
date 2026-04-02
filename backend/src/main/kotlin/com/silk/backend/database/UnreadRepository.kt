package com.silk.backend.database

import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * 未读消息追踪仓库
 * 追踪每个用户在每个群组的最后阅读时间和最新消息时间
 */
object UnreadRepository {
    private val logger = LoggerFactory.getLogger(UnreadRepository::class.java)
    // 用户最后阅读时间: key = "${userId}_${groupId}", value = timestamp
    private val lastReadTimestamps = ConcurrentHashMap<String, Long>()
    
    // 群组最新消息时间: key = groupId, value = timestamp
    private val latestMessageTimestamps = ConcurrentHashMap<String, Long>()
    
    // 群组消息计数（从最后阅读时间后的消息数）: key = groupId, value = list of timestamps
    private val messageTimestamps = ConcurrentHashMap<String, MutableList<Long>>()
    
    /**
     * 记录用户阅读了群组（标记已读）
     */
    fun markAsRead(userId: String, groupId: String) {
        val key = "${userId}_${groupId}"
        lastReadTimestamps[key] = System.currentTimeMillis()
        logger.debug("📖 [UnreadRepo] 用户 {} 已读群组 {}", userId, groupId)
    }
    
    /**
     * 记录新消息到达群组
     * @param groupId 群组ID
     * @param timestamp 消息时间戳
     * @param senderId 发送者ID（可选），如果提供则自动将该发送者标记为已读
     */
    fun recordNewMessage(groupId: String, timestamp: Long = System.currentTimeMillis(), senderId: String? = null) {
        latestMessageTimestamps[groupId] = timestamp
        
        // 添加到消息时间戳列表（只保留最近100条）
        val timestamps = messageTimestamps.getOrPut(groupId) { mutableListOf() }
        synchronized(timestamps) {
            timestamps.add(timestamp)
            if (timestamps.size > 100) {
                timestamps.removeAt(0)
            }
        }
        logger.debug("📨 [UnreadRepo] 群组 {} 收到新消息 @ {}", groupId, timestamp)
        
        // 自动将发送者标记为已读（自己发的消息不应该显示为未读）
        if (senderId != null) {
            val key = "${senderId}_${groupId}"
            lastReadTimestamps[key] = timestamp
            logger.debug("📖 [UnreadRepo] 自动标记发送者 {} 已读群组 {}", senderId, groupId)
        }
    }
    
    /**
     * 获取用户在某群组的未读消息数
     */
    fun getUnreadCount(userId: String, groupId: String): Int {
        val key = "${userId}_${groupId}"
        val lastRead = lastReadTimestamps[key] ?: 0L
        val timestamps = messageTimestamps[groupId] ?: return 0
        
        synchronized(timestamps) {
            return timestamps.count { it > lastRead }
        }
    }
    
    /**
     * 批量获取用户的所有群组未读数
     */
    fun getUnreadCounts(userId: String, groupIds: List<String>): Map<String, Int> {
        return groupIds.associateWith { groupId ->
            getUnreadCount(userId, groupId)
        }
    }
    
    /**
     * 检查群组是否有新消息（相对于用户的最后阅读时间）
     */
    fun hasUnread(userId: String, groupId: String): Boolean {
        val key = "${userId}_${groupId}"
        val lastRead = lastReadTimestamps[key] ?: 0L
        val latestMessage = latestMessageTimestamps[groupId] ?: 0L
        return latestMessage > lastRead
    }
    
    /**
     * 清理指定群组的所有追踪数据（群组删除时调用）
     */
    fun cleanupGroup(groupId: String) {
        latestMessageTimestamps.remove(groupId)
        messageTimestamps.remove(groupId)
        // 清理所有用户对该群组的阅读记录
        lastReadTimestamps.keys.filter { it.endsWith("_$groupId") }.forEach {
            lastReadTimestamps.remove(it)
        }
        logger.debug("🗑️ [UnreadRepo] 已清理群组 {} 的追踪数据", groupId)
    }
}

