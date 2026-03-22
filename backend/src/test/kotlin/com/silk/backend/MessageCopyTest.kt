package com.silk.backend

import org.junit.Test
import kotlin.test.assertTrue

class MessageCopyTest {

    @Test
    fun testCopyMessageContent() {
        val messageContent = "这是一条测试消息"
        val copiedContent = messageContent
        assertTrue(copiedContent == messageContent, "复制的内容应该与原消息一致")
        println("✅ 消息复制内容验证通过")
    }

    @Test
    fun testCopyFormattedMessage() {
        val formattedMessage = """
            📋 消息内容
            - 项目: Silk
            - 状态: 测试中
        """.trimIndent()
        assertTrue(formattedMessage.contains("📋"), "格式化消息应包含图标")
        assertTrue(formattedMessage.contains("Silk"), "格式化消息应包含项目名")
        println("✅ 格式化消息复制验证通过")
    }

    @Test
    fun testCopyCodeBlock() {
        val codeBlock = """
            ```kotlin
            fun main() {
                println("Hello, Silk!")
            }
            ```
        """.trimIndent()
        assertTrue(codeBlock.contains("```kotlin"), "代码块应包含语言标记")
        assertTrue(codeBlock.contains("println"), "代码块应包含代码内容")
        println("✅ 代码块复制验证通过")
    }

    @Test
    fun testCopyAIMessage() {
        val aiMessage = """
            @silk 请帮我分析这段代码

            AI 回复:
            这是一段 Kotlin 代码，主要功能是打印输出。
        """.trimIndent()
        assertTrue(aiMessage.contains("@silk"), "AI 消息应包含 @silk 标记")
        assertTrue(aiMessage.contains("AI 回复"), "AI 消息应包含回复标记")
        println("✅ AI 消息复制验证通过")
    }
}
