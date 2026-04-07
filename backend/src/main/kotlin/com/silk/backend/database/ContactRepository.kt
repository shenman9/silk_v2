package com.silk.backend.database

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * 联系人数据访问层
 */
object ContactRepository {
    private val logger = LoggerFactory.getLogger(ContactRepository::class.java)
    
    /**
     * 获取用户的所有联系人
     */
    fun getContacts(userId: String): List<Contact> {
        return transaction {
            Contacts.select { Contacts.userId eq userId }
                .mapNotNull { row ->
                    val contactId = row[Contacts.contactId]
                    val contactUser = UserRepository.findUserById(contactId)
                    contactUser?.let {
                        Contact(
                            userId = userId,
                            contactId = contactId,
                            contactName = it.fullName,
                            contactPhone = it.phoneNumber,
                            createdAt = row[Contacts.createdAt].toString()
                        )
                    }
                }
        }
    }
    
    /**
     * 检查两个用户是否已经是联系人
     */
    fun areContacts(userId: String, contactId: String): Boolean {
        return transaction {
            Contacts.select { 
                (Contacts.userId eq userId) and (Contacts.contactId eq contactId)
            }.count() > 0
        }
    }
    
    /**
     * 添加联系人（双向添加）
     */
    fun addContact(userId: String, contactId: String): Boolean {
        return try {
            transaction {
                // 添加 userId -> contactId
                Contacts.insert {
                    it[Contacts.userId] = userId
                    it[Contacts.contactId] = contactId
                }
                // 添加 contactId -> userId（双向）
                Contacts.insert {
                    it[Contacts.userId] = contactId
                    it[Contacts.contactId] = userId
                }
            }
            true
        } catch (e: Exception) {
            logger.error("❌ 添加联系人失败: {}", e.message)
            false
        }
    }
    
    /**
     * 删除联系人（双向删除）
     */
    fun removeContact(userId: String, contactId: String): Boolean {
        return try {
            transaction {
                Contacts.deleteWhere {
                    ((Contacts.userId eq userId) and (Contacts.contactId eq contactId)) or
                    ((Contacts.userId eq contactId) and (Contacts.contactId eq userId))
                }
            }
            true
        } catch (e: Exception) {
            logger.error("❌ 删除联系人失败: {}", e.message)
            false
        }
    }
    
    // ==================== 联系人请求相关 ====================
    
    /**
     * 创建联系人请求
     */
    fun createContactRequest(fromUserId: String, toUserId: String): ContactRequest? {
        return try {
            transaction {
                // 检查是否已存在待处理的请求
                val existing = ContactRequests.select {
                    (ContactRequests.fromUserId eq fromUserId) and 
                    (ContactRequests.toUserId eq toUserId) and
                    (ContactRequests.status eq ContactRequestStatus.PENDING.name)
                }.singleOrNull()
                
                if (existing != null) {
                    return@transaction null // 已存在待处理请求
                }
                
                val requestId = UUID.randomUUID().toString()
                ContactRequests.insert {
                    it[id] = requestId
                    it[ContactRequests.fromUserId] = fromUserId
                    it[ContactRequests.toUserId] = toUserId
                    it[status] = ContactRequestStatus.PENDING.name
                }
                
                val fromUser = UserRepository.findUserById(fromUserId)
                ContactRequest(
                    id = requestId,
                    fromUserId = fromUserId,
                    fromUserName = fromUser?.fullName ?: "",
                    fromUserPhone = fromUser?.phoneNumber ?: "",
                    toUserId = toUserId,
                    status = ContactRequestStatus.PENDING
                )
            }
        } catch (e: Exception) {
            logger.error("❌ 创建联系人请求失败: {}", e.message)
            null
        }
    }
    
    /**
     * 获取用户收到的待处理联系人请求
     */
    fun getPendingRequests(userId: String): List<ContactRequest> {
        return transaction {
            ContactRequests.select {
                (ContactRequests.toUserId eq userId) and 
                (ContactRequests.status eq ContactRequestStatus.PENDING.name)
            }.mapNotNull { row ->
                val fromUserId = row[ContactRequests.fromUserId]
                val fromUser = UserRepository.findUserById(fromUserId)
                ContactRequest(
                    id = row[ContactRequests.id],
                    fromUserId = fromUserId,
                    fromUserName = fromUser?.fullName ?: "",
                    fromUserPhone = fromUser?.phoneNumber ?: "",
                    toUserId = userId,
                    status = ContactRequestStatus.PENDING,
                    createdAt = row[ContactRequests.createdAt].toString()
                )
            }
        }
    }
    
    /**
     * 处理联系人请求（接受或拒绝）
     */
    fun handleContactRequest(requestId: String, accept: Boolean): Boolean {
        return try {
            transaction {
                val request = ContactRequests.select {
                    ContactRequests.id eq requestId
                }.singleOrNull() ?: return@transaction false
                
                val fromUserId = request[ContactRequests.fromUserId]
                val toUserId = request[ContactRequests.toUserId]
                
                // 更新请求状态
                ContactRequests.update({ ContactRequests.id eq requestId }) {
                    it[status] = if (accept) ContactRequestStatus.ACCEPTED.name else ContactRequestStatus.REJECTED.name
                }
                
                // 如果接受，添加联系人关系
                if (accept) {
                    addContact(fromUserId, toUserId)
                }
                
                true
            }
        } catch (e: Exception) {
            logger.error("❌ 处理联系人请求失败: {}", e.message)
            false
        }
    }
    
    /**
     * 获取请求详情
     */
    fun getRequestById(requestId: String): ContactRequest? {
        return transaction {
            ContactRequests.select { ContactRequests.id eq requestId }
                .singleOrNull()?.let { row ->
                    val fromUserId = row[ContactRequests.fromUserId]
                    val fromUser = UserRepository.findUserById(fromUserId)
                    ContactRequest(
                        id = row[ContactRequests.id],
                        fromUserId = fromUserId,
                        fromUserName = fromUser?.fullName ?: "",
                        fromUserPhone = fromUser?.phoneNumber ?: "",
                        toUserId = row[ContactRequests.toUserId],
                        status = ContactRequestStatus.valueOf(row[ContactRequests.status]),
                        createdAt = row[ContactRequests.createdAt].toString()
                    )
                }
        }
    }
}

