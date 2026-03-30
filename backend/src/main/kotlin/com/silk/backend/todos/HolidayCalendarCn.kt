package com.silk.backend.todos

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 中国法定节假日（最小可用内置版）。
 * - holidayDates: 法定放假日
 * - makeupWorkdays: 调休上班日（即使周末也算工作日）
 */
object HolidayCalendarCn {
    private val holidayDates: Set<LocalDate> = setOf(
        // 2026 元旦/春节/清明/劳动节/端午/中秋国庆（示例，后续可扩成年度数据文件）
        LocalDate.parse("2026-01-01"),
        LocalDate.parse("2026-02-17"), LocalDate.parse("2026-02-18"), LocalDate.parse("2026-02-19"),
        LocalDate.parse("2026-02-20"), LocalDate.parse("2026-02-21"), LocalDate.parse("2026-02-22"),
        LocalDate.parse("2026-04-04"), LocalDate.parse("2026-04-05"), LocalDate.parse("2026-04-06"),
        LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-02"), LocalDate.parse("2026-05-03"),
        LocalDate.parse("2026-06-19"), LocalDate.parse("2026-06-20"), LocalDate.parse("2026-06-21"),
        LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-03"),
        LocalDate.parse("2026-10-04"), LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-06"),
        LocalDate.parse("2026-10-07"), LocalDate.parse("2026-10-08")
    )

    private val makeupWorkdays: Set<LocalDate> = setOf(
        LocalDate.parse("2026-02-15"),
        LocalDate.parse("2026-02-28"),
        LocalDate.parse("2026-09-27"),
        LocalDate.parse("2026-10-10")
    )

    fun isHoliday(date: LocalDate): Boolean {
        if (date in holidayDates) return true
        if (date in makeupWorkdays) return false
        return date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
    }

    fun isWorkday(date: LocalDate): Boolean {
        return !isHoliday(date)
    }

    fun nextWorkday(date: LocalDate): LocalDate {
        var d = date.plusDays(1)
        while (!isWorkday(d)) {
            d = d.plusDays(1)
        }
        return d
    }
}
