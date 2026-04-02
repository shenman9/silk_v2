// backend/src/test/kotlin/com/silk/backend/claudecode/StreamParserTest.kt
package com.silk.backend.claudecode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamParserTest {

    @Test
    fun `parse assistant text block`() {
        val json = """{"type":"assistant","message":{"content":[{"type":"text","text":"Hello world"}]}}"""
        val result = StreamParser.parseLine(json)
        assertEquals("Hello world", result.textChunk)
        assertEquals(emptyList(), result.toolLogs)
        assertNull(result.meta)
    }

    @Test
    fun `parse assistant tool_use block`() {
        val json = """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"tu_1","name":"Read","input":{"file_path":"/src/main.kt"}}]}}"""
        val result = StreamParser.parseLine(json)
        assertEquals("", result.textChunk)
        assertEquals(1, result.toolLogs.size)
        assertEquals("📖 Read `/src/main.kt`", result.toolLogs[0].line)
        assertEquals("tu_1", result.toolLogs[0].toolUseId)
    }

    @Test
    fun `parse user tool_result success`() {
        val json = """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tu_1","content":"ok"}]}}"""
        val result = StreamParser.parseLine(json)
        assertEquals(1, result.toolResults.size)
        assertEquals("tu_1", result.toolResults[0].toolUseId)
        assertEquals(false, result.toolResults[0].isError)
    }

    @Test
    fun `parse user tool_result error`() {
        val json = """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tu_2","is_error":true,"content":"File not found"}]}}"""
        val result = StreamParser.parseLine(json)
        assertEquals(1, result.toolResults.size)
        assertEquals(true, result.toolResults[0].isError)
        assertEquals("File not found", result.toolResults[0].summary)
    }

    @Test
    fun `parse result event`() {
        val json = """{"type":"result","cost_usd":0.05,"duration_ms":12300,"num_turns":3,"session_id":"abc-123-def"}"""
        val result = StreamParser.parseLine(json)
        val meta = result.meta
        assert(meta != null) { "meta should not be null" }
        assertEquals(0.05, meta!!.costUsd, 0.001)
        assertEquals(12300L, meta.durationMs)
        assertEquals(3, meta.numTurns)
        assertEquals("abc-123-def", meta.sessionId)
    }

    @Test
    fun `parse result with fallback text`() {
        val json = """{"type":"result","result":"Done","cost_usd":0.01,"duration_ms":1000,"num_turns":1,"session_id":"s1"}"""
        val result = StreamParser.parseLine(json)
        assertEquals("Done", result.textChunk)
    }

    @Test
    fun `parse system compact event`() {
        val json = """{"type":"system","subtype":"compact_boundary","compact_metadata":{"pre_tokens":50000}}"""
        val result = StreamParser.parseLine(json)
        assert(result.textChunk.contains("50,000"))
    }

    @Test
    fun `parse invalid json returns empty`() {
        val result = StreamParser.parseLine("not json at all")
        assertEquals("", result.textChunk)
        assertEquals(emptyList(), result.toolLogs)
        assertNull(result.meta)
    }

    @Test
    fun `parse thinking block produces log`() {
        val json = """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"let me think"}]}}"""
        val result = StreamParser.parseLine(json)
        assertEquals(1, result.toolLogs.size)
        assert(result.toolLogs[0].line.contains("思考"))
    }
}
