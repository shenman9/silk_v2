package com.silk.backend.database

import com.silk.backend.ChatHistoryBackupManager
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.random.Random

/**
 * 群组数据访问层
 */
object GroupRepository {
    private val logger = LoggerFactory.getLogger(GroupRepository::class.java)

    /**
     * 创建新群组
     * @param name 群组名称
     * @param hostId 群主用户ID
     * @return 创建的群组对象
     */
    fun createGroup(name: String, hostId: String): Group? {
        return try {
            transaction {
                // 检查群组名是否已存在
                val existingGroup = Groups.select { Groups.name eq name }
                    .singleOrNull()
                
                if (existingGroup != null) {
                    logger.error("❌ 群组名已存在: {}", name)
                    return@transaction null
                }
                
                val groupId = UUID.randomUUID().toString()
                val invitationCode = generateInvitationCode()
                
                // 创建群组
                Groups.insert {
                    it[id] = groupId
                    it[Groups.name] = name
                    it[Groups.invitationCode] = invitationCode
                    it[Groups.hostId] = hostId
                }
                
                // 将群主添加为成员
                GroupMembers.insert {
                    it[GroupMembers.groupId] = groupId
                    it[GroupMembers.userId] = hostId
                    it[GroupMembers.role] = MemberRole.HOST.name
                }
                
                // 创建群组的聊天历史文件夹
                val sessionDir = java.io.File("chat_history/group_$groupId")
                sessionDir.mkdirs()
                logger.info("📁 群组聊天历史文件夹已创建: {}", sessionDir.path)

                findGroupById(groupId)
            }
        } catch (e: Exception) {
            logger.error("❌ 创建群组失败: {}", e.message)
            null
        }
    }

    /**
     * 根据ID查找群组
     */
    fun findGroupById(groupId: String): Group? {
        return transaction {
            Groups.select { Groups.id eq groupId }
                .mapNotNull { row ->
                    val hostUser = UserRepository.findUserById(row[Groups.hostId])
                    Group(
                        id = row[Groups.id],
                        name = row[Groups.name],
                        invitationCode = row[Groups.invitationCode],
                        hostId = row[Groups.hostId],
                        hostName = hostUser?.fullName ?: "",
                        createdAt = row[Groups.createdAt].toString()
                    )
                }
                .singleOrNull()
        }
    }
    
    /**
     * 根据邀请码查找群组
     */
    fun findGroupByInvitationCode(invitationCode: String): Group? {
        return transaction {
            Groups.select { Groups.invitationCode eq invitationCode }
                .mapNotNull { row ->
                    val hostUser = UserRepository.findUserById(row[Groups.hostId])
                    Group(
                        id = row[Groups.id],
                        name = row[Groups.name],
                        invitationCode = row[Groups.invitationCode],
                        hostId = row[Groups.hostId],
                        hostName = hostUser?.fullName ?: "",
                        createdAt = row[Groups.createdAt].toString()
                    )
                }
                .singleOrNull()
        }
    }
    
    /**
     * 获取用户所属的所有群组
     */
    fun getUserGroups(userId: String): List<Group> {
        return transaction {
            // 先查询用户所属的群组ID列表
            val groupIds = GroupMembers
                .select { GroupMembers.userId eq userId }
                .map { it[GroupMembers.groupId] }
            
            // 如果用户没有群组，返回空列表
            if (groupIds.isEmpty()) {
                return@transaction emptyList()
            }
            
            // 根据群组ID列表查询群组详情
            Groups
                .select { Groups.id inList groupIds }
                .map { row ->
                    val hostUser = UserRepository.findUserById(row[Groups.hostId])
                    Group(
                        id = row[Groups.id],
                        name = row[Groups.name],
                        invitationCode = row[Groups.invitationCode],
                        hostId = row[Groups.hostId],
                        hostName = hostUser?.fullName ?: "",
                        createdAt = row[Groups.createdAt].toString()
                    )
                }
        }
    }
    
    /**
     * 将用户添加到群组
     */
    fun addUserToGroup(groupId: String, userId: String, role: MemberRole = MemberRole.GUEST): Boolean {
        return try {
            transaction {
                GroupMembers.insert {
                    it[GroupMembers.groupId] = groupId
                    it[GroupMembers.userId] = userId
                    it[GroupMembers.role] = role.name
                }
            }
            true
        } catch (e: Exception) {
            logger.error("❌ 添加用户到群组失败: {}", e.message)
            false
        }
    }
    
