/**
 * Silk API Client - HTTP 网络请求封装
 * 使用 @ohos.net.http 实现 RESTful API 调用
 */
import http from '@ohos.net.http';
import { User, LoginResponse, RegisterResponse } from '../models/User';
import { Group, GroupMember } from '../models/Group';
import { UserSettings, UserSettingsResponse } from '../models/UserSettings';
import { API_BASE_URL, API_TIMEOUT } from '../common/Constants';

/**
 * API 响应通用结构
 */
export interface ApiResponse<T> {
  success: boolean;
  message: string;
  data?: T;
}

/**
 * 登录请求参数
 */
export interface LoginRequest {
  loginName: string;
  password: string;
}

/**
 * 注册请求参数
 */
export interface RegisterRequest {
  loginName: string;
  password: string;
  fullName: string;
  phoneNumber?: string;
}

/**
 * 创建群组请求
 */
export interface CreateGroupRequest {
  name: string;
  description?: string;
  createdBy: string;
}

/**
 * 文件搜索请求
 */
export interface SearchRequest {
  query: string;
  userId: string;
  groupId?: string;
  limit?: number;
}

/**
 * 文件搜索结果
 */
export interface SearchResult {
  fileName: string;
  filePath: string;
  content: string;
  score: number;
  highlight?: string;
}

/**
 * Silk API 客户端
 */
export class ApiClient {
  private baseUrl: string = API_BASE_URL;
  private timeout: number = API_TIMEOUT;

  /**
   * 设置基础 URL
   */
  setBaseUrl(url: string): void {
    this.baseUrl = url;
  }

  /**
   * 创建 HTTP 请求
   */
  private createHttpRequest(): http.HttpRequest {
    return http.createHttp();
  }

  /**
   * 发送 GET 请求
   */
  async get<T>(endpoint: string, headers?: Record<string, string>): Promise<ApiResponse<T>> {
    const httpRequest = this.createHttpRequest();
    try {
      const response = await httpRequest.request(
        `${this.baseUrl}${endpoint}`,
        {
          method: http.RequestMethod.GET,
          header: {
            'Content-Type': 'application/json',
            ...headers
          },
          connectTimeout: this.timeout,
          readTimeout: this.timeout
        }
      );

      if (response.responseCode === 200) {
        const result = JSON.parse(response.result as string);
        return result as ApiResponse<T>;
      } else {
        return {
          success: false,
          message: `HTTP Error: ${response.responseCode}`
        };
      }
    } catch (error) {
      return {
        success: false,
        message: `Request failed: ${error.message}`
      };
    } finally {
      httpRequest.destroy();
    }
  }

  /**
   * 发送 POST 请求
   */
  async post<T>(endpoint: string, body: object, headers?: Record<string, string>): Promise<ApiResponse<T>> {
    const httpRequest = this.createHttpRequest();
    try {
      const response = await httpRequest.request(
        `${this.baseUrl}${endpoint}`,
        {
          method: http.RequestMethod.POST,
          header: {
            'Content-Type': 'application/json',
            ...headers
          },
          extraData: body,
          connectTimeout: this.timeout,
          readTimeout: this.timeout
        }
      );

      if (response.responseCode === 200 || response.responseCode === 201) {
        const result = JSON.parse(response.result as string);
        return result as ApiResponse<T>;
      } else {
        return {
          success: false,
          message: `HTTP Error: ${response.responseCode}`
        };
      }
    } catch (error) {
      return {
        success: false,
        message: `Request failed: ${error.message}`
      };
    } finally {
      httpRequest.destroy();
    }
  }

  // ==================== 认证相关 API ====================

  /**
   * 用户登录
   */
  async login(loginName: string, password: string): Promise<ApiResponse<LoginResponse>> {
    return this.post<LoginResponse>('/api/auth/login', {
      loginName,
      password
    });
  }

  /**
   * 用户注册
   */
  async register(request: RegisterRequest): Promise<ApiResponse<RegisterResponse>> {
    return this.post<RegisterResponse>('/api/auth/register', request);
  }

  /**
   * 验证会话
   */
  async validateSession(userId: string): Promise<ApiResponse<User>> {
    return this.get<User>(`/api/auth/validate?userId=${userId}`);
  }

  // ==================== 群组相关 API ====================

  /**
   * 获取用户的群组列表
   */
  async getGroups(userId: string): Promise<ApiResponse<Group[]>> {
    return this.get<Group[]>(`/api/groups?userId=${userId}`);
  }

  /**
   * 创建新群组
   */
  async createGroup(request: CreateGroupRequest): Promise<ApiResponse<Group>> {
    return this.post<Group>('/api/groups/create', request);
  }

  /**
   * 获取群组成员
   */
  async getGroupMembers(groupId: string): Promise<ApiResponse<GroupMember[]>> {
    return this.get<GroupMember[]>(`/api/groups/${groupId}/members`);
  }

  /**
   * 加入群组
   */
  async joinGroup(groupId: string, userId: string): Promise<ApiResponse<void>> {
    return this.post<void>('/api/groups/join', { groupId, userId });
  }

  /**
   * 离开群组
   */
  async leaveGroup(groupId: string, userId: string): Promise<ApiResponse<void>> {
    return this.post<void>('/api/groups/leave', { groupId, userId });
  }

  // ==================== 用户设置 API ====================

  /**
   * 获取用户设置
   */
  async getUserSettings(userId: string): Promise<ApiResponse<UserSettingsResponse>> {
    return this.get<UserSettingsResponse>(`/api/settings/${userId}`);
  }

  /**
   * 更新用户设置
   */
  async updateUserSettings(userId: string, settings: UserSettings): Promise<ApiResponse<UserSettingsResponse>> {
    return this.post<UserSettingsResponse>(`/api/settings/${userId}`, {
      userId,
      language: settings.language,
      defaultAgentInstruction: settings.defaultAgentInstruction
    });
  }

  // ==================== 搜索相关 API ====================

  /**
   * 文件搜索
   */
  async searchFiles(request: SearchRequest): Promise<ApiResponse<SearchResult[]>> {
    return this.post<SearchResult[]>('/api/search', request);
  }

  // ==================== 版本相关 API ====================

  /**
   * 获取最新 APK 版本信息
   */
  async getLatestVersion(): Promise<ApiResponse<{ versionName: string; versionCode: number; downloadUrl: string }>> {
    return this.get<{ versionName: string; versionCode: number; downloadUrl: string }>('/api/version/latest');
  }
}

// 导出单例
export const apiClient = new ApiClient();
