package com.silk.android

import android.content.Context
import android.content.SharedPreferences

/**
 * 运行时可配置的后端地址。
 * 优先使用用户在「设置」中填写的服务器地址，未设置时使用构建时注入的 BuildConfig.BACKEND_BASE_URL。
 * 这样同一份 APK 可连不同环境，或构建时未配置 .env 时也可在应用内填写远程地址。
 */
object BackendUrlHolder {
    private const val PREFS_NAME = "silk_backend"
    private const val KEY_BACKEND_BASE_URL = "backend_base_url"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun getBaseUrl(): String {
        val url = prefs?.getString(KEY_BACKEND_BASE_URL, null)?.trim()
        return if (!url.isNullOrEmpty()) url else BuildConfig.BACKEND_BASE_URL
    }

    fun setBaseUrl(url: String) {
        prefs?.edit()?.putString(KEY_BACKEND_BASE_URL, url.trim())?.apply()
    }

    /** 清除手动设置的地址，恢复为构建时默认（BuildConfig） */
    fun clearOverride() {
        prefs?.edit()?.remove(KEY_BACKEND_BASE_URL)?.apply()
    }

    /** 当前是否使用了用户覆盖的地址（而非 BuildConfig） */
    fun hasOverride(): Boolean {
        return !prefs?.getString(KEY_BACKEND_BASE_URL, null)?.trim().isNullOrEmpty()
    }
}
