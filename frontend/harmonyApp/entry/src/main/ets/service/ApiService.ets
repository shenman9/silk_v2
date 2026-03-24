/**
 * 类型化 REST API 服务
 * 对齐 Android ApiClient.kt 中的全部端点
 */
import { httpGet, httpPost, httpPut, httpDelete } from './HttpClient';
import { AuthResponse, LoginRequest, RegisterRequest } from '../model/UserModel';
import { GroupResponse, CreateGroupRequest, JoinGroupRequest, LeaveGroupResponse, SimpleResponse }
  from '../model/GroupModel';
import { UnreadCountResponse, MarkReadRequest } from '../model/UnreadModel';
import { ContactResponse, UserSearchResult, HandleContactRequestData,
  PrivateChatResponse, GroupMembersResponse } from '../model/ContactModel';
import { UserSettingsResponse, UpdateUserSettingsRequest, Language } from '../model/SettingsModel';

function safeParse<T>(json: string): T | null {
  try {
    return JSON.parse(json) as T;
  } catch (e) {
    console.error('[ApiService] JSON parse error:', json);
    return null;
  }
}

// ==================== 认证 ====================

export async function login(loginName: string, password: string): Promise<AuthResponse> {
  const body: LoginRequest = { loginName, password };
  const result = await httpPost('/auth/login', JSON.stringify(body));
  const parsed = safeParse<AuthResponse>(result.data);
  return parsed ?? { success: false, message: '解析响应失败' };
}

export async function register(
  loginName: string, fullName: string, phoneNumber: string, password: string
): Promise<AuthResponse> {
  const body: RegisterRequest = { loginName, fullName, phoneNumber, password };
  const result = await httpPost('/auth/register', JSON.stringify(body));
  const parsed = safeParse<AuthResponse>(result.data);
  return parsed ?? { success: false, message: '解析响应失败' };
}

export async function validateUser(userId: string): Promise<AuthResponse> {
  const result = await httpGet('/auth/validate/' + userId);
  const parsed = safeParse<AuthResponse>(result.data);
  return parsed ?? { success: false, message: '解析响应失败' };
}

// ==================== 群组 ====================

export async function getUserGroups(userId: string): Promise<GroupResponse> {
  const result = await httpGet('/groups/user/' + userId);
  const parsed = safeParse<GroupResponse>(result.data);
  return parsed ?? { success: false, message: '解析响应失败' };
}

export async function createGroup(userId: string, groupName: string): Promise<GroupResponse> {
  const body: CreateGroupRequest = { userId, groupName };
  const result = await httpPost('/groups/create', JSON.stringify(body));
  const parsed = safeParse<GroupResponse>(result.data);
  return parsed ?? { success: false, message: '解析响应失败' };
}

export async function joinGroup(userId: string, invitationCode: string): Promise<GroupResponse> {
  const body: JoinGroupRequest = { userId, invitationCode };
  const result = await httpPost('/groups/join', JSON.stringify(body));
  const parsed = safeParse<GroupResponse>(result.data);
  return parsed ?? { success: false, message: '解析响应失败' };
}

export async function leaveGroup(groupId: string, userId: string): Promise<LeaveGroupResponse> {
  const body = JSON.stringify({ userId: userId });
  const result = await httpPost('/groups/' + groupId + '/leave', body);
  const parsed = safeParse<LeaveGroupResponse>(result.data);
  return parsed ?? { success: false, message: '解析响应失败', groupDeleted: false };
}

export async function deleteGroup(groupId: string, userId: string): Promise<SimpleResponse> {
  const body = JSON.stringify({ userId: userId });
  const result = await httpDelete('/groups/' + groupId, body);
  const parsed = safeParse<SimpleResponse>(result.data);
  return parsed ?? { success: false, message: '解析响应失败' };
}

export async function getGroupMembers(groupId: string): Promise<GroupMembersResponse> {
  const result = await httpGet('/groups/' + groupId + '/members');
  const parsed = safeParse<GroupMembersResponse>(result.data);
  return parsed ?? { success: false, members: [] };
}

export async function addMemberToGroup(groupId: string, userId: string): Promise<SimpleResponse> {
  const body = JSON.stringify({ userId: userId });
  const result = await httpPost('/groups/' + groupId + '/add-member', body);
  const parsed = safeParse<SimpleResponse>(result.data);
  return parsed ?? { success: false, message: '解析响应失败' };
}

