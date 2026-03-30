/**
 * 群组数据模型
 */
export interface Group {
  id: string;
  name: string;
  invitationCode?: string;
  description?: string;
  avatar?: string;
  ownerId: string;
  memberCount: number;
  createdAt: number;
  updatedAt: number;
  lastMessage?: string;
  lastMessageTime?: number;
  unreadCount?: number;
}

/**
 * 群组成员
 */
export interface GroupMember {
  groupId: string;
  userId: string;
  userName: string;
  userAvatar?: string;
  role: MemberRole;
  joinedAt: number;
}

/**
 * 成员角色枚举
 */
export enum MemberRole {
  OWNER = 'OWNER',
  ADMIN = 'ADMIN',
  MEMBER = 'MEMBER'
}

/**
 * 创建群组请求
 */
export interface CreateGroupRequest {
  name: string;
  description?: string;
  ownerId: string;
  memberIds?: string[];
}

/**
 * 创建群组响应
 */
export interface CreateGroupResponse {
  success: boolean;
  message: string;
  group?: Group;
}

/**
 * 获取群组列表响应
 */
export interface GetGroupsResponse {
  success: boolean;
  groups: Group[];
}

/**
 * 创建默认群组
 */
export function createDefaultGroup(id: string, name: string, ownerId: string): Group {
  const now = Date.now();
  return {
    id,
    name,
    ownerId,
    memberCount: 1,
    createdAt: now,
    updatedAt: now
  };
}
