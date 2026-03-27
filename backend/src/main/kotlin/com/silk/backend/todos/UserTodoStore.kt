package com.silk.backend.todos

import com.silk.backend.database.UserTodoItemDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class UserTodoFilePayload(
    val userId: String,
    val items: List<UserTodoItemDto> = emptyList()
)

/**
 * 按用户持久化待办（chat_history/user_todos/{userId}.json）
 */
object UserTodoStore {
    private const val BASE_DIR = "chat_history/user_todos"
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val locks = ConcurrentHashMap<String, Any>()

    private fun fileFor(userId: String): File {
        val safe = userId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(BASE_DIR, "$safe.json")
    }

    fun load(userId: String): List<UserTodoItemDto> {
        val f = fileFor(userId)
        if (!f.exists()) return emptyList()
        return try {
            val text = f.readText()
            json.decodeFromString<UserTodoFilePayload>(text).items
        } catch (e: Exception) {
            println("⚠️ [UserTodoStore] 读取失败 user=${userId.take(8)}… : ${e.message}")
            emptyList()
        }
    }

    fun save(userId: String, items: List<UserTodoItemDto>) {
        val lock = locks.computeIfAbsent(userId) { Any() }
        synchronized(lock) {
            File(BASE_DIR).mkdirs()
            val f = fileFor(userId)
            val payload = UserTodoFilePayload(userId = userId, items = items)
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(json.encodeToString(payload))
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
    }

    /** 用新列表完全替换该用户待办（用于 LLM 去冗合并后写回）。 */
    fun replaceAll(userId: String, items: List<UserTodoItemDto>) {
        val sorted = items.sortedByDescending { it.updatedAt }
        save(userId, sorted)
    }

    /**
     * 用本次从聊天抽取的结果**替换**所有未完成待办；已勾选完成的条目保留（历史完成态）。
     * 避免仅 merge 追加导致「一事多条」永远堆在列表里。
     */
    fun replaceUndoneWithExtracted(userId: String, incoming: List<ExtractedTodoDraft>) {
        val lock = locks.computeIfAbsent(userId) { Any() }
        synchronized(lock) {
            val existing = load(userId)
            val keptDone = existing.filter { it.done }
            val now = System.currentTimeMillis()
            val seenKeys = mutableSetOf<String>()
            val newUndone = mutableListOf<UserTodoItemDto>()
            for (draft in incoming) {
                val t = draft.title.trim()
                if (t.isEmpty() || t.length > 500) continue
                val at = draft.actionType?.trim()?.lowercase()?.ifBlank { null }
                val ad = draft.actionDetail?.trim()?.ifBlank { null }
                val lk = logicalDedupKey(t, at, ad)
                if (lk in seenKeys) continue
                seenKeys.add(lk)
                newUndone.add(
                    UserTodoItemDto(
                        id = UUID.randomUUID().toString(),
                        title = t,
                        sourceGroupId = draft.sourceGroupId?.ifBlank { null },
                        sourceGroupName = draft.sourceGroupName?.ifBlank { null },
                        actionType = at,
                        actionDetail = ad,
                        createdAt = now,
                        updatedAt = now,
                        done = false
                    )
                )
            }
            save(userId, (keptDone + newUndone).sortedByDescending { it.updatedAt })
        }
    }

    fun mergeExtracted(userId: String, incoming: List<ExtractedTodoDraft>) {
        if (incoming.isEmpty()) return
        val lock = locks.computeIfAbsent(userId) { Any() }
        synchronized(lock) {
            val existing = load(userId).toMutableList()
            val seenKeys = existing.map { logicalDedupKey(it.title, it.actionType, it.actionDetail) }.toMutableSet()
            val now = System.currentTimeMillis()
            for (draft in incoming) {
                val t = draft.title.trim()
                if (t.isEmpty() || t.length > 500) continue
                val at = draft.actionType?.trim()?.lowercase()?.ifBlank { null }
                val ad = draft.actionDetail?.trim()?.ifBlank { null }
                val lk = logicalDedupKey(t, at, ad)
                if (lk in seenKeys) continue
                existing.add(
                    UserTodoItemDto(
                        id = UUID.randomUUID().toString(),
                        title = t,
                        sourceGroupId = draft.sourceGroupId?.ifBlank { null },
                        sourceGroupName = draft.sourceGroupName?.ifBlank { null },
                        actionType = at,
                        actionDetail = ad,
                        createdAt = now,
                        updatedAt = now,
                        done = false
                    )
                )
                seenKeys.add(lk)
            }
            existing.sortByDescending { it.updatedAt }
            save(userId, existing)
        }
    }

    fun setItemDone(userId: String, itemId: String, done: Boolean): Boolean {
        val lock = locks.computeIfAbsent(userId) { Any() }
        synchronized(lock) {
            val items = load(userId).toMutableList()
            val idx = items.indexOfFirst { it.id == itemId }
            if (idx < 0) return false
            val cur = items[idx]
            val now = System.currentTimeMillis()
            items[idx] = cur.copy(done = done, updatedAt = now)
            save(userId, items)
            return true
        }
    }

    /**
     * 合并、写库前按「一事一条」去重：同闹钟/日程且时间一致仅一条；标题相似（去掉套话后）合并。
     */
    fun dedupeByLogicalKeyInPlace(userId: String) {
        val lock = locks.computeIfAbsent(userId) { Any() }
        synchronized(lock) {
            val items = load(userId)
            if (items.isEmpty()) return@synchronized
            val beforeIds = items.map { it.id }.toHashSet()
            var merged = mergeItemsByLogicalKey(items)
            merged = mergeContainedUndoneTitles(merged)
            val afterIds = merged.map { it.id }.toHashSet()
            if (beforeIds == afterIds && merged.size == items.size) return@synchronized
            save(userId, merged.sortedByDescending { it.updatedAt })
            println("✅ [UserTodoStore] 本地去重/合并 ${items.size} → ${merged.size}")
        }
    }

    internal fun logicalDedupKey(title: String, actionType: String?, actionDetail: String?): String {
        val at = actionType?.trim()?.lowercase(Locale.getDefault())?.ifBlank { null }
        var adNorm = normalizeActionDetailForKey(at, actionDetail)
        // actionType=alarm but missing actionDetail: try to extract time from title
        if ((at == "alarm" || at == "calendar") && adNorm == null) {
            adNorm = extractTimeFromTitle(title)
            if (adNorm != null) return "$at:$adNorm"
        }
        if ((at == "alarm" || at == "calendar") && adNorm != null) {
            return "$at:$adNorm"
        }
        return "t:${normKey(title)}"
    }

    /** Try to parse a time string like "七点起床" → "07:00", "下午3点半开会" → "15:30" */
    private fun extractTimeFromTitle(title: String): String? {
        val t = title.trim()
        val pm = t.contains("下午") || t.contains("晚上") || t.contains("傍晚")
        // Chinese numeral + 点 (e.g. 七点, 十二点半)
        val cnHalf = Regex("([一二三四五六七八九十两零]+点半)").find(t)
        if (cnHalf != null) {
            val cn = cnHalf.groupValues[1].replace("点半", "")
            val h = parseCnHour(cn) ?: return null
            val hour = if (pm && h in 1..11) h + 12 else h
            if (hour in 0..23) return "%02d:%02d".format(hour, 30)
        }
        val cnExact = Regex("([一二三四五六七八九十两零]+点)").find(t)
        if (cnExact != null) {
            val cn = cnExact.groupValues[1].replace("点", "")
            val h = parseCnHour(cn) ?: return null
            val hour = if (pm && h in 1..11) h + 12 else h
            if (hour in 0..23) return "%02d:%02d".format(hour, 0)
        }
        // Arabic numeral + 点 (e.g. 7点, 3点半)
        val hOnly = Regex("(\\d{1,2})\\s*点").find(t)
        if (hOnly != null) {
            val h = hOnly.groupValues[1].toIntOrNull() ?: return null
            var hour = h
            if (pm) {
                if (h in 1..11) hour = h + 12
                else if (h == 12) hour = 12
            }
            if (hour !in 0..23) return null
            val minute = if (t.contains("半")) 30 else 0
            return "%02d:%02d".format(hour, minute)
        }
        // HH:mm format
        val hm = Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{2})").find(t)
        if (hm != null) {
            val h = hm.groupValues[1].toIntOrNull() ?: return null
            val m = hm.groupValues[2].toIntOrNull() ?: return null
            if (h in 0..23 && m in 0..59) return "%02d:%02d".format(h, m)
        }
        return null
    }

