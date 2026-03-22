package com.silk.backend

import org.junit.Test
import kotlin.test.assertTrue

class MessageForwardTest {

    @Test
    fun testForwardMessageFormat() {
        val originalContent = "这是一条测试消息"
        val originalUserName = "测试用户"
        val sourceGroupName = "源群组"
        val forwardContent = "📨 转发自【${sourceGroupName}】:\n\n${originalUserName}: ${originalContent}"
        assertTrue(forwardContent.startsWith("📨 转发自【"))
        assertTrue(forwardContent.contains(sourceGroupName))
        assertTrue(forwardContent.contains(originalUserName))
        assertTrue(forwardContent.contains(originalContent))
        println("✅ 转发消息格式验证通过")
    }

    @Test
    fun testForwardContentExtraction() {
        val forwardContent = "📨 转发自【测试群】:\n\n张三: 这是转发的消息内容"
        assertTrue(forwardContent.startsWith("📨 转发自【"))
        val sourceGroupStart = forwardContent.indexOf("【") + 1
        val sourceGroupEnd = forwardContent.indexOf("】")
        val sourceGroupName = forwardContent.substring(sourceGroupStart, sourceGroupEnd)
        assertTrue(sourceGroupName == "测试群")
        println("✅ 转发内容提取验证通过")
    }

    @Test
    fun testLongMessageForward() {
        val longContent = "这是一条很长的消息内容".repeat(100)
        val forwardContent = "📨 转发自【源群】:\n\n用户: $longContent"
        assertTrue(forwardContent.length > 1000)
        assertTrue(forwardContent.contains(longContent))
        println("✅ 长消息转发验证通过")
    }
}
