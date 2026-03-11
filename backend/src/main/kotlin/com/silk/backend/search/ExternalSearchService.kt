package com.silk.backend.search

import com.silk.backend.ai.AIConfig
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * 外部搜索引擎服务
 * 支持多种搜索引擎 API：
 * 1. Bing Search API (国内可访问，推荐)
 * 2. SerpAPI (Google 结果，需付费)
 * 3. DuckDuckGo (免费但国内可能不通)
 */
class ExternalSearchService {
    private val logger = LoggerFactory.getLogger(ExternalSearchService::class.java)
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
    }
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        engine {
            requestTimeout = 30000
            // 优化连接配置以提升稳定性
            endpoint {
                connectTimeout = 15000
                keepAliveTime = 10000
                socketTimeout = 30000
            }
        }
    }
    
    companion object {
        // 搜索 API Key 统一从 AIConfig 读取（AIConfig 从 .env 加载）
        private val BING_API_KEY: String get() = AIConfig.BING_API_KEY
        private val SERPAPI_KEY: String get() = AIConfig.SERPAPI_KEY
        val BING_SEARCH_URL: String = "https://api.bing.microsoft.com/v7.0/search"
        val SERPAPI_URL: String = "https://serpapi.com/search"
        
        // Wikipedia API（完全免费，国内可访问）
        val WIKIPEDIA_API_URL: String = "https://zh.wikipedia.org/w/api.php"
        val WIKIPEDIA_EN_API_URL: String = "https://en.wikipedia.org/w/api.php"
        
        // DuckDuckGo Instant Answer API（无需 API Key，但国内可能不通）
        val DUCKDUCKGO_URL: String = "https://api.duckduckgo.com/"
        
        // 搜索超时 - 缩短以提升响应速度
        const val SEARCH_TIMEOUT_MS = 5000L  // 5秒超时，避免用户等待太久
        const val MAX_RESULTS = 5
    }
    
    /**
     * 执行外部搜索
     * 优先级（SerpAPI 优先）：
     * 1. SerpAPI (Google 结果，最高优先级)
     * 2. Bing (需API Key，国内可访问)
     * 3. Wikipedia (免费，国内稳定)
     * 4. DuckDuckGo (免费，可能超时)
     */
    suspend fun search(query: String, limit: Int = MAX_RESULTS): ExternalSearchResults {
        val startTime = System.currentTimeMillis()
        
        return try {
            // 1. 优先使用 SerpAPI（Google 搜索结果）
            if (SERPAPI_KEY.isNotBlank()) {
                logger.info("🔍 [1/4] 尝试 SerpAPI (Google 搜索) - 最高优先级")
                try {
                    val serpResult = searchWithSerpAPI(query, limit)
                    if (serpResult.success && serpResult.results.isNotEmpty()) {
                        logger.info("✅ SerpAPI 搜索成功 (${System.currentTimeMillis() - startTime}ms)")
                        return serpResult
                    }
                } catch (e: Exception) {
                    logger.warn("⚠️ SerpAPI 失败: ${e.message?.take(50)}")
                }
            }
            
            // 2. 尝试 Bing（如果有 API Key，国内可访问）
            if (BING_API_KEY.isNotBlank()) {
                logger.info("🔍 [2/4] 尝试 Bing Search API")
                try {
                    val bingResult = searchWithBing(query, limit)
                    if (bingResult.success && bingResult.results.isNotEmpty()) {
                        logger.info("✅ Bing 搜索成功 (${System.currentTimeMillis() - startTime}ms)")
                        return bingResult
                    }
                } catch (e: Exception) {
                    logger.warn("⚠️ Bing 失败: ${e.message?.take(50)}")
                }
            }

            // 3. 使用 Wikipedia（免费，国内稳定可访问）
            logger.info("🔍 [3/4] 尝试 Wikipedia API（免费，稳定）")
            try {
                val wikiResult = searchWithWikipedia(query, limit)
                if (wikiResult.success && wikiResult.results.isNotEmpty()) {
                    logger.info("✅ Wikipedia 搜索成功 (${System.currentTimeMillis() - startTime}ms)")
                    return wikiResult
                }
            } catch (e: Exception) {
                logger.warn("⚠️ Wikipedia 失败: ${e.message?.take(50)}")
            }
            
            // 4. 尝试 DuckDuckGo（免费，但国内可能超时）
            logger.info("🔍 [4/4] 尝试 DuckDuckGo API（免费）")
            try {
                val ddgResult = searchWithDuckDuckGo(query, limit)
                if (ddgResult.success && ddgResult.results.isNotEmpty()) {
                    logger.info("✅ DuckDuckGo 搜索成功 (${System.currentTimeMillis() - startTime}ms)")
                    return ddgResult
                }
            } catch (e: Exception) {
                logger.warn("⚠️ DuckDuckGo 失败: ${e.message?.take(50)}")
            }
            
            // 所有搜索都失败
            logger.warn("❌ 所有外部搜索引擎都无法获取结果 (总耗时: ${System.currentTimeMillis() - startTime}ms)")
            ExternalSearchResults(
                success = false,
                source = "none",
                results = emptyList(),
                searchTimeMs = System.currentTimeMillis() - startTime,
                error = "所有搜索引擎都无法获取结果"
            )
        } catch (e: Exception) {
            logger.error("❌ 外部搜索异常: ${e.message}")
            ExternalSearchResults(
                success = false,
                source = "error",
                results = emptyList(),
                searchTimeMs = System.currentTimeMillis() - startTime,
                error = e.message
            )
        }
    }
    
    /**
     * 使用 Wikipedia API 搜索（完全免费，国内可访问）
     * 优先搜索中文维基百科，如果没结果再搜索英文
     */
    private suspend fun searchWithWikipedia(query: String, limit: Int): ExternalSearchResults {
        val startTime = System.currentTimeMillis()
        
        return withTimeout(SEARCH_TIMEOUT_MS) {
            try {
                // 先尝试中文维基百科
                logger.info("🔍 [Wikipedia] 搜索中文维基: $query")
                var results = searchWikipediaLanguage(query, WIKIPEDIA_API_URL, "zh", limit)
                
                // 如果中文没结果，尝试英文
                if (results.isEmpty()) {
                    logger.info("🔍 [Wikipedia] 中文无结果，尝试英文维基")
                    results = searchWikipediaLanguage(query, WIKIPEDIA_EN_API_URL, "en", limit)
                }
                
                if (results.isNotEmpty()) {
                    logger.info("📚 [Wikipedia] 搜索成功: 找到 ${results.size} 条结果")
                }
                
                ExternalSearchResults(
                    success = results.isNotEmpty(),
                    source = "Wikipedia",
                    results = results,
                    searchTimeMs = System.currentTimeMillis() - startTime
                )
            } catch (e: Exception) {
                logger.error("❌ [Wikipedia] 搜索失败: ${e.message}")
                ExternalSearchResults(
                    success = false,
                    source = "Wikipedia",
                    results = emptyList(),
                    searchTimeMs = System.currentTimeMillis() - startTime,
                    error = e.message
                )
            }
        }
    }
    
    /**
     * 搜索指定语言的维基百科
     */
    private suspend fun searchWikipediaLanguage(
        query: String, 
        apiUrl: String, 
        lang: String,
        limit: Int
    ): List<ExternalSearchResult> {
        // 第一步：搜索相关页面
        val searchResponse = client.get(apiUrl) {
            parameter("action", "query")
            parameter("list", "search")
            parameter("srsearch", query)
            parameter("srlimit", limit)
            parameter("format", "json")
            parameter("utf8", "1")
        }
        
        val searchBody = searchResponse.bodyAsText()
        val searchJson = json.parseToJsonElement(searchBody).jsonObject
        
        val searchResults = searchJson["query"]?.jsonObject
            ?.get("search")?.jsonArray ?: return emptyList()
        
        if (searchResults.isEmpty()) return emptyList()
        
        // 第二步：获取页面摘要
        val titles = searchResults.take(limit).mapNotNull { 
            it.jsonObject["title"]?.jsonPrimitive?.content 
        }
        
        if (titles.isEmpty()) return emptyList()
        
        val summaryResponse = client.get(apiUrl) {
            parameter("action", "query")
            parameter("titles", titles.joinToString("|"))
            parameter("prop", "extracts|info")
            parameter("exintro", "1")
            parameter("explaintext", "1")
            parameter("exsentences", "3")
            parameter("inprop", "url")
            parameter("format", "json")
            parameter("utf8", "1")
        }
        
        val summaryBody = summaryResponse.bodyAsText()
        val summaryJson = json.parseToJsonElement(summaryBody).jsonObject
        
        val pages = summaryJson["query"]?.jsonObject
            ?.get("pages")?.jsonObject ?: return emptyList()
        
        return pages.entries.mapNotNull { (_, pageValue) ->
            val page = pageValue.jsonObject
            val title = page["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val extract = page["extract"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val url = page["fullurl"]?.jsonPrimitive?.content 
                ?: "https://${lang}.wikipedia.org/wiki/${title.replace(" ", "_")}"
            
            if (extract.isBlank()) return@mapNotNull null
            
            ExternalSearchResult(
                title = title,
                snippet = extract.take(500),
                url = url,
                source = "Wikipedia ($lang)"
            )
        }
    }
    
    /**
     * 使用 Bing Search API（国内可访问，推荐）
     * 需要 Azure 订阅和 Bing Search API Key
     */
    private suspend fun searchWithBing(query: String, limit: Int): ExternalSearchResults {
        val startTime = System.currentTimeMillis()
        
        return withTimeout(SEARCH_TIMEOUT_MS) {
            try {
                logger.info("🔍 [Bing] 搜索: $query")
                
                val response = client.get(BING_SEARCH_URL) {
                    parameter("q", query)
                    parameter("count", limit)
                    parameter("mkt", "zh-CN")  // 中文市场
                    parameter("setLang", "zh-Hans")
                    header("Ocp-Apim-Subscription-Key", BING_API_KEY)
                }
                
                val body = response.bodyAsText()
                logger.info("🔍 [Bing] 响应: ${body.take(500)}...")
                
                val bingResult = json.decodeFromString<BingSearchResponse>(body)
                
                val results = bingResult.webPages?.value?.take(limit)?.map { item ->
                    ExternalSearchResult(
                        title = item.name ?: "无标题",
                        snippet = item.snippet ?: "",
                        url = item.url ?: "",
                        source = "Bing"
                    )
                } ?: emptyList()
                
                logger.info("🔍 [Bing] 搜索成功: 找到 ${results.size} 条结果")
                
                ExternalSearchResults(
                    success = results.isNotEmpty(),
                    source = "Bing",
                    results = results,
                    searchTimeMs = System.currentTimeMillis() - startTime
                )
            } catch (e: Exception) {
                logger.error("❌ [Bing] 搜索失败: ${e.message}")
                ExternalSearchResults(
                    success = false,
                    source = "Bing",
                    results = emptyList(),
                    searchTimeMs = System.currentTimeMillis() - startTime,
                    error = e.message
                )
            }
        }
    }
    
    /**
     * 使用 SerpAPI 搜索（Google 结果）
     */
    private suspend fun searchWithSerpAPI(query: String, limit: Int): ExternalSearchResults {
        val startTime = System.currentTimeMillis()
        
        return withTimeout(SEARCH_TIMEOUT_MS) {
            try {
                val response = client.get(SERPAPI_URL) {
                    parameter("q", query)
                    parameter("api_key", SERPAPI_KEY)
                    parameter("engine", "google")
                    parameter("num", limit)
                    parameter("hl", "zh-CN")  // 中文结果
                }
                
                val body = response.bodyAsText()
                val serpResult = json.decodeFromString<SerpAPIResponse>(body)
                
                val results = serpResult.organic_results?.take(limit)?.map { item ->
                    ExternalSearchResult(
                        title = item.title ?: "无标题",
                        snippet = item.snippet ?: "",
                        url = item.link ?: "",
                        source = "Google (via SerpAPI)"
                    )
                } ?: emptyList()
                
                logger.info("🌐 SerpAPI 搜索成功: 找到 ${results.size} 条结果")
                
                ExternalSearchResults(
                    success = true,
                    source = "SerpAPI (Google)",
                    results = results,
                    searchTimeMs = System.currentTimeMillis() - startTime
                )
            } catch (e: Exception) {
                logger.warn("⚠️ SerpAPI 搜索失败，尝试 DuckDuckGo: ${e.message}")
                searchWithDuckDuckGo(query, limit)
            }
        }
    }
    
    /**
     * 使用 DuckDuckGo Instant Answer API（免费）
     */
    private suspend fun searchWithDuckDuckGo(query: String, limit: Int): ExternalSearchResults {
        val startTime = System.currentTimeMillis()
        
        return withTimeout(SEARCH_TIMEOUT_MS) {
            try {
                val response = client.get(DUCKDUCKGO_URL) {
                    parameter("q", query)
                    parameter("format", "json")
                    parameter("no_html", "1")
                    parameter("skip_disambig", "1")
                }
                
                val body = response.bodyAsText()
                val ddgResult = json.decodeFromString<DuckDuckGoResponse>(body)
                
                val results = mutableListOf<ExternalSearchResult>()
                
                // 主要结果（Abstract）
                if (ddgResult.Abstract?.isNotBlank() == true) {
                    results.add(ExternalSearchResult(
                        title = ddgResult.Heading ?: "搜索结果",
                        snippet = ddgResult.Abstract,
                        url = ddgResult.AbstractURL ?: "",
                        source = "DuckDuckGo (Abstract)"
                    ))
                }
                
                // 相关主题
                ddgResult.RelatedTopics?.take(limit - results.size)?.forEach { topic ->
                    if (topic.Text?.isNotBlank() == true) {
                        results.add(ExternalSearchResult(
                            title = topic.Text.take(50) + if (topic.Text.length > 50) "..." else "",
                            snippet = topic.Text,
                            url = topic.FirstURL ?: "",
                            source = "DuckDuckGo (Related)"
                        ))
                    }
                }
                
                logger.info("🦆 DuckDuckGo 搜索成功: 找到 ${results.size} 条结果")
                
                ExternalSearchResults(
                    success = true,
                    source = "DuckDuckGo",
                    results = results.take(limit),
                    searchTimeMs = System.currentTimeMillis() - startTime
                )
            } catch (e: Exception) {
                logger.error("❌ DuckDuckGo 搜索失败: ${e.message}")
                ExternalSearchResults(
                    success = false,
                    source = "DuckDuckGo",
                    results = emptyList(),
                    searchTimeMs = System.currentTimeMillis() - startTime,
                    error = e.message
                )
            }
        }
    }
    
    /**
     * 检查服务是否可用
     */
    fun isAvailable(): Boolean {
        // DuckDuckGo 总是可用的（免费 API）
        return true
    }
    
    fun close() {
        client.close()
    }
}

