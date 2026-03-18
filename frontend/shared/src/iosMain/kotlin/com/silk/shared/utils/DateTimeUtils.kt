package com.silk.shared.utils

import platform.Foundation.*

/**
 * iOS 平台的日期时间工具实现 - 使用上海时区 (Asia/Shanghai, UTC+8)
 */
private val shanghaiTimeZone = NSTimeZone.timeZoneWithName("Asia/Shanghai")!!

/**
 * 格式化时间戳为 HH:mm 格式 (上海时区)
 */
actual fun formatTimeHM(timestamp: Long): String {
    val date = NSDate(timeIntervalSince1970 = timestamp / 1000.0)
    val formatter = NSDateFormatter()
    formatter.dateFormat = "HH:mm"
    formatter.timeZone = shanghaiTimeZone
    return formatter.stringFromDate(date)
}

/**
 * 格式化时间戳为 HH:mm:ss 格式 (上海时区)
 */
actual fun formatTimeHMS(timestamp: Long): String {
    val date = NSDate(timeIntervalSince1970 = timestamp / 1000.0)
    val formatter = NSDateFormatter()
    formatter.dateFormat = "HH:mm:ss"
    formatter.timeZone = shanghaiTimeZone
    return formatter.stringFromDate(date)
}

/**
 * 格式化时间戳为 yyyy-MM-dd HH:mm 格式 (上海时区)
 */
actual fun formatDateTime(timestamp: Long): String {
    val date = NSDate(timeIntervalSince1970 = timestamp / 1000.0)
    val formatter = NSDateFormatter()
    formatter.dateFormat = "yyyy-MM-dd HH:mm"
    formatter.timeZone = shanghaiTimeZone
    return formatter.stringFromDate(date)
}

/**
 * 格式化时间戳为 MM-dd HH:mm 格式 (上海时区)
 */
actual fun formatDateShortTime(timestamp: Long): String {
    val date = NSDate(timeIntervalSince1970 = timestamp / 1000.0)
    val formatter = NSDateFormatter()
    formatter.dateFormat = "MM-dd HH:mm"
    formatter.timeZone = shanghaiTimeZone
    return formatter.stringFromDate(date)
}

/**
 * 获取当前时间戳（毫秒）
 */
actual fun currentTimeMillis(): Long {
    return (NSDate().timeIntervalSince1970 * 1000).toLong()
}
