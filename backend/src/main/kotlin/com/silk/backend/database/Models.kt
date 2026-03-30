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
 * 用户跨群待办事项（Harmony 专属 Silk 对话「待办」）
 */
@Serializable
data class UserTodoItemDto(
    val id: String,
    val title: String,
    val sourceGroupId: String? = null,
    val sourceGroupName: String? = null,
    /** none | alarm | calendar — 鸿蒙端可对 alarm/calendar 调系统能力 */
    val actionType: String? = null,
    /** 附加结构化提示，如 07:00、明天09:30（供客户端解析） */
    val actionDetail: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val done: Boolean = false,
    /** 首次成功执行的时间戳（毫秒），null = 从未执行 */
    val executedAt: Long? = null,
    /** 最近一次创建的系统提醒/日历事件 ID，用于重新执行时先删除旧的 */
    val reminderId: Long? = null,
    /** long_term_template | short_term_instance */
    val taskKind: String = "short_term_instance",
    /** yearly | monthly | workday | custom */
    val repeatRule: String? = null,
    /** 规则锚点：07:00 / MM-DD / dayOfMonth 等 */
    val repeatAnchor: String? = null,
    val activeFrom: Long? = null,
    val activeTo: Long? = null,
    /** 实例回指模板 ID（仅 short_term_instance 使用） */
    val templateId: String? = null,
    /** active | done | cancelled | deferred */
    val lifecycleState: String = "active",
    /** 关闭时间（完成/取消/延期） */
    val closedAt: Long? = null,
    /** 最近一次触发该任务的证据消息时间 */
    val lastEvidenceAt: Long? = null,
    /** 本次证据是否为明确指令（用于 cancelled 回流门槛） */
    val explicitIntent: Boolean = false,
    /** 模板实例化分桶键（如 2026-03-27） */
    val dateBucket: String? = null,
    /** 被重新激活次数（观测字段） */
    val reopenCount: Int = 0
)

@Serializable
data class UserTodosResponse(
    val success: Boolean,
    val message: String,
    val items: List<UserTodoItemDto> = emptyList()
)

@Serializable
data class UserTodoRefreshStatusResponse(
    val success: Boolean,
    val message: String,
    val running: Boolean = false,
    val lastStartedAt: Long? = null,
    val lastFinishedAt: Long? = null,
    val lastError: String? = null
)

@Serializable
data class UserTodoExtractionDiagnosticsResponse(
    val success: Boolean,
    val message: String,
    val userId: String,
    val updatedAt: Long,
    val source: String,
    val totalGroups: Int,
    val transcriptChars: Int,
    val llmDraftCount: Int,
    val heuristicDraftCount: Int,
    val forcedRecurringCount: Int,
    val finalDraftCount: Int,
    val matchedRecurringLines: List<String> = emptyList(),
    val note: String = ""
)

@Serializable
data class UpdateUserTodoRequest(
    val userId: String,
    val itemId: String,
    val done: Boolean? = null,
    val title: String? = null,
    val actionType: String? = null,
    val actionDetail: String? = null,
    val executedAt: Long? = null,
    val reminderId: Long? = null,
    val clearReminderId: Boolean = false,
    val taskKind: String? = null,
    val repeatRule: String? = null,
    val repeatAnchor: String? = null,
    val activeFrom: Long? = null,
    val activeTo: Long? = null,
    val templateId: String? = null,
    val lifecycleState: String? = null,
    val closedAt: Long? = null,
    val lastEvidenceAt: Long? = null,
    val explicitIntent: Boolean? = null,
    val dateBucket: String? = null,
    val reopenCount: Int? = null
)

@Serializable
data class DeleteUserTodoRequest(val userId: String, val itemId: String)

@Serializable
data class RefreshUserTodosRequest(
    val userId: String
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
