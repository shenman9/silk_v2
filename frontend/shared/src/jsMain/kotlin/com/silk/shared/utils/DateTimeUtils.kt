package com.silk.shared.utils

import kotlin.js.Date

/**
 * JS平台日期时间工具实现 - 使用上海时区 (UTC+8)
 */

/**
 * 格式化时间戳为 HH:mm 格式 (上海时区)
 */
actual fun formatTimeHM(timestamp: Long): String {
    val date = Date(timestamp.toDouble())
    // 上海时区偏移：+8小时
    val shanghaiTime = Date(timestamp.toDouble() + 8 * 60 * 60 * 1000)
    val hours = shanghaiTime.getUTCHours()
    val minutes = shanghaiTime.getUTCMinutes()
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
}

/**
 * 格式化时间戳为 HH:mm:ss 格式 (上海时区)
 */
actual fun formatTimeHMS(timestamp: Long): String {
    val shanghaiTime = Date(timestamp.toDouble() + 8 * 60 * 60 * 1000)
    val hours = shanghaiTime.getUTCHours()
    val minutes = shanghaiTime.getUTCMinutes()
    val seconds = shanghaiTime.getUTCSeconds()
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}

/**
 * 格式化时间戳为 yyyy-MM-dd HH:mm 格式 (上海时区)
 */
actual fun formatDateTime(timestamp: Long): String {
    val shanghaiTime = Date(timestamp.toDouble() + 8 * 60 * 60 * 1000)
    val year = shanghaiTime.getUTCFullYear()
    val month = (shanghaiTime.getUTCMonth() + 1) // JS月份从0开始
    val day = shanghaiTime.getUTCDate()
    val hours = shanghaiTime.getUTCHours()
    val minutes = shanghaiTime.getUTCMinutes()
    return "${year}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')} ${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
}

/**
 * 格式化时间戳为 MM-dd HH:mm 格式 (上海时区)
 */
actual fun formatDateShortTime(timestamp: Long): String {
    val shanghaiTime = Date(timestamp.toDouble() + 8 * 60 * 60 * 1000)
    val month = (shanghaiTime.getUTCMonth() + 1) // JS月份从0开始
    val day = shanghaiTime.getUTCDate()
    val hours = shanghaiTime.getUTCHours()
    val minutes = shanghaiTime.getUTCMinutes()
    return "${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')} ${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
}

/**
 * 获取当前时间戳（毫秒）
 */
actual fun currentTimeMillis(): Long = Date.now().toLong()
