/**
 * i18n 国际化字符串系统
 * 对齐 shared/i18n/Strings.kt 的全部 key
 */
import { Language } from '../model/SettingsModel';

export interface AppStrings {
  settingsTitle: string;
  languageLabel: string;
  defaultAgentInstructionLabel: string;
  saveButton: string;
  cancelButton: string;
  settingsSaved: string;
  settingsSaveError: string;
  languageEnglish: string;
  languageChinese: string;
  languageEnglishNative: string;
  languageChineseNative: string;
  exitButton: string;
  createButton: string;
  joinButton: string;
  contactsButton: string;
  settingsButton: string;
  logoutButton: string;
  backButton: string;
  closeButton: string;
  inviteButton: string;
  membersButton: string;
  addButton: string;
  loading: string;
  noGroupsMessage: string;
  noGroupsSubmessage: string;
  createGroup: string;
  joinGroup: string;
  selectGroupsToExit: string;
  exitMode: string;
  exitConfirm: string;
  exiting: string;
  newMessage: string;
  groupName: string;
  invitationCode: string;
  fullName: string;
  createGroupTitle: string;
  joinGroupTitle: string;
  groupMembersTitle: string;
  noMembers: string;
  host: string;
  me: string;
  creating: string;
  joining: string;
  reconnecting: string;
  connected: string;
  connecting: string;
  disconnected: string;
  sendButton: string;
  messageInputPlaceholder: string;
  noMatchingUsers: string;
  addContact: string;
  addMembersToGroup: string;
  noContactsToAdd: string;
  inviteToGroup: string;
  copyInvitationCode: string;
  invitationCodeCopied: string;
  aiAssistant: string;
  currentUser: string;
  contactClickToChat: string;
  clickToAddContact: string;
  contactsTitle: string;
  phoneNumberLabel: string;
  phoneNumberPlaceholder: string;
  searchButton: string;
  sendAddRequestButton: string;
  contactRequestTitle: string;
  wantsToAddYouAsContact: string;
  acceptButton: string;
  rejectButton: string;
  backToGroups: string;
  pendingRequestsTitle: string;
  addContactButton: string;
  noContactsYet: string;
  addFirstContact: string;
  pendingStatus: string;
  chatWithSilk: string;
  silkChatInputPlaceholder: string;
  sendContactRequestQuestion: string;
  contactRequestSent: string;
  sendingRequest: string;
  sendRequest: string;
  memberAdded: string;
  memberNotInContacts: string;
  invitationCodePlaceholder: string;
  recallMessage: string;
  messageRecalled: string;
  recallFailed: string;
  login: string;
  register: string;
  username: string;
  password: string;
  phoneNumber: string;
  loginButton: string;
  registerButton: string;
  pleaseWait: string;
  fillAllFields: string;
  passwordMinLength: string;
  loginFailed: string;
  registerFailed: string;
  instantMessaging: string;
}

const stringsEN: AppStrings = {
  settingsTitle: 'Settings',
  languageLabel: 'Language',
  defaultAgentInstructionLabel: 'Default Agent Instruction',
  saveButton: 'Save',
  cancelButton: 'Cancel',
  settingsSaved: 'Settings saved successfully',
  settingsSaveError: 'Failed to save settings',
  languageEnglish: 'English',
  languageChinese: 'Chinese',
  languageEnglishNative: 'English',
  languageChineseNative: '中文',
  exitButton: 'Exit',
  createButton: 'Create',
  joinButton: 'Join',
  contactsButton: 'Contacts',
  settingsButton: 'Settings',
  logoutButton: 'Logout',
  backButton: 'Back',
  closeButton: 'Close',
  inviteButton: 'Invite',
  membersButton: 'Members',
  addButton: 'Add',
  loading: 'Loading...',
  noGroupsMessage: "You haven't joined any groups",
  noGroupsSubmessage: 'Create a new group or join an existing group',
  createGroup: 'Create Group',
  joinGroup: 'Join Group',
  selectGroupsToExit: 'Click to select groups to exit',
  exitMode: 'Exit',
  exitConfirm: 'Exit',
  exiting: 'Exiting...',
  newMessage: 'New message',
  groupName: 'Group Name',
  invitationCode: 'Invitation Code',
  fullName: 'Full Name',
  createGroupTitle: 'Create New Group',
  joinGroupTitle: 'Join Group',
  groupMembersTitle: 'Group Members',
  noMembers: 'No members',
  host: '(Host)',
  me: '(Me)',
  creating: 'Creating...',
  joining: 'Joining...',
  reconnecting: 'Reconnect',
  connected: 'Connected',
  connecting: 'Connecting...',
  disconnected: 'Disconnected',
  sendButton: 'Send',
  messageInputPlaceholder: 'Type message... (@silk to ask AI)',
  noMatchingUsers: 'No matching users',
  addContact: 'Add Contact',
  addMembersToGroup: 'Add Members to Group',
  noContactsToAdd: 'No contacts to add',
  inviteToGroup: 'Invite to Group',
  copyInvitationCode: 'Copy Invitation Code',
  invitationCodeCopied: 'Invitation code copied',
  aiAssistant: 'AI Assistant',
  currentUser: 'Current User',
  contactClickToChat: 'Contact · Click to chat',
  clickToAddContact: 'Click to add contact',
  contactsTitle: 'Contacts',
  phoneNumberLabel: 'Enter phone number',
  phoneNumberPlaceholder: 'Please enter phone number',
  searchButton: 'Search',
  sendAddRequestButton: 'Send add request',
  contactRequestTitle: 'Contact Request',
  wantsToAddYouAsContact: 'Wants to add you as contact',
  acceptButton: 'Accept',
  rejectButton: 'Reject',
  backToGroups: '← Groups',
  pendingRequestsTitle: 'Pending Requests',
  addContactButton: '+ Add Contact',
  noContactsYet: 'No contacts yet',
  addFirstContact: 'Add first contact',
  pendingStatus: 'Pending',
  chatWithSilk: 'Chat with Silk',
  silkChatInputPlaceholder: 'Ask Silk anything...',
  sendContactRequestQuestion: 'Send contact request?',
  contactRequestSent: 'Contact request sent',
  sendingRequest: 'Sending...',
  sendRequest: 'Send Request',
  memberAdded: 'Member added',
  memberNotInContacts: 'Not in your contacts list',
  invitationCodePlaceholder: 'Enter 6-digit invitation code',
  recallMessage: 'Recall',
  messageRecalled: 'Message recalled',
  recallFailed: 'Recall failed',
  login: 'Login',
  register: 'Register',
  username: 'Username',
  password: 'Password',
  phoneNumber: 'Phone Number',
  loginButton: 'Login',
  registerButton: 'Register',
  pleaseWait: 'Please wait...',
  fillAllFields: 'Please fill all fields',
  passwordMinLength: 'Password must be at least 6 characters',
  loginFailed: 'Login failed',
  registerFailed: 'Register failed',
  instantMessaging: 'Instant Messaging',
};

