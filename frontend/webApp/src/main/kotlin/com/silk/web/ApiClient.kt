package com.silk.web

import com.silk.shared.models.*
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.w3c.fetch.RequestInit
import kotlin.js.json

@Serializable
data class User(
    val id: String,
    val loginName: String,
    val fullName: String,
    val phoneNumber: String,
    val createdAt: String = ""
)

@Serializable
data class Group(
    val id: String,
    val name: String,
    val invitationCode: String,
    val hostId: String,
    val hostName: String = "",
    val createdAt: String = ""
)

@Serializable
data class UnreadCountResponse(
    val success: Boolean,
    val unreadCounts: Map<String, Int> = emptyMap()
)

@Serializable
data class AuthResponse(
    val success: Boolean,
    val message: String,
    val user: User? = null
)

@Serializable
data class GroupResponse(
    val success: Boolean,
    val message: String,
    val group: Group? = null,
    val groups: List<Group>? = null
)

// ==================== 联系人相关数据模型 ====================

@Serializable
enum class ContactRequestStatus {
    PENDING,
    ACCEPTED,
    REJECTED
}

@Serializable
data class Contact(
    val userId: String,
    val contactId: String,
    val contactName: String,
    val contactPhone: String,
    val createdAt: String = ""
)

@Serializable
data class ContactRequest(
    val id: String,
    val fromUserId: String,
    val fromUserName: String,
    val fromUserPhone: String,
    val toUserId: String,
    val status: ContactRequestStatus,
    val createdAt: String = ""
)

@Serializable
data class ContactResponse(
    val success: Boolean,
    val message: String,
    val contact: Contact? = null,
    val contacts: List<Contact>? = null,
    val pendingRequests: List<ContactRequest>? = null
)

@Serializable
data class UserSearchResult(
    val found: Boolean,
    val user: User? = null,
    val message: String = ""
)

@Serializable
data class PrivateChatResponse(
    val success: Boolean,
    val message: String,
    val group: Group? = null,
    val isNew: Boolean = false
)

// ==================== 群组成员相关数据模型 ====================

@Serializable
data class GroupMember(
    val id: String,
    val fullName: String,
    val phone: String = ""
)

@Serializable
data class GroupMembersResponse(
    val success: Boolean,
    val members: List<GroupMember>
)

@Serializable
data class AddMemberResponse(
    val success: Boolean,
    val message: String
)

@Serializable
data class LeaveGroupResponse(
    val success: Boolean,
    val message: String,
    val groupDeleted: Boolean = false
)

@Serializable
data class DeleteGroupResponse(
    val success: Boolean,
    val message: String
)

// ==================== 通用响应模型 ====================

@Serializable
data class SimpleResponse(
    val success: Boolean,
    val message: String
)

object ApiClient {
    private val BASE_URL: String
        get() {
            val hostname = window.location.hostname
            val protocol = window.location.protocol
            return "$protocol//$hostname:${BuildConfig.BACKEND_HTTP_PORT}"
        }
    private val jsonParser = Json { ignoreUnknownKeys = true }
    
