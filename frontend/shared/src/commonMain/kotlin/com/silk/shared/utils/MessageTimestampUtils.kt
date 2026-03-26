package com.silk.shared.utils

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val shanghaiTimeZone by lazy { TimeZone.of(DateTimeUtils.TIMEZONE_ID) }

/**
 * 聊天消息时间展示：
 * - 同一天：只显示时间
 * - 跨天：显示日期 + 时间
 *
 * @param timestamp 消息时间戳（毫秒）
 * @param referenceTimestamp 作为“今天”判断基准的时间戳（毫秒），默认当前时间
 * @param includeSeconds 是否保留秒
 */
fun formatMessageTimestamp(
    timestamp: Long,
    referenceTimestamp: Long = currentTimeMillis(),
    includeSeconds: Boolean = true
): String {
    if (timestamp <= 0L) return ""

    val messageDateTime = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(shanghaiTimeZone)
    val referenceDate = Instant.fromEpochMilliseconds(referenceTimestamp).toLocalDateTime(shanghaiTimeZone).date

    val timePart = buildString {
        append(messageDateTime.hour.twoDigits())
        append(':')
        append(messageDateTime.minute.twoDigits())
        if (includeSeconds) {
            append(':')
            append(messageDateTime.second.twoDigits())
        }
    }

    if (messageDateTime.date == referenceDate) {
        return timePart
    }

    val datePart = if (messageDateTime.year == referenceDate.year) {
        "${messageDateTime.monthNumber.twoDigits()}-${messageDateTime.dayOfMonth.twoDigits()}"
    } else {
        "${messageDateTime.year}-${messageDateTime.monthNumber.twoDigits()}-${messageDateTime.dayOfMonth.twoDigits()}"
    }

    return "$datePart $timePart"
}

private fun Int.twoDigits(): String = toString().padStart(2, '0')