const stringsZH: AppStrings = {
  settingsTitle: '设置',
  languageLabel: '语言',
  defaultAgentInstructionLabel: '默认代理指令',
  saveButton: '保存',
  cancelButton: '取消',
  settingsSaved: '设置保存成功',
  settingsSaveError: '保存设置失败',
  languageEnglish: '英文',
  languageChinese: '中文',
  languageEnglishNative: 'English',
  languageChineseNative: '中文',
  exitButton: '退出',
  createButton: '创建',
  joinButton: '加入',
  contactsButton: '联系人',
  settingsButton: '设置',
  logoutButton: '登出',
  backButton: '返回',
  closeButton: '关闭',
  inviteButton: '邀请',
  membersButton: '成员',
  addButton: '添加',
  loading: '加载中...',
  noGroupsMessage: '您还没有加入任何群组',
  noGroupsSubmessage: '创建一个新群组或加入现有群组',
  createGroup: '创建群组',
  joinGroup: '加入群组',
  selectGroupsToExit: '点击选择要退出的群组',
  exitMode: '退出',
  exitConfirm: '退出',
  exiting: '退出中...',
  newMessage: '新消息',
  groupName: '群组名称',
  invitationCode: '邀请码',
  fullName: '完整名称',
  createGroupTitle: '创建新群组',
  joinGroupTitle: '加入群组',
  groupMembersTitle: '群组成员',
  noMembers: '暂无成员',
  host: '(群主)',
  me: '(我)',
  creating: '创建中...',
  joining: '加入中...',
  reconnecting: '重新连接',
  connected: '已连接',
  connecting: '连接中...',
  disconnected: '未连接',
  sendButton: '发送',
  messageInputPlaceholder: '输入消息... (@silk 提问AI)',
  noMatchingUsers: '无匹配用户',
  addContact: '添加联系人',
  addMembersToGroup: '添加成员到群组',
  noContactsToAdd: '没有可添加的联系人',
  inviteToGroup: '邀请入群',
  copyInvitationCode: '复制邀请码',
  invitationCodeCopied: '邀请码已复制',
  aiAssistant: 'AI 助手',
  currentUser: '当前用户',
  contactClickToChat: '联系人 · 点击聊天',
  clickToAddContact: '点击添加联系人',
  contactsTitle: '联系人',
  phoneNumberLabel: '输入电话号码',
  phoneNumberPlaceholder: '请输入电话号码',
  searchButton: '搜索',
  sendAddRequestButton: '发送添加请求',
  contactRequestTitle: '联系人请求',
  wantsToAddYouAsContact: '希望添加您为联系人',
  acceptButton: '接受',
  rejectButton: '拒绝',
  backToGroups: '← 群组',
  pendingRequestsTitle: '待处理请求',
  addContactButton: '+ 添加联系人',
  noContactsYet: '还没有联系人',
  addFirstContact: '添加第一个联系人',
  pendingStatus: '待处理',
  chatWithSilk: '与 Silk 对话',
  silkChatInputPlaceholder: '直接输入消息与 Silk 对话...',
  sendContactRequestQuestion: '是否发送联系人请求？',
  contactRequestSent: '联系人请求已发送',
  sendingRequest: '发送中...',
  sendRequest: '发送请求',
  memberAdded: '已添加成员',
  memberNotInContacts: '不在您的联系人列表中',
  invitationCodePlaceholder: '输入6位邀请码',
  recallMessage: '撤回',
  messageRecalled: '消息已撤回',
  recallFailed: '撤回失败',
  login: '登录',
  register: '注册',
  username: '用户名',
  password: '密码',
  phoneNumber: '手机号',
  loginButton: '登录',
  registerButton: '注册',
  pleaseWait: '请稍候...',
  fillAllFields: '请填写所有字段',
  passwordMinLength: '密码至少 6 位',
  loginFailed: '登录失败',
  registerFailed: '注册失败',
  instantMessaging: '即时通讯',
};

export function getStrings(language: Language): AppStrings {
  if (language === Language.ENGLISH) {
    return stringsEN;
  }
  return stringsZH;
}
