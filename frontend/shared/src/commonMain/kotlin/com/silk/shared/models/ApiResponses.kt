package com.silk.shared.models

import kotlinx.serialization.Serializable

/**
 * 通用简单响应
 */
@Serializable
data class SimpleResponse(
    val success: Boolean,
    val message: String
)

/**
 * 退出群组响应
 */
@Serializable
data class LeaveGroupResponse(
    val success: Boolean,
    val message: String,
    val groupDeleted: Boolean = false
)
