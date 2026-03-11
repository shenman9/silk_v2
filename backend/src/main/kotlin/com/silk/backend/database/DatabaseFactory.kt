package com.silk.backend.database

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 数据库工厂：初始化数据库连接和创建表
 */
object DatabaseFactory {
    fun init() {
        // 使用 SQLite 数据库
        val database = Database.connect(
            url = "jdbc:sqlite:./silk_database.db",
            driver = "org.sqlite.JDBC"
        )
        
        transaction(database) {
            // 创建所有表
            SchemaUtils.create(Users, Groups, GroupMembers, Contacts, ContactRequests, UserSettingsTable)
        }
        
        println("✅ 数据库初始化完成")
    }
}

