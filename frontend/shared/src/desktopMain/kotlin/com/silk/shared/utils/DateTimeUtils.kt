package com.silk.shared.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Desktop (JVM) 平台的 DateTimeUtils 实现
 * 使用中国上海时区 (Asia/Shanghai, UTC+8)
 */

actual fun formatTimeHM(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    return sdf.format(Date(timestamp))
}

actual fun formatTimeHMS(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    return sdf.format(Date(timestamp))
}

actual fun formatDateTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    return sdf.format(Date(timestamp))
}

actual fun formatDateShortTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    return sdf.format(Date(timestamp))
}

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
