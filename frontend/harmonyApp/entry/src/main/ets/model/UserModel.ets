/**
 * 用户相关数据模型
 * 对齐后端 Models.kt 中的 User 及认证相关类型
 */

export interface User {
  id: string;
  loginName: string;
  fullName: string;
  phoneNumber: string;
  createdAt?: string;
}

export interface AuthResponse {
  success: boolean;
  message: string;
  user?: User;
}

export interface LoginRequest {
  loginName: string;
  password: string;
}

export interface RegisterRequest {
  loginName: string;
  fullName: string;
  phoneNumber: string;
  password: string;
}
