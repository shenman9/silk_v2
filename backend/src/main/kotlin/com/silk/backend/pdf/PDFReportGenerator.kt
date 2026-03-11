package com.silk.backend.pdf

import com.silk.backend.ai.AIStepwiseAgent
import com.silk.backend.ChatHistoryManager
import com.silk.backend.SilkAgent
import com.itextpdf.io.font.PdfEncodings
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.AreaBreakType
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * PDF 报告生成器
 * 使用 iText 库生成格式化的诊断报告 PDF
 * 支持中文字体
 */
class PDFReportGenerator {
    
    // 颜色定义（函数形式，避免跨PDF文档重用）
    private fun primaryColor() = DeviceRgb(25, 118, 210)      // 蓝色
    private fun secondaryColor() = DeviceRgb(76, 175, 80)     // 绿色
    private fun headerBgColor() = DeviceRgb(245, 245, 245)    // 浅灰色
    private fun successColor() = DeviceRgb(76, 175, 80)       // 绿色
    private fun errorColor() = DeviceRgb(244, 67, 54)         // 红色
    
    // 中文字体路径（静态配置）- 优先使用对中英文都支持好的字体
    private val chineseFontPath: String? by lazy {
        // macOS 系统自带的字体，优先选择对中英文都支持好的
        val fontPaths = listOf(
            "/Library/Fonts/Arial Unicode.ttf",               // ✅ Arial Unicode MS - 对中英文都支持好
            "/System/Library/Fonts/PingFang.ttc,0",           // PingFang SC Regular
            "/System/Library/Fonts/STHeiti Light.ttc,0",      // 华文黑体
            "/System/Library/Fonts/Supplemental/Songti.ttc,0" // 宋体
        )
        
        // 尝试找到第一个可用的字体
        for (fontPath in fontPaths) {
            try {
                val file = java.io.File(fontPath.split(",")[0])
                if (file.exists()) {
                    println("✅ 找到中文字体: $fontPath")
                    return@lazy fontPath
                }
            } catch (e: Exception) {
                // 尝试下一个字体
                continue
            }
        }
        
        // 如果都失败，返回 null（使用内置字体）
        println("⚠️ 未找到系统字体，将使用内置字体")
        null
    }
    
    /**
     * 为每个 PDF 文档创建独立的中文字体对象
     * ✅ 使用正确的策略：内置CJK字体不需要embed
     */
    private fun createChineseFont(): PdfFont {
        return try {
            // ✅ STSong-Light是预定义的CJK字体，不能使用EMBEDDED策略
            // 使用PREFER_NOT_EMBEDDED即可（这是预定义字体的正确策略）
            PdfFontFactory.createFont(
                "STSong-Light", 
                "UniGB-UCS2-H", 
                PdfFontFactory.EmbeddingStrategy.PREFER_NOT_EMBEDDED  // ✅ 预定义字体的正确策略
            )
        } catch (e: Exception) {
            println("❌ 内置字体加载失败: ${e.message}")
            e.printStackTrace()
            // ✅ 回退到标准字体
            PdfFontFactory.createFont(com.itextpdf.io.font.constants.StandardFonts.HELVETICA)
        }
    }
    
    /**
     * ✅ 创建英文字体（用于英文字母、数字，避免字符重叠）
     */
    private fun createEnglishFont(): PdfFont {
        return try {
            // 使用 Helvetica 标准字体（清晰、无衬线）
            PdfFontFactory.createFont(com.itextpdf.io.font.constants.StandardFonts.HELVETICA)
        } catch (e: Exception) {
            println("❌ 英文字体加载失败")
            e.printStackTrace()
            PdfFontFactory.createFont()
        }
    }
    
    /**
     * ✅ 创建统一的Paragraph（使用支持中英文的Unicode字体）
     * 简化方案：使用Arial Unicode或PingFang等对中英文都支持好的字体
     * 增加字符间距以避免字符重叠
     */
    private fun createMixedFontParagraph(text: String, chineseFont: PdfFont, englishFont: PdfFont, fontSize: Float = 9f): Paragraph {
        // ✅ 简化方案：只使用中文字体（选用的字体对英文也有良好支持）
        // 避免混合字体导致的字符宽度、间距问题
        val cleanedText = sanitizeText(text)
        val paragraph = Paragraph(cleanedText)
            .setFont(chineseFont)  // 使用支持中英文的Unicode字体
            .setFontSize(fontSize)  // 默认字体从10f减小到9f
        
        // ✅ 增加字符间距，使用更明显的值
        // 1.2f 表示每个字符后增加1.2点的间距（效果明显）
        try {
            paragraph.setCharacterSpacing(1.2f)
        } catch (e: Exception) {
            // 如果设置失败，忽略错误，使用默认间距
            println("⚠️ 设置字符间距失败，使用默认值")
        }
        
        return paragraph
    }
    