    /**
     * 检查用户是否已在群组中
     */
    fun isUserInGroup(groupId: String, userId: String): Boolean {
        return transaction {
            GroupMembers.select { 
                (GroupMembers.groupId eq groupId) and (GroupMembers.userId eq userId)
            }.count() > 0
        }
    }
    
    /**
     * 获取群组的所有成员
     */
    fun getGroupMembers(groupId: String): List<GroupMember> {
        return transaction {
            GroupMembers.select { GroupMembers.groupId eq groupId }
                .map { row ->
                    val user = UserRepository.findUserById(row[GroupMembers.userId])
                    GroupMember(
                        groupId = row[GroupMembers.groupId],
                        userId = row[GroupMembers.userId],
                        userName = user?.fullName ?: "",
                        role = MemberRole.valueOf(row[GroupMembers.role]),
                        joinedAt = row[GroupMembers.joinedAt].toString()
                    )
                }
        }
    }
    
    /**
     * 检查群组名称是否已存在
     */
    fun isGroupNameExists(name: String): Boolean {
        return transaction {
            Groups.select { Groups.name eq name }.count() > 0
        }
    }
    
    /**
     * 生成6位邀请码
     */
    private fun generateInvitationCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 排除容易混淆的字符
        return (1..6)
            .map { chars[Random.nextInt(chars.length)] }
            .joinToString("")
    }
    
    // ==================== 联系人对话相关 ====================
    
    /**
     * 查找两个用户共同的对话群组
     *
     * 设计目标：
     * 1. 支持“2人私聊升级为多人群聊”后仍复用原 groupId
     * 2. 避免因为成员数变化（>2）导致创建新群、历史看起来“消失”
     */
    fun findCommonGroup(user1Id: String, user2Id: String): Group? {
        return transaction {
            // 获取 user1 所在的所有群组
            val user1Groups = GroupMembers
                .select { GroupMembers.userId eq user1Id }
                .map { it[GroupMembers.groupId] }
                .toSet()
            
            // 获取 user2 所在的所有群组
            val user2Groups = GroupMembers
                .select { GroupMembers.userId eq user2Id }
                .map { it[GroupMembers.groupId] }
                .toSet()
            
            // 找到两个用户共同的群组
            val commonGroupIds = user1Groups.intersect(user2Groups)
            
            if (commonGroupIds.isEmpty()) {
                logger.debug("ℹ️ 未找到 {} 和 {} 的共同群组", user1Id, user2Id)
                return@transaction null
            }
            
            // 候选群组：同时包含两个用户（成员数>=2）
            // 排序策略：
            // - 先按成员数升序（优先更接近“私聊”的会话）
            // - 再按创建时间升序（优先复用更早建立的对话，保证稳定性）
            val candidates = commonGroupIds.mapNotNull { groupId ->
                val memberCount = GroupMembers
                    .select { GroupMembers.groupId eq groupId }
                    .count()
                val group = findGroupById(groupId)
                if (group != null) Triple(group, memberCount, group.createdAt) else null
            }
            
            val selected = candidates
                .sortedWith(
                    compareBy<Triple<Group, Long, String>> { it.second }
                        .thenBy { it.third }
                )
                .firstOrNull()
                ?.first
            
            if (selected != null) {
                val selectedMemberCount = candidates.first { it.first.id == selected.id }.second
                logger.info("✅ 找到共同会话群组: {} ({}), 成员数: {}", selected.name, selected.id, selectedMemberCount)
            } else {
                logger.debug("ℹ️ 未找到 {} 和 {} 的有效共同群组", user1Id, user2Id)
            }
            
            selected
        }
    }
    
    /**
     * 创建联系人对话群组
     * 这是一个普通群组，后续可以添加更多成员
     */
    fun createContactGroup(user1Id: String, user2Id: String, groupName: String): Group? {
        return try {
            transaction {
                val groupId = UUID.randomUUID().toString()
                val invitationCode = generateInvitationCode()
                
                // 创建群组（user1 作为 host）
                Groups.insert {
                    it[id] = groupId
                    it[name] = groupName
                    it[Groups.invitationCode] = invitationCode
                    it[hostId] = user1Id
                }
                
                // 添加两个成员
                GroupMembers.insert {
                    it[GroupMembers.groupId] = groupId
                    it[userId] = user1Id
                    it[role] = MemberRole.HOST.name
                }
                
                GroupMembers.insert {
                    it[GroupMembers.groupId] = groupId
                    it[userId] = user2Id
                    it[role] = MemberRole.GUEST.name
                }
                
                // 创建聊天历史文件夹
                val sessionDir = java.io.File("chat_history/group_$groupId")
                sessionDir.mkdirs()
                logger.info("📁 群组聊天历史文件夹已创建: {}", sessionDir.path)

                findGroupById(groupId)
            }
        } catch (e: Exception) {
            logger.error("❌ 创建群组失败: {}", e.message)
            null
        }
    }

    // ==================== 退出/删除群组 ====================
    
    /**
     * 用户退出群组
     * 返回值：Pair<是否成功, 群组是否被删除>
     */
    fun leaveGroup(groupId: String, userId: String): Pair<Boolean, Boolean> {
        return try {
            transaction {
                // 检查用户是否在群组中
                val isMember = GroupMembers.select { 
                    (GroupMembers.groupId eq groupId) and (GroupMembers.userId eq userId)
                }.count() > 0
                
                if (!isMember) {
                    logger.warn("⚠️ 用户 {} 不在群组 {} 中", userId, groupId)
                    return@transaction Pair(false, false)
                }
                
                // 删除用户与群组的关联
                GroupMembers.deleteWhere { 
                    (GroupMembers.groupId eq groupId) and (GroupMembers.userId eq userId) 
                }
                logger.info("👋 用户 {} 已退出群组 {}", userId, groupId)
                
                // 检查群组剩余成员数
                val remainingMembers = GroupMembers.select { GroupMembers.groupId eq groupId }.count()
                
                if (remainingMembers == 0L) {
                    // 删除群组
                    deleteGroup(groupId)
                    Pair(true, true)
                } else {
                    Pair(true, false)
                }
            }
        } catch (e: Exception) {
            logger.error("❌ 退出群组失败: {}", e.message)
            Pair(false, false)
        }
    }
    
    /**
     * 删除群组及其聊天历史（内部使用）
     */
    private fun deleteGroupInternal(groupId: String): Boolean {
        return try {
            transaction {
                // 删除所有成员关联
                GroupMembers.deleteWhere { GroupMembers.groupId eq groupId }
                
                // 删除群组
                Groups.deleteWhere { Groups.id eq groupId }
                
                logger.info("🗑️ 群组 {} 已删除", groupId)
            }
            
            // 删除聊天历史目录
            val sessionDir = java.io.File("chat_history/group_$groupId")
            if (sessionDir.exists()) {
                // 先备份再删除，避免误删导致历史不可恢复
                ChatHistoryBackupManager.backupGroupHistory(
                    groupId = groupId,
                    backupType = ChatHistoryBackupManager.BackupType.GROUP_DELETED,
                    reason = "deleteGroupInternal before deleteRecursively"
                )
                sessionDir.deleteRecursively()
                logger.info("📁 群组聊天历史目录已删除: {}", sessionDir.path)
            }
            
            // 清理未读追踪数据
            UnreadRepository.cleanupGroup(groupId)
            
            true
        } catch (e: Exception) {
            logger.error("❌ 删除群组失败: {}", e.message)
            false
        }
    }

    /**
     * 删除群组及其聊天历史
     */
    fun deleteGroup(groupId: String): Boolean {
        return deleteGroupInternal(groupId)
    }
    
    /**
     * 群主删除群组（需要验证群主权限）
     * @param groupId 群组ID
     * @param userId 请求删除的用户ID
     * @return Triple<是否成功, 错误消息, 被移除的成员ID列表>
     */
    fun deleteGroupByHost(groupId: String, userId: String): Triple<Boolean, String, List<String>> {
        return try {
            transaction {
                // 查找群组
                val group = Groups.select { Groups.id eq groupId }.singleOrNull()
                
                if (group == null) {
                    return@transaction Triple(false, "群组不存在", emptyList())
                }
                
                // 验证是否是群主
                val hostId = group[Groups.hostId]
                if (hostId != userId) {
                    return@transaction Triple(false, "只有群主才能删除群组", emptyList())
                }
                
                // 获取所有成员ID（用于通知）
                val memberIds = GroupMembers
                    .select { GroupMembers.groupId eq groupId }
                    .map { it[GroupMembers.userId] }
                
                // 删除群组
                val success = deleteGroupInternal(groupId)
                
                if (success) {
                    Triple(true, "群组已删除", memberIds)
                } else {
                    Triple(false, "删除群组失败", emptyList())
                }
            }
        } catch (e: Exception) {
            logger.error("❌ 删除群组失败: {}", e.message)
            Triple(false, "删除群组失败: ${e.message}", emptyList())
        }
    }
    
    /**
     * 获取群组成员数量
     */
    fun getGroupMemberCount(groupId: String): Long {
        return transaction {
            GroupMembers.select { GroupMembers.groupId eq groupId }.count()
        }
    }
}


