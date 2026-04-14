package com.silk.backend.claudecode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamParserTest {

    @Test
    fun `format meta includes populated fields in stable order`() {
        val formatted = StreamParser.formatMeta(
            StreamParser.ResultMeta(
                costUsd = 0.05,
                durationMs = 12_300L,
                numTurns = 3,
                sessionId = "abc-123-def"
            )
        )

        assertEquals("⏱ 费用: $0.0500 | 耗时: 12.3s | 轮次: 3 | 会话: abc-123-...", formatted)
    }

    @Test
    fun `format meta skips empty fields`() {
        val formatted = StreamParser.formatMeta(StreamParser.ResultMeta())

        assertEquals("", formatted)
    }

    @Test
    fun `format meta truncates long session id for display`() {
        val formatted = StreamParser.formatMeta(
            StreamParser.ResultMeta(sessionId = "1234567890abcdef")
        )

        assertTrue(formatted.contains("会话: 12345678..."))
    }
}
