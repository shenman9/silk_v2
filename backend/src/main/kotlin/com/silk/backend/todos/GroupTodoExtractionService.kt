package com.silk.backend.todos

import com.silk.backend.ChatHistoryManager
import com.silk.backend.ai.AIConfig
import com.silk.backend.database.GroupRepository
import com.silk.backend.database.UserRepository
import com.silk.backend.database.UserTodoItemDto
import com.silk.backend.models.ChatHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * 从用户所属各群近期聊天记录中提取待办，并合并写入 [UserTodoStore]。
 *
 * - 多路径查找 `chat_history`（兼容从仓库根目录或 backend 子目录启动进程）
 * - LLM 抽取 + 启发式兜底（二选一，不叠加）；每次成功刷新用新列表**替换**未完成项（保留已完成），避免一事多条堆叠
 */
object GroupTodoExtractionService {
    data class ExtractionDiagnostics(
        val userId: String,
        val updatedAt: Long,
        val source: String,
        val totalGroups: Int,
        val transcriptChars: Int,
        val llmDraftCount: Int,
        val heuristicDraftCount: Int,
        val forcedRecurringCount: Int,
        val finalDraftCount: Int,
        val matchedRecurringLines: List<String>,
        val note: String
    )

    private val diagnosticsByUser = ConcurrentHashMap<String, ExtractionDiagnostics>()

