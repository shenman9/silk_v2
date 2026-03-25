/**
 * 用户数据模型
 */
export interface User {
  id: string;
  name: string;
  email?: string;
  phone?: string;
  avatar?: string;
  createdAt?: number;
}

/**
 * 语言枚举
 */
export enum Language {
  ENGLISH = 'ENGLISH',
  CHINESE = 'CHINESE'
}

/**
 * 用户设置
 */
export interface UserSettings {
  language: Language;
  defaultAgentInstruction: string;
}

/**
 * 登录请求
 */
export interface LoginRequest {
  loginName: string;
  password: string;
}

/**
 * 登录响应
 */
export interface LoginResponse {
  success: boolean;
  message: string;
  user?: User;
  token?: string;
}

/**
 * 注册请求
 */
export interface RegisterRequest {
  loginName: string;
  password: string;
  fullName: string;
  phoneNumber?: string;
}

/**
 * 注册响应
 */
export interface RegisterResponse {
  success: boolean;
  message: string;
  user?: User;
}

/**
 * 更新用户设置请求
 */
export interface UpdateUserSettingsRequest {
  userId: string;
  language: Language;
  defaultAgentInstruction: string;
}

/**
 * 创建默认用户设置
 */
export function createDefaultUserSettings(): UserSettings {
  return {
    language: Language.CHINESE,
    defaultAgentInstruction: 'You are a helpful technical research assistant.'
  };
}
