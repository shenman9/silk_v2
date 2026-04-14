package com.silk.android

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class Scene {
    LOGIN,
    GROUP_LIST,
    CONTACTS,
    CHAT_ROOM,
    SETTINGS
}

class AppState(
    private val context: Context,
    private val scope: CoroutineScope
) {
    var currentScene by mutableStateOf(Scene.LOGIN)
        private set
    
    var currentUser by mutableStateOf<User?>(null)
        private set
    
    var selectedGroup by mutableStateOf<Group?>(null)
        private set
    
    var isValidating by mutableStateOf(false)
        private set
    
    // 标记用户是否明确请求了退出登录
    // 只有当用户点击"登出"按钮时才为 true
    private var explicitLogoutRequested = false
    
    private val sceneHistory = mutableListOf<Scene>()
    
    // 版本检查器
    val versionChecker: VersionChecker
    
    init {
        BackendUrlHolder.init(context)

        // 获取当前 App 版本信息
        val packageInfo = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val versionCode = packageInfo?.let {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                it.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                it.versionCode
            }
        } ?: 1
        val versionName = packageInfo?.versionName ?: "1.0.0"
        
        // 初始化版本检查器
        versionChecker = VersionChecker(context, versionCode, versionName)
        versionChecker.startChecking()
        
        loadUserFromStorage()
    }
    
    fun setUser(user: User) {
        currentUser = user
        saveUserToStorage(user)
        navigateTo(Scene.GROUP_LIST)
    }
    
    fun selectGroup(group: Group) {
        println("📌 选择群组: ${group.name}")
        selectedGroup = group
        navigateTo(Scene.CHAT_ROOM)
    }
    
    fun navigateTo(scene: Scene) {
        if (currentScene != scene) {
            sceneHistory.add(currentScene)
        }
        currentScene = scene
    }
    
    fun navigateBack(): Boolean {
        if (sceneHistory.isNotEmpty()) {
            // 先检查目标页面，不要先移除
            val previousScene = sceneHistory.last()
            
            // 防止意外退出登录：从群组列表不能返回到登录页面
            // 用户必须通过点击登出按钮来明确退出登录
            if (previousScene == Scene.LOGIN && currentUser != null) {
                println("🚫 阻止返回到登录页面（用户已登录）")
                // 不允许返回到登录页，保持在当前页面，不修改历史
                return false
            }
            
            // 确认可以返回后，再移除历史记录
            sceneHistory.removeAt(sceneHistory.size - 1)
            
            // 如果返回到群组列表，清除选中的群组
            if (previousScene == Scene.GROUP_LIST) {
                selectedGroup = null
            }
            
            currentScene = previousScene
            return true
        }
        return false
    }
    
    fun logout() {
        println("🚪 用户明确请求退出登录")
        explicitLogoutRequested = true
        currentUser = null
        selectedGroup = null
        sceneHistory.clear()
        clearUserFromStorage()
        navigateTo(Scene.LOGIN)
    }
    
    /**
     * 检查是否应该恢复到群组列表页面
     * 在 Login 页面调用，如果用户没有明确退出登录但意外到达了登录页，则自动恢复
     * @return true 如果已恢复到群组列表，false 如果应该保持在登录页
     */
    fun checkAndRestoreSession(): Boolean {
        // 检查是否有保存的用户数据
        val prefs = context.getSharedPreferences("silk_prefs", Context.MODE_PRIVATE)
        val savedUserId = prefs.getString("user_id", null)
        val savedLoginName = prefs.getString("user_login_name", null)
        val savedFullName = prefs.getString("user_full_name", null)
        val savedPhoneNumber = prefs.getString("user_phone_number", null)
        
        // 如果用户明确请求了退出登录，不恢复
        if (explicitLogoutRequested) {
            println("🔐 用户明确退出登录，保持在登录页")
            return false
        }
        
        // 如果有保存的用户数据，说明用户没有明确退出，是意外到达登录页的
        if (savedUserId != null && savedLoginName != null && savedFullName != null && savedPhoneNumber != null) {
            println("🔄 检测到保存的用户数据，用户未明确退出登录，自动恢复到群组列表")
            // 恢复用户数据
            currentUser = User(savedUserId, savedLoginName, savedFullName, savedPhoneNumber)
            // 直接跳转到群组列表
            currentScene = Scene.GROUP_LIST
            sceneHistory.clear()
            return true
        }
        
        println("🔐 没有保存的用户数据，保持在登录页")
        return false
    }
    
    /**
     * 清理资源（Activity 销毁时调用）
     */
    fun destroy() {
        versionChecker.destroy()
    }
    
    /**
     * 重新验证用户
     * @return Pair<Boolean, Boolean> - first: 是否验证成功, second: 是否是网络错误
     */
    suspend fun revalidateUser(): Pair<Boolean, Boolean> {
        isValidating = true
        return try {
            val user = currentUser
            if (user != null) {
                val response = ApiClient.validateUser(user.id)
                if (response.success && response.user != null) {
                    currentUser = response.user
                    saveUserToStorage(response.user)
                    Pair(true, false)
                } else {
                    // 服务器明确拒绝此用户（如用户被删除），才登出
                    logout()
                    Pair(false, false)
                }
            } else {
                Pair(false, false)
            }
        } catch (e: Exception) {
            // 网络异常时，不登出，保留用户信息，允许离线使用
            println("⚠️ 用户验证失败（网络异常），保持登录状态: ${e.message}")
            Pair(false, true)
        } finally {
            isValidating = false
        }
    }
    
    private fun loadUserFromStorage() {
        val prefs = context.getSharedPreferences("silk_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", null)
        val loginName = prefs.getString("user_login_name", null)
        val fullName = prefs.getString("user_full_name", null)
        val phoneNumber = prefs.getString("user_phone_number", null)
        
        if (userId != null && loginName != null && fullName != null && phoneNumber != null) {
            currentUser = User(userId, loginName, fullName, phoneNumber)
            
            // 启动时重新验证用户，但即使验证失败（网络问题）也保持登录状态
            scope.launch {
                val (isValid, isNetworkError) = revalidateUser()
                // 验证成功 或 网络错误（保持登录）都进入主界面
                if (isValid || isNetworkError) {
                    navigateTo(Scene.GROUP_LIST)
                }
                // 只有服务器明确拒绝时才保持在登录页面
            }
        }
    }
    
    private fun saveUserToStorage(user: User) {
        val prefs = context.getSharedPreferences("silk_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("user_id", user.id)
            putString("user_login_name", user.loginName)
            putString("user_full_name", user.fullName)
            putString("user_phone_number", user.phoneNumber)
            apply()
        }
    }
    
    private fun clearUserFromStorage() {
        val prefs = context.getSharedPreferences("silk_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }
}