    /**
     * ✅ 清理文本，移除字体不支持的特殊字符
     * 修复 "Cannot invoke Glyph.getWidth() because glyph is null" 错误
     */
    private fun sanitizeText(text: String): String {
        return text
            // 移除控制字符（除了换行、制表符）
            .replace(Regex("[\u0000-\u0008\u000B-\u000C\u000E-\u001F\u007F-\u009F]"), "")
            // 移除特殊的Unicode字符（零宽字符、方向标记等）
            .replace(Regex("[\u200B-\u200F\u202A-\u202E\u2060-\u206F\uFEFF]"), "")
            // 移除私有使用区字符
            .replace(Regex("[\uE000-\uF8FF]"), "")
            // 移除emoji的变体选择符
            .replace(Regex("[\uFE00-\uFE0F]"), "")
            // 将各种特殊引号统一为标准ASCII引号（避免字体Glyph缺失）
            .replace("\u2018", "'")   // 左单引号 ' -> ASCII单引号
            .replace("\u2019", "'")   // 右单引号 ' -> ASCII单引号
            .replace("\u201C", "\"")  // 左双引号 " -> ASCII双引号
            .replace("\u201D", "\"")  // 右双引号 " -> ASCII双引号
            .replace("\u2014", "-")   // 长破折号 — -> ASCII短横线
            .replace("\u2013", "-")   // 短破折号 – -> ASCII短横线
            .replace("\u2026", "...") // 省略号 … -> 三个点
            // 保留常用字符：中文、英文、数字、标点、常见符号、换行
            .filter { char ->
                when {
                    // 中文基本区和扩展区
                    char.code in 0x4E00..0x9FFF -> true
                    char.code in 0x3400..0x4DBF -> true
                    char.code in 0xF900..0xFAFF -> true
                    // 中文标点符号区
                    char.code in 0x3000..0x303F -> true
                    // 英文字母
                    char in 'a'..'z' || char in 'A'..'Z' -> true
                    // 数字
                    char in '0'..'9' -> true
                    // 基本标点和符号（ASCII范围）
                    char.code in 0x0020..0x007E -> true
                    // 换行符
                    char == '\n' || char == '\r' || char == '\t' -> true
                    // emoji基本区
                    char.code in 0x1F300..0x1F6FF -> true
                    // 其他字符过滤掉
                    else -> false
                }
            }
            .trim()
    }
    