// ==================== 数据模型 ====================

/**
 * 外部搜索结果
 */
@Serializable
data class ExternalSearchResult(
    val title: String,
    val snippet: String,
    val url: String,
    val source: String
)

/**
 * 外部搜索结果集
 */
@Serializable
data class ExternalSearchResults(
    val success: Boolean,
    val source: String,
    val results: List<ExternalSearchResult>,
    val searchTimeMs: Long,
    val error: String? = null
)

// ==================== API 响应模型 ====================

/**
 * SerpAPI 响应
 */
@Serializable
data class SerpAPIResponse(
    val organic_results: List<SerpAPIOrganicResult>? = null
)

@Serializable
data class SerpAPIOrganicResult(
    val title: String? = null,
    val snippet: String? = null,
    val link: String? = null
)

/**
 * DuckDuckGo 响应
 */
@Serializable
data class DuckDuckGoResponse(
    val Abstract: String? = null,
    val AbstractText: String? = null,
    val AbstractURL: String? = null,
    val Heading: String? = null,
    val RelatedTopics: List<DuckDuckGoTopic>? = null
)

@Serializable
data class DuckDuckGoTopic(
    val Text: String? = null,
    val FirstURL: String? = null
)

/**
 * Bing Search API 响应
 */
@Serializable
data class BingSearchResponse(
    val webPages: BingWebPages? = null
)

@Serializable
data class BingWebPages(
    val value: List<BingWebPage>? = null,
    val totalEstimatedMatches: Long? = null
)

@Serializable
data class BingWebPage(
    val name: String? = null,
    val url: String? = null,
    val snippet: String? = null,
    val displayUrl: String? = null
)



