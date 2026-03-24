/**
 * 未读消息相关数据模型
 */

export interface UnreadCountResponse {
  success: boolean;
  unreadCounts: Record<string, number>;
}

export interface MarkReadRequest {
  userId: string;
  groupId: string;
}
