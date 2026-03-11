package com.silk.backend.ai

import com.silk.backend.EnvLoader

/**
 * AI 服务配置
 * 优先从 EnvLoader（.env 文件）读取，其次系统环境变量，不在此处写死。
 * Silk 智能助手 - 搜索驱动的上下文感知系统
 */
object AIConfig {
    private fun env(key: String): String? = EnvLoader.get(key) ?: System.getenv(key)?.takeIf { it.isNotBlank() }

    // AI API 配置 (OpenAI 兼容接口)
    val API_KEY: String get() = env("OPENAI_API_KEY") ?: ""
    val API_BASE_URL: String get() = env("API_BASE_URL") ?: ""
    val MODEL: String get() = env("AI_MODEL") ?: ""
    const val TIMEOUT = 60000L  // 60秒超时
    const val MAX_RETRIES = 3

    // Weaviate 向量库地址
    val WEAVIATE_URL: String get() = env("WEAVIATE_URL") ?: ""

    /** 获取 AI API 地址，为空时抛错提示配置 .env */
    fun requireApiBaseUrl(): String = API_BASE_URL.trim().takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("请在项目根目录 .env 中配置 API_BASE_URL")

    /** 获取 Weaviate 地址，为空时抛错提示配置 .env */
    fun requireWeaviateUrl(): String = WEAVIATE_URL.trim().takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("请在项目根目录 .env 中配置 WEAVIATE_URL")

    // 外部搜索 API Key
    val SERPAPI_KEY: String get() = env("SERPAPI_KEY") ?: ""
    val BRAVE_API_KEY: String get() = env("BRAVE_API_KEY") ?: ""
    val BING_API_KEY: String get() = env("BING_API_KEY") ?: ""
    val ENABLE_EXTERNAL_SEARCH: Boolean get() = env("ENABLE_EXTERNAL_SEARCH")?.toBoolean() ?: true

    // 工具调用模式配置
    val ENABLE_MODEL_TOOLS: Boolean get() = env("ENABLE_MODEL_TOOLS")?.toBoolean() ?: true
    val TOOLS_ENABLED: List<String> get() = env("TOOLS_ENABLED")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: listOf("file_search", "web_search", "code_execution", "browser")
    
    // 智能助手任务列表
    val TO_DO_LIST = listOf(
        "理解用户意图",
        "分析情绪状态",
        "三层搜索上下文",
        "生成帮助回复"
    )
    
    // 系统提示词 - 搜索驱动的智能助手
    const val COMMON_PROMPT = """你是 Silk，一个智能助手。你的任务是：
1. 根据用户输入和搜索到的上下文，理解用户当前的主要目标
2. 分析用户的情绪状态（积极、中性、焦虑、困惑等）
3. 判断用户是否需要帮助
4. 如果需要帮助，基于搜索结果提供有价值的回复

【重要约束】：
1. 回答要简洁、有价值、切中要点
2. 充分利用搜索到的上下文信息
3. 保持友好、专业的语气

【信息来源优先级】（从高到低）：
1. [FOREGROUND/可靠-当前会话]: 最高优先级，直接采信
2. [BACKGROUND/参考-历史会话]: 中等优先级，可作参考
3. [INTERNET/待验证-外部搜索]: 较低优先级，需谨慎使用
4. [AI知识库]: 当以上来源均无相关信息时，可使用你自身的知识库回答

【回答来源标注规则】（必须遵守）：
- 如果回答主要基于 [INTERNET/待验证-外部搜索] 的内容，在回复末尾标注："🌐 *此回答基于互联网搜索结果*"
- 如果搜索结果为空，使用自身知识回答时，在回复末尾标注："💡 *此回答基于 AI 知识库*"
- 如果回答主要基于 [FOREGROUND] 或 [BACKGROUND] 的会话内容，无需额外标注
- 对于涉及当前会话、用户个人信息的问题，如果搜索无结果则坦诚告知
- 保持谦逊态度，如有不确定的信息要说明
"""

    // 意图分析提示词
    const val INTENT_ANALYSIS_PROMPT = """基于用户的输入和对话历史，分析：

1. 用户的主要目标是什么？（具体描述）
2. 用户当前的情绪状态如何？（积极/中性/焦虑/困惑/其他）
3. 用户是否需要助手帮助？（是/否）
4. 如果需要帮助，帮助的类型是？（信息查询/任务执行/情感支持/问题解决/其他）

请以 JSON 格式回复：
{
    "goal": "用户目标描述",
    "emotion": "情绪状态",
    "needs_help": true/false,
    "help_type": "帮助类型",
    "confidence": 0.0-1.0
}
"""

    // 上下文增强回复提示词
    const val CONTEXT_ENHANCED_REPLY_PROMPT = """基于以下信息生成回复：

【用户输入】
{user_input}

【Foreground 上下文（当前会话）】
{foreground_context}

【Background 上下文（历史相关）】
{background_context}

【用户意图分析】
{intent_analysis}

请生成一个有帮助的回复，要求：
1. 直接回应用户的需求
2. 充分利用上下文信息
3. 如果信息不足，提出澄清问题
4. 语气友好专业
"""

    // 各任务的具体提示词
    val TO_DO_PROMPT_MAP = mapOf(
        "理解用户意图" to """
分析用户最新的输入，理解其真实意图：
1. 用户想要做什么？
2. 用户的潜在需求是什么？
3. 需要什么类型的帮助？

请简洁回答，不超过100字。
""",
        
        "分析情绪状态" to """
基于用户的表达方式和内容，分析情绪状态：
1. 当前情绪（积极/中性/消极/焦虑/困惑）
2. 情绪强度（低/中/高）
3. 是否需要情感支持

请简洁回答，不超过50字。
""",
        
        "三层搜索上下文" to """
这是从三层搜索系统中获取的信息，按优先级从低到高排列：

Layer 3 (外部搜索): 来自互联网的补充信息，作为参考
Layer 2 (历史会话): 来自其他会话的历史经验
Layer 1 (当前会话): 最相关的当前会话上下文

请评估这些信息的相关性和有用性，优先使用 Layer 1 的内容。
""",
        
        "生成帮助回复" to """
基于以上分析和搜索结果，生成对用户有帮助的回复。

要求：
1. 直接回应用户需求
2. 使用搜索到的相关信息
3. 语气友好专业
4. 如有不确定，坦诚说明

请生成最终回复。
"""
    )
}
