package com.silk.backend.database

import kotlinx.serialization.Serializable
import java.time.LocalDateTime

/**
 * 用户数据模型
 */
@Serializable
data class User(
    val id: String,
    val loginName: String,
    val fullName: String,
    val phoneNumber: String,
    val createdAt: String = LocalDateTime.now().toString()
)

/**
 * 群组数据模型
 */
@Serializable
data class Group(
    val id: String,
    val name: String,
    val invitationCode: String,
    val hostId: String,
    val hostName: String = "", // 群主名称（用于显示）
    val createdAt: String = LocalDateTime.now().toString()
)

/**
 * 群组成员数据模型
 */
@Serializable
data class GroupMember(
    val groupId: String,
    val userId: String,
    val userName: String = "", // 用户名称（用于显示）
    val role: MemberRole,
    val joinedAt: String = LocalDateTime.now().toString()
)

/**
 * 用户已读状态追踪
 */
@Serializable
data class UserReadStatus(
    val groupId: String,
    val userId: String,
    val lastReadTimestamp: Long = 0L  // 最后一次阅读的消息时间戳
)

/**
 * 群组未读数响应
 */
@Serializable
data class UnreadCountResponse(
    val success: Boolean,
    val unreadCounts: Map<String, Int> = emptyMap()  // groupId -> unreadCount
)

/**
 * 标记已读请求
 */
@Serializable
data class MarkReadRequest(
    val userId: String,
    val groupId: String
)

/**
 * 成员角色枚举
 */
@Serializable
enum class MemberRole {
    HOST,  // 群主（管理员）
    GUEST  // 普通成员
}

/**
 * 注册请求数据
 */
@Serializable
data class RegisterRequest(
    val loginName: String,
    val fullName: String,
    val phoneNumber: String,
    val password: String
)

/**
 * 登录请求数据
 */
@Serializable
data class LoginRequest(
    val loginName: String,
    val password: String
)

/**
 * 认证响应数据
 */
@Serializable
data class AuthResponse(
    val success: Boolean,
    val message: String,
    val user: User? = null
)

/**
 * 创建群组请求数据
 */
@Serializable
data class CreateGroupRequest(
    val userId: String,
    val groupName: String // 用户输入的群组名称
)

/**
 * 加入群组请求数据
 */
@Serializable
data class JoinGroupRequest(
    val userId: String,
    val invitationCode: String
)

/**
 * 添加成员到群组请求数据
 */
@Serializable
data class AddMemberRequest(
    val userId: String
)

/**
 * 退出群组请求数据
 */
@Serializable
data class LeaveGroupRequest(
    val userId: String
)

/**
 * 删除群组请求数据（群主删除群组）
 */
@Serializable
data class DeleteGroupRequest(
    val userId: String  // 必须是群主
)

/**
 * 群组响应数据
 */
@Serializable
data class GroupResponse(
    val success: Boolean,
    val message: String,
    val group: Group? = null,
    val groups: List<Group>? = null
)

// ==================== 联系人相关模型 ====================

/**
 * 联系人状态枚举
 */
@Serializable
enum class ContactRequestStatus {
    PENDING,   // 待处理
    ACCEPTED,  // 已接受
    REJECTED   // 已拒绝
}

/**
 * 联系人数据模型
 */
@Serializable
data class Contact(
    val userId: String,
    val contactId: String,
    val contactName: String,      // 联系人姓名
    val contactPhone: String,     // 联系人电话
    val createdAt: String = LocalDateTime.now().toString()
)

/**
 * 联系人请求数据模型
 */
@Serializable
data class ContactRequest(
    val id: String,
    val fromUserId: String,
    val fromUserName: String,     // 发起人姓名
    val fromUserPhone: String,    // 发起人电话
    val toUserId: String,
    val status: ContactRequestStatus,
    val createdAt: String = LocalDateTime.now().toString()
)

/**
 * 发送联系人请求
 */
@Serializable
data class SendContactRequestData(
    val fromUserId: String,
    val toPhoneNumber: String  // 通过电话号码查找用户
)

/**
 * 通过用户ID发送联系人请求
 */
@Serializable
data class SendContactRequestByIdData(
    val fromUserId: String,
    val toUserId: String
)

/**
 * 处理联系人请求（接受/拒绝）
 */
@Serializable
data class HandleContactRequestData(
    val requestId: String,
    val userId: String,
    val accept: Boolean
)

/**
 * 联系人响应
 */
@Serializable
data class ContactResponse(
    val success: Boolean,
    val message: String,
    val contact: Contact? = null,
    val contacts: List<Contact>? = null,
    val pendingRequests: List<ContactRequest>? = null
)

/**
 * 用户搜索结果
 */
@Serializable
data class UserSearchResult(
    val found: Boolean,
    val user: User? = null,
    val message: String = ""
)

/**
 * 开始/获取私聊会话请求
 */
@Serializable
data class StartPrivateChatRequest(
    val userId: String,
    val contactId: String
)

/**
 * 开始与 Silk AI 直接对话请求
 */
@Serializable
data class StartSilkPrivateChatRequest(
    val userId: String
)

/**
 * 发送消息请求（用于转发等功能）
 */
@Serializable
data class SendMessageRequest(
    val groupId: String,
    val userId: String,
    val userName: String,
    val content: String
)

/**
 * 私聊会话响应
 */
@Serializable
data class PrivateChatResponse(
    val success: Boolean,
    val message: String,
    val group: Group? = null,
    val isNew: Boolean = false  // 是否是新创建的会话
)

/**
 * 简单响应（通用）
 */
@Serializable
data class SimpleResponse(
    val success: Boolean,
    val message: String
)

/**
 * 群组成员API响应数据（前端使用）
 */
@Serializable
data class GroupMemberApi(
    val id: String,       // userId
    val fullName: String,
    val phone: String = ""
)

/**
 * 群组成员列表响应
 */
@Serializable
data class GroupMembersResponse(
    val success: Boolean,
    val members: List<GroupMemberApi>
)

/**
 * 退出群组响应
 */
@Serializable
data class LeaveGroupResponse(
    val success: Boolean,
    val message: String,
    val groupDeleted: Boolean = false
)

// ==================== 用户设置相关模型 ====================

/**
 * 语言枚举
 */
@Serializable
enum class Language {
    ENGLISH,
    CHINESE
}

/**
 * 用户设置数据模型
 */
@Serializable
data class UserSettings(
    val language: Language = Language.CHINESE,
    val defaultAgentInstruction: String = "You are a helpful technical research assistant. "
)

/**
 * 更新用户设置请求
 */
@Serializable
data class UpdateUserSettingsRequest(
    val userId: String,
    val language: Language,
    val defaultAgentInstruction: String
)

/**
 * 用户设置响应
 */
@Serializable
data class UserSettingsResponse(
    val success: Boolean,
    val message: String,
    val settings: UserSettings? = null
)

// ==================== 消息撤回相关模型 ====================

/**
 * 撤回消息请求
 */
@Serializable
data class RecallMessageRequest(
    val groupId: String,
    val messageId: String,
    val userId: String  // 撤回操作者
)

/**
 * 撤回消息响应
 */
@Serializable
data class RecallMessageResponse(
    val success: Boolean,
    val message: String,
    val recalledMessageIds: List<String> = emptyList()  // 被撤回的消息ID列表（可能包含用户消息和AI回复）
)