    /** Parse Chinese numeral hour string: "七"→7, "十二"→12, "十"→10 */
    private fun parseCnHour(cn: String): Int? {
        val map = mapOf('一' to 1,'二' to 2,'三' to 3,'四' to 4,'五' to 5,'六' to 6,'七' to 7,'八' to 8,'九' to 9,'十' to 10,'两' to 2,'零' to 0)
        if (cn.length == 1) return map[cn.single()]
        if (cn.length == 2 && cn[0] == '十') {
            val unit = map[cn[1]] ?: 0
            return 10 + unit
        }
        if (cn.length == 2) {
            val tens = map[cn[0]] ?: return null
            val units = map[cn[1]] ?: return null
            return tens * 10 + units
        }
        return null
    }

    private fun normKey(title: String): String {
        var s = title.trim().lowercase(Locale.getDefault())
        s = s.replace(Regex("\\s+"), " ")
        s = s.replace(Regex("[“”‘’，。、；:!?！．·…~～\"'（）()\\[\\]【】|/《》〈〉{}]+"), "")
        s = s.replace(
            Regex("^(设置闹钟|设闹钟|闹钟|提醒我|提醒|记得|记得要|请|帮忙|麻烦|帮我|好的我会|好的|没问题|收到|知道了|明白了|ok|好)\\s*[:：]?\\s*"),
            ""
        )
        s = s.replace(Regex("[:：]\\s*$"), "")
        s = s.replace(Regex("^\\s*(设置|设定|安排?|创建?|添加?)\\s*[:：]?\\s*"), "")
        s = s.replace(
            Regex("的?(闹钟|提醒|叫醒|起床铃|日程|日历|事项)$"),
            ""
        )
        return s.trim().ifEmpty { title.trim().lowercase(Locale.getDefault()) }
    }