    /**
     * 生成诊断报告 PDF
     * 
     * @param diagnosisResult 诊断结果
     * @param sessionName 会话名称（群组ID，格式：group_<uuid>）
     * @param patientInfo 患者信息（从聊天历史提取）
     * @param userName 用户名称
     * @param summaryReportText 文字版总结报告（与聊天室显示内容一致）
     * @param groupDisplayName 群组显示名称（可选，如果提供则用于标题）
     * @return Pair<PDF文件路径, 下载URL>
     */
    fun generateDiagnosisReportPDF(
        diagnosisResult: AIStepwiseAgent.DiagnosisResult,
        sessionName: String,
        patientInfo: String,
        userName: String = "用户",
        summaryReportText: String = "",
        groupDisplayName: String? = null
    ): Pair<String, String> {
        // 创建 PDF 保存目录
        val pdfDir = File("chat_history/$sessionName/reports")
        pdfDir.mkdirs()
        
        // ✅ 生成文件名：完全避免空格和特殊字符
        val dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))  // ✅ 使用下划线代替空格
        
        // 使用群组显示名称作为文件名的一部分
        val reportTitle = groupDisplayName ?: sessionName
        
        // ✅ 清理文件名：移除所有特殊字符，空格也替换为下划线
        val safeTitleName = reportTitle
            .replace(Regex("[/\\\\:*?\"<>|']"), "_")  // 替换文件系统不安全的字符
            .replace(Regex("\\s+"), "_")  // ✅ 所有空格替换为下划线
            .replace(Regex("[^\\w\\u4e00-\\u9fa5_-]"), "_")  // 只保留字母、数字、中文、下划线、连字符
            .replace(Regex("_{2,}"), "_")  // 合并连续的下划线
            .trim('_')  // 去除首尾下划线
        
        // 文件名格式：群组名称_日期_时间.pdf（完全无空格）
        val fileName = "${safeTitleName}_$dateTime.pdf"
        println("📋 生成的文件名: '$fileName' (长度: ${fileName.length})")
        
        val pdfPath = "${pdfDir.path}/$fileName"
        
        // ✅ 增强错误处理：捕获PDF生成过程中的异常
        var pdfDoc: PdfDocument? = null
        var document: Document? = null
        
        try {
            // 创建 PDF 文档
            val writer = PdfWriter(pdfPath)
            pdfDoc = PdfDocument(writer)
            document = Document(pdfDoc)
            
            // ✅ 设置页面边距，增加可用宽度（默认边距为36pt左右）
            // 将边距从默认36pt减少到20pt，增加页面可用宽度
            document.setMargins(20f, 20f, 20f, 20f)  // 上、右、下、左
            
            // ✅ 为此 PDF 文档创建独立的字体对象（避免跨文档重用）
            val chineseFont = createChineseFont()  // 中文字体
            val englishFont = createEnglishFont()  // 英文字体
            
            println("✅ 字体加载完成：中文字体 + 英文字体")
            
            // 生成报告标题（包含群组名称和生成时间）
            val reportGeneratedTime = LocalDateTime.now()
            val reportTitle = groupDisplayName ?: sessionName
            
            println("📋 PDF生成开始: '$reportTitle'")
            println("   - groupDisplayName: $groupDisplayName")
            println("   - sessionName: $sessionName")
            println("   - 诊断步骤数: ${diagnosisResult.stepResults.size}")
            println("   - 总结报告长度: ${summaryReportText.length}")
            
            addReportHeader(document, reportTitle, reportGeneratedTime, chineseFont, englishFont)
            
            // 第一部分：患者信息表格
            addPatientInfo(document, patientInfo, userName, sessionName, diagnosisResult, chineseFont, englishFont)
            
            // 分页：患者信息 → 诊断步骤
            document.add(AreaBreak(AreaBreakType.NEXT_PAGE))
            
            // 第二部分：诊断步骤表格
            addDiagnosisStepsTable(document, diagnosisResult, chineseFont, englishFont)
            
            // 分页：诊断步骤 → 总结报告
            if (summaryReportText.isNotEmpty() && summaryReportText.length > 100) {
                document.add(AreaBreak(AreaBreakType.NEXT_PAGE))
                
                // 第三部分：格式化的总结报告
                addSummaryReportSection(document, summaryReportText, chineseFont, englishFont)
            }
            
            addReportFooter(document, chineseFont, englishFont)
            
            // ✅ 正常关闭文档
            document.close()
            println("✅ PDF文档已正确关闭")
            println("✅ PDF 报告已生成并保存: $pdfPath")
            
        } catch (e: Exception) {
            println("❌ PDF 生成失败: ${e.message}")
            e.printStackTrace()
            
            // ✅ 即使生成失败，也尝试关闭文档避免资源泄漏
            try {
                document?.close()
                println("✅ 异常处理：文档已关闭")
            } catch (closeEx: Exception) {
                println("⚠️ 异常处理：关闭文档也失败: ${closeEx.message}")
                // 忽略关闭错误
            }
            
            // ✅ 重新抛出异常，让调用方知道PDF生成失败
            throw RuntimeException("PDF 生成失败：${e.message}", e)
        }
        
        // 生成下载 URL（对文件名进行 URL 编码，处理中文和特殊字符）
        // 使用 URLEncoder 但替换 '+' 为 '%20'，因为在 URL 路径中空格应该是 %20 而不是 +
        val encodedFileName = java.net.URLEncoder.encode(fileName, "UTF-8")
            .replace("+", "%20")  // URL 路径中空格应该是 %20
            .replace("%2F", "/")  // 恢复斜杠（如果有的话）
        val downloadUrl = "/download/report/$sessionName/$encodedFileName"
        
        return Pair(pdfPath, downloadUrl)
    }
    
    /**
     * 添加报告标题
     * @param document PDF文档
     * @param groupTitle 群组标题（已经是实际的显示名称）
     * @param generatedTime 报告生成时间
     * @param chineseFont 中文字体
     * @param englishFont 英文字体
     */
    private fun addReportHeader(document: Document, groupTitle: String, generatedTime: LocalDateTime, chineseFont: PdfFont, englishFont: PdfFont) {
        // ✅ 主标题：使用混合字体（中英文自动识别）- 字体从24f减到20f
        val title = createMixedFontParagraph(groupTitle, chineseFont, englishFont, 20f)
            .setBold()
            .setTextAlignment(TextAlignment.CENTER)
            .setFontColor(primaryColor())
            .setMarginBottom(8f)
        
        document.add(title)
        
        // 副标题：报告生成日期和时间 - 字体从14f减到11f
        val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss")
        val formattedDateTime = generatedTime.format(dateTimeFormatter)
        
        val subtitle = createMixedFontParagraph("诊断报告 - $formattedDateTime", chineseFont, englishFont, 11f)
            .setTextAlignment(TextAlignment.CENTER)
            .setFontColor(ColorConstants.GRAY)
            .setMarginBottom(10f)
        
        document.add(subtitle)
        
        // 系统标识（包含"Silk AI Agent"英文）- 字体从12f减到10f
        val systemInfo = createMixedFontParagraph("Silk AI Agent 智能诊断系统", chineseFont, englishFont, 10f)
            .setTextAlignment(TextAlignment.CENTER)
            .setFontColor(ColorConstants.LIGHT_GRAY)
            .setMarginBottom(20f)
        
        document.add(systemInfo)
        
        // 分隔线
        addHorizontalLine(document)
    }
    
    /**
     * 添加患者信息
     */
    private fun addPatientInfo(
        document: Document, 
        patientInfo: String, 
        userName: String, 
        sessionName: String,
        diagnosisResult: AIStepwiseAgent.DiagnosisResult,
        chineseFont: PdfFont,
        englishFont: PdfFont
    ) {
        val sectionTitle = Paragraph(sanitizeText("患者情况"))
            .setFont(chineseFont)
            .setFontSize(14f)  // 从16f减到14f
            .setBold()
            .setFontColor(primaryColor())
            .setMarginTop(10f)
            .setMarginBottom(10f)
        
        // ✅ 添加字符间距
        try {
            sectionTitle.setCharacterSpacing(1.2f)
        } catch (e: Exception) {
            // 忽略错误
        }
        
        document.add(sectionTitle)
        
        // 患者信息表格
        val table = Table(UnitValue.createPercentArray(floatArrayOf(30f, 70f)))
            .useAllAvailableWidth()
            .setMarginBottom(15f)
        
        // 从 patientInfo 提取消息数量
        val lines = patientInfo.split("\n")
        var messageCount = 0
        
        lines.forEach { line ->
            when {
                line.contains("用户消息数:") -> {
                    messageCount = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
        }
        
        // 从聊天历史中提取用户的症状描述消息（格式化显示）
        val symptoms = extractUserSymptomsFromHistory(sessionName)
        
        // 从会话数据中获取参与人清单（排除本人和AI）
        val historyManager = ChatHistoryManager()
        val sessionData = historyManager.loadSessionData(sessionName)
        val participantNames = sessionData?.members
            ?.filter { it.userId != SilkAgent.AGENT_ID }  // 排除 AI Agent
            ?.map { it.userName }
            ?.distinct()
            ?.filter { it != userName }  // 排除本人
            ?.joinToString("、") ?: "无其他参与者"
        
        // 提取医生（Host）的所有指令消息
        val doctorInstructions = extractDoctorInstructions(sessionName)
        
        // 从诊断结果中提取中西医疾病诊断
        val diagnosisText = extractDiagnosisSummary(diagnosisResult)
        
        // 添加表格行
        table.addHeaderCell(createHeaderCell("项目", chineseFont))
        table.addHeaderCell(createHeaderCell("内容", chineseFont))
        
        table.addCell(createCell("患者姓名", chineseFont = chineseFont))
        table.addCell(createCell(userName, chineseFont = chineseFont))
        
        table.addCell(createCell("就诊时间", chineseFont = chineseFont))
        table.addCell(createCell(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm")), chineseFont = chineseFont))
        
        table.addCell(createCell("参与人清单", chineseFont = chineseFont))
        table.addCell(createCell(participantNames, chineseFont = chineseFont))
        
        table.addCell(createCell("消息记录", chineseFont = chineseFont))
        table.addCell(createCell("$messageCount 条", chineseFont = chineseFont))
        
        if (symptoms.isNotEmpty()) {
            table.addCell(createCell("主诉症状", chineseFont = chineseFont))
            table.addCell(createCell(symptoms, chineseFont = chineseFont))
        }
        
        // 添加医生指令（如果有）
        if (doctorInstructions.isNotEmpty()) {
            table.addCell(createCell("医生指令", chineseFont = chineseFont))
            table.addCell(createCell(doctorInstructions, chineseFont = chineseFont))
        }
        
        // 添加中西医疾病诊断（倒数第二行）
        if (diagnosisText.isNotEmpty()) {
            table.addCell(createCell("疾病诊断", chineseFont = chineseFont))
            table.addCell(createCell(diagnosisText, chineseFont = chineseFont))
        }
        
        // 添加报告生成时间到患者情况表格的最后一行
        table.addCell(createCell("报告生成时间", chineseFont = chineseFont))
        table.addCell(createCell(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss")), chineseFont = chineseFont))
        
        document.add(table)
    }
    
    /**
     * 提取医生（Host）的所有指令消息
     * 按时间排序，每条消息前加时间戳
     */
    private fun extractDoctorInstructions(sessionName: String): String {
        return try {
            // 获取群组Host的用户ID
            val groupId = if (sessionName.startsWith("group_")) {
                sessionName.removePrefix("group_")
            } else {
                return ""  // 不是群组，没有Host
            }
            
            val group = com.silk.backend.database.GroupRepository.findGroupById(groupId)
            val hostId = group?.hostId ?: return ""
            
            // 加载聊天历史
            val historyManager = ChatHistoryManager()
            val chatHistory = historyManager.loadChatHistory(sessionName)
            val messages = chatHistory?.messages ?: return ""
            
            // 过滤出Host的消息（排除@诊断命令）
            val doctorMessages = messages
                .filter { it.senderId == hostId }
                .filter { !it.content.startsWith("@诊断") && !it.content.startsWith("@diagnosis") }
                .sortedBy { it.timestamp }  // 按时间排序
            
            if (doctorMessages.isEmpty()) {
                return ""
            }
            
            // 格式化：时间 + 消息内容
            val formattedMessages = doctorMessages.mapIndexed { index, msg ->
                val dateTime = java.util.Date(msg.timestamp)
                val formatter = java.text.SimpleDateFormat("MM-dd HH:mm")
                val timeStr = formatter.format(dateTime)
                
                "${index + 1}. [$timeStr] ${msg.content}"
            }
            
            formattedMessages.joinToString("\n\n")
            
        } catch (e: Exception) {
            println("⚠️ 提取医生指令失败: ${e.message}")
            ""
        }
    }
    
    /**
     * 从诊断结果中提取中西医疾病诊断的简要摘要
     */
    private fun extractDiagnosisSummary(diagnosisResult: AIStepwiseAgent.DiagnosisResult): String {
        val diagnosisStep = diagnosisResult.stepResults["中西医疾病的诊断"]
        
        if (diagnosisStep == null || !diagnosisStep.success) {
            return ""
        }
        
        val fullText = diagnosisStep.result
        val lines = fullText.split("\n")
        val summary = mutableListOf<String>()
        
        // 提取西医诊断和中医诊断的关键信息
        var inWesternDiagnosis = false
        var inChineseDiagnosis = false
        val westernDiseases = mutableListOf<String>()
        val chineseDiseases = mutableListOf<String>()
        
        for (line in lines) {
            val trimmed = line.trim()
            
            when {
                trimmed.contains("西医诊断") || trimmed.contains("【西医") -> {
                    inWesternDiagnosis = true
                    inChineseDiagnosis = false
                }
                trimmed.contains("中医诊断") || trimmed.contains("【中医") -> {
                    inChineseDiagnosis = true
                    inWesternDiagnosis = false
                }
                inWesternDiagnosis && trimmed.isNotEmpty() -> {
                    // 提取疾病名称（通常包含"、"或数字编号）
                    if (trimmed.matches(Regex(".*[：:].+")) || 
                        trimmed.matches(Regex("\\d+[.、].*")) ||
                        trimmed.contains("可能") ||
                        trimmed.contains("考虑")) {
                        westernDiseases.add(trimmed.replace(Regex("^\\d+[.、]\\s*"), ""))
                    }
                }
                inChineseDiagnosis && trimmed.isNotEmpty() -> {
                    // 提取中医病名
                    if (trimmed.matches(Regex(".*[：:].+")) || 
                        trimmed.matches(Regex("\\d+[.、].*")) ||
                        trimmed.contains("不寐") ||
                        trimmed.contains("虚劳") ||
                        trimmed.contains("头痛")) {
                        chineseDiseases.add(trimmed.replace(Regex("^\\d+[.、]\\s*"), ""))
                    }
                }
            }
        }
        
        // 构建简要诊断摘要
        val result = buildString {
            if (westernDiseases.isNotEmpty()) {
                append("【西医】")
                append(westernDiseases.take(2).joinToString("；").take(100))
                if (westernDiseases.size > 2 || westernDiseases.joinToString().length > 100) {
                    append("...")
                }
            }
            
            if (chineseDiseases.isNotEmpty()) {
                if (westernDiseases.isNotEmpty()) append("\n")
                append("【中医】")
                append(chineseDiseases.take(2).joinToString("；").take(100))
                if (chineseDiseases.size > 2 || chineseDiseases.joinToString().length > 100) {
                    append("...")
                }
            }
            
            // 如果提取失败，使用简化的完整文本前150字
            if (isEmpty()) {
                append(fullText.replace(Regex("\\s+"), " ").take(150))
                if (fullText.length > 150) append("...")
            }
        }
        
        return result.toString()
    }
    
    /**
     * 从聊天历史中提取用户的症状描述消息
     * 格式：时间戳 + 用户名 + 消息内容，按时间排序
     */
    private fun extractUserSymptomsFromHistory(sessionName: String): String {
        return try {
            // 加载聊天历史
            val historyManager = ChatHistoryManager()
            val chatHistory = historyManager.loadChatHistory(sessionName)
            val messages = chatHistory?.messages ?: return ""
            
            // 过滤用户消息（排除AI和系统消息）
            val userMessages = messages
                .filter { it.senderId != SilkAgent.AGENT_ID }
                .filter { it.messageType == "TEXT" }
                .filter { !it.content.startsWith("@诊断") && !it.content.startsWith("@diagnosis") }
                .filter { it.content.isNotEmpty() && it.content.length >= 3 }
                .sortedBy { it.timestamp }  // 按时间排序
            
            if (userMessages.isEmpty()) {
                return "暂无症状描述"
            }
            
            // 格式化：时间 + 用户名 + 消息
            val formattedMessages = userMessages.mapIndexed { index, msg ->
                val dateTime = java.util.Date(msg.timestamp)
                val formatter = java.text.SimpleDateFormat("MM-dd HH:mm")
                val timeStr = formatter.format(dateTime)
                
                val userName = msg.senderName
                val content = msg.content
                
                "${index + 1}. [$timeStr] $userName: $content"
            }
            
            // 只取前5条，避免过长
            formattedMessages.take(5).joinToString("\n\n")
            
        } catch (e: Exception) {
            println("⚠️ 提取用户症状失败: ${e.message}")
            "提取症状失败"
        }
    }
    
    /**
     * 添加执行摘要
     */
    private fun addExecutionSummary(document: Document, result: AIStepwiseAgent.DiagnosisResult, chineseFont: PdfFont) {
        val sectionTitle = Paragraph("诊断执行摘要")
            .setFont(chineseFont)
            .setFontSize(16f)
            .setBold()
            .setFontColor(primaryColor())
            .setMarginTop(15f)
            .setMarginBottom(10f)
        
        // ✅ 添加字符间距
        try {
            sectionTitle.setCharacterSpacing(1.2f)
        } catch (e: Exception) {
            // 忽略错误
        }
        
        document.add(sectionTitle)
        
        // 摘要表格
        val table = Table(UnitValue.createPercentArray(floatArrayOf(30f, 70f)))
            .useAllAvailableWidth()
            .setMarginBottom(15f)
        
        table.addHeaderCell(createHeaderCell("统计项", chineseFont))
        table.addHeaderCell(createHeaderCell("数值", chineseFont))
        
        table.addCell(createCell("诊断步骤总数", chineseFont = chineseFont))
        table.addCell(createCell("${result.stepResults.size} 步", chineseFont = chineseFont))
        
        table.addCell(createCell("成功完成", chineseFont = chineseFont))
        table.addCell(createCell("${result.stepResults.count { it.value.success }} 步", 
            if (result.allSuccess) successColor() else errorColor(), chineseFont))
        
        table.addCell(createCell("执行状态", chineseFont = chineseFont))
        table.addCell(createCell(
            if (result.allSuccess) "✓ 全部成功" else "⚠ 部分失败",
            if (result.allSuccess) successColor() else errorColor(),
            chineseFont
        ))
        
        table.addCell(createCell("报告生成时间", chineseFont = chineseFont))
        table.addCell(createCell(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), chineseFont = chineseFont))
        
        document.add(table)
    }
    
    /**
     * 添加诊断步骤表格（每个步骤一行，排除总结报告）
     */
    private fun addDiagnosisStepsTable(document: Document, result: AIStepwiseAgent.DiagnosisResult, chineseFont: PdfFont, englishFont: PdfFont) {
        val sectionTitle = Paragraph(sanitizeText("诊断详细过程"))
            .setFont(chineseFont)
            .setFontSize(14f)  // 从16f减到14f
            .setBold()
            .setFontColor(primaryColor())
            .setMarginTop(15f)
            .setMarginBottom(10f)
        
        // ✅ 添加字符间距
        try {
            sectionTitle.setCharacterSpacing(1.2f)
        } catch (e: Exception) {
            // 忽略错误
        }
        
        document.add(sectionTitle)
        
        // 步骤表格：序号、步骤名称、结果摘要
        val table = Table(UnitValue.createPercentArray(floatArrayOf(8f, 30f, 62f)))
            .useAllAvailableWidth()
            .setMarginBottom(20f)
        
        // 表头
        table.addHeaderCell(createHeaderCell("序号", chineseFont))
        table.addHeaderCell(createHeaderCell("诊断步骤", chineseFont))
        table.addHeaderCell(createHeaderCell("诊断结果", chineseFont))
        
        // 包含所有诊断步骤（不排除任何步骤，完整记录诊断过程）
        val allSteps = result.stepResults.entries.toList()
        
        // 表格内容 - 逐个填入所有步骤
        allSteps.forEachIndexed { index, (stepName, stepResult) ->
            // 序号
            table.addCell(createCellCentered("${index + 1}", chineseFont = chineseFont))
            
            // ✅ 步骤名称：使用混合字体，字体从10f减到9f
            val nameCell = Cell()
                .add(createMixedFontParagraph(stepName, chineseFont, englishFont, 9f)
                    .setBold())
                .setTextAlignment(TextAlignment.LEFT)
                .setPadding(8f)
                .setBackgroundColor(DeviceRgb(250, 250, 250))
            table.addCell(nameCell)
            
            // 结果内容（简化版，适合表格显示）
            val resultText = if (stepResult.success) {
                val formattedText = formatResultForTable(stepResult.result)
                println("   步骤 ${index + 1} ($stepName): 原始长度=${stepResult.result.length}, 格式化后=${formattedText.length}")
                formattedText
            } else {
                "执行失败：${stepResult.error ?: "未知错误"}"
            }
            
            // ✅ 结果内容：使用混合字体（中英文自动识别）
            val resultCell = Cell()
                .add(createMixedFontParagraph(resultText, chineseFont, englishFont, 9f))
                .setTextAlignment(TextAlignment.LEFT)
                .setPadding(8f)
            
            // 失败的步骤用红色背景标记
            if (!stepResult.success) {
                resultCell.setBackgroundColor(DeviceRgb(255, 245, 245))
            }
            
            table.addCell(resultCell)
        }
        
        document.add(table)
    }
    
    /**
     * 格式化结果用于表格显示（保留完整内容）
     */
    private fun formatResultForTable(text: String): String {
        // 移除多余的空行和格式标记
        val cleaned = text
            .replace(Regex("\\n{3,}"), "\n\n")  // 多个空行合并
            .replace(Regex("^\\s+", RegexOption.MULTILINE), "")  // 移除行首空格
            .replace(Regex("【|】|\\[|\\]"), "")  // 移除方括号标记
            .trim()
        
        // ✅ 不再截断内容，保留完整诊断结果
        // PDF表格会自动换行，可以容纳较长内容
        return cleaned
    }
    
    /**
     * 添加总结报告章节（使用与聊天室相同的内容，支持格式化标记）
     * 注意：调用此方法前，外部已添加分页符
     */
    private fun addSummaryReportSection(document: Document, summaryText: String, chineseFont: PdfFont, englishFont: PdfFont) {
        // 不在此处分页，由外部控制
        val sectionTitle = Paragraph(sanitizeText("诊断总结报告"))
            .setFont(chineseFont)
            .setFontSize(15f)  // 从18f减到15f
            .setBold()
            .setFontColor(primaryColor())
            .setMarginTop(20f)
            .setMarginBottom(15f)
        
        // ✅ 添加字符间距
        try {
            sectionTitle.setCharacterSpacing(1.2f)
        } catch (e: Exception) {
            // 忽略错误
        }
        
        document.add(sectionTitle)
        
        // 解析并渲染格式化的文本
        parseAndRenderFormattedText(document, summaryText, chineseFont, englishFont)
        
        // 添加免责声明
        addDisclaimer(document, chineseFont, englishFont)
    }
    
    /**
     * 解析并渲染格式化的文本
     * 支持标记：##主标题##, ###副标题###, ####要点####
     */
    private fun parseAndRenderFormattedText(document: Document, text: String, chineseFont: PdfFont, englishFont: PdfFont) {
        val lines = text.split("\n")
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // 跳过空行和分隔线
            if (trimmed.isEmpty() || 
                trimmed.startsWith("═") || 
                trimmed.startsWith("─") ||
                trimmed.contains("Silk AI Agent")) {
                continue
            }
            
            when {
                // ####要点#### - 三级标题/要点（最长标记，优先检测）
                trimmed.startsWith("####") -> {
                    // 移除前缀和可能的后缀标记
                    var text = trimmed.removePrefix("####")
                    // 查找第一个出现的#### 作为结束标记
                    val endMarkerIndex = text.indexOf("####")
                    if (endMarkerIndex >= 0) {
                        text = text.substring(0, endMarkerIndex)
                    }
                    val point = text.trim()
                    
                    // ✅ 使用混合字体，字体从11f减到9.5f
                    val paragraph = createMixedFontParagraph(point, chineseFont, englishFont, 9.5f)
                        .setBold()
                        .setFontColor(DeviceRgb(100, 100, 100))
                        .setMarginTop(8f)
                        .setMarginBottom(4f)
                    document.add(paragraph)
                }
                
                // ###副标题### - 二级章节标题
                trimmed.startsWith("###") -> {
                    var text = trimmed.removePrefix("###")
                    val endMarkerIndex = text.indexOf("###")
                    if (endMarkerIndex >= 0) {
                        text = text.substring(0, endMarkerIndex)
                    }
                    val subtitle = text.trim()
                    
                    // ✅ 使用混合字体，字体从12f减到10f
                    val paragraph = createMixedFontParagraph(subtitle, chineseFont, englishFont, 10f)
                        .setBold()
                        .setFontColor(secondaryColor())
                        .setMarginTop(10f)
                        .setMarginBottom(6f)
                    document.add(paragraph)
                }
                
                // ##主标题## - 一级章节标题
                trimmed.startsWith("##") -> {
                    var text = trimmed.removePrefix("##")
                    val endMarkerIndex = text.indexOf("##")
                    if (endMarkerIndex >= 0) {
                        text = text.substring(0, endMarkerIndex)
                    }
                    val title = text.trim()
                    
                    // ✅ 使用混合字体，字体从14f减到12f
                    val paragraph = createMixedFontParagraph(title, chineseFont, englishFont, 12f)
                        .setBold()
                        .setFontColor(primaryColor())
                        .setMarginTop(12f)
                        .setMarginBottom(8f)
                    document.add(paragraph)
                }
                
                // 普通段落
                else -> {
                    // ✅ 使用混合字体（中英文自动识别，避免字符重叠），使用默认9f
                    val paragraph = createMixedFontParagraph(trimmed, chineseFont, englishFont)
                        .setMarginBottom(5f)
                        .setFirstLineIndent(20f)  // 首行缩进
                    document.add(paragraph)
                }
            }
        }
    }
    
    
    /**
     * 添加免责声明（使用中文字体）
     */
    private fun addDisclaimer(document: Document, chineseFont: PdfFont, englishFont: PdfFont) {
        val disclaimerText = """
            【免责声明】
            
            本诊断报告由 Silk AI Agent 基于患者症状描述自动生成，仅供医疗参考，不构成正式医疗建议。
            
            • 本报告不能替代专业医师的临床诊断
            • 所有治疗方案需在持证中医师指导下实施
            • 中药处方需要由专业中医师开具
            • 针灸、艾灸等治疗需要专业人员操作
            • 如有疑问，请及时就医咨询
            
            承山堂提醒您：生命健康至关重要，请谨慎对待！
        """.trimIndent()
        
        // ✅ 使用混合字体（包含"Silk AI Agent"等英文）
        val disclaimer = createMixedFontParagraph(disclaimerText, chineseFont, englishFont, 9f)
            .setFontColor(ColorConstants.GRAY)
            .setBackgroundColor(DeviceRgb(255, 248, 225))
            .setPadding(10f)
            .setMarginTop(15f)
        
        document.add(disclaimer)
    }
    
    /**
     * 添加报告页脚（使用中文字体）
     */
    private fun addReportFooter(document: Document, chineseFont: PdfFont, englishFont: PdfFont) {
        addHorizontalLine(document)
        
        // ✅ Footer使用混合字体（包含"Silk AI Agent", "DeepSeek AI"等英文）
        val footer = createMixedFontParagraph("本报告由 Silk AI Agent 自动生成 | 技术支持: DeepSeek AI | 承山堂中医诊所", chineseFont, englishFont, 9f)
            .setTextAlignment(TextAlignment.CENTER)
            .setFontColor(ColorConstants.GRAY)
            .setMarginTop(10f)
        
        document.add(footer)
    }
    
    /**
     * 创建表头单元格（支持中文）
     */
    private fun createHeaderCell(text: String, chineseFont: PdfFont): Cell {
        val sanitizedText = sanitizeText(text)  // ✅ 清理文本
        val paragraph = Paragraph(sanitizedText)
            .setFont(chineseFont)  // 使用中文字体
            .setBold()
            .setFontSize(9.5f)  // 从11f减到9.5f
        
        // ✅ 增加字符间距（1.2f = 更明显的间距效果）
        try {
            paragraph.setCharacterSpacing(1.2f)
        } catch (e: Exception) {
            // 忽略错误
        }
        
        return Cell()
            .add(paragraph)
            .setBackgroundColor(headerBgColor())
            .setTextAlignment(TextAlignment.CENTER)
            .setPadding(8f)
    }
    
    /**
     * 创建普通单元格（支持中文）
     */
    private fun createCell(
        text: String, 
        textColor: DeviceRgb? = null,
        chineseFont: PdfFont
    ): Cell {
        val sanitizedText = sanitizeText(text)  // ✅ 清理文本
        val paragraph = Paragraph(sanitizedText)
            .setFont(chineseFont)  // 使用中文字体
            .setFontSize(9f)  // 从10f减到9f
        
        // ✅ 增加字符间距（1.2f = 更明显的间距效果）
        try {
            paragraph.setCharacterSpacing(1.2f)
        } catch (e: Exception) {
            // 忽略错误
        }
        
        if (textColor != null) {
            paragraph.setFontColor(textColor).setBold()
        }
        
        return Cell()
            .add(paragraph)
            .setTextAlignment(TextAlignment.LEFT)
            .setPadding(8f)
    }
    
    /**
     * 创建居中单元格（支持中文）
     */
    private fun createCellCentered(
        text: String,
        textColor: DeviceRgb? = null,
        chineseFont: PdfFont
    ): Cell {
        val sanitizedText = sanitizeText(text)  // ✅ 清理文本
        val paragraph = Paragraph(sanitizedText)
            .setFont(chineseFont)  // 使用中文字体
            .setFontSize(9f)  // 从10f减到9f
        
        // ✅ 增加字符间距（1.2f = 更明显的间距效果）
        try {
            paragraph.setCharacterSpacing(1.2f)
        } catch (e: Exception) {
            // 忽略错误
        }
        
        if (textColor != null) {
            paragraph.setFontColor(textColor).setBold()
        }
        
        return Cell()
            .add(paragraph)
            .setTextAlignment(TextAlignment.CENTER)
            .setPadding(8f)
    }
    
    /**
     * 格式化结果文本用于 PDF 显示
     * 确保人类可读，去除 JSON 等技术格式
     */
    private fun formatResultForPDF(text: String): String {
        // 检查是否是 JSON 格式
        if (text.trim().startsWith("{") || text.trim().startsWith("[")) {
            return "【系统信息】\n内容格式需要优化，请查看聊天室中的文字版总结报告。"
        }
        
        // 移除过多的空行和特殊字符
        val cleaned = text.split("\n")
            .filter { line ->
                val trimmed = line.trim()
                trimmed.isNotEmpty() && 
                !trimmed.startsWith("{") && 
                !trimmed.startsWith("[") &&
                !trimmed.contains("\"content\"") &&
                !trimmed.contains("\"isTransient\"")
            }
            .joinToString("\n")
        
        // 限制长度（每个单元格最多1000字）
        return if (cleaned.length > 1000) {
            cleaned.take(997) + "..."
        } else {
            cleaned
        }
    }
    
    /**
     * 添加水平分隔线
     */
    private fun addHorizontalLine(document: Document) {
        val line = com.itextpdf.layout.element.LineSeparator(
            com.itextpdf.kernel.pdf.canvas.draw.SolidLine()
        )
        line.setMarginTop(5f)
        line.setMarginBottom(5f)
        document.add(line)
    }
}

