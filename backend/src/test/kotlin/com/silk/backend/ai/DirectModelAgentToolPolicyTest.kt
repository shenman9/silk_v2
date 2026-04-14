package com.silk.backend.ai

import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectModelAgentToolPolicyTest {
    @BeforeTest
    fun resetToolPolicies() {
        ToolPolicyManager.resetForTest()
    }

    @Test
    fun `disabled tools are hidden from available tool list`() {
        val agent = DirectModelAgent(sessionId = "group_accessible")

        val toolNames = agent.availableToolNamesForTest()

        assertFalse("execute_command" in toolNames)
        assertTrue("read_file" in toolNames)
        assertTrue("search_files" in toolNames)
        assertTrue("search_context" in toolNames)
    }

    @Test
    fun `read_file is denied when caller has no accessible sessions`() = runBlocking {
        val agent = DirectModelAgent(sessionId = "group_accessible")

        val result = agent.executeToolForTest(
            toolName = "read_file",
            arguments = """{"path":"chat_history/group_accessible/missing.txt"}""",
            requestUserId = "reader",
            accessibleSessionIds = emptyList()
        )

        assertEquals("⚠️ 权限不足：无法访问该会话的相关内容。", result)
        val audit = ToolPolicyManager.getAuditLog(limit = 1).single()
        assertEquals("read_file", audit.toolName)
        assertEquals("DENIED", audit.result)
        assertEquals("group_accessible", audit.sessionId)
        assertEquals("reader", audit.userId)
    }

    @Test
    fun `read_file cross session path is denied and audited as denied`() = runBlocking {
        val agent = DirectModelAgent(sessionId = "group_accessible")

        val result = agent.executeToolForTest(
            toolName = "read_file",
            arguments = """{"path":"chat_history/group_other/private.txt"}""",
            requestUserId = "reader",
            accessibleSessionIds = listOf("group_accessible")
        )

        assertEquals("⛔ 路径不在当前用户可访问的会话目录内", result)
        val audit = ToolPolicyManager.getAuditLog(limit = 1).single()
        assertEquals("read_file", audit.toolName)
        assertEquals("DENIED", audit.result)
    }

    @Test
    fun `search_files inside accessible session keeps sandboxed audit`() = runBlocking {
        val agent = DirectModelAgent(sessionId = "group_accessible")

        val result = agent.executeToolForTest(
            toolName = "search_files",
            arguments = """{"query":"needle","path":"chat_history/group_accessible"}""",
            requestUserId = "reader",
            accessibleSessionIds = listOf("group_accessible")
        )

        assertEquals("未找到匹配的文件", result)
        val audit = ToolPolicyManager.getAuditLog(limit = 1).single()
        assertEquals("search_files", audit.toolName)
        assertEquals("SANDBOXED", audit.result)
    }
}