    private fun normalizeActionDetailForKey(actionType: String?, detail: String?): String? {
        if (detail == null) return null
        val t = detail.trim().lowercase(Locale.getDefault()).ifBlank { return null }
        // "HH:mm" exact
        val hm = Regex("^(\\d{1,2})\\s*[:：]\\s*(\\d{2})$").find(t)
        if (hm != null) {
            val h = hm.groupValues[1].toIntOrNull() ?: return normKey(t)
            val m = hm.groupValues[2].toIntOrNull() ?: return normKey(t)
            if (h in 0..23 && m in 0..59) return "%02d:%02d".format(h, m)
        }
        // "YYYY-MM-DD HH:mm" or "YYYY-MM-DDTHH:mm"
        val fullDt = Regex("(\\d{4})-(\\d{2})-(\\d{2})[T ]?(\\d{1,2})[:：](\\d{2})").find(t)
        if (fullDt != null) {
            val h = fullDt.groupValues[4].toIntOrNull() ?: return normKey(t)
            val m = fullDt.groupValues[5].toIntOrNull() ?: return normKey(t)
            if (h in 0..23 && m in 0..59) {
                return "%s-%s-%s %02d:%02d".format(
                    fullDt.groupValues[1], fullDt.groupValues[2], fullDt.groupValues[3], h, m
                )
            }
        }
        // "今天 15:00" / "明天 9:30"
        val relTime = Regex("^(今天|明天|后天|大后天|下周)\\s*(\\d{1,2})\\s*[:：]\\s*(\\d{2})").find(t)
        if (relTime != null) {
            val h = relTime.groupValues[2].toIntOrNull() ?: return normKey(t)
            val m = relTime.groupValues[3].toIntOrNull() ?: return normKey(t)
            if (h in 0..23 && m in 0..59) return "%s %02d:%02d".format(relTime.groupValues[1], h, m)
        }
        return normKey(t)
    }

