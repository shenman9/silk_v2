package com.silk.shared.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Android平台时间格式化实现 - 使用上海时区 (Asia/Shanghai, UTC+8)
 */

private val shanghaiTimeZone = TimeZone.getTimeZone("Asia/Shanghai")

actual fun formatTimeHM(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    sdf.timeZone = shanghaiTimeZone
    return sdf.format(Date(timestamp))
}

actual fun formatTimeHMS(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    sdf.timeZone = shanghaiTimeZone
    return sdf.format(Date(timestamp))
}

actual fun formatDateTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    sdf.timeZone = shanghaiTimeZone
    return sdf.format(Date(timestamp))
}

actual fun formatDateShortTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    sdf.timeZone = shanghaiTimeZone
    return sdf.format(Date(timestamp))
}

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
