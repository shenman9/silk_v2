package com.silk.shared.i18n

/**
 * Chinese string resources
 */
object Strings_ZH : Strings {
    override val settingsTitle = "设置"
    override val languageLabel = "语言"
    override val defaultAgentInstructionLabel = "默认代理指令"
    override val saveButton = "保存"
    override val cancelButton = "取消"
    override val settingsSaved = "设置保存成功"
    override val settingsSaveError = "保存设置失败"
    
    override val languageEnglish = "英文"
    override val languageChinese = "中文"
    override val languageEnglishNative = "English"
    override val languageChineseNative = "中文"
    
    // Navigation buttons
    override val exitButton = "退出"
    override val createButton = "创建"
    override val joinButton = "加入"
    override val contactsButton = "联系人"
    override val settingsButton = "设置"
    override val logoutButton = "登出"
    override val backButton = "返回"
    override val closeButton = "关闭"
    override val inviteButton = "邀请"
    override val membersButton = "成员"
    override val addButton = "添加"
    
    // Group list page
    override val loading = "加载中..."
    override val noGroupsMessage = "您还没有加入任何群组"
    override val noGroupsSubmessage = "创建一个新群组或加入现有群组"
    override val createGroup = "创建群组"
    override val joinGroup = "加入群组"
    override val selectGroupsToExit = "点击选择要退出的群组"
    override val exitMode = "退出"
    override val exitConfirm = "退出"
    override val exiting = "退出中..."
    override val newMessage = "新消息"
    override val groupName = "群组名称"
    override val invitationCode = "邀请码"
    override val fullName = "完整名称"
    
    // Dialogs
    override val createGroupTitle = "创建新群组"
    override val joinGroupTitle = "加入群组"
    override val groupMembersTitle = "群组成员"
    override val noMembers = "暂无成员"
    override val host = "(群主)"
    override val me = "(我)"
    override val creating = "创建中..."
    override val joining = "加入中..."
    
    // Chat page
    override val reconnecting = "重新连接"
    override val connected = "已连接"
    override val connecting = "连接中..."
    override val disconnected = "未连接"
    override val sendButton = "发送"
    override val messageInputPlaceholder = "输入消息... (@silk 提问AI, Enter发送)"
    override val noMatchingUsers = "无匹配用户"
    
    // Chat dialogs
    override val memberAdded = "✅ 已添加 {name}"  // {name} will be replaced
    override val memberNotInContacts = "{name} 不在您的联系人列表中。"  // {name} will be replaced
    override val sendContactRequestQuestion = "是否发送联系人请求？"
    override val contactRequestSent = "✅ 联系人请求已发送"
    override val sendingRequest = "发送中..."
    override val sendRequest = "发送请求"
    override val addContact = "添加联系人"
    override val addMembersToGroup = "➕ 添加成员到群组"
    override val noContactsToAdd = "没有可添加的联系人\n（所有联系人已在群组中）"
    override val groupMembersTitleWithCount = "👥 群组成员 ({count})"  // {count} will be replaced
    override val inviteToGroup = "邀请入群"
    override val selectShareMethod = "选择分享方式："
    override val copyInvitationCode = "复制邀请码"
    override val invitationCodeCopied = "✅ 邀请码已复制: {code}"  // {code} will be replaced
    override val copyFullMessage = "复制完整消息"
    override val fullMessageCopied = "✅ 完整邀请消息已复制到剪贴板"
    override val aiAssistant = "AI 助手"
    override val currentUser = "当前用户"
    override val contactClickToChat = "联系人 · 点击聊天"
    override val clickToAddContact = "点击添加联系人"
    
    // File dialog
    override val sessionFiles = "📁 会话文件"
    override val noFilesYet = "📭 暂无文件"
    override val useBottomButtonToUpload = "使用底部的 📎 按钮上传文件"
    override val download = "下载"
    override val unknownFile = "未知文件"
    
    // Contacts page
    override val contactsTitle = "联系人"
    override val myContactsWithCount = "我的联系人 ({count})"  // {count} will be replaced
    
    // Invitation code
    override val invitationCodePlaceholder = "输入6位邀请码"
    
    // Contact dialogs
    override val phoneNumberLabel = "输入电话号码"
    override val phoneNumberPlaceholder = "请输入电话号码"
    override val searchButton = "搜索"
    override val sendAddRequestButton = "发送添加请求"
    override val contactRequestTitle = "联系人请求"
    override val wantsToAddYouAsContact = "希望添加您为联系人"
    override val acceptButton = "接受"
    override val rejectButton = "拒绝"
    
    // Contacts scene
    override val backToGroups = "← 群组"
    override val pendingRequestsTitle = "待处理请求 ({count})"  // {count} will be replaced
    override val addContactButton = "+ 添加联系人"
    override val noContactsYet = "还没有联系人"
    override val addFirstContact = "添加第一个联系人"
    override val pendingStatus = "待处理"
    
    // Silk AI chat
    override val chatWithSilk = "与 Silk 对话"
    override val silkChatInputPlaceholder = "直接输入消息与 Silk 对话... (Enter发送)"
}