    /** 若归一化标题互为包含则并成一条（解决一事多条）；已完成项也参与合并以清理历史。 */
    private fun mergeContainedUndoneTitles(items: List<UserTodoItemDto>): List<UserTodoItemDto> {
        var pool = items.toMutableList()
        if (pool.size < 2) return items
        val now = System.currentTimeMillis()
        var changed = true
        while (changed && pool.size > 1) {
            changed = false
            pair@ for (i in 0 until pool.size) {
                for (j in i + 1 until pool.size) {
                    val a = pool[i]
                    val b = pool[j]
                    val merged = tryMergeByContainedNormTitle(a, b, now) ?: continue
                    pool.removeAt(j)
                    pool.removeAt(i)
                    pool.add(merged)
                    changed = true
                    break@pair
                }
            }
        }
        return pool
    }

    private fun tryMergeByContainedNormTitle(
        a: UserTodoItemDto,
        b: UserTodoItemDto,
        now: Long
    ): UserTodoItemDto? {
        val anyDone = a.done || b.done
        // For same-key structured alarm/calendar: let mergeItemsByLogicalKey handle
        // Here we catch cross-key semantic overlap via normKey containment
        val ta = a.actionType?.trim()?.lowercase(Locale.getDefault()) ?: ""
        val tb = b.actionType?.trim()?.lowercase(Locale.getDefault()) ?: ""
        if ((ta == "alarm" || ta == "calendar") && (tb == "alarm" || tb == "calendar")) {
            // both structured: if keys differ, they genuinely refer to different times
            if (logicalDedupKey(a.title, a.actionType, a.actionDetail) !=
                logicalDedupKey(b.title, b.actionType, b.actionDetail)
            ) {
                return null
            }
        }
        val na = normKey(a.title)
        val nb = normKey(b.title)
        if (na.length < 2 || nb.length < 2) return null
        val contained = (na.contains(nb) || nb.contains(na))
        if (!contained) return null
        val newer = if (a.updatedAt >= b.updatedAt) a else b
        val other = if (newer.id == a.id) b else a
        val shortTitle = if (a.title.trim().length <= b.title.trim().length) a.title.trim() else b.title.trim()
        return newer.copy(
            title = shortTitle.take(500),
            actionType = (newer.actionType ?: other.actionType)
                ?.trim()?.lowercase(Locale.getDefault())?.ifBlank { null },
            actionDetail = listOfNotNull(a.actionDetail?.trim(), b.actionDetail?.trim())
                .filter { it.isNotEmpty() }
                .maxByOrNull { it.length }
                ?.ifBlank { null },
            done = anyDone,
            updatedAt = now
        )
    }

    private fun mergeItemsByLogicalKey(items: List<UserTodoItemDto>): List<UserTodoItemDto> {
        val byKey = items.groupBy { logicalDedupKey(it.title, it.actionType, it.actionDetail) }
        val now = System.currentTimeMillis()
        return byKey.values.map { g ->
            val sorted = g.sortedByDescending { it.updatedAt }
            val newest = sorted.first()
            val mergedRow = g.size > 1
            val anyDone = g.any { it.done }
            val bestDetail = g.mapNotNull { it.actionDetail?.trim()?.takeIf { x -> x.isNotEmpty() } }
                .maxByOrNull { it.length }
            val preferredType = g.mapNotNull { it.actionType?.trim()?.lowercase(Locale.getDefault())?.ifBlank { null } }
                .firstOrNull { it == "alarm" || it == "calendar" }
                ?: newest.actionType?.trim()?.lowercase(Locale.getDefault())?.ifBlank { null }
            val shortTitle = g.map { it.title.trim() }.filter { it.isNotEmpty() }
                .minByOrNull { it.length } ?: newest.title
            newest.copy(
                title = shortTitle.take(500),
                actionType = preferredType?.ifBlank { null },
                actionDetail = (bestDetail ?: newest.actionDetail)?.trim()?.ifBlank { null },
                done = anyDone,
                updatedAt = if (mergedRow) now else newest.updatedAt
            )
        }
    }
}

data class ExtractedTodoDraft(
    val title: String,
    val sourceGroupId: String? = null,
    val sourceGroupName: String? = null,
    val actionType: String? = null,
    val actionDetail: String? = null
)
