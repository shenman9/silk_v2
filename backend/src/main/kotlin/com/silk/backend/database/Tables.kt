package com.silk.backend.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime

/**
 * Users 表：存储用户信息
 */
object Users : Table("users") {
    val id = varchar("id", 128).uniqueIndex() // 唯一用户ID
    val loginName = varchar("login_name", 128).uniqueIndex() // 登录名（唯一）
    val fullName = varchar("full_name", 256) // 全名
    val phoneNumber = varchar("phone_number", 20).uniqueIndex() // 手机号（唯一）
    val passwordHash = varchar("password_hash", 256) // 密码哈希
    val createdAt = datetime("created_at").default(LocalDateTime.now()) // 创建时间
    
    override val primaryKey = PrimaryKey(id)
}

/**
 * Groups 表：存储群组信息
 */
object Groups : Table("groups") {
    val id = varchar("id", 128).uniqueIndex() // 群组ID
    val name = varchar("name", 256) // 群组名称
    val invitationCode = varchar("invitation_code", 32).uniqueIndex() // 邀请码（唯一）
    val hostId = varchar("host_id", 128) // 群主ID（创建者）
    val createdAt = datetime("created_at").default(LocalDateTime.now()) // 创建时间
    
    override val primaryKey = PrimaryKey(id)
}

/**
 * GroupMembers 表：存储群组成员关系
 */
object GroupMembers : Table("group_members") {
    val groupId = varchar("group_id", 128) // 群组ID
    val userId = varchar("user_id", 128) // 用户ID
    val role = varchar("role", 20) // 角色：host 或 guest
    val joinedAt = datetime("joined_at").default(LocalDateTime.now()) // 加入时间
    
    override val primaryKey = PrimaryKey(groupId, userId)
}

/**
 * Contacts 表：存储联系人关系（双向）
 */
object Contacts : Table("contacts") {
    val userId = varchar("user_id", 128) // 用户ID
    val contactId = varchar("contact_id", 128) // 联系人ID
    val createdAt = datetime("created_at").default(LocalDateTime.now()) // 添加时间
    
    override val primaryKey = PrimaryKey(userId, contactId)
}

/**
 * ContactRequests 表：存储联系人请求
 */
object ContactRequests : Table("contact_requests") {
    val id = varchar("id", 128).uniqueIndex() // 请求ID
    val fromUserId = varchar("from_user_id", 128) // 发起请求的用户ID
    val toUserId = varchar("to_user_id", 128) // 接收请求的用户ID
    val status = varchar("status", 20) // pending, accepted, rejected
    val createdAt = datetime("created_at").default(LocalDateTime.now()) // 创建时间
    
    override val primaryKey = PrimaryKey(id)
}

/**
 * UserSettings 表：存储用户设置
 */
object UserSettingsTable : Table("user_settings") {
    val userId = varchar("user_id", 128).uniqueIndex() // 用户ID（唯一）
    val language = varchar("language", 20).default("CHINESE") // 语言偏好：ENGLISH 或 CHINESE
    val defaultAgentInstruction = text("default_agent_instruction").default("You are a helpful technical research assistant. ") // 默认代理指令
    val updatedAt = datetime("updated_at").default(LocalDateTime.now()) // 更新时间
    
    override val primaryKey = PrimaryKey(userId)
}
