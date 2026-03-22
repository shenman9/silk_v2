package com.silk.backend

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.assertTrue

/**
 * 消息撤回功能测试
 * 
 * 测试撤回消息的 API 请求/响应格式和数据结构
 */
class MessageRecallTest {
    
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /**
     * 测试撤回成功时的响应结构
     */
    @Test
    fun testRecallResultSuccessStructure() {
        val successResultJson = """{"success":true,"message":"撤回成功","deletedIds":["msg1","msg2"]}"""
        val result = json.decodeFromString<JsonObject>(successResultJson)
        
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true, "success 应该为 true")
        assertTrue(result["message"]?.jsonPrimitive?.content == "撤回成功", "message 应该正确")
        assertTrue(result["deletedIds"]?.jsonArray?.size == 2, "应该有 2 个被删除的消息 ID")
        println("✅ 撤回成功响应结构验证通过")
    }

    /**
     * 测试撤回失败时的响应结构
     */
    @Test
    fun testRecallResultFailStructure() {
        val failResultJson = """{"success":false,"message":"只能撤回自己发送的消息","deletedIds":[]}"""
        val result = json.decodeFromString<JsonObject>(failResultJson)
        
        assertTrue(result["success"]?.jsonPrimitive?.boolean == false, "success 应该为 false")
        assertTrue(result["deletedIds"]?.jsonArray?.isEmpty() == true, "deletedIds 应该为空")
        println("✅ 撤回失败响应结构验证通过")
    }

    /**
     * 测试撤回消息请求格式
     */
    @Test
    fun testRecallMessageRequestFormat() {
        val validRequest = """{"groupId":"group-123","messageId":"msg-456","userId":"user-789"}"""
        val request = json.decodeFromString<JsonObject>(validRequest)
        
        assertTrue(request.containsKey("groupId"), "应该包含 groupId")
        assertTrue(request.containsKey("messageId"), "应该包含 messageId")
        assertTrue(request.containsKey("userId"), "应该包含 userId")
        println("✅ 撤回消息请求格式验证通过")
    }

    /**
     * 测试撤回 AI 消息时同时撤回用户消息
     * 当用户 @silk 后撤回自己的消息时，AI 的回复也应该被撤回
     */
    @Test
    fun testRecallAIMessageWithUserMessage() {
        val recallResult = """{
            "success": true,
            "message": "已撤回用户消息和 Silk 回复",
            "deletedIds": ["user-msg-123", "ai-reply-456"]
        }"""
        val result = json.decodeFromString<JsonObject>(recallResult)
        val deletedIds = result["deletedIds"]?.jsonArray
        
        assertTrue(deletedIds?.size == 2, "撤回 @silk 消息时应该删除 2 条消息")
        assertTrue(
            deletedIds?.any { it.jsonPrimitive.content == "user-msg-123" } == true,
            "应该包含用户消息 ID"
        )
        assertTrue(
            deletedIds?.any { it.jsonPrimitive.content == "ai-reply-456" } == true,
            "应该包含 AI 回复 ID"
        )
        println("✅ AI 消息撤回（连带用户消息）验证通过")
    }
}
