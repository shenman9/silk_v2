package com.silk.backend.database

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * 用户数据访问层
 */
object UserRepository {
    private val logger = LoggerFactory.getLogger(UserRepository::class.java)
    
    /**
     * 创建新用户
     */
    fun createUser(
        loginName: String,
        fullName: String,
        phoneNumber: String,
        passwordHash: String
    ): User? {
        return try {
            transaction {
                val userId = UUID.randomUUID().toString()
                
                Users.insert {
                    it[id] = userId
                    it[Users.loginName] = loginName
                    it[Users.fullName] = fullName
                    it[Users.phoneNumber] = phoneNumber
                    it[Users.passwordHash] = passwordHash
                }
                
                findUserById(userId)
            }
        } catch (e: Exception) {
            logger.error("❌ 创建用户失败: {}", e.message)
            null
        }
    }
    
    /**
     * 根据ID查找用户
     */
    fun findUserById(userId: String): User? {
        return transaction {
            Users.select { Users.id eq userId }
                .mapNotNull { rowToUser(it) }
                .singleOrNull()
        }
    }
    
    /**
     * 根据登录名查找用户
     */
    fun findUserByLoginName(loginName: String): User? {
        return transaction {
            Users.select { Users.loginName eq loginName }
                .mapNotNull { rowToUser(it) }
                .singleOrNull()
        }
    }
    
    /**
     * 根据手机号查找用户
     */
    fun findUserByPhoneNumber(phoneNumber: String): User? {
        return transaction {
            Users.select { Users.phoneNumber eq phoneNumber }
                .mapNotNull { rowToUser(it) }
                .singleOrNull()
        }
    }
    
    /**
     * 根据全名查找用户
     */
    fun findUserByFullName(fullName: String): User? {
        return transaction {
            Users.select { Users.fullName eq fullName }
                .mapNotNull { rowToUser(it) }
                .singleOrNull()
        }
    }
    
    /**
     * 获取用户的密码哈希（用于验证）
     */
    fun getUserPasswordHash(loginName: String): String? {
        return transaction {
            Users.select { Users.loginName eq loginName }
                .map { it[Users.passwordHash] }
                .singleOrNull()
        }
    }
    
    /**
     * 检查登录名是否已存在
     */
    fun loginNameExists(loginName: String): Boolean {
        return transaction {
            Users.select { Users.loginName eq loginName }
                .count() > 0
        }
    }
    
    /**
     * 检查手机号是否已存在
     */
    fun phoneNumberExists(phoneNumber: String): Boolean {
        return transaction {
            Users.select { Users.phoneNumber eq phoneNumber }
                .count() > 0
        }
    }
    
    /**
     * 将数据库行转换为User对象
     */
    private fun rowToUser(row: ResultRow): User {
        return User(
            id = row[Users.id],
            loginName = row[Users.loginName],
            fullName = row[Users.fullName],
            phoneNumber = row[Users.phoneNumber],
            createdAt = row[Users.createdAt].toString()
        )
    }
}

