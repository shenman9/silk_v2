package com.silk.backend.auth

import com.silk.backend.database.*
import org.mindrot.jbcrypt.BCrypt

/**
 * 用户认证服务
 */
object AuthService {
    
    /**
     * 注册新用户
     */
    fun register(request: RegisterRequest): AuthResponse {
        // 验证输入
        if (request.loginName.isBlank()) {
            return AuthResponse(false, "登录名不能为空")
        }
        if (request.fullName.isBlank()) {
            return AuthResponse(false, "姓名不能为空")
        }
        if (request.phoneNumber.isBlank()) {
            return AuthResponse(false, "手机号不能为空")
        }
        if (request.password.length < 6) {
            return AuthResponse(false, "密码至少需要6位字符")
        }
        
        // 检查登录名是否已存在
        if (UserRepository.loginNameExists(request.loginName)) {
            return AuthResponse(false, "该登录名已被使用")
        }
        
        // 检查手机号是否已存在
        if (UserRepository.phoneNumberExists(request.phoneNumber)) {
            return AuthResponse(false, "该手机号已被注册")
        }
        
        // 生成密码哈希
        val passwordHash = BCrypt.hashpw(request.password, BCrypt.gensalt(12))
        
        // 创建用户
        val user = UserRepository.createUser(
            loginName = request.loginName,
            fullName = request.fullName,
            phoneNumber = request.phoneNumber,
            passwordHash = passwordHash
        )
        
        return if (user != null) {
            AuthResponse(true, "注册成功", user)
        } else {
            AuthResponse(false, "注册失败，请稍后重试")
        }
    }
    
    /**
     * 用户登录 - 支持 loginName、手机号、全名登录
     */
    fun login(request: LoginRequest): AuthResponse {
        println("🔐 [Login] 尝试登录: ${request.loginName}")
        
        // 验证输入
        if (request.loginName.isBlank()) {
            println("🔐 [Login] 失败: 登录名为空")
            return AuthResponse(false, "登录名不能为空")
        }
        if (request.password.isBlank()) {
            println("🔐 [Login] 失败: 密码为空")
            return AuthResponse(false, "密码不能为空")
        }
        
        // 查找用户 - 支持多种登录方式
        var user = UserRepository.findUserByLoginName(request.loginName)
        if (user == null) {
            println("🔐 [Login] loginName 未找到，尝试手机号...")
            user = UserRepository.findUserByPhoneNumber(request.loginName)
        }
        if (user == null) {
            println("🔐 [Login] 手机号未找到，尝试全名...")
            user = UserRepository.findUserByFullName(request.loginName)
        }
        if (user == null) {
            println("🔐 [Login] 失败: 用户不存在 - ${request.loginName}")
            return AuthResponse(false, "用户名或密码错误")
        }
        println("🔐 [Login] 找到用户: ${user.id} (loginName: ${user.loginName})")
        
        // 验证密码 - 使用找到的用户的 loginName
        val passwordHash = UserRepository.getUserPasswordHash(user.loginName)
        println("🔐 [Login] 密码哈希: ${passwordHash?.take(20)}...")
        if (passwordHash == null) {
            println("🔐 [Login] 失败: 无法获取密码哈希")
            return AuthResponse(false, "用户名或密码错误")
        }
        
        val passwordMatch = BCrypt.checkpw(request.password, passwordHash)
        println("🔐 [Login] 密码验证结果: $passwordMatch")
        if (!passwordMatch) {
            return AuthResponse(false, "用户名或密码错误")
        }
        
        println("🔐 [Login] 成功: ${user.loginName}")
        return AuthResponse(true, "登录成功", user)
    }
    
    /**
     * 验证用户是否存在
     */
    fun validateUser(userId: String): Boolean {
        return UserRepository.findUserById(userId) != null
    }
}

