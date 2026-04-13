package com.silk.backend

import com.silk.backend.database.ContactRequests
import com.silk.backend.database.Contacts
import com.silk.backend.database.GroupMembers
import com.silk.backend.database.Groups
import com.silk.backend.database.UserSettingsTable
import com.silk.backend.database.Users
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.io.path.createTempDirectory

internal class TestWorkspace : AutoCloseable {
    private val rootDir = createTempDirectory("silk-backend-test").toFile()
    private val dbFile = File(rootDir, "silk-test.db")
    val chatHistoryDir = File(rootDir, "chat_history")
    val userTodoDir = File(chatHistoryDir, "user_todos")

    init {
        System.setProperty("silk.chatHistoryDir", chatHistoryDir.absolutePath)
        System.setProperty("silk.userTodoBaseDir", userTodoDir.absolutePath)

        val database = Database.connect(
            url = "jdbc:sqlite:${dbFile.absolutePath}",
            driver = "org.sqlite.JDBC"
        )
        transaction(database) {
            SchemaUtils.create(Users, Groups, GroupMembers, Contacts, ContactRequests, UserSettingsTable)
        }
    }

    override fun close() {
        System.clearProperty("silk.chatHistoryDir")
        System.clearProperty("silk.userTodoBaseDir")
        rootDir.deleteRecursively()
    }
}