    private val httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofMillis(AIConfig.TIMEOUT))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 与 [ChatHistoryManager] 默认目录一致，并兼容从仓库根目录、`backend/` 等启动时的工作目录。
     */
    private val historyBaseDirs: List<String> = listOf(
        "chat_history",
        "backend/chat_history",
        "../chat_history"
    ).distinct()

    @Serializable
    private data class SimpleMsg(val role: String, val content: String)

    @Serializable
    private data class SimpleChatRequest(
        val model: String,
        val messages: List<SimpleMsg>,
        val temperature: Double = 0.35,
        val max_tokens: Int = 2048,
        val stream: Boolean = false
    )

    @Serializable
    private data class CompactTodoInputRow(
        val id: String,
        val title: String,
        val actionType: String? = null,
        val actionDetail: String? = null,
        val done: Boolean = false,
        val sourceGroupId: String? = null,
        val sourceGroupName: String? = null
    )

    private const val MAX_MESSAGES_PER_GROUP = 55
    private const val MAX_TRANSCRIPT_CHARS = 26_000
    private const val MAX_TODOS_FOR_COMPACT = 80
    private const val MAX_COMPACT_INPUT_CHARS = 48_000
    /** 单次刷新写入的未完成待办上限（一事一条，避免刷屏） */
    private const val MAX_TODOS_PER_REFRESH = 12

    private data class GroupSlice(
        val groupId: String,
        val groupName: String,
        val messages: List<Triple<String, String, Long>>
    )

    private val skipSmallTalk = Regex(
        """^(好的|嗯|行|可以|谢谢|收到|哈哈|哈哈哈哈|嗯嗯|对的|是|不是|没错|明白|了解|OK|ok|6|牛|赞|玫瑰|咖啡)[\s!！。…~～]*$"""
    )

    suspend fun refreshTodosForUser(userId: String) = withContext(Dispatchers.IO) {
        if (userId.isBlank()) return@withContext
        val apiKey = AIConfig.API_KEY.trim()
        if (apiKey.isEmpty()) {
            println("ℹ️ [GroupTodoExtractionService] 跳过：未配置 OPENAI_API_KEY")
            diagnosticsByUser[userId] = ExtractionDiagnostics(
                userId = userId,
                updatedAt = System.currentTimeMillis(),
                source = "skip",
                totalGroups = 0,
                transcriptChars = 0,
                llmDraftCount = 0,
                heuristicDraftCount = 0,
                forcedRecurringCount = 0,
                finalDraftCount = 0,
                matchedRecurringLines = emptyList(),
                note = "未配置 OPENAI_API_KEY"
            )
            return@withContext
        }
        val user = UserRepository.findUserById(userId)
        val userName = user?.fullName?.ifBlank { user.loginName } ?: "用户"
        val groups = GroupRepository.getUserGroups(userId)
        if (groups.isEmpty()) {
            println("ℹ️ [GroupTodoExtractionService] 用户无群组，跳过")
            diagnosticsByUser[userId] = ExtractionDiagnostics(
                userId = userId,
                updatedAt = System.currentTimeMillis(),
                source = "skip",
                totalGroups = 0,
                transcriptChars = 0,
                llmDraftCount = 0,
                heuristicDraftCount = 0,
                forcedRecurringCount = 0,
                finalDraftCount = 0,
                matchedRecurringLines = emptyList(),
                note = "用户无群组"
            )
            return@withContext
        }

        val slices = collectGroupSlices(groups.map { it.id to it.name })
        if (slices.isEmpty()) {
            println(
                "ℹ️ [GroupTodoExtractionService] 未读到任何群消息文件；已尝试目录: " +
                    historyBaseDirs.joinToString()
            )
            diagnosticsByUser[userId] = ExtractionDiagnostics(
                userId = userId,
                updatedAt = System.currentTimeMillis(),
                source = "skip",
                totalGroups = groups.size,
                transcriptChars = 0,
                llmDraftCount = 0,
                heuristicDraftCount = 0,
                forcedRecurringCount = 0,
                finalDraftCount = 0,
                matchedRecurringLines = emptyList(),
                note = "未读到群消息文件"
            )
            compactStoredTodosForUser(userId, apiKey)
            UserTodoStore.dedupeByLogicalKeyInPlace(userId)
            return@withContext
        }

        val transcript = buildTranscriptString(slices)
        val heuristicDraftsPre = heuristicFromSlices(slices)
        val latestEvidenceTs = slices.asSequence()
            .flatMap { it.messages.asSequence() }
            .map { it.third }
            .maxOrNull() ?: System.currentTimeMillis()
        println(
            "📋 [GroupTodoExtractionService] 摘录长度=${transcript.length}，群段=${slices.size}，启发式候选=${heuristicDraftsPre.size}"
        )

        val system = """你是「真人待办」整理助手。输入为多群聊天节选，格式为 [发送者]: 内容。

核心原则（严格执行）：
- **一事一条**：同一个意图/事项在整个对话中只输出一条待办，禁止任何形式的重复。
- 只为人（真实用户）需要落实的「一件事」各写一条待办；不要把 AI 助手（如 Silk、🤖）的寒暄、泛泛解释、长篇回复拆成多条待办。
- **禁止**把助手回复里的建议列表、客套话、「好的我可以帮你」等整段当成待办；除非聊天里**明确**出现了用户要执行的具体动作。
- 同一件事合并成一条 title（例如用户说「提醒我七点起床」→ title="7点起床闹钟" 一条即可，去掉"设置闹钟""提醒我"等套话前缀）。
- title 用简短可执行中文，10-30 字为佳；**禁止**把多句话、列表、段落原样粘贴进一条 title。
- 输出前必须逐条检查：删除任何两条之间仅有措辞差异、语义实质相同的待办。

actionType / actionDetail（能填就填，影响手机端是否显示「运行」按钮）：
- 用户要闹钟/叫醒/起床：actionType="alarm"，actionDetail **必须**填 24 小时制时间如 "07:00" 或 "19:30"（从聊天推断具体时间）。
- 要日程/会议：actionType="calendar"，actionDetail **必须**填 "YYYY-MM-DD HH:mm" 或 "明天 15:00" 等含时间的可解析片段。
- 普通事务：actionType="none" 或省略。

**硬性上限：todos 数组最多 $MAX_TODOS_PER_REFRESH 条**；宁可少输出也不要重复。
输出：仅一段 JSON，无 Markdown。
格式：{"todos":[{"title":"...","actionType":"alarm","actionDetail":"07:00","sourceGroupId":"可省略","sourceGroupName":"可省略"}]}"""

        val userPrompt = "用户显示名：$userName（userId=${userId.take(8)}…）\n\n$transcript"

        val raw = try {
            callLlm(system, userPrompt, apiKey, temperature = 0.2)
        } catch (e: Exception) {
            println("❌ [GroupTodoExtractionService] LLM 调用失败: ${e.message}")
            null
        }

        val parseResult = raw?.let { parseTodoJsonStrict(it) }
        val llmDrafts = parseResult?.first ?: emptyList()
        val jsonOk = parseResult?.second == true
        val llmOrParseFailed = raw == null || !jsonOk

        // LLM 与启发式二选一，禁止叠加（否则同一聊天会出来两套待办）
        val sourceDrafts = when {
            llmDrafts.isNotEmpty() -> llmDrafts
            llmOrParseFailed -> heuristicDraftsPre
            else -> emptyList()
        }

        val recurringResult = extractRecurringTemplateDrafts(slices)
        val forcedRecurringDrafts = recurringResult.first
        val recurringLines = recurringResult.second
        val combinedDrafts = sourceDrafts + forcedRecurringDrafts
        val finalDrafts = normalizeDraftsWithKind(dedupeDrafts(combinedDrafts), latestEvidenceTs)
            .take(MAX_TODOS_PER_REFRESH)
        val shouldReplaceUndone = jsonOk || finalDrafts.isNotEmpty()

        if (shouldReplaceUndone) {
            UserTodoStore.replaceUndoneWithExtracted(userId, finalDrafts)
            val srcLabel = when {
                llmDrafts.isNotEmpty() -> "模型"
                finalDrafts.isNotEmpty() -> "启发式"
                else -> "清空未完成"
            }
            println(
                "✅ [GroupTodoExtractionService] 已用本次抽取替换未完成待办 ${finalDrafts.size} 条（来源=$srcLabel，jsonOk=$jsonOk）"
            )
        } else {
            println(
                "ℹ️ [GroupTodoExtractionService] 抽取失败且无启发式结果，保留原未完成待办（LLM/JSON 异常）"
            )
        }
        compactStoredTodosForUser(userId, apiKey)
        UserTodoStore.dedupeByLogicalKeyInPlace(userId)
        diagnosticsByUser[userId] = ExtractionDiagnostics(
            userId = userId,
            updatedAt = System.currentTimeMillis(),
            source = when {
                llmDrafts.isNotEmpty() -> "llm+forced_recurring"
                llmOrParseFailed && heuristicDraftsPre.isNotEmpty() -> "heuristic+forced_recurring"
                forcedRecurringDrafts.isNotEmpty() -> "forced_recurring_only"
                else -> "none"
            },
            totalGroups = slices.size,
            transcriptChars = transcript.length,
            llmDraftCount = llmDrafts.size,
            heuristicDraftCount = heuristicDraftsPre.size,
            forcedRecurringCount = forcedRecurringDrafts.size,
            finalDraftCount = finalDrafts.size,
            matchedRecurringLines = recurringLines.take(6),
            note = if (jsonOk) "json_ok" else "json_not_ok_or_empty"
        )
    }

    fun getDiagnostics(userId: String): ExtractionDiagnostics {
        return diagnosticsByUser[userId] ?: ExtractionDiagnostics(
            userId = userId,
            updatedAt = System.currentTimeMillis(),
            source = "none",
            totalGroups = 0,
            transcriptChars = 0,
            llmDraftCount = 0,
            heuristicDraftCount = 0,
            forcedRecurringCount = 0,
            finalDraftCount = 0,
            matchedRecurringLines = emptyList(),
            note = "暂无抽取记录"
        )
    }

    /**
     * 对已持久化的待办做 LLM 去冗：合并语义重复、缩短 title；仅接受输出中出现且合法的已有 id。
     */
    private fun compactStoredTodosForUser(userId: String, apiKey: String) {
        val all = UserTodoStore.load(userId).sortedByDescending { it.updatedAt }
        if (all.isEmpty()) return
        var batch = all.take(MAX_TODOS_FOR_COMPACT)
        if (all.size > MAX_TODOS_FOR_COMPACT) {
            println(
                "ℹ️ [GroupTodoExtractionService] 去冗仅处理最近 ${MAX_TODOS_FOR_COMPACT} 条，其余 ${all.size - MAX_TODOS_FOR_COMPACT} 条保留至下次"
            )
        }
        val rowSerializer = ListSerializer(CompactTodoInputRow.serializer())
        var rows = batch.map {
            CompactTodoInputRow(
                id = it.id,
                title = it.title,
                actionType = it.actionType,
                actionDetail = it.actionDetail,
                done = it.done,
                sourceGroupId = it.sourceGroupId,
                sourceGroupName = it.sourceGroupName
            )
        }
        var payload = json.encodeToString(rowSerializer, rows)
        if (payload.length > MAX_COMPACT_INPUT_CHARS) {
            println("⚠️ [GroupTodoExtractionService] 去冗输入过长，已截断条数")
            while (payload.length > MAX_COMPACT_INPUT_CHARS && rows.size > 1) {
                rows = rows.dropLast(1)
                batch = batch.dropLast(1)
                payload = json.encodeToString(rowSerializer, rows)
            }
            if (payload.length > MAX_COMPACT_INPUT_CHARS) return
        }

        val system = """你是「待办去冗」助手。输入为一个 JSON 数组，每条为已存在的待办，字段含 id、title、actionType、actionDetail、done 等。

任务：
- 合并**语义重复**的条目为一条；合并后 **id 必须等于保留条目的 id**（从输入里选一条保留，不得编造新 id）。
- 缩短冗长、啰嗦的 title：一句一事，尽量 ≤40 字；不要把列表或多段话原样保留；**禁止**把一条拆成多条；**禁止**编造输入中不存在的任务。
- 若组合并：任一条 done 为 true 则结果为 done=true。
- actionType / actionDetail：在组内选更完整、更便于执行的一份（alarm/calendar/none 规则与抽取阶段一致）。

输出：仅一段 JSON，无 Markdown。
格式：{"todos":[{"id":"必须是输入中的id","title":"...","actionType":"alarm","actionDetail":"07:00","sourceGroupId":"可省略","sourceGroupName":"可省略","done":false}]}
输出的每条 id 必须出现在输入 JSON 的 id 集合中。"""

        val raw = try {
            callLlm(system, payload, apiKey)
        } catch (e: Exception) {
            println("❌ [GroupTodoExtractionService] 去冗 LLM 失败: ${e.message}")
            UserTodoStore.dedupeByLogicalKeyInPlace(userId)
            return
        }
        val originalsById = batch.associateBy { it.id }
        val validIds = batch.map { it.id }.toSet()
        val compacted = parseCompactTodoJson(raw, validIds, originalsById) ?: run {
            println("⚠️ [GroupTodoExtractionService] 去冗解析失败，跳过 LLM 写回（将尝试本地逻辑去重）")
            UserTodoStore.dedupeByLogicalKeyInPlace(userId)
            return
        }
        if (compacted.isEmpty()) {
            println("⚠️ [GroupTodoExtractionService] 去冗结果为空，保留原列表")
            UserTodoStore.dedupeByLogicalKeyInPlace(userId)
            return
        }
        val tail = all.drop(batch.size)
        val merged = (compacted + tail).sortedByDescending { it.updatedAt }
        UserTodoStore.replaceAll(userId, merged)
        println("✅ [GroupTodoExtractionService] 去冗完成：${batch.size} → ${compacted.size}（未参与批次的条目已追加）")
    }

    private fun parseCompactTodoJson(
        raw: String,
        validIds: Set<String>,
        originalsById: Map<String, UserTodoItemDto>
    ): List<UserTodoItemDto>? {
        val slice = extractJsonObject(raw) ?: return null
        return try {
            val obj = json.parseToJsonElement(slice).jsonObject
            val arr = obj["todos"]?.jsonArray ?: return null
            val now = System.currentTimeMillis()
            val out = mutableListOf<UserTodoItemDto>()
            val seenOutIds = mutableSetOf<String>()
            for (el in arr) {
                val o = el.jsonObject
                val id = o["id"]?.jsonPrimitive?.content?.trim() ?: continue
                if (id !in validIds) {
                    println("⚠️ [GroupTodoExtractionService] 去冗跳过未知 id: ${id.take(12)}…")
                    continue
                }
                if (id in seenOutIds) continue
                seenOutIds.add(id)
                val orig = originalsById[id] ?: continue
                val title = o["title"]?.jsonPrimitive?.content?.trim() ?: continue
                if (title.isEmpty() || title.length > 500) continue
                val gid = o["sourceGroupId"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
                val gname = o["sourceGroupName"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
                val at = o["actionType"]?.jsonPrimitive?.content?.trim()?.lowercase()
                    ?.takeIf { it.isNotEmpty() && it != "null" }
                val ad = o["actionDetail"]?.jsonPrimitive?.content?.trim()
                    ?.takeIf { it.isNotEmpty() }
                val donePrim = o["done"]?.jsonPrimitive
                val done = when {
                    donePrim == null -> orig.done
                    donePrim.booleanOrNull != null -> donePrim.booleanOrNull == true
                    else -> donePrim.content.equals("true", ignoreCase = true) || donePrim.content == "1"
                }
                out.add(
                    UserTodoItemDto(
                        id = id,
                        title = title,
                        sourceGroupId = gid ?: orig.sourceGroupId,
                        sourceGroupName = gname ?: orig.sourceGroupName,
                        actionType = at?.ifBlank { null },
                        actionDetail = ad?.ifBlank { null },
                        createdAt = orig.createdAt,
                        updatedAt = now,
                        done = done,
                        executedAt = orig.executedAt,
                        reminderId = orig.reminderId
                    )
                )
            }
            if (out.isEmpty()) null else out
        } catch (e: Exception) {
            println("⚠️ [GroupTodoExtractionService] 去冗 JSON 解析失败: ${e.message}")
            null
        }
    }

    private fun loadChatHistoryForGroup(groupId: String): ChatHistory? {
        val sessionName = "group_$groupId"
        for (base in historyBaseDirs) {
            val dir = File(base, sessionName)
            val hf = File(dir, "chat_history.json")
            if (!hf.isFile || hf.length() == 0L) continue
            val hm = ChatHistoryManager(base)
            val h = hm.loadChatHistory(sessionName)
            if (h != null && h.messages.isNotEmpty()) return h
        }
        return null
    }

    private fun isContentMessage(msgType: String): Boolean {
        val t = msgType.trim().uppercase()
        return t == "TEXT" || t == "FILE"
    }

    private fun collectGroupSlices(groupIdToName: List<Pair<String, String>>): List<GroupSlice> {
        val out = mutableListOf<GroupSlice>()
        for ((groupId, groupName) in groupIdToName) {
            val hist = loadChatHistoryForGroup(groupId) ?: continue
            val msgs = hist.messages
                .filter { isContentMessage(it.messageType) }
                .filter { it.content.isNotBlank() }
                .map { Triple(it.senderName, it.content.trim(), it.timestamp) }
                .takeLast(MAX_MESSAGES_PER_GROUP)
            if (msgs.isEmpty()) continue
            out.add(GroupSlice(groupId, groupName, msgs))
        }
        return out
    }

    private fun buildTranscriptString(slices: List<GroupSlice>): String {
        val sb = StringBuilder()
        for (slice in slices) {
            sb.appendLine("=== 群：${slice.groupName} (id=${slice.groupId}) ===")
            for ((sender, content, _) in slice.messages) {
                for (line in content.split('\n')) {
                    val one = line.trim().replace("\r", "")
                    if (one.isEmpty()) continue
                    sb.appendLine("[$sender]: $one")
                    if (sb.length >= MAX_TRANSCRIPT_CHARS) return sb.toString().trim()
                }
            }
            sb.appendLine()
            if (sb.length >= MAX_TRANSCRIPT_CHARS) break
        }
        return sb.toString().trim()
    }

    private fun isLikelyAssistantSender(sender: String): Boolean {
        val s = sender.trim()
        if (s.isEmpty()) return false
        if (s.contains("silk", ignoreCase = true)) return true
        if (s.contains("🤖")) return true
        if (s.contains("助手") && s.length <= 12) return true
        return false
    }

    /** 仅作 LLM 失败时的弱兜底：勾选列表 + 明显提醒句，且跳过助手气泡 */
    private fun heuristicFromSlices(slices: List<GroupSlice>): List<ExtractedTodoDraft> {
        val out = mutableListOf<ExtractedTodoDraft>()
        val checklist =
            Regex("""^\s*[-*•]\s+\[[ xX]\]\s*(.+)$""")
        val alarmCue =
            Regex("""(提醒|闹钟|叫醒|起床|几点).{0,80}""")

        for (slice in slices) {
            for ((sender, raw, ts) in slice.messages) {
                if (isLikelyAssistantSender(sender)) continue
                var usedAlarmLineFallback = false
                for (line in raw.split('\n')) {
                    val t = line.trim()
                    if (t.length < 4 || t.length > 200) continue
                    if (skipSmallTalk.matches(t)) continue
                    if (t.startsWith("http://", true) || t.startsWith("https://", true)) continue

                    val fromCheck = checklist.find(t)?.groupValues?.getOrNull(1)?.trim()
                    val alarmish = alarmCue.containsMatchIn(t)
                    val candidate = when {
                        fromCheck != null && fromCheck.length >= 2 -> fromCheck
                        alarmish && t.length <= 80 -> t
                        else -> null
                    } ?: continue

                    if (fromCheck == null && alarmish) {
                        if (usedAlarmLineFallback) continue
                        usedAlarmLineFallback = true
                    }

                    val title = if (candidate.length > 120) candidate.take(117) + "..." else candidate
                    val hm = extractRoughHourMinute(t)
                    val actionType = when {
                        alarmish || hm != null -> "alarm"
                        else -> "none"
                    }
                    val actionDetail = hm?.let { "%02d:%02d".format(it.first, it.second) }
                    val explicitIntent = isExplicitTaskIntent(t)
                    out.add(
                        ExtractedTodoDraft(
                            title = title,
                            sourceGroupId = slice.groupId,
                            sourceGroupName = slice.groupName,
                            actionType = actionType,
                            actionDetail = actionDetail,
                            evidenceAt = ts,
                            explicitIntent = explicitIntent
                        )
                    )
                }
            }
        }
        return dedupeDrafts(out)
    }

    /**
     * 对“工作日/纪念日”这类长期重复任务做强兜底：
     * 即使 LLM 成功但漏抽，也尽量保留周期模板。
     */
    private fun extractRecurringTemplateDrafts(slices: List<GroupSlice>): Pair<List<ExtractedTodoDraft>, List<String>> {
        val out = mutableListOf<ExtractedTodoDraft>()
        val matchedLines = mutableListOf<String>()
        val workdayHabitCue = Regex("(工作日|每个?工作日|上班日).{0,40}(起床|吃药|提醒|闹钟)|((起床|吃药|提醒|闹钟).{0,40}(工作日|每个?工作日|上班日))")
        val anniversaryCue = Regex("(纪念日|周年|生日)")

        for (slice in slices) {
            for ((sender, raw, ts) in slice.messages) {
                if (isLikelyAssistantSender(sender)) continue
                for (line in raw.split('\n')) {
                    val t = line.trim()
                    if (t.length < 3 || t.length > 200) continue
                    if (skipSmallTalk.matches(t)) continue
                    if (t.startsWith("http://", true) || t.startsWith("https://", true)) continue

                    val isWorkdayHabit = workdayHabitCue.containsMatchIn(t)
                    val isAnniversary = anniversaryCue.containsMatchIn(t)
                    if (!isWorkdayHabit && !isAnniversary) continue
                    matchedLines.add(t.take(120))

                    val hm = extractRoughHourMinute(t)
                    val compactTitle = if (t.length > 80) t.take(77) + "..." else t
                    out.add(
                        ExtractedTodoDraft(
                            title = compactTitle,
                            sourceGroupId = slice.groupId,
                            sourceGroupName = slice.groupName,
                            actionType = if (hm != null) "alarm" else "none",
                            actionDetail = hm?.let { "%02d:%02d".format(it.first, it.second) },
                            taskKind = "long_term_template",
                            repeatRule = if (isWorkdayHabit) "workday" else "yearly",
                            repeatAnchor = if (isWorkdayHabit) hm?.let { "%02d:%02d".format(it.first, it.second) } else null,
                            evidenceAt = ts,
                            explicitIntent = true
                        )
                    )
                }
            }
        }
        return dedupeDrafts(out) to matchedLines.distinct()
    }

    private fun isExplicitTaskIntent(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val explicit = Regex("(请|帮我|安排|提醒|闹钟|开会|准备|提交|截止|deadline|纪念日|每周|每月|每年|工作日|吃药|起床)")
        return explicit.containsMatchIn(t)
    }

    private fun classifyTaskKind(title: String): Triple<String, String?, String?> {
        val t = title.trim()
        if (t.isEmpty()) return Triple("short_term_instance", null, null)
        if (Regex("(纪念日|周年|生日)").containsMatchIn(t)) {
            val md = Regex("(\\d{1,2})\\s*[-月]\\s*(\\d{1,2})").find(t)
            val anchor = md?.let {
                val m = it.groupValues[1].toIntOrNull()
                val d = it.groupValues[2].toIntOrNull()
                if (m != null && d != null && m in 1..12 && d in 1..31) "%02d-%02d".format(m, d) else null
            }
            return Triple("long_term_template", "yearly", anchor)
        }
        if (Regex("(工作日).*(起床|吃药|提醒)|((起床|吃药).*(工作日))").containsMatchIn(t)) {
            val hm = extractRoughHourMinute(t)
            val anchor = hm?.let { "%02d:%02d".format(it.first, it.second) }
            return Triple("long_term_template", "workday", anchor)
        }
        return Triple("short_term_instance", null, null)
    }

    private fun normalizeDraftsWithKind(list: List<ExtractedTodoDraft>, fallbackTs: Long): List<ExtractedTodoDraft> {
        val out = mutableListOf<ExtractedTodoDraft>()
        for (d in list) {
            val (kind, rule, anchor) = classifyTaskKind(d.title)
            val eAt = if (d.evidenceAt > 0L) d.evidenceAt else fallbackTs
            val existingAnchor = d.repeatAnchor?.trim()?.ifBlank { null }
            val existingRule = d.repeatRule?.trim()?.lowercase()?.ifBlank { null }
            out.add(
                d.copy(
                    taskKind = kind,
                    repeatRule = existingRule ?: rule,
                    repeatAnchor = existingAnchor ?: anchor,
                    evidenceAt = eAt
                )
            )
        }
        return out
    }

    /** 本地时间提取：支持中文数字（七点、十点半）和阿拉伯数字（07:30） */
    private fun extractRoughHourMinute(text: String): Pair<Int, Int>? {
        val t = text.trim()
        val pm = t.contains("下午") || t.contains("晚上") || t.contains("傍晚")
        // Chinese numeral + 点
        val cnHalf = Regex("([一二三四五六七八九十两零]+点半)").find(t)
        if (cnHalf != null) {
            val cn = cnHalf.groupValues[1].replace("点半", "")
            val h = parseCnHourSimple(cn) ?: return null
            val hour = if (pm && h in 1..11) h + 12 else h
            if (hour in 0..23) return hour to 30
        }
        val cnExact = Regex("([一二三四五六七八九十两零]+点)").find(t)
        if (cnExact != null) {
            val cn = cnExact.groupValues[1].replace("点", "")
            val h = parseCnHourSimple(cn) ?: return null
            val hour = if (pm && h in 1..11) h + 12 else h
            if (hour in 0..23) return hour to 0
        }
        // Arabic HH:mm
        val hm = Regex("""(\d{1,2})\s*[:：]\s*(\d{2})""").find(t)
        if (hm != null) {
            val h = hm.groupValues[1].toIntOrNull() ?: return null
            val m = hm.groupValues[2].toIntOrNull() ?: return null
            if (h in 0..23 && m in 0..59) return h to m
        }
        // Arabic 点
        val hOnly = Regex("""(\d{1,2})\s*点""").find(t)
        if (hOnly != null) {
            val h = hOnly.groupValues[1].toIntOrNull() ?: return null
            var hour = h
            if (pm) {
                if (h in 1..11) hour = h + 12
                else if (h == 12) hour = 12
            }
            if (hour !in 0..23) return null
            val minute = if (t.contains("半")) 30 else 0
            return hour to minute
        }
        return null
    }

    private fun parseCnHourSimple(cn: String): Int? {
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
    private fun dedupeDrafts(list: List<ExtractedTodoDraft>): List<ExtractedTodoDraft> {
        val pickedByKey = linkedMapOf<String, ExtractedTodoDraft>()
        for (d in list) {
            if (d.title.trim().length < 2) continue
            val key = UserTodoStore.logicalDedupKey(
                d.title,
                d.actionType,
                d.actionDetail,
                d.taskKind
            )
            val old = pickedByKey[key]
            if (old == null) {
                pickedByKey[key] = d
                continue
            }
            val keep = when {
                old.taskKind != "long_term_template" && d.taskKind == "long_term_template" -> d
                old.taskKind == "long_term_template" && d.taskKind != "long_term_template" -> old
                !old.explicitIntent && d.explicitIntent -> d
                old.explicitIntent && !d.explicitIntent -> old
                (old.actionDetail ?: "").length < (d.actionDetail ?: "").length -> d
                else -> old
            }
            pickedByKey[key] = keep
        }
        return pickedByKey.values.take(MAX_TODOS_PER_REFRESH)
    }

    private fun callLlm(system: String, user: String, apiKey: String, temperature: Double = 0.35): String {
        val body = SimpleChatRequest(
            model = AIConfig.MODEL.ifBlank { "gpt-4o-mini" },
            messages = listOf(SimpleMsg("system", system), SimpleMsg("user", user)),
            temperature = temperature,
            max_tokens = 2048,
            stream = false
        )
        val req = HttpRequest.newBuilder()
            .uri(URI.create("${AIConfig.requireApiBaseUrl().trimEnd('/')}/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofMillis(AIConfig.TIMEOUT))
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(SimpleChatRequest.serializer(), body)))
            .build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) {
            throw IllegalStateException("HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
        }
        val root = json.parseToJsonElement(resp.body()).jsonObject
        val choices = root["choices"]?.jsonArray ?: return ""
        val first = choices.firstOrNull()?.jsonObject ?: return ""
        val message = first["message"]?.jsonObject ?: return ""
        return message["content"]?.jsonPrimitive?.content ?: ""
    }

    /** 第二个返回值：是否解析到合法 JSON 且含 todos 字段（空数组也算成功） */
    private fun parseTodoJsonStrict(raw: String): Pair<List<ExtractedTodoDraft>, Boolean> {
        val slice = extractJsonObject(raw) ?: return emptyList<ExtractedTodoDraft>() to false
        return try {
            val obj = json.parseToJsonElement(slice).jsonObject
            if (!obj.containsKey("todos")) return emptyList<ExtractedTodoDraft>() to false
            val arr = obj["todos"]?.jsonArray ?: return emptyList<ExtractedTodoDraft>() to false
            val out = mutableListOf<ExtractedTodoDraft>()
            for (el in arr) {
                val o = el.jsonObject
                val title = o["title"]?.jsonPrimitive?.content?.trim() ?: continue
                val gid = o["sourceGroupId"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
                val gname = o["sourceGroupName"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
                val at = o["actionType"]?.jsonPrimitive?.content?.trim()?.lowercase()
                    ?.takeIf { it.isNotEmpty() && it != "null" }
                val ad = o["actionDetail"]?.jsonPrimitive?.content?.trim()
                    ?.takeIf { it.isNotEmpty() }
                val tk = o["taskKind"]?.jsonPrimitive?.content?.trim()?.lowercase()
                    ?.takeIf { it == "long_term_template" || it == "short_term_instance" }
                    ?: "short_term_instance"
                val rr = o["repeatRule"]?.jsonPrimitive?.content?.trim()?.lowercase()
                    ?.takeIf { it.isNotEmpty() }
                val ra = o["repeatAnchor"]?.jsonPrimitive?.content?.trim()
                    ?.takeIf { it.isNotEmpty() }
                out.add(
                    ExtractedTodoDraft(
                        title = title,
                        sourceGroupId = gid,
                        sourceGroupName = gname,
                        actionType = at,
                        actionDetail = ad,
                        taskKind = tk,
                        repeatRule = rr,
                        repeatAnchor = ra
                    )
                )
            }
            out to true
        } catch (e: Exception) {
            println("⚠️ [GroupTodoExtractionService] JSON 解析失败: ${e.message}")
            emptyList<ExtractedTodoDraft>() to false
        }
    }

    private fun extractJsonObject(text: String): String? {
        val t = text.trim()
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(t)
        if (fence != null) {
            val inner = fence.groupValues[1].trim()
            if (inner.startsWith("{")) return inner
        }
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start >= 0 && end > start) return t.substring(start, end + 1)
        return null
    }
}
