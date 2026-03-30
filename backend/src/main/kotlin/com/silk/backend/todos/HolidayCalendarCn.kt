package com.silk.backend.todos

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 中国法定节假日（2026 年官方安排）。
 * 数据来源：国务院办公厅关于2026年部分节假日安排的通知（2025-11-04发布）
 *
 * - holidayDates: 法定放假日
 * - makeupWorkdays: 调休上班日（即使周末也算工作日）
 */
object HolidayCalendarCn {
    private val holidayDates: Set<LocalDate> = setOf(
        // 2026 元旦：1月1日(周四)-1月3日(周六)，共3天
        LocalDate.parse("2026-01-01"),
        LocalDate.parse("2026-01-02"),
        LocalDate.parse("2026-01-03"),

        // 2026 春节：2月15日(周日)-2月23日(周一)，共9天
        LocalDate.parse("2026-02-15"),
        LocalDate.parse("2026-02-16"),
        LocalDate.parse("2026-02-17"),
        LocalDate.parse("2026-02-18"),
        LocalDate.parse("2026-02-19"),
        LocalDate.parse("2026-02-20"),
        LocalDate.parse("2026-02-21"),
        LocalDate.parse("2026-02-22"),
        LocalDate.parse("2026-02-23"),

        // 2026 清明：4月4日(周六)-4月6日(周一)，共3天
        LocalDate.parse("2026-04-04"),
        LocalDate.parse("2026-04-05"),
        LocalDate.parse("2026-04-06"),

        // 2026 劳动节：5月1日(周五)-5月5日(周二)，共5天
        LocalDate.parse("2026-05-01"),
        LocalDate.parse("2026-05-02"),
        LocalDate.parse("2026-05-03"),
        LocalDate.parse("2026-05-04"),
        LocalDate.parse("2026-05-05"),

        // 2026 端午：6月19日(周五)-6月21日(周日)，共3天
        LocalDate.parse("2026-06-19"),
        LocalDate.parse("2026-06-20"),
        LocalDate.parse("2026-06-21"),

        // 2026 中秋：9月25日(周五)-9月27日(周日)，共3天
        LocalDate.parse("2026-09-25"),
        LocalDate.parse("2026-09-26"),
        LocalDate.parse("2026-09-27"),

        // 2026 国庆：10月1日(周四)-10月7日(周三)，共7天
        LocalDate.parse("2026-10-01"),
        LocalDate.parse("2026-10-02"),
        LocalDate.parse("2026-10-03"),
        LocalDate.parse("2026-10-04"),
        LocalDate.parse("2026-10-05"),
        LocalDate.parse("2026-10-06"),
        LocalDate.parse("2026-10-07")
    )

    private val makeupWorkdays: Set<LocalDate> = setOf(
        // 元旦调休：1月4日(周日)上班
        LocalDate.parse("2026-01-04"),
        // 春节调休：2月14日(周六)、2月28日(周六)上班
        LocalDate.parse("2026-02-14"),
        LocalDate.parse("2026-02-28"),
        // 劳动节调休：5月9日(周六)上班
        LocalDate.parse("2026-05-09"),
        // 国庆调休：9月20日(周日)、10月10日(周六)上班
        LocalDate.parse("2026-09-20"),
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
