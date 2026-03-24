/**
 * 群组相关数据模型
 * 对齐后端 Models.kt 中的 Group 及群组管理相关类型
 */

export interface Group {
  id: string;
  name: string;
  invitationCode: string;
  hostId: string;
  hostName?: string;
  createdAt?: string;
}

export interface GroupResponse {
  success: boolean;
  message: string;
  group?: Group;
  groups?: Group[];
}

export interface CreateGroupRequest {
  userId: string;
  groupName: string;
}

export interface JoinGroupRequest {
  userId: string;
  invitationCode: string;
}

export interface LeaveGroupRequest {
  userId: string;
}

export interface LeaveGroupResponse {
  success: boolean;
  message: string;
  groupDeleted: boolean;
}

export interface DeleteGroupRequest {
  userId: string;
}

export interface SimpleResponse {
  success: boolean;
  message: string;
}
