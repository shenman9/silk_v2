package com.silk.backend.ai

import com.silk.backend.SilkAgent
import com.silk.backend.models.ChatHistoryEntry
import com.silk.backend.pdf.PDFReportGenerator
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * AI 逐步执行代理
 * 类似于 MoxiTreat 的 DeepSeekDiagnosis.stepwise_diagnosis
 */
class AIStepwiseAgent(
    private val apiKey: String = AIConfig.API_KEY,
    private val sessionName: String = "default_room"
) {
    private val logger = LoggerFactory.getLogger(AIStepwiseAgent::class.java)
    
    private val httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)  // 强制使用 HTTP/1.1，避免 HTTP/2 升级导致 vLLM 兼容问题
        .connectTimeout(Duration.ofMillis(AIConfig.TIMEOUT))
        .build()
    
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    
    private val pdfGenerator = PDFReportGenerator()
    
    /**
     * 步骤执行结果
     */
    data class StepResult(
        val stepName: String,
        val result: String,
        val success: Boolean,
        val error: String? = null
    )
    
    /**
     * 诊断任务执行结果
     */
    data class DiagnosisResult(
        val patientContext: String,
        val stepResults: Map<String, StepResult>,
        val allSuccess: Boolean
    )
    
    /**
     * 步骤化执行诊断任务
     * 
     * @param chatHistory 聊天历史记录
     * @param callback 实时回调函数，用于发送进度消息到聊天室
     *                 参数：(stepType, message, currentStep, totalSteps)
     * @param userName 用户名（用于 PDF 文件命名）
     * @param groupDisplayName 群组显示名称（用于PDF标题和文件名）
     * @param hostId Host用户ID（用于区分医生和病人）
     * @return 诊断结果
     */
    suspend fun executeStepwiseDiagnosis(
        chatHistory: List<ChatHistoryEntry>,
        callback: suspend (stepType: String, message: String, currentStep: Int?, totalSteps: Int?) -> Unit,
        userName: String = "用户",
        groupDisplayName: String? = null,
        hostId: String? = null
    ): DiagnosisResult {
        // 1. 从聊天历史生成患者上下文（区分医生和病人）
        val patientContext = generatePatientContext(chatHistory, hostId)
        
        val totalSteps = AIConfig.TO_DO_LIST.size
        
        // 发送开始消息
        callback("开始", "🤖 Silk Agent 开始分析聊天历史...", null, totalSteps)
        delay(500)
        
        // 显示 To Do List
        val todoListMessage = buildString {
            append("📋 我将按以下步骤执行：\n")
            AIConfig.TO_DO_LIST.forEachIndexed { index, task ->
                append("${index + 1}. $task\n")
            }
        }
        callback("todo_list", todoListMessage, null, totalSteps)
        delay(1000)
        
        // 存储所有步骤的结果
        val stepResults = mutableMapOf<String, StepResult>()
        
        // 累积的信息（传递给下一步）
        var accumulatedInfo = patientContext
        var allSuccess = true
        
        // 累积的执行摘要（用于临时消息显示）
        val executionSummary = StringBuilder()
        executionSummary.append("📋 诊断执行进度\n")
        executionSummary.append("═".repeat(50) + "\n\n")
        
        // 2. 逐步执行 To Do List
        for ((index, taskName) in AIConfig.TO_DO_LIST.withIndex()) {
            val stepNumber = index + 1
            
            try {
                // ✅ 发送步骤开始消息（isIncremental=false，清空之前的累积内容）
                val stepStartMessage = buildString {
                    append("🔄 正在执行第 $stepNumber/$totalSteps 步：$taskName\n")
                    append("─".repeat(50) + "\n")
                    append("请稍候...\n")
                }
                callback("step_start", stepStartMessage, stepNumber, totalSteps)
                delay(300)
                
                // 执行当前步骤（带流式输出）
                val stepResult = executeDiagnosisStep(
                    taskName = taskName,
                    accumulatedInfo = accumulatedInfo,
                    streamingCallback = callback  // 传递 callback 用于流式输出
                )
                
                // 保存结果
                stepResults[taskName] = stepResult
                
                // ✅ 步骤完成后，发送完整的步骤结果（单独一条消息，可转发）
                if (stepResult.success) {
                    val stepCompleteMessage = buildString {
                        append("✅ 步骤 $stepNumber/$totalSteps：$taskName\n")
                        append("─".repeat(50) + "\n")
                        append(stepResult.result)
                    }
                    callback("step_complete", stepCompleteMessage, stepNumber, totalSteps)
                    
                    // 更新累积信息
                    accumulatedInfo = updateAccumulatedInfo(
                        accumulatedInfo,
                        taskName,
                        stepResult.result
                    )
                } else {
                    allSuccess = false
                    val stepFailMessage = buildString {
                        append("❌ 步骤 $stepNumber/$totalSteps：$taskName\n")
                        append("─".repeat(50) + "\n")
                        append("执行失败\n")
                    }
                    callback("step_complete", stepFailMessage, stepNumber, totalSteps)
                }
                
                // 短暂延迟，避免 API 请求过快
                delay(800)
                
            } catch (e: Exception) {
                allSuccess = false
                val errorResult = StepResult(
                    stepName = taskName,
                    result = "",
                    success = false,
                    error = e.message
                )
                stepResults[taskName] = errorResult
                executionSummary.append("❌ [$stepNumber] $taskName - 异常\n\n")
                
                // 继续执行下一步
                continue
            }
        }
        
        // 3. 生成总结报告（✅ 不发送到chat，只用于PDF生成）
        delay(200)
        // ✅ 生成总结报告，但不发送到chat（避免超长消息）
        val summaryReport = generateSummaryReport(stepResults, allSuccess)
        // ✅ 注释掉：不发送总结报告到chat，内容已在PDF中
        // callback("总结报告", summaryReport, null, null)
        
        // 4. 直接生成 PDF 报告（使用总结报告的内容）
        delay(500)
        try {
            val diagnosisResult = DiagnosisResult(
                patientContext = patientContext,
                stepResults = stepResults,
                allSuccess = allSuccess
            )
            
            // 将文字版总结报告也传递给 PDF 生成器，确保内容一致
            val (pdfPath, downloadUrl) = pdfGenerator.generateDiagnosisReportPDF(
                diagnosisResult = diagnosisResult,
                sessionName = sessionName,
                patientInfo = patientContext,
                userName = userName,
                summaryReportText = summaryReport,  // 传递总结报告文本
                groupDisplayName = groupDisplayName  // 传递群组显示名称
            )
            
            // ✅ 修改消息格式：不显示文件路径，只显示友好的文件名
            val displayFileName = pdfPath.substringAfterLast("/")
            val pdfMessage = buildString {
                append("📄 诊断报告已生成\n\n")
                append("文件名：$displayFileName\n\n")
                append("━".repeat(50) + "\n")
                append("📥 点击下方按钮下载完整报告\n")
                append("━".repeat(50) + "\n\n")
                // ✅ 不显示URL路径，改为在Android UI中处理下载
                append("$downloadUrl\n\n")
                append("💡 报告包含完整的诊断信息和建议")
            }
            
            callback("PDF报告", pdfMessage, null, null)
        } catch (e: Exception) {
            logger.error("⚠️ PDF 生成失败: ${e.message}", e)
            callback("PDF报告", "⚠️ PDF 生成失败：${e.message}\n\n文字版总结报告已在上方显示。", null, null)
        }
        
        // 5. 发送完成消息
        delay(500)
        if (allSuccess) {
            callback("完成", "🎉 所有任务已完成！Silk Agent 执行完毕。\n诊断报告（文字版和 PDF 版）已生成。", null, null)
        } else {
            callback("完成", "⚠️ 任务执行完成，但部分步骤失败。请查看详细结果。", null, null)
        }
        
        // 保存诊断结果供医生更新时使用
        saveDiagnosisResults(stepResults)
        
        return DiagnosisResult(
            patientContext = patientContext,
            stepResults = stepResults,
            allSuccess = allSuccess
        )
    }
    
    /**
     * 从聊天历史生成用户上下文
     * @param chatHistory 聊天历史记录
     * @param hostId Host用户ID，用于区分医生和病人
     */
    private fun generatePatientContext(chatHistory: List<ChatHistoryEntry>, hostId: String? = null): String {
        if (chatHistory.isEmpty()) {
            return "【聊天历史】\n暂无聊天记录。用户刚刚加入对话。"
        }
        
        val contextBuilder = StringBuilder()
        contextBuilder.append("【完整对话记录】\n")
        contextBuilder.append("以下是去除AI回复后的完整对话，用于诊断参考：\n\n")
        
        // 只提取非Silk的消息（去除AI回复）
        val userMessages = chatHistory
            .filter { it.senderId != SilkAgent.AGENT_ID }  // 排除Silk消息
            .filter { it.messageType == "TEXT" }  // 只要文本消息
            .filter { !it.content.startsWith("@诊断") && !it.content.startsWith("@diagnosis") }  // 排除命令
            .takeLast(50)  // 最近50条
        
        if (userMessages.isEmpty()) {
            contextBuilder.append("暂无对话记录。\n")
        } else {
            contextBuilder.append("对话记录（共 ${userMessages.size} 条）：\n")
            contextBuilder.append("═".repeat(50) + "\n\n")
            
            userMessages.forEach { entry ->
                val timestamp = java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(entry.timestamp))
                
                // 区分医生（Host）和病人（Guest）
                val rolePrefix = if (hostId != null && entry.senderId == hostId) {
                    "医生${entry.senderName}叙述"
                } else {
                    "病人${entry.senderName}叙述"
                }
                
                contextBuilder.append("[$timestamp] $rolePrefix: ${entry.content}\n\n")
            }
            
            contextBuilder.append("═".repeat(50) + "\n")
        }
        
        // 添加统计信息
        if (userMessages.isNotEmpty()) {
            contextBuilder.append("\n【统计信息】\n")
            contextBuilder.append("参与人数: ${userMessages.map { it.senderId }.distinct().size}\n")
            contextBuilder.append("消息总数: ${userMessages.size}\n")
            
            if (hostId != null) {
                val doctorMsgCount = userMessages.count { it.senderId == hostId }
                val patientMsgCount = userMessages.size - doctorMsgCount
                contextBuilder.append("医生消息: ${doctorMsgCount} 条\n")
                contextBuilder.append("病人消息: ${patientMsgCount} 条\n")
            }
        }
        
        contextBuilder.append("\n【分析要求】\n")
        contextBuilder.append("请仔细阅读以上完整的聊天历史，理解用户的需求、问题和讨论的上下文。\n")
        contextBuilder.append("基于这些对话内容，进行专业的分析和建议。\n")
        
        return contextBuilder.toString()
    }
    
    /**
     * 执行单个诊断步骤
     * 
     * @param taskName 任务名称
     * @param accumulatedInfo 累积的上下文信息
     * @param streamingCallback 流式输出回调（用于实时显示AI输出）
     */
    private suspend fun executeDiagnosisStep(
        taskName: String,
        accumulatedInfo: String,
        streamingCallback: suspend (String, String, Int?, Int?) -> Unit
    ): StepResult {
        logger.info("🚀 开始执行步骤: $taskName")
        
        // 获取该步骤的具体提示词
        val taskPrompt = AIConfig.TO_DO_PROMPT_MAP[taskName] ?: ""
        
        // 构建完整的提示词
        val fullPrompt = """
${AIConfig.COMMON_PROMPT}

═══════════════════════════════════════
以下是完整的聊天历史和上下文信息：
═══════════════════════════════════════

$accumulatedInfo

═══════════════════════════════════════
当前需要执行的任务：
═══════════════════════════════════════

【任务名称】：$taskName

【任务要求】：
$taskPrompt

请基于以上聊天历史和已有的分析结论，完成当前任务。
"""
        
        // ✅ 移除日志输出以提升性能
        // logger.info("📝 [$taskName] Prompt: ${fullPrompt.length} 字符, 上下文: ${accumulatedInfo.length}, 任务: ${taskPrompt.length}, 预计tokens: ~${fullPrompt.length / 3}")
        
        // ✅ 完整prompt只在DEBUG级别打印
        if (logger.isDebugEnabled) {
            logger.debug("━".repeat(80))
            logger.debug("📄 [$taskName] 完整 Prompt：")
            logger.debug("━".repeat(80))
            logger.debug(fullPrompt)
            logger.debug("━".repeat(80))
        }
        
        logger.info("📤 [$taskName] 开始调用 AI API...")
        
        // 如果没有配置 API Key，返回基于上下文的离线结果
        if (apiKey.isEmpty()) {
            return StepResult(
                stepName = taskName,
                result = getOfflineResult(taskName, accumulatedInfo),
                success = true
            )
        }
        
        // 调用 AI API（流式输出，增量发送优化）
        var result = ""
        
        return try {
            val fullTextBuffer = StringBuilder()  // 完整文本累积
            val pendingChunks = StringBuilder()   // 待发送的块缓冲
            var lastSendTime = System.currentTimeMillis()
            var lastSentLength = 0  // ✅ 记录已发送的字符位置
            var sendCount = 0  // 统计发送次数
            var totalBytesSent = 0  // 统计总字节数
            
            // 使用流式API调用，带智能缓冲
            result = callAIApiStreaming(fullPrompt) { chunk ->
                // 累积到完整文本
                fullTextBuffer.append(chunk)
                pendingChunks.append(chunk)
                
                val currentTime = System.currentTimeMillis()
                val timeSinceLastSend = currentTime - lastSendTime
                
                // ✅ 优化：累积3行（换行符）再发送，更频繁更新
                val newlineCount = pendingChunks.count { it == '\n' }
                val shouldSend = newlineCount >= 3 ||  // ✅ 改为3行更新一次
                                 pendingChunks.length >= 300 ||  // ✅ 减少到300字符
                                 timeSinceLastSend >= 1500  // ✅ 改为1.5秒兜底
                
                if (shouldSend && pendingChunks.isNotEmpty()) {
                    // ✅ 计算增量内容（只发送新增的部分）
                    val currentLength = fullTextBuffer.length
                    val incrementalText = fullTextBuffer.substring(lastSentLength, currentLength)
                    
                    // 构建增量消息（只包含新增内容）
                    val streamingMessage = incrementalText
                    
                    // 统计信息
                    sendCount++
                    val messageBytes = streamingMessage.toByteArray().size
                    totalBytesSent += messageBytes
                    
                    // ✅ 移除日志输出以提升性能
                    // if (sendCount % 20 == 1) {
                    //     logger.info("📤 [$taskName] 发送进度: 第 $sendCount 次, 累积 $currentLength 字符, 包含 $newlineCount 个换行")
                    // }
                    
                    // ✅ 以增量临时消息方式发送（isIncremental=true）
                    streamingCallback("streaming_incremental", streamingMessage, null, null)
                    
                    // 更新已发送位置
                    lastSentLength = currentLength
                    pendingChunks.clear()
                    lastSendTime = currentTime
                }
            }
            
            // 最后一次发送（确保所有内容都显示）
            if (pendingChunks.isNotEmpty()) {
                val incrementalText = fullTextBuffer.substring(lastSentLength)
                streamingCallback("streaming_incremental", incrementalText, null, null)
                
                sendCount++
                totalBytesSent += incrementalText.toByteArray().size
            }
            
            // ✅ 移除日志输出以提升性能
            val finalLength = fullTextBuffer.length
            // if (finalLength > 0) {
            //     val avgCharsPerSend = if (sendCount > 0) finalLength / sendCount else 0
            //     logger.info("✅ [$taskName] 流式输出完成: $finalLength 字符, 发送 $sendCount 次, 平均 $avgCharsPerSend 字符/次")
            // }
            
            // ✅ 移除重复的完成日志
            StepResult(
                stepName = taskName,
                result = result,
                success = true
            )
        } catch (e: Exception) {
            logger.error("❌ 步骤异常: $taskName - ${e.message}", e)
            
            // 如果已经接收到部分数据，返回部分数据而不是空字符串
            if (result.isNotEmpty()) {
                logger.warn("⚠️ 步骤部分完成: $taskName (已接收 ${result.length} 字符)")
                StepResult(
                    stepName = taskName,
                    result = result + "\n\n⚠️ 注意：此步骤因超时或异常而提前结束，以上为部分结果。",
                    success = true,  // 标记为成功，因为有部分数据
                    error = null
                )
            } else {
                logger.error("❌ 步骤完全失败: $taskName - 无数据返回")
                StepResult(
                    stepName = taskName,
                    result = "",
                    success = false,
                    error = "步骤执行失败: ${e.message}"
                )
            }
        }
    }
    
    /**
     * 更新累积的诊断信息
     */
    private fun updateAccumulatedInfo(
        currentInfo: String,
        taskName: String,
        stepResult: String
    ): String {
        // 提取关键结论
        val conclusion = extractConclusion(taskName, stepResult)
        
        // 拼接到累积信息中
        return """$currentInfo

【$taskName 的结论】：
$conclusion
"""
    }
    
    /**
     * 从完整结果中提取关键结论
     */
    private fun extractConclusion(taskName: String, fullResult: String): String {
        // 如果结果太长，截取关键部分
        return if (fullResult.length > 300) {
            val lines = fullResult.split("\n")
            val keyLines = lines.filter { line ->
                line.contains("总结") || 
                line.contains("结论") || 
                line.contains("需求") ||
                line.contains("计划") ||
                line.startsWith("1.") ||
                line.startsWith("2.") ||
                line.startsWith("3.")
            }
            
            if (keyLines.isNotEmpty()) {
                keyLines.take(5).joinToString("\n")
            } else {
                fullResult.take(300) + "..."
            }
        } else {
            fullResult
        }
    }
    
    /**
     * 调用 AI API（非流式）
     */
    private suspend fun callAIApi(prompt: String): String {
        val requestBody = ApiRequest(
            model = AIConfig.MODEL,
            messages = listOf(
                ApiMessage(role = "user", content = prompt)
            ),
            temperature = 0.7,
            max_tokens = 65536,  // ✅ 提升到65536，允许生成超详细的诊断内容
            stream = false
        )
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(AIConfig.requireApiBaseUrl()))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(requestBody)))
            .timeout(Duration.ofMillis(AIConfig.TIMEOUT))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        return if (response.statusCode() == 200) {
            val apiResponse = json.decodeFromString<ApiResponse>(response.body())
            apiResponse.choices.firstOrNull()?.message?.content 
                ?: "API 返回空结果"
        } else {
            throw Exception("API 调用失败：${response.statusCode()} - ${response.body()}")
        }
    }
    
    /**
     * 调用 AI API（流式输出）
     * 
     * @param prompt 提示词
     * @param onChunk 每接收到一个文本块时的回调函数（用于实时显示）
     * @return 完整的响应文本
     */
    private suspend fun callAIApiStreaming(
        prompt: String,
        onChunk: suspend (String) -> Unit
    ): String {
        logger.info("🌐 准备发送 API 请求...")
        logger.info("   模型: ${AIConfig.MODEL}")
        // 移除详细日志输出以提升性能
        // logger.info("   API地址: ${AIConfig.API_BASE_URL}")
        // logger.info("   Prompt长度: ${prompt.length} 字符")
        // logger.info("   超时设置: ${AIConfig.TIMEOUT}ms (${AIConfig.TIMEOUT/1000}秒)")
        
        val requestBody = ApiRequest(
            model = AIConfig.MODEL,
            messages = listOf(
                ApiMessage(role = "user", content = prompt)
            ),
            temperature = 0.7,
            max_tokens = 65536,  // ✅ 提升到65536，允许生成超详细的诊断内容
            stream = true  // 启用流式输出
        )
        
        val startTime = System.currentTimeMillis()
        logger.info("🚀 发送请求时间: ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(startTime))}")
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(AIConfig.requireApiBaseUrl()))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(requestBody)))
            .timeout(Duration.ofMillis(AIConfig.TIMEOUT))
            .build()
        
        // 使用 InputStream 处理流式响应
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            logger.error("❌ HTTP 请求失败 (耗时 ${elapsed}ms): ${e.message}", e)
            throw e
        }
        
        val responseTime = System.currentTimeMillis()
        val requestDuration = responseTime - startTime
        logger.info("✅ 收到 HTTP 响应 (耗时 ${requestDuration}ms)")
        logger.info("   状态码: ${response.statusCode()}")
        
        if (response.statusCode() != 200) {
            logger.error("❌ API 返回错误状态码: ${response.statusCode()}")
            throw Exception("API 调用失败：${response.statusCode()}")
        }
        
        val fullText = StringBuilder()
        var lastDataTime = System.currentTimeMillis()
        val idleTimeoutMs = 30000L  // 30秒空闲超时
        var dataChunkCount = 0  // 数据块计数
        
        // 移除日志输出以提升性能
        // logger.info("📖 开始读取流式响应...")
        
        // 使用 withTimeout 包裹整个读取过程，防止永久挂起
        try {
            kotlinx.coroutines.withTimeout(AIConfig.TIMEOUT + 10000) {  // 总超时：70秒
                // 逐行读取 SSE（Server-Sent Events）数据
                response.body().bufferedReader().use { reader ->
                    var line: String?
                    var emptyLineCount = 0  // 连续空行计数
                    var lineCount = 0
                    
                    while (true) {
                        // 检查空闲超时
                        val idleTime = System.currentTimeMillis() - lastDataTime
                        if (idleTime > idleTimeoutMs) {
                            // 移除详细日志输出
                            // logger.warn("⚠️ 流式读取空闲超时（${idleTime}ms 无新数据），主动中断")
                            // logger.warn("   已接收数据: ${fullText.length} 字符, ${dataChunkCount} 个数据块")
                            break
                        }
                        
                        // 移除心跳日志以提升性能
                        // if (idleTime > 0 && idleTime % 10000 < 100) {
                        //     logger.info("💓 流式读取中... (已等待 ${idleTime/1000}秒, 已接收 ${fullText.length} 字符)")
                        // }
                        
                        // 非阻塞式读取一行
                        line = try {
                            reader.readLine()
                        } catch (e: Exception) {
                            logger.warn("⚠️ 读取行失败: ${e.message}", e)
                            break
                        }
                        
                        lineCount++
                        
                        // 流结束
                        if (line == null) {
                            // 移除日志输出以提升性能
                            // logger.info("✅ 流正常结束（收到 null）")
                            // logger.info("   共读取 $lineCount 行, 接收 ${fullText.length} 字符")
                            break
                        }
                        
                        // 跟踪空行（连续多个空行可能表示流结束）
                        if (line.trim().isEmpty()) {
                            emptyLineCount++
                            if (emptyLineCount > 5) {
                                logger.warn("⚠️ 检测到连续 $emptyLineCount 个空行，可能流已结束")
                                break
                            }
                            continue
                        } else {
                            emptyLineCount = 0
                        }
                        
                        // SSE 格式：data: {"choices":[{"delta":{"content":"文本"},...}]}
                        if (line.startsWith("data: ")) {
                            lastDataTime = System.currentTimeMillis()  // 更新最后接收数据时间
                            dataChunkCount++
                            
                            val jsonData = line.substring(6).trim()
                            
                            // 跳过特殊标记
                            if (jsonData == "[DONE]") {
                                // 移除日志输出以提升性能
                                // logger.info("✅ 收到 [DONE] 标记，流正常结束 - 共接收 $dataChunkCount 个数据块, ${fullText.length} 字符")
                                break
                            }
                            
                            try {
                                // 解析流式响应
                                val streamResponse = json.decodeFromString<StreamResponse>(jsonData)
                                val content = streamResponse.choices.firstOrNull()?.delta?.content
                                
                                if (content != null) {
                                    fullText.append(content)
                                    // 实时回调，发送到前端
                                    onChunk(content)
                                    
                                    // 移除日志输出以提升性能
                                    // if (dataChunkCount % 200 == 0) {
                                    //     logger.info("📊 AI模型流式输出进度: $dataChunkCount 数据块, ${fullText.length} 字符")
                                    // }
                                }
                            } catch (e: Exception) {
                                // 移除详细日志输出
                                // logger.warn("⚠️ 解析流式数据失败 (行$lineCount): ${line.take(100)}...")
                            }
                        }
                        
                        // 短暂让出 CPU，避免 CPU 100%
                        kotlinx.coroutines.delay(1)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.error("❌ 流式读取总超时（70秒），当前已接收 ${fullText.length} 字符", e)
            // 返回已接收的部分数据
        } catch (e: Exception) {
            logger.error("❌ 流式读取异常: ${e.message}", e)
            throw e
        }
        
        return fullText.toString()
    }
    
    /**
     * 生成完整的总结报告
     */
    private suspend fun generateSummaryReport(
        stepResults: Map<String, StepResult>,
        allSuccess: Boolean
    ): String {
        // 先生成原始总结
        val rawReport = buildString {
            append("执行状态: ${if (allSuccess) "全部成功" else "部分失败"}\n")
            append("完成步骤: ${stepResults.count { it.value.success }}/${stepResults.size}\n\n")
            
            append("各步骤总结：\n\n")
            
            stepResults.forEach { (taskName, result) ->
                if (result.success) {
                    append("$taskName:\n")
                    append("${result.result}\n\n")
                } else {
                    append("$taskName: 执行失败\n")
                    append("错误: ${result.error}\n\n")
                }
            }
        }
        
        // 如果有API Key，使用AI美化格式
        val formattedReport = if (apiKey.isNotEmpty()) {
            try {
                formatReportWithAI(rawReport)
            } catch (e: Exception) {
                logger.warn("⚠️ AI格式化失败，使用原始格式: ${e.message}", e)
                generateFallbackReport(stepResults, allSuccess)
            }
        } else {
            generateFallbackReport(stepResults, allSuccess)
        }
        
        return formattedReport
    }
    
    /**
     * 使用AI美化总结报告格式
     * 将报告结构化，添加章节标记
     */
    private suspend fun formatReportWithAI(rawReport: String): String {
        val formatPrompt = """
你是一位专业的医疗文档编辑专家。请将以下中医诊断报告整理成结构化、美观的格式。

【格式要求】：
1. 使用标记来标识不同层级：
   - ##章节标题## 用于主要章节（如"一、中西医诊断"）
   - ###小节标题### 用于子章节（如"1. 西医诊断"）
   - ####要点标题#### 用于要点（如"【病因】"）

2. 章节编排：
   - 一、中西医诊断
   - 二、辨证分型与病因病机
   - 三、体质诊断
   - 四、综合分析
   - 五、治疗方案
     - 中药处方
     - 中成药推荐
     - 针灸治疗
     - 艾灸治疗
     - 生活调养
   - 六、预后说明

3. 内容要求：
   - 删除技术性标记（如"✅"、"═"等）
   - 保持内容的专业性和准确性
   - 段落要清晰，层次分明
   - 每个章节内容要完整

【原始报告】：
$rawReport

请按照上述要求重新整理报告，使其更专业、更易读。只输出格式化后的报告内容，不要添加额外说明。
"""
        
        return callAIApi(formatPrompt)
    }
    
    /**
     * 生成备用格式的总结报告（当AI不可用时）
     */
    private fun generateFallbackReport(
        stepResults: Map<String, StepResult>,
        allSuccess: Boolean
    ): String {
        val report = buildString {
            append("##承山堂中医诊断总结报告##\n\n")
            
            append("###诊断执行状态###\n")
            append("执行结果: ${if (allSuccess) "✓ 全部成功" else "⚠ 部分失败"}\n")
            append("完成步骤: ${stepResults.count { it.value.success }}/${stepResults.size}\n\n")
            
            // 按章节组织内容
            append("##一、中西医诊断##\n\n")
            val diagnosis = stepResults["中西医疾病的诊断"]
            if (diagnosis != null && diagnosis.success) {
                append("${diagnosis.result}\n\n")
            }
            
            append("##二、辨证分型与病因病机##\n\n")
            
            append("###1. 辨证分型###\n")
            val dialectics = stepResults["中医辨证分型"]
            if (dialectics != null && dialectics.success) {
                append("${dialectics.result}\n\n")
            }
            
            append("###2. 病因病机###\n")
            val pathogenesis = stepResults["中医的病因病机分析"]
            if (pathogenesis != null && pathogenesis.success) {
                append("${pathogenesis.result}\n\n")
            }
            
            append("##三、体质诊断##\n\n")
            val constitution = stepResults["中医体质诊断"]
            if (constitution != null && constitution.success) {
                append("${constitution.result}\n\n")
            }
            
            append("##四、综合分析##\n\n")
            val analysis = stepResults["分析汇总"]
            if (analysis != null && analysis.success) {
                append("${analysis.result}\n\n")
            }
            
            append("##五、治疗方案##\n\n")
            
            append("###1. 中药处方###\n")
            val prescription = stepResults["中医处方建议"]
            if (prescription != null && prescription.success) {
                append("${prescription.result}\n\n")
            }
            
            append("###2. 中成药推荐###\n")
            val patent = stepResults["推荐中成药"]
            if (patent != null && patent.success) {
                append("${patent.result}\n\n")
            }
            
            append("###3. 针灸治疗###\n")
            val acupuncture = stepResults["针灸处方及针灸方法"]
            if (acupuncture != null && acupuncture.success) {
                append("${acupuncture.result}\n\n")
            }
            
            append("###4. 艾灸治疗###\n")
            val moxibustion = stepResults["艾灸选穴及艾灸方法"]
            if (moxibustion != null && moxibustion.success) {
                append("${moxibustion.result}\n\n")
            }
            
            append("###5. 生活调养###\n")
            val lifestyle = stepResults["饮食运动起居调养方案"]
            if (lifestyle != null && lifestyle.success) {
                append("${lifestyle.result}\n\n")
            }
            
            append("##六、预后说明##\n\n")
            val prognosis = stepResults["预后说明"]
            if (prognosis != null && prognosis.success) {
                append("${prognosis.result}\n\n")
            }
        }
        
        return report.toString()
    }
    
    /**
     * 提取关键摘要
     */
    private fun extractKeySummary(fullText: String): String {
        // 提取前3个要点或前150字
        val lines = fullText.split("\n").filter { it.trim().isNotEmpty() }
        val keyLines = lines.filter { line ->
            line.trim().matches(Regex("^[0-9]+\\..*")) || // 数字列表
            line.contains("需求") ||
            line.contains("问题") ||
            line.contains("建议") ||
            line.contains("结论")
        }
        
        return if (keyLines.isNotEmpty()) {
            keyLines.take(3).joinToString("\n   ")
        } else if (fullText.length > 150) {
            fullText.take(150) + "..."
        } else {
            fullText
        }
    }
    
    /**
     * 离线模式结果（当没有 API Key 时）
     * 基于 MoxiTreat 的 11 步流程提供示例结果
     */
    private fun getOfflineResult(taskName: String, context: String): String {
        // 从上下文中提取症状信息
        val symptoms = extractSymptomsFromContext(context)
        val symptomsText = if (symptoms.isNotEmpty()) symptoms.joinToString("、") else "相关症状"
        
        return when (taskName) {
            "中西医疾病的诊断" -> """
【西医诊断】
1. 西医诊断：基于患者主诉「$symptomsText」，可能的疾病包括：[需要结合四诊进行诊断]
2. 中医诊断：根据症状表现，中医病名可能为：[需要辨证论治]
3. 鉴别诊断：需要排除类似疾病，建议进一步检查
4. 诊断依据：根据患者主诉症状和聊天历史中的描述

【离线模式】配置 API Key 后可获得详细的专业诊断。
""".trimIndent()
            
            "中医辨证分型" -> """
【中医辨证分型】
1. 八纲辨证：根据症状表现，初步判断为 [里证/表证]、[寒证/热证]、[虚证/实证]
2. 脏腑辨证：可能涉及脏腑 [需要四诊合参]
3. 气血津液辨证：初步判断为 [气虚/血瘀/津液不足等]
4. 六经辨证：根据症状可能属于 [太阳/阳明/少阳等]
5. 主要证型总结：[需要专业中医师综合判断]

【离线模式】配置 API Key 后可获得详细的辨证分析。
""".trimIndent()
            
            "中医的病因病机分析" -> """
【病因病机分析】
1. 病因：根据患者情况，可能涉及外感、内伤等因素
2. 病机：发病机理需要结合四诊信息综合判断
3. 病位：病变主要涉及的脏腑和经络
4. 病性：虚实寒热的具体性质
5. 病势：疾病的发展趋势和预后

【离线模式】配置 API Key 后可获得详细的病因病机分析。
""".trimIndent()
            
            "中医体质诊断" -> """
【中医体质诊断】
1. 九种体质分类：根据表现，需要评估是否属于气虚质、阳虚质、阴虚质等
2. 主要体质类型：需要通过详细问诊确定
3. 兼夹体质：可能存在多种体质兼夹
4. 体质与疾病关系：体质因素在疾病发生发展中的作用
5. 体质调理建议：根据体质类型提供调理方案

【离线模式】配置 API Key 后可获得详细的体质分析。
""".trimIndent()
            
            "分析汇总" -> """
【综合分析汇总】
1. 整体病情评估：综合中西医诊断，患者病情需要系统治疗
2. 中西医诊断的关联性：中西医诊断相互印证，病机明确
3. 核心病机总结：[需要根据前面的辨证分析总结]
4. 治疗的关键点：治疗需要注重调理脏腑功能，扶正祛邪
5. 预期治疗难度：根据病情轻重和患者配合度综合评估

【离线模式】配置 API Key 后可获得详细的综合分析。
""".trimIndent()
            
            "中医处方建议" -> """
【中医处方建议】
1. 治疗法则：根据证型，采用相应的治法和治则
2. 推荐方剂：建议选用经典方剂，如 [需要专业中医师开具]
3. 方剂组成：药物名称及剂量需要根据患者具体情况调整
4. 方解：方剂配伍体现了中医的整体观念和辨证论治原则
5. 加减化裁：根据兼症和体质进行个性化调整
6. 服用方法：煎服方法、服用时间、疗程等需要医嘱指导

【离线模式】配置 API Key 后可获得具体的处方建议。
⚠️ 重要提示：中药处方需要由持证中医师开具，切勿自行配药。
""".trimIndent()
            
            "推荐中成药" -> """
【推荐中成药】
1. 推荐中成药：根据证型，可考虑相应的中成药（需医师指导）
2. 功效主治：各药物的具体功效和适应证
3. 用法用量：严格按照说明书或医嘱服用
4. 注意事项：注意禁忌症和不良反应
5. 配合建议：中成药可与汤药配合使用，增强疗效

【离线模式】配置 API Key 后可获得具体的中成药建议。
⚠️ 重要提示：请在医师指导下使用中成药。
""".trimIndent()
            
            "针灸处方及针灸方法" -> """
【针灸治疗方案】
1. 主穴选择：根据证型选择主要穴位
2. 配穴选择：配合辅助穴位增强疗效
3. 针刺方法：进针方向、深度、手法需要专业针灸师操作
4. 灸法选择：温针灸、艾灸等方法
5. 疗程安排：建议每周2-3次，疗程因人而异
6. 注意事项：孕妇、出血性疾病等禁忌

【离线模式】配置 API Key 后可获得详细的针灸方案。
⚠️ 重要提示：针灸需要由专业针灸师进行，切勿自行操作。
""".trimIndent()
            
            "艾灸选穴及艾灸方法" -> """
【艾灸治疗方案】
1. 艾灸主穴：根据证型和病机选择主要艾灸穴位
2. 艾灸配穴：辅助穴位配合，增强疗效
3. 艾灸方法：
   - 艾灸类型：温和灸、隔姜灸等
   - 施灸时间：每穴15-20分钟
   - 操作要点：保持适当距离，感觉温热为度
4. 灸量把握：
   - 施灸时间：根据个体耐受度调整
   - 施灸强度：以患者感觉舒适为宜
5. 疗程安排：
   - 每日或隔日1次
   - 连续施灸7-10天为一疗程
6. 注意事项：
   - 禁忌症：孕妇、急性炎症、出血倾向等
   - 注意事项：避免烫伤，保持通风
   - 灸后调护：注意保暖，避免受风

【离线模式】配置 API Key 后可获得详细的艾灸方案。
⚠️ 重要提示：首次艾灸建议在专业人士指导下进行。
""".trimIndent()
            
            "饮食运动起居调养方案" -> """
【生活调养方案】
1. 饮食调理：
   - 宜食：根据证型选择合适的食材
   - 忌食：避免辛辣刺激、生冷食物（具体需根据证型）
   - 食疗方：建议在医师指导下选用
2. 运动建议：
   - 运动方式：适度的有氧运动，如散步、八段锦
   - 运动时间：每天30分钟左右
   - 注意事项：避免剧烈运动，量力而行
3. 起居调摄：
   - 作息时间：规律作息，早睡早起
   - 生活习惯：保持心情舒畅
   - 情志调理：避免过度紧张和焦虑
4. 季节养生：根据四时节气调整养生方案

【离线模式】配置 API Key 后可获得个性化的调养建议。
""".trimIndent()
            
            "预后说明" -> """
【预后说明】
1. 疾病预后：需要根据具体病情判断，及时治疗预后较好
2. 治疗周期：根据病情轻重，一般需要1-3个月
3. 复发可能：注意调理可降低复发风险
4. 注意事项：
   - 遵医嘱服药
   - 注意饮食起居调理
   - 保持良好心态
5. 复诊建议：建议1-2周复诊一次，观察治疗效果
6. 长期管理：慢性疾病需要长期调理，定期复查

【离线模式】配置 API Key 后可获得详细的预后分析。

【免责声明】
本诊断分析仅供参考，不构成医疗建议。
请务必咨询专业中医师进行正式诊断和治疗。
""".trimIndent()
            
            else -> "【$taskName】\n正在处理患者信息..."
        }
    }
    
    // 从聊天历史中提取症状信息
    private fun extractSymptomsFromContext(context: String): List<String> {
        val symptoms = mutableListOf<String>()
        val commonSymptoms = listOf(
            "头痛", "失眠", "疲劳", "咳嗽", "发热", "腹痛", "腹泻",
            "便秘", "食欲不振", "心悸", "胸闷", "腰痛", "关节痛"
        )
        
        commonSymptoms.forEach { symptom ->
            if (context.contains(symptom)) {
                symptoms.add(symptom)
            }
        }
        
        return symptoms
    }
    
    // 辅助方法：从上下文中提取最后的用户输入
    private fun extractLastUserInput(context: String): String {
        val userMessagePattern = Regex("\\[用户\\][^:]+: (.+)")
        val matches = userMessagePattern.findAll(context).toList()
        return matches.lastOrNull()?.groupValues?.get(1)?.take(50) ?: "（暂无）"
    }
    
    // 分析用户意图
    private fun analyzeUserIntent(input: String): String {
        return when {
            input.contains("学习") || input.contains("了解") -> "学习知识或技能"
            input.contains("问题") || input.contains("错误") -> "解决问题"
            input.contains("你好") || input.contains("hello") -> "建立对话"
            input.contains("帮助") || input.contains("help") -> "寻求帮助"
            else -> "进行对话交流"
        }
    }
    
    // 识别主要问题
    private fun identifyMainIssue(input: String): String {
        return when {
            input.contains("如何") || input.contains("怎么") -> "寻求方法指导"
            input.contains("为什么") -> "理解原理或原因"
            input.contains("什么") -> "获取信息和知识"
            input.isEmpty() -> "尚未明确"
            else -> "需要进一步沟通了解"
        }
    }
    
    // 提取讨论主题
    private fun extractTopics(context: String): String {
        return when {
            context.contains("编程") || context.contains("代码") -> "编程和技术"
            context.contains("学习") -> "学习和教育"
            context.contains("Kotlin") -> "Kotlin 编程语言"
            context.contains("项目") -> "项目开发"
            else -> "日常对话"
        }
    }
    
    // 分析用户情绪
    private fun analyzeUserSentiment(context: String): String {
        return when {
            context.contains("谢谢") || context.contains("感谢") -> "积极感激"
            context.contains("困难") || context.contains("问题") -> "遇到挑战，需要帮助"
            context.contains("好") || context.contains("棒") -> "积极正面"
            else -> "中性，开放沟通"
        }
    }
    
    // 识别未解决的问题
    private fun identifyOpenQuestions(input: String): String {
        return when {
            input.contains("？") || input.contains("?") -> "存在明确的疑问"
            input.contains("如何") || input.contains("怎么") -> "需要具体的方法指导"
            else -> "待用户进一步表达"
        }
    }
    
    // 生成建议
    private fun generateRecommendations(input: String): String {
        return when {
            input.contains("学习") -> "提供学习资源和循序渐进的学习路径"
            input.contains("问题") -> "定位问题根源，提供解决方案"
            input.contains("Kotlin") -> "推荐 Kotlin 官方文档和实践项目"
            else -> "根据用户具体需求提供个性化建议"
        }
    }
    
    /**
     * 处理医生诊断更新（Host角色的额外诊断）
     * 整合之前的诊断结果，添加医生医嘱，重新生成完整报告
     * 
     * @param chatHistory 聊天历史
     * @param doctorMessage 医生的诊断消息
     * @param callback 回调函数
     * @param userName 患者名称
     * @param groupDisplayName 群组显示名称
     */
    suspend fun processDoctorDiagnosisUpdate(
        chatHistory: List<ChatHistoryEntry>,
        doctorMessage: String,
        callback: suspend (stepType: String, message: String, currentStep: Int?, totalSteps: Int?) -> Unit,
        userName: String = "用户",
        groupDisplayName: String? = null
    ) {
        // 1. 发送处理开始消息
        callback("processing", "🩺 正在处理医生的诊断意见...", null, null)
        delay(500)
        
        // 2. 生成患者上下文（暂时不区分医生病人，因为医生更新时已经在医嘱中）
        val patientContext = generatePatientContext(chatHistory, null)
        
        // 3. 尝试加载之前的诊断结果和时间戳
        val previousDiagnosis = loadPreviousDiagnosisResults()
        val previousDiagnosisResults = previousDiagnosis?.first
        val lastDiagnosisTime = previousDiagnosis?.second ?: 0L
        
        // 4. 过滤聊天历史：只使用上次诊断之后的新消息
        val newMessages = if (lastDiagnosisTime > 0) {
            val filtered = chatHistory.filter { it.timestamp > lastDiagnosisTime }
            val dateTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(java.util.Date(lastDiagnosisTime))
            logger.info("📋 过滤消息:")
            logger.info("   上次诊断时间: $dateTime")
            logger.info("   总消息数: ${chatHistory.size}")
            logger.info("   新消息数: ${filtered.size}")
            filtered
        } else {
            logger.info("📋 无历史时间戳，使用所有消息")
            chatHistory
        }
        
        // 5. 构建新消息的上下文
        val newMessagesContext = if (newMessages.isNotEmpty()) {
            buildString {
                append("【上次诊断后的新对话】\n")
                newMessages.forEach { entry ->
                    val timestamp = java.text.SimpleDateFormat("MM-dd HH:mm")
                        .format(java.util.Date(entry.timestamp))
                    append("[$timestamp] ${entry.senderName}: ${entry.content}\n")
                }
            }
        } else {
            "【上次诊断后的新对话】\n暂无新对话"
        }
        
        // 6. 构建之前诊断的摘要（✅ 优化：只取关键信息，减少token消耗）
        val previousDiagnosisSummary = if (previousDiagnosisResults != null && previousDiagnosisResults.isNotEmpty()) {
            buildString {
                append("【之前的诊断记录（摘要）】\n")
                // ✅ 只保留最重要的几个步骤，每个步骤最多100字
                val keySteps = listOf("中医诊断", "西医诊断", "治疗方案")
                previousDiagnosisResults.filter { (stepName, _) -> 
                    keySteps.any { key -> stepName.contains(key) }
                }.forEach { (stepName, stepResult) ->
                    append("$stepName：${stepResult.result.take(100)}...\n")
                }
            }
        } else {
            "【之前的诊断记录】\n暂无之前的诊断记录"
        }
        
        // ✅ 优化Prompt：更简洁直接，减少生成时间
        val systemPrompt = """
基于以下信息，快速生成更新的诊断报告：

$previousDiagnosisSummary

$newMessagesContext

【医生最新意见】
$doctorMessage

要求（简洁回答，不超过500字）：
1. 整合新信息更新诊断
2. 明确治疗方案调整
3. 给出关键注意事项

直接输出更新的诊断报告，使用清晰的分段格式。
        """.trimIndent()
        
        // 5. 调用AI模型（使用Streaming方式）
        callback("processing", "🩺 AI正在快速整合诊断信息...", null, 3)
        delay(100)  // ✅ 减少延迟
        
        val updatedDiagnosis = try {
            // 使用streaming方式调用AI
            val response = StringBuilder()
            var lastSentContent = ""
            var lastSentTime = System.currentTimeMillis()
            
            generateQuickResponse(systemPrompt) { content, isComplete ->
                response.clear()
                response.append(content)
                
                if (!isComplete) {
                    // 计算增量内容（只发送新增的部分）
                    val newContent = if (content.length > lastSentContent.length) {
                        content.substring(lastSentContent.length)
                    } else {
                        ""
                    }
                    
                    // ✅ 改进更新条件：每3行或每2秒更新一次
                    val currentTime = System.currentTimeMillis()
                    val timeSinceLastSend = currentTime - lastSentTime
                    val newlineCount = content.substring(lastSentContent.length).count { it == '\n' }
                    
                    if (newContent.isNotEmpty() && (newlineCount >= 3 || timeSinceLastSend >= 2000)) {
                        // ✅ 每3行或每2秒发送增量内容
                        callback("streaming_incremental", newContent, 1, 3)
                        lastSentContent = content
                        lastSentTime = currentTime
                    }
                }
            }
            
            response.toString()
        } catch (e: Exception) {
            logger.error("❌ AI调用失败: ${e.message}", e)
            "⚠️ AI模型调用失败，无法更新诊断"
        }
        
        // 6. 发送AI的诊断更新结果（最终消息）
        callback("doctor_update", updatedDiagnosis, 2, 3)
        delay(500)
        
        // 7. 整合所有步骤并重新生成PDF报告
        try {
            // 构建完整的步骤结果（医生医嘱 + 之前的诊断步骤）
            val stepResults = mutableMapOf<String, StepResult>()
            
            // 第一步：添加医生诊断更新（放在最前面）
            stepResults["医生诊断意见"] = StepResult(
                stepName = "医生诊断意见",
                result = """
【医生医嘱】
$doctorMessage

【说明】
以下诊断结果是基于初步诊断报告和上述医生医嘱综合更新后的结果。
                """.trimIndent(),
                success = true
            )
            
            // 整合之前的诊断步骤（如果有）
            if (previousDiagnosisResults != null) {
                stepResults.putAll(previousDiagnosisResults)
            }
            
            // 添加AI更新诊断步骤
            stepResults["AI综合诊断（更新）"] = StepResult(
                stepName = "AI综合诊断（更新）",
                result = updatedDiagnosis,
                success = true
            )
            
            val diagnosisResult = DiagnosisResult(
                patientContext = patientContext,
                stepResults = stepResults,
                allSuccess = true
            )
            
            // 生成PDF（文件名和标题使用当前时间）
            val (pdfPath, downloadUrl) = pdfGenerator.generateDiagnosisReportPDF(
                diagnosisResult = diagnosisResult,
                sessionName = sessionName,
                patientInfo = patientContext,
                userName = userName,
                summaryReportText = updatedDiagnosis,
                groupDisplayName = groupDisplayName
            )
            
            val pdfMessage = buildString {
                append("📄 诊断更新报告已生成\n\n")
                append("文件名：${pdfPath.substringAfterLast("/")}\n\n")
                append("━".repeat(50) + "\n")
                append("📥 下载更新报告\n")
                append("━".repeat(50) + "\n\n")
                append("$downloadUrl\n\n")
                append("💡 基于医生的专业意见，诊断已更新\n")
                append("   报告包含：医生医嘱 + 之前诊断 + 综合更新")
            }
            
            callback("PDF报告", pdfMessage, null, null)
            
            // 保存当前诊断结果供下次使用
            saveDiagnosisResults(stepResults)
            
        } catch (e: Exception) {
            logger.error("❌ 生成更新PDF失败: ${e.message}", e)
        }
    }
    
    /**
     * 加载之前的诊断结果
     * @return Pair<诊断结果, 诊断时间戳>
     */
    private fun loadPreviousDiagnosisResults(): Pair<Map<String, StepResult>, Long>? {
        return try {
            // 尝试多个可能的路径
            val possiblePaths = listOf(
                "chat_history/$sessionName/last_diagnosis.json",
                "backend/chat_history/$sessionName/last_diagnosis.json"
            )
            
            val file = possiblePaths
                .map { java.io.File(it) }
                .firstOrNull { it.exists() }
            
            if (file != null && file.exists()) {
                logger.info("📖 正在加载诊断历史:")
                logger.info("   找到文件: ${file.absolutePath}")
                logger.info("   大小: ${file.length()} bytes")
                
                val json = file.readText()
                
                // 使用kotlinx.serialization.json解析
                val jsonElement = Json.parseToJsonElement(json)
                val jsonObject = jsonElement.jsonObject
                
                val timestamp = jsonObject["timestamp"]?.jsonPrimitive?.long ?: 0L
                val resultsObject = jsonObject["results"]?.jsonObject
                
                val results = mutableMapOf<String, StepResult>()
                resultsObject?.forEach { (key, value) ->
                    val obj = value.jsonObject
                    val stepResult = StepResult(
                        stepName = obj["stepName"]?.jsonPrimitive?.content ?: "",
                        result = obj["result"]?.jsonPrimitive?.content?.replace("\\n", "\n") ?: "",
                        success = obj["success"]?.jsonPrimitive?.boolean ?: false
                    )
                    results[key] = stepResult
                }
                
                val dateTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(java.util.Date(timestamp))
                logger.info("✅ 加载成功")
                logger.info("   诊断时间: $dateTime")
                logger.info("   步骤数量: ${results.size}")
                
                Pair(results, timestamp)
            } else {
                logger.info("ℹ️ 所有路径都不存在历史诊断文件")
                null
            }
        } catch (e: Exception) {
            logger.error("❌ 加载诊断历史失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * 保存诊断结果供下次使用
     * 同时保存诊断时间戳，用于增量诊断时过滤消息
     */
    private fun saveDiagnosisResults(stepResults: Map<String, StepResult>) {
        try {
            // 统一使用backend/chat_history路径
            val file = java.io.File("backend/chat_history/$sessionName/last_diagnosis.json")
            
            logger.info("💾 正在保存诊断结果:")
            logger.info("   sessionName: $sessionName")
            logger.info("   文件路径: ${file.absolutePath}")
            logger.info("   步骤数量: ${stepResults.size}")
            
            // 确保父目录存在
            val parentDir = file.parentFile
            if (parentDir != null && !parentDir.exists()) {
                val created = parentDir.mkdirs()
                logger.info("   创建目录: ${if (created) "成功" else "失败"}")
            } else {
                logger.info("   目录已存在: ${parentDir?.absolutePath}")
            }
            
            // 创建包含时间戳的诊断记录（使用简单的手动JSON格式）
            val timestamp = System.currentTimeMillis()
            
            // 手动构建JSON以避免序列化问题
            val json = buildString {
                append("{\n")
                append("  \"timestamp\": $timestamp,\n")
                append("  \"results\": {\n")
                
                stepResults.entries.forEachIndexed { index, (key, value) ->
                    append("    \"$key\": {\n")
                    append("      \"stepName\": \"${value.stepName.replace("\"", "\\\"")}\",\n")
                    append("      \"result\": \"${value.result.replace("\"", "\\\"").replace("\n", "\\n")}\",\n")
                    append("      \"success\": ${value.success}\n")
                    append("    }")
                    if (index < stepResults.size - 1) append(",")
                    append("\n")
                }
                
                append("  }\n")
                append("}")
            }
            
            // 移除详细日志输出
            // logger.info("   JSON大小: ${json.length} 字符")
            // logger.info("   步骤列表: ${stepResults.keys.take(3).joinToString(", ")}...")
            
            // 写入文件
            file.writeText(json)
            
            // 验证写入
            if (file.exists()) {
                val dateTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(java.util.Date(timestamp))
                logger.info("✅ 诊断结果已成功保存")
                logger.info("   诊断时间: $dateTime")
                logger.info("   文件大小: ${file.length()} bytes")
                logger.info("   包含步骤: ${stepResults.keys.joinToString(", ")}")
            } else {
                logger.error("❌ 文件保存后不存在！")
            }
        } catch (e: Exception) {
            logger.error("❌ 保存诊断结果失败: ${e.message}", e)
        }
    }
    
    /**
     * 生成快速响应（对话模式）
     * 使用streaming方式逐步输出
     * @param prompt AI prompt
     * @param callback 回调函数 (累积内容, 是否完成)
     */
    suspend fun generateQuickResponse(
        prompt: String,
        callback: suspend (content: String, isComplete: Boolean) -> Unit
    ) {
        try {
            val requestBody = ApiRequest(
                model = AIConfig.MODEL,
                messages = listOf(
                    ApiMessage(role = "user", content = prompt)
                ),
                temperature = 0.7,
                max_tokens = 4096,  // ✅ GLM-5 的 max_tokens 限制，避免 4K 超出导致截断
                stream = true  // 启用streaming
            )
            
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${AIConfig.requireApiBaseUrl()}/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .timeout(Duration.ofMillis(60000))
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(requestBody)))
                .build()
            
            // Streaming响应处理
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines())
            
            if (response.statusCode() == 200) {
                val lines = response.body().toList()
                val accumulatedContent = StringBuilder()
                var lastSentContent = ""  // ✅ 记录上次发送的内容
                var isDone = false  // ✅ 标记是否已完成
                
                for (line in lines) {
                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()
                        
                        if (data == "[DONE]") {
                            isDone = true
                            break
                        }
                        
                        try {
                            val streamResponse = json.decodeFromString<StreamResponse>(data)
                            val delta = streamResponse.choices.firstOrNull()?.delta
                            
                            // ✅ GLM-5 模型：content 字段是乱码，reasoning 字段才是真正的中文回答
                            // 只读取 reasoning 字段，忽略 content
                            val content = delta?.reasoning ?: ""
                            
                            if (content.isNotEmpty()) {
                                accumulatedContent.append(content)
                                
                                // ✅ 累积3行后发送一次临时消息（更频繁更新）
                                val newlineCount = accumulatedContent.count { it == '\n' }
                                if (newlineCount >= 3 && accumulatedContent.length > lastSentContent.length) {
                                    // ✅ 发送增量内容（只发送新增的部分）
                                    val incrementalContent = accumulatedContent.substring(lastSentContent.length)
                                    callback(incrementalContent, false)
                                    lastSentContent = accumulatedContent.toString()
                                    delay(50)  // 减少延迟，提升响应速度
                                }
                            }
                        } catch (e: Exception) {
                            // 忽略解析错误，继续处理下一行
                        }
                    }
                }
                
                // 确保发送最后的增量内容（如果有）
                if (accumulatedContent.length > lastSentContent.length) {
                    val finalIncrement = accumulatedContent.substring(lastSentContent.length)
                    if (finalIncrement.isNotEmpty()) {
                        // 先发送最后的增量内容
                        callback(finalIncrement, false)
                        delay(50)
                    }
                }
                
                // ✅ 只发送一次完成标记（包含完整内容）
                if (isDone) {
                    callback(accumulatedContent.toString(), true)
                }
            } else {
                logger.error("❌ AI API返回错误: ${response.statusCode()}")
                callback("⚠️ AI暂时无法回答，请稍后重试", true)
            }
        } catch (e: Exception) {
            logger.error("❌ 调用AI API异常: ${e.message}", e)
            callback("⚠️ AI暂时无法回答，请稍后重试", true)
        }
    }
}

/**
 * API 请求模型
 */
@Serializable
data class ApiRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val temperature: Double = 0.7,
    val max_tokens: Int = 65536,  // ✅ 默认值提升到65536，支持超详细的诊断报告
    val stream: Boolean = false  // 支持流式输出
)

@Serializable
data class ApiMessage(
    val role: String,
    val content: String
)

/**
 * API 响应模型
 */
@Serializable
data class ApiResponse(
    val choices: List<ApiChoice>
)

@Serializable
data class ApiChoice(
    val message: ApiResponseMessage
)

@Serializable
data class ApiResponseMessage(
    val content: String
)

/**
 * 流式响应模型（SSE）
 */
@Serializable
data class StreamResponse(
    val choices: List<StreamChoice>
)

@Serializable
data class StreamChoice(
    val delta: StreamDelta,
    val finish_reason: String? = null
)

@Serializable
data class StreamDelta(
    val reasoning: String? = null,
    val content: String? = null,
    val role: String? = null
)

