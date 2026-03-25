package com.silk.android

import com.silk.shared.models.Message

/**
 * 解析聊天里「转发」消息的固定前缀格式（与 Harmony ForwardedMessageParse.ets 一致）
 */
data class ForwardedMessageParts(
    val sourceName: String,
    /** 单条转发时可为原发送者昵称；旧版无此行则为空 */
    val senderName: String,
    val body: String,
    val isBatch: Boolean,
)

data class BatchForwardItem(
    val senderName: String,
    val content: String,
)

fun parseBatchForwardMarkdownBody(body: String): List<BatchForwardItem> {
    val text = body.replace("\r", "").trim()
    if (text.isEmpty()) return emptyList()
    val segments = text.split(Regex("\n\n(?=###\\s+)"))
    val items = mutableListOf<BatchForwardItem>()
    for (segRaw in segments) {
        val seg = segRaw.trim()
        val m = Regex("^###\\s+([^\\n]+)\\n\\n([\\s\\S]*)\$").find(seg) ?: continue
        val name = m.groupValues[1].trim()
        val inner = m.groupValues.getOrElse(2) { "" }
        items.add(BatchForwardItem(senderName = name, content = inner))
    }
    return items
}

private data class ForwardedSenderStrip(
    val senderName: String,
    val body: String,
)

private fun stripOptionalSenderLine(rest: String): ForwardedSenderStrip {
    var senderName = ""
    var body = rest
    val senderM = Regex("^👤\\s*([^\\n]+)\\s*\\n+").find(body)
    if (senderM != null) {
        senderName = senderM.groupValues[1].trim()
        body = body.substring(senderM.range.last + 1)
    }
    body = body.trimStart()
    return ForwardedSenderStrip(senderName, body)
}

fun parseForwardedMessageContent(raw: String): ForwardedMessageParts? {
    val text = raw
    val singleHead = Regex("^📨 转发自【([^】]*)】:\\s*\\n").find(text)
    if (singleHead != null) {
        val sourceName = singleHead.groupValues[1].trim()
        val rest = text.substring(singleHead.range.last + 1)
        val stripped = stripOptionalSenderLine(rest)
        return ForwardedMessageParts(
            sourceName = sourceName,
            senderName = stripped.senderName,
            body = stripped.body,
            isBatch = false,
        )
    }
    val batchHead = Regex("^📨 批量转发自【([^】]*)】:\\s*\\n").find(text)
    if (batchHead != null) {
        val sourceName = batchHead.groupValues[1].trim()
        val rest = text.substring(batchHead.range.last + 1)
        val stripped = stripOptionalSenderLine(rest)
        return ForwardedMessageParts(
            sourceName = sourceName,
            senderName = stripped.senderName,
            body = stripped.body,
            isBatch = true,
        )
    }
    return null
}

/** 多条合并为 Markdown：每条以三级标题展示发送者（与 Harmony mergeSelectedMessagesMarkdown 一致） */
fun mergeSelectedMessagesMarkdown(selected: List<Message>): String {
    val sb = StringBuilder()
    for (item in selected) {
        val rawName = item.userName.ifBlank { "用户" }.replace(Regex("[\r\n]+"), " ").trim()
        val content = item.content
        if (sb.isNotEmpty()) sb.append("\n\n")
        sb.append("### ").append(rawName).append("\n\n").append(content)
    }
    return sb.toString()
}

fun sanitizeForwardInput(source: String): String {
    val cleaned = source.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
    val maxLength = 800
    return if (cleaned.length > maxLength) cleaned.take(maxLength) + "..." else cleaned
}

fun buildForwardPayloadForSingle(groupName: String, message: Message): String {
    val sender = message.userName.trim().ifBlank { "未知用户" }
    return sanitizeForwardInput("📨 转发自【$groupName】:\n👤 $sender\n\n${message.content}")
}

/** 多选转发始终带「批量转发」前缀（与 Harmony handleBatchForward 一致） */
fun buildForwardPayloadForBatch(groupName: String, messages: List<Message>): String {
    val sorted = messages.sortedBy { it.timestamp }
    if (sorted.isEmpty()) return ""
    val merged = mergeSelectedMessagesMarkdown(sorted)
    val batchHint =
        if (sorted.size == 1 && sorted.first().userName.isNotBlank()) sorted.first().userName.trim()
        else "共 ${sorted.size} 条"
    return sanitizeForwardInput("📨 批量转发自【$groupName】:\n👤 $batchHint\n\n$merged")
}
