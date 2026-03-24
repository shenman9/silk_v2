/**
 * 联系人相关数据模型
 * 对齐后端 Models.kt
 */

export enum ContactRequestStatus {
  PENDING = 'pending',
  ACCEPTED = 'accepted',
  REJECTED = 'rejected'
}

export interface Contact {
  userId: string;
  contactId: string;
  contactName: string;
  contactPhone: string;
  createdAt?: string;
}

export interface ContactRequest {
  id: string;
  fromUserId: string;
  fromUserName: string;
  fromUserPhone: string;
  toUserId: string;
  status: ContactRequestStatus;
  createdAt?: string;
}

export interface ContactResponse {
  success: boolean;
  message: string;
  contact?: Contact;
  contacts?: Contact[];
  pendingRequests?: ContactRequest[];
}

export interface UserSearchResult {
  found: boolean;
  user?: SearchedUser;
  message?: string;
}

export interface SearchedUser {
  id: string;
  loginName: string;
  fullName: string;
  phoneNumber: string;
}

export interface SendContactRequestData {
  fromUserId: string;
  toPhoneNumber: string;
}

export interface SendContactRequestByIdData {
  fromUserId: string;
  toUserId: string;
}

export interface HandleContactRequestData {
  requestId: string;
  userId: string;
  accept: boolean;
}

export interface PrivateChatResponse {
  success: boolean;
  message: string;
  group?: PrivateChatGroup;
  isNew?: boolean;
}

export interface PrivateChatGroup {
  id: string;
  name: string;
  invitationCode: string;
  hostId: string;
  hostName?: string;
}

export interface GroupMember {
  id: string;
  fullName: string;
  phone?: string;
}

export interface GroupMembersResponse {
  success: boolean;
  members: GroupMember[];
}
