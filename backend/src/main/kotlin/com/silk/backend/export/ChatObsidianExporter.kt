package com.silk.backend.export

import com.silk.backend.models.ChatHistory
import com.silk.backend.models.ChatHistoryEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ChatObsidianExporter {
    private val timelineFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun toMarkdown(
        groupId: String,
        groupName: String,
        sessionName: String,
        history: ChatHistory,
        exportedAtMs: Long = System.currentTimeMillis()
    ): String {
        val exportedAt = formatIso(exportedAtMs)
        val createdAt = history.messages.minOfOrNull { it.timestamp }?.let(::formatIso) ?: exportedAt
        val updatedAt = history.messages.maxOfOrNull { it.timestamp }?.let(::formatIso) ?: exportedAt

        val builder = StringBuilder()
        builder.appendLine("---")
        builder.appendLine("silk_export: true")
        builder.appendLine("group_id: \"$groupId\"")
        builder.appendLine("group_name: \"${escapeYaml(groupName)}\"")
        builder.appendLine("session_id: \"${escapeYaml(history.sessionId)}\"")
        builder.appendLine("session_name: \"${escapeYaml(sessionName)}\"")
        builder.appendLine("exported_at: \"$exportedAt\"")
        builder.appendLine("created_at: \"$createdAt\"")
        builder.appendLine("updated_at: \"$updatedAt\"")
        builder.appendLine("message_count: ${history.messages.size}")
        builder.appendLine("tags: [silk, chat, export]")
        builder.appendLine("---")
        builder.appendLine()
        builder.appendLine("# ${if (groupName.isBlank()) "Silk Chat Export" else groupName}")
        builder.appendLine()
        builder.appendLine("- Session: `$sessionName`")
        builder.appendLine("- Group ID: `$groupId`")
        builder.appendLine("- Messages: `${history.messages.size}`")
        builder.appendLine("- Exported At: `$exportedAt`")
        if (!history.rolePrompt.isNullOrBlank()) {
            builder.appendLine("- Role Prompt: `${history.rolePrompt!!.take(120).replace("\n", " ")}`")
        }
        builder.appendLine()
        builder.appendLine("## Timeline")
        builder.appendLine()

        if (history.messages.isEmpty()) {
            builder.appendLine("_No messages in this session._")
            return builder.toString()
        }

        history.messages.sortedBy { it.timestamp }.forEach { entry ->
            builder.appendLine("### [${formatTimeline(entry.timestamp)}] ${entry.senderName} (${entry.senderId})")
            builder.appendLine("- Message ID: `${entry.messageId}`")
            builder.appendLine("- Type: `${entry.messageType}`")
            builder.appendLine()
            builder.appendLine(formatMessageContent(entry))
            builder.appendLine()
        }

        return builder.toString()
    }

    private fun formatMessageContent(entry: ChatHistoryEntry): String {
        return when (entry.messageType.uppercase()) {
            "TEXT" -> if (entry.content.isBlank()) "_(empty text)_" else entry.content
            "SYSTEM", "JOIN", "LEAVE" -> "> [${entry.messageType}] ${entry.content}"
            else -> {
                if (entry.content.isBlank()) {
                    "> [${entry.messageType}]"
                } else {
                    "> [${entry.messageType}] ${entry.content}"
                }
            }
        }
    }

    private fun formatIso(timestampMs: Long): String =
        isoFormatter.format(Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()))

    private fun formatTimeline(timestampMs: Long): String = timelineFormatter.format(Instant.ofEpochMilli(timestampMs))

    private fun escapeYaml(input: String): String = input.replace("\\", "\\\\").replace("\"", "\\\"")
}

