package com.silk.shared.models

import kotlinx.serialization.Serializable

/**
 * 语言枚举
 */
@Serializable
enum class Language {
    ENGLISH,
    CHINESE
}

/**
 * 用户设置数据模型
 */
@Serializable
data class UserSettings(
    val language: Language = Language.CHINESE,
    val defaultAgentInstruction: String = "You are a helpful technical research assistant. ",
    val ccBridgeToken: String? = null,
)

/**
 * 更新用户设置请求
 */
@Serializable
data class UpdateUserSettingsRequest(
    val userId: String,
    val language: Language,
    val defaultAgentInstruction: String
)

/**
 * 用户设置响应
 */
@Serializable
data class UserSettingsResponse(
    val success: Boolean,
    val message: String,
    val settings: UserSettings? = null
)

/**
 * CC 设置响应
 */
@Serializable
data class CcSettingsResponse(
    val success: Boolean,
    val message: String,
    val ccBridgeToken: String? = null,
    val bridgeConnected: Boolean = false,
)