// ==================== 未读消息 ====================

export async function getUnreadCounts(userId: string): Promise<UnreadCountResponse> {
  const result = await httpGet('/api/unread/' + userId);
  const parsed = safeParse<UnreadCountResponse>(result.data);
  return parsed ?? { success: false, unreadCounts: {} };
}

export async function markGroupAsRead(userId: string, groupId: string): Promise<boolean> {
  const body: MarkReadRequest = { userId, groupId };
  const result = await httpPost('/api/unread/mark-read', JSON.stringify(body));
  const parsed = safeParse<SimpleResponse>(result.data);
  return parsed?.success ?? false;
}

// ==================== 联系人 ====================

export async function getContacts(userId: string): Promise<ContactResponse> {
  const result = await httpGet('/contacts/' + userId);
  const parsed = safeParse<ContactResponse>(result.data);
  return parsed ?? { success: false, message: '解析响应失败' };
}

export async function searchUserByPhone(phone: string): Promise<UserSearchResult> {
  const result = await httpGet('/users/search?phone=' + encodeURIComponent(phone));
  const parsed = safeParse<UserSearchResult>(result.data);
  return parsed ?? { found: false, message: '解析响应失败' };
}

export async function sendContactRequest(fromUserId: string, toPhoneNumber: string): Promise<ContactResponse> {
  const body = JSON.stringify({ fromUserId: fromUserId, toPhoneNumber: toPhoneNumber });
  const result = await httpPost('/contacts/request', body);
  const parsed = safeParse<ContactResponse>(result.data);
  return parsed ?? { success: false, message: '解析响应失败' };
}

export async function sendContactRequestById(fromUserId: string, toUserId: string): Promise<ContactResponse> {
  const body = JSON.stringify({ fromUserId: fromUserId, toUserId: toUserId });
  const result = await httpPost('/contacts/request-by-id', body);
  const parsed = safeParse<ContactResponse>(result.data);
  return parsed ?? { success: false, message: '解析响应失败' };
}

export async function handleContactRequest(requestId: string, userId: string, accept: boolean): Promise<ContactResponse> {
  const body: HandleContactRequestData = { requestId, userId, accept };
  const result = await httpPost('/contacts/handle-request', JSON.stringify(body));
  const parsed = safeParse<ContactResponse>(result.data);
  return parsed ?? { success: false, message: '解析响应失败' };
}

export async function startPrivateChat(userId: string, contactId: string): Promise<PrivateChatResponse> {
  const body = JSON.stringify({ userId: userId, contactId: contactId });
  const result = await httpPost('/contacts/private-chat', body);
  const parsed = safeParse<PrivateChatResponse>(result.data);
  return parsed ?? { success: false, message: '解析响应失败' };
}

export async function startSilkPrivateChat(userId: string): Promise<PrivateChatResponse> {
  const body = JSON.stringify({ userId: userId });
  const result = await httpPost('/api/silk-private-chat', body);
  const parsed = safeParse<PrivateChatResponse>(result.data);
  return parsed ?? { success: false, message: '解析响应失败' };
}

// ==================== 消息操作 ====================

export async function recallMessage(groupId: string, messageId: string, userId: string): Promise<SimpleResponse> {
  const body = JSON.stringify({ groupId: groupId, messageId: messageId, userId: userId });
  const result = await httpPost('/api/messages/recall', body);
  const parsed = safeParse<SimpleResponse>(result.data);
  return parsed ?? { success: false, message: '解析响应失败' };
}

// ==================== 用户设置 ====================

export async function getUserSettings(userId: string): Promise<UserSettingsResponse> {
  const result = await httpGet('/users/' + userId + '/settings');
  const parsed = safeParse<UserSettingsResponse>(result.data);
  return parsed ?? { success: false, message: '解析响应失败' };
}

export async function updateUserSettings(userId: string, language: Language, defaultAgentInstruction: string): Promise<UserSettingsResponse> {
  const body: UpdateUserSettingsRequest = { userId, language, defaultAgentInstruction };
  const result = await httpPut('/users/' + userId + '/settings', JSON.stringify(body));
  const parsed = safeParse<UserSettingsResponse>(result.data);
  return parsed ?? { success: false, message: '解析响应失败' };
}
