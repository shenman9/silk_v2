package com.silk.backend.auth

import com.silk.backend.database.*
import org.slf4j.LoggerFactory

/**
 * 群组管理服务
 */
object GroupService {
    private val logger = LoggerFactory.getLogger(GroupService::class.java)
    
    /**
     * 创建新群组
     */
    fun createGroup(request: CreateGroupRequest): GroupResponse {
        // 验证输入
        if (request.groupName.isBlank()) {
            return GroupResponse(false, "群组名称不能为空")
        }
        
        // 验证用户是否存在
        val user = UserRepository.findUserById(request.userId)
        if (user == null) {
            return GroupResponse(false, "用户不存在")
        }
        
        // 使用用户输入的群名（不添加前缀）
        // 如果名字重复，自动添加 (数字) 后缀
        val uniqueGroupName = generateUniqueGroupName(request.groupName)
        
        // 创建群组
        val group = GroupRepository.createGroup(uniqueGroupName, request.userId)
        
        if (group == null) {
            return GroupResponse(false, "创建群组失败，请稍后重试")
        }
        
        logger.info("✅ 群组创建成功: {} (邀请码: {})", group.name, group.invitationCode)
        
        return GroupResponse(true, "群组创建成功", group)
    }
    
    /**
     * 生成唯一的群组名称
     * 如果名称已存在，在后面添加 (1), (2), (3)... 直到找到唯一名称
     */
    private fun generateUniqueGroupName(baseName: String): String {
        // 检查原始名称是否可用
        if (!GroupRepository.isGroupNameExists(baseName)) {
            return baseName
        }
        
        // 名称已存在，尝试添加数字后缀
        var counter = 1
        var candidateName: String
        
        do {
            candidateName = "$baseName($counter)"
            counter++
        } while (GroupRepository.isGroupNameExists(candidateName) && counter < 1000)
        
        return candidateName
    }
    
    /**
     * 加入群组（通过邀请码）
     */
    fun joinGroup(request: JoinGroupRequest): GroupResponse {
        // 验证输入
        if (request.invitationCode.isBlank()) {
            return GroupResponse(false, "邀请码不能为空")
        }
        
        // 验证用户是否存在
        val user = UserRepository.findUserById(request.userId)
        if (user == null) {
            return GroupResponse(false, "用户不存在")
        }
        
        // 根据邀请码查找群组
        val group = GroupRepository.findGroupByInvitationCode(request.invitationCode)
        if (group == null) {
            return GroupResponse(false, "邀请码无效")
        }
        
        // 检查用户是否已在群组中
        if (GroupRepository.isUserInGroup(group.id, request.userId)) {
            return GroupResponse(false, "您已经在该群组中")
        }
        
        // 将用户添加到群组（角色为guest）
        val success = GroupRepository.addUserToGroup(group.id, request.userId, MemberRole.GUEST)
        
        if (!success) {
            return GroupResponse(false, "加入群组失败")
        }
        
        logger.info("✅ 用户 {} 加入群组: {}", user.fullName, group.name)
        
        return GroupResponse(true, "成功加入群组", group)
    }
    
    /**
     * 获取用户的所有群组
     */
    fun getUserGroups(userId: String): GroupResponse {
        // 验证用户是否存在
        if (!AuthService.validateUser(userId)) {
            return GroupResponse(false, "用户不存在")
        }
        
        val groups = GroupRepository.getUserGroups(userId)
        
        return GroupResponse(true, "获取成功", groups = groups)
    }
    
    /**
     * 获取群组详情
     */
    fun getGroupDetails(groupId: String): GroupResponse {
        val group = GroupRepository.findGroupById(groupId)
        
        if (group == null) {
            return GroupResponse(false, "群组不存在")
        }
        
        return GroupResponse(true, "获取成功", group)
    }
    
    /**
     * 获取群组成员列表
     */
    fun getGroupMembers(groupId: String): List<GroupMember> {
        return GroupRepository.getGroupMembers(groupId)
    }
}