    suspend fun register(
        loginName: String,
        fullName: String,
        phoneNumber: String,
        password: String
    ): AuthResponse {
        return try {
            val body = """{"loginName":"$loginName","fullName":"$fullName","phoneNumber":"$phoneNumber","password":"$password"}"""
            val response = post("/auth/register", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("注册失败:", e)
            AuthResponse(false, "网络错误: ${e.message}")
        }
    }
    
    suspend fun login(loginName: String, password: String): AuthResponse {
        return try {
            val body = """{"loginName":"$loginName","password":"$password"}"""
            val response = post("/auth/login", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("登录失败:", e)
            AuthResponse(false, "网络错误")
        }
    }
    
    suspend fun validateUser(userId: String): AuthResponse {
        return try {
            val response = get("/auth/validate/$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            AuthResponse(false, "验证失败")
        }
    }
    
    suspend fun getUserGroups(userId: String): GroupResponse {
        return try {
            val response = get("/groups/user/$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取群组失败:", e)
            GroupResponse(false, "网络错误")
        }
    }
    
    /**
     * 获取用户所有群组的未读消息数
     */
    suspend fun getUnreadCounts(userId: String): UnreadCountResponse {
        return try {
            val response = get("/api/unread/$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取未读数失败:", e)
            UnreadCountResponse(false)
        }
    }
    
    /**
     * 标记群组消息为已读
     */
    suspend fun markGroupAsRead(userId: String, groupId: String): Boolean {
        return try {
            val body = """{"userId":"$userId","groupId":"$groupId"}"""
            val response = post("/api/unread/mark-read", body)
            response.contains("\"success\":true") || response.contains("\"success\": true")
        } catch (e: Exception) {
            console.log("标记已读失败:", e)
            false
        }
    }
    
    suspend fun createGroup(userId: String, groupName: String): GroupResponse {
        return try {
            val body = """{"userId":"$userId","groupName":"$groupName"}"""
            val response = post("/groups/create", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("创建群组失败:", e)
            GroupResponse(false, "网络错误")
        }
    }
    
    suspend fun joinGroup(userId: String, invitationCode: String): GroupResponse {
        return try {
            val body = """{"userId":"$userId","invitationCode":"$invitationCode"}"""
            val response = post("/groups/join", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("加入群组失败:", e)
            GroupResponse(false, "网络错误")
        }
    }
    
    // ==================== 联系人相关 API ====================
    
    /**
     * 获取联系人列表（包含待处理请求）
     */
    suspend fun getContacts(userId: String): ContactResponse {
        return try {
            val response = get("/contacts/$userId")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取联系人失败:", e)
            ContactResponse(false, "网络错误")
        }
    }
    
    /**
     * 通过电话号码搜索用户
     */
    suspend fun searchUserByPhone(phoneNumber: String): UserSearchResult {
        return try {
            val response = get("/users/search?phone=$phoneNumber")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("搜索用户失败:", e)
            UserSearchResult(false, message = "网络错误")
        }
    }
    
    /**
     * 发送联系人请求（通过电话号码）
     */
    suspend fun sendContactRequest(fromUserId: String, toPhoneNumber: String): ContactResponse {
        return try {
            val body = """{"fromUserId":"$fromUserId","toPhoneNumber":"$toPhoneNumber"}"""
            val response = post("/contacts/request", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("发送联系人请求失败:", e)
            ContactResponse(false, "网络错误")
        }
    }
    
    /**
     * 发送联系人请求（通过用户ID）
     */
    suspend fun sendContactRequestById(fromUserId: String, toUserId: String): ContactResponse {
        return try {
            val body = """{"fromUserId":"$fromUserId","toUserId":"$toUserId"}"""
            val response = post("/contacts/request-by-id", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("发送联系人请求失败:", e)
            ContactResponse(false, "网络错误")
        }
    }
    
    /**
     * 处理联系人请求（接受/拒绝）
     */
    suspend fun handleContactRequest(requestId: String, userId: String, accept: Boolean): ContactResponse {
        return try {
            val body = """{"requestId":"$requestId","userId":"$userId","accept":$accept}"""
            val response = post("/contacts/handle-request", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("处理联系人请求失败:", e)
            ContactResponse(false, "网络错误")
        }
    }
    
    /**
     * 开始/获取私聊会话
     */
    suspend fun startPrivateChat(userId: String, contactId: String): PrivateChatResponse {
        return try {
            val body = """{"userId":"$userId","contactId":"$contactId"}"""
            val response = post("/contacts/private-chat", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取私聊会话失败:", e)
            PrivateChatResponse(false, "网络错误")
        }
    }
    
    /**
     * 开始/获取与 Silk AI 的专属私聊会话
     */
    suspend fun startSilkPrivateChat(userId: String): PrivateChatResponse {
        return try {
            val body = """{"userId":"$userId"}"""
            val response = post("/api/silk-private-chat", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取Silk私聊会话失败:", e)
            PrivateChatResponse(false, "网络错误")
        }
    }
    
    /**
     * 获取群组成员列表
     */
    suspend fun getGroupMembers(groupId: String): GroupMembersResponse {
        return try {
            val response = get("/groups/$groupId/members")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取群组成员失败:", e)
            GroupMembersResponse(false, emptyList())
        }
    }
    
    /**
     * 添加成员到群组
     */
    suspend fun addMemberToGroup(groupId: String, userId: String): AddMemberResponse {
        return try {
            val body = """{"userId":"$userId"}"""
            val response = post("/groups/$groupId/add-member", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("添加成员失败:", e)
            AddMemberResponse(false, "网络错误")
        }
    }
    
    /**
     * 退出群组
     */
    suspend fun leaveGroup(groupId: String, userId: String): LeaveGroupResponse {
        return try {
            val body = """{"userId":"$userId"}"""
            val response = post("/groups/$groupId/leave", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("退出群组失败:", e)
            LeaveGroupResponse(false, "网络错误")
        }
    }
    

    /**
     * 删除群组（仅群主可操作）
     */
    suspend fun deleteGroup(groupId: String, userId: String): DeleteGroupResponse {
        return try {
            val body = """{"userId":"$userId"}"""
            val response = delete("/groups/$groupId", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("删除群组失败:", e)
            DeleteGroupResponse(false, "网络错误")
        }
    }
    // ==================== 用户设置相关 API ====================
    
    /**
     * 获取用户设置
     */
    suspend fun getUserSettings(userId: String): UserSettingsResponse {
        return try {
            val response = get("/users/$userId/settings")
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("获取用户设置失败:", e)
            UserSettingsResponse(false, "网络错误")
        }
    }
    
    /**
     * 更新用户设置
     */
    suspend fun updateUserSettings(userId: String, language: Language, defaultAgentInstruction: String): UserSettingsResponse {
        return try {
            val request = UpdateUserSettingsRequest(userId, language, defaultAgentInstruction)
            val body = jsonParser.encodeToString(UpdateUserSettingsRequest.serializer(), request)
            val response = put("/users/$userId/settings", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("更新用户设置失败:", e)
            UserSettingsResponse(false, "网络错误")
        }
    }
    
    // ==================== 消息撤回相关 API ====================
    
    /**
     * 撤回消息
     * @param groupId 群组ID
     * @param messageId 要撤回的消息ID
     * @param userId 当前用户ID
     */
    suspend fun sendMessageToGroup(
        groupId: String,
        userId: String,
        userName: String,
        content: String
    ): Boolean {
        return try {
            val escapedContent = content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
            val body = """{"groupId":"$groupId","userId":"$userId","userName":"$userName","content":"$escapedContent"}"""
            val response = post("/api/messages/send", body)
            response.contains("\"success\":true") || response.contains("\"success\": true")
        } catch (e: Exception) {
            console.log("❌ 发送消息失败:", e)
            false
        }
    }

    suspend fun recallMessage(groupId: String, messageId: String, userId: String): SimpleResponse {
        return try {
            val body = """{"groupId":"$groupId","messageId":"$messageId","userId":"$userId"}"""
            val response = post("/api/messages/recall", body)
            jsonParser.decodeFromString(response)
        } catch (e: Exception) {
            console.log("撤回消息失败:", e)
            SimpleResponse(false, "网络错误")
        }
    }
    
    private suspend fun post(endpoint: String, jsonBody: String): String {
        val headers = org.w3c.fetch.Headers()
        headers.append("Content-Type", "application/json")
        
        val init = RequestInit(
            method = "POST",
            headers = headers,
            body = jsonBody
        )
        
        val response = window.fetch("$BASE_URL$endpoint", init).await()
        return response.text().await()
    }
    
    private suspend fun put(endpoint: String, jsonBody: String): String {
        val url = "$BASE_URL$endpoint"
        val response = window.fetch(url, RequestInit(
            method = "PUT",
            headers = json("Content-Type" to "application/json"),
            body = jsonBody
        )).await()
        
        if (!response.ok) {
            throw Exception("HTTP ${response.status}: ${response.statusText}")
        }
        
        return response.text().await()
    }
    
    private suspend fun delete(endpoint: String, jsonBody: String): String {
        val headers = org.w3c.fetch.Headers()
        headers.append("Content-Type", "application/json")
        
        val init = RequestInit(
            method = "DELETE",
            headers = headers,
            body = jsonBody
        )
        
        val response = window.fetch("$BASE_URL$endpoint", init).await()
        return response.text().await()
    }

    private suspend fun get(endpoint: String): String {
        val response = window.fetch("$BASE_URL$endpoint").await()
        return response.text().await()
    }
}

