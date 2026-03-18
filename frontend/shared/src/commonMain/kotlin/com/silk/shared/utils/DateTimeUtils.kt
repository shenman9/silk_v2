package com.silk.shared.utils

/**
 * 日期时间工具类 - 统一使用中国上海时区 (Asia/Shanghai, UTC+8)
 * 
 * 支持平台：
 * - Android (JVM)
 * - Web (JS)
 * - Desktop (JVM)
 * - iOS
 */
object DateTimeUtils {
    /**
     * 时区偏移量（上海时区 UTC+8）
     */
    const val TIMEZONE_OFFSET_HOURS = 8
    const val TIMEZONE_ID = "Asia/Shanghai"
}

/**
 * 格式化时间戳为 HH:mm 格式
 * @param timestamp 毫秒级时间戳
 * @return 格式化后的时间字符串，如 "14:30"
 */
expect fun formatTimeHM(timestamp: Long): String

/**
 * 格式化时间戳为 HH:mm:ss 格式
 * @param timestamp 毫秒级时间戳
 * @return 格式化后的时间字符串，如 "14:30:25"
 */
expect fun formatTimeHMS(timestamp: Long): String

/**
 * 格式化时间戳为 yyyy-MM-dd HH:mm 格式
 * @param timestamp 毫秒级时间戳
 * @return 格式化后的时间字符串，如 "2024-03-15 14:30"
 */
expect fun formatDateTime(timestamp: Long): String

/**
 * 格式化时间戳为 MM-dd HH:mm 格式
 * @param timestamp 毫秒级时间戳
 * @return 格式化后的时间字符串，如 "03-15 14:30"
 */
expect fun formatDateShortTime(timestamp: Long): String

/**
 * 获取当前时间戳（毫秒）
 * @return 当前时间的毫秒级时间戳
 */
expect fun currentTimeMillis(): Long
