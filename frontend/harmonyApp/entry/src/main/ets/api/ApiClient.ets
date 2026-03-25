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

interface LoginPayload {
  loginName: string;
  password: string;
}

interface LoginData {
  success: boolean;
  message: string;
  user?: User;
  token?: string;
}

interface BackendUser {
  id: string;
  loginName?: string;
  fullName?: string;
  phoneNumber?: string;
  createdAt?: string;
}

interface BackendGroup {
  id: string;
  name: string;
  hostId?: string;
  createdAt?: string;
  invitationCode?: string;
  hostName?: string;
  lastMessage?: string;
  lastMessageTime?: number;
}

interface BackendCreateGroupRequest {
  userId: string;
  groupName: string;
}

interface BackendJoinGroupRequest {
  userId: string;
  invitationCode: string;
}

interface BackendLeaveGroupRequest {
  userId: string;
}

interface SendMessageRequestBody {
  groupId: string;
  userId: string;
  userName: string;
  content: string;
}

interface UpdateSettingsRequestBody {
  userId: string;
  language: string;
  defaultAgentInstruction: string;
}

interface LatestVersionInfo {
  versionName: string;
  versionCode: number;
  downloadUrl: string;
}

interface UnreadCountsResponse {
  success: boolean;
  unreadCounts?: Record<string, number>;
}

interface ResponseEnvelope {
  success: boolean;
  message: string;
  data?: Object;
  user?: BackendUser;
  token?: string;
  group?: BackendGroup;
  groups?: BackendGroup[];
  settings?: UserSettings;
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
  async get<T>(endpoint: string): Promise<ApiResponse<T>> {
    const httpRequest = this.createHttpRequest();
    try {
      const response = await httpRequest.request(
        `${this.baseUrl}${endpoint}`,
        {
          method: http.RequestMethod.GET,
          header: {
            'Content-Type': 'application/json'
          },
          connectTimeout: this.timeout,
          readTimeout: this.timeout
        }
      );

      if (response.responseCode === 200) {
        const resultText: string = response.result as string;
        return this.parseResponse<T>(resultText);
      } else {
        return this.failedResponse<T>(`HTTP Error: ${response.responseCode}`);
      }
    } catch (error) {
      return this.failedResponse<T>(`Request failed: ${this.stringifyError(error)}`);
    } finally {
      httpRequest.destroy();
    }
  }

  /**
   * 发送 POST 请求
   */
  async post<T>(endpoint: string, body: Object): Promise<ApiResponse<T>> {
    const httpRequest = this.createHttpRequest();
    try {
      const response = await httpRequest.request(
        `${this.baseUrl}${endpoint}`,
        {
          method: http.RequestMethod.POST,
          header: {
            'Content-Type': 'application/json'
          },
          extraData: body,
          connectTimeout: this.timeout,
          readTimeout: this.timeout
        }
      );

      if (response.responseCode === 200 || response.responseCode === 201) {
        const resultText: string = response.result as string;
        return this.parseResponse<T>(resultText);
      } else {
        return this.failedResponse<T>(`HTTP Error: ${response.responseCode}`);
      }
    } catch (error) {
      return this.failedResponse<T>(`Request failed: ${this.stringifyError(error)}`);
    } finally {
      httpRequest.destroy();
    }
  }

  async put<T>(endpoint: string, body: Object): Promise<ApiResponse<T>> {
    const httpRequest = this.createHttpRequest();
    try {
      const response = await httpRequest.request(
        `${this.baseUrl}${endpoint}`,
        {
          method: http.RequestMethod.PUT,
          header: {
            'Content-Type': 'application/json'
          },
          extraData: body,
          connectTimeout: this.timeout,
          readTimeout: this.timeout
        }
      );

      if (response.responseCode === 200 || response.responseCode === 201) {
        const resultText: string = response.result as string;
        return this.parseResponse<T>(resultText);
      } else {
        return this.failedResponse<T>(`HTTP Error: ${response.responseCode}`);
      }
    } catch (error) {
      return this.failedResponse<T>(`Request failed: ${this.stringifyError(error)}`);
    } finally {
      httpRequest.destroy();
    }
  }

  private failedResponse<T>(message: string): ApiResponse<T> {
    return {
      success: false,
      message: message
    };
  }

  private parseResponse<T>(resultText: string): ApiResponse<T> {
    const parsedObj: ResponseEnvelope = JSON.parse(resultText) as ResponseEnvelope;

    if (parsedObj.data !== undefined) {
      return parsedObj as ApiResponse<T>;
    }

    if (parsedObj.user !== undefined || parsedObj.token !== undefined) {
      const normalizedUser: User | undefined = parsedObj.user ? this.normalizeUser(parsedObj.user) : undefined;
      const loginData: LoginData = {
        success: parsedObj.success,
        message: parsedObj.message,
        user: normalizedUser,
        token: parsedObj.token
      };
      const normalizedLogin: ApiResponse<T> = {
        success: parsedObj.success,
        message: parsedObj.message,
        data: loginData as T
      };
      return normalizedLogin;
    }

    if (parsedObj.groups !== undefined) {
      const normalizedGroupsData: Group[] = parsedObj.groups.map((group: BackendGroup) => this.normalizeGroup(group));
      const normalizedGroups: ApiResponse<T> = {
        success: parsedObj.success,
        message: parsedObj.message,
        data: normalizedGroupsData as T
      };
      return normalizedGroups;
    }

    if (parsedObj.group !== undefined) {
      const normalizedGroupData: Group = this.normalizeGroup(parsedObj.group);
      const normalizedGroup: ApiResponse<T> = {
        success: parsedObj.success,
        message: parsedObj.message,
        data: normalizedGroupData as T
      };
      return normalizedGroup;
    }

    if (parsedObj.settings !== undefined) {
      const settingsResponse: UserSettingsResponse = {
        success: parsedObj.success,
        message: parsedObj.message,
        settings: parsedObj.settings
      };
      const normalizedSettings: ApiResponse<T> = {
        success: parsedObj.success,
        message: parsedObj.message,
        data: settingsResponse as T
      };
      return normalizedSettings;
    }

    return parsedObj as ApiResponse<T>;
  }

  private normalizeUser(user: BackendUser): User {
    const displayName: string = user.fullName ? user.fullName : (user.loginName ? user.loginName : '用户');
    const phone: string | undefined = user.phoneNumber ? user.phoneNumber : undefined;
    return {
      id: user.id,
      name: displayName,
      phone: phone
    };
  }

  private normalizeGroup(group: BackendGroup): Group {
    const now: number = Date.now();
    return {
      id: group.id,
      name: group.name,
      ownerId: group.hostId ? group.hostId : '',
      memberCount: 0,
      createdAt: now,
      updatedAt: now,
      lastMessage: group.lastMessage,
      lastMessageTime: group.lastMessageTime
    };
  }

  private stringifyError(error: Object): string {
    const maybeError = error as Error;
    if (maybeError && maybeError.message) {
      return maybeError.message;
    }
    return 'unknown error';
  }

  // ==================== 认证相关 API ====================

  /**
   * 用户登录
   */
  async login(loginName: string, password: string): Promise<ApiResponse<LoginResponse>> {
    const payload: LoginPayload = { loginName: loginName, password: password };
    return this.post<LoginResponse>('/auth/login', payload);
  }

  /**
   * 用户注册
   */
  async register(request: RegisterRequest): Promise<ApiResponse<RegisterResponse>> {
    return this.post<RegisterResponse>('/auth/register', request);
  }

  /**
   * 验证会话
   */
  async validateSession(userId: string): Promise<ApiResponse<User>> {
    return this.get<User>(`/auth/validate/${userId}`);
  }

  // ==================== 群组相关 API ====================

  /**
   * 获取用户的群组列表
   */
  async getGroups(userId: string): Promise<ApiResponse<Group[]>> {
    return this.get<Group[]>(`/groups/user/${userId}`);
  }

  /**
   * 获取用户所有群组未读数
   */
  async getUnreadCounts(userId: string): Promise<Record<string, number>> {
    const response = await this.get<Object>(`/api/unread/${userId}`);
    const unreadResponse = response as UnreadCountsResponse;
    if (unreadResponse.success && unreadResponse.unreadCounts) {
      return unreadResponse.unreadCounts;
    }
    return {};
  }

  /**
   * 创建新群组
   */
  async createGroup(request: CreateGroupRequest): Promise<ApiResponse<Group>> {
    const payload: BackendCreateGroupRequest = {
      userId: request.createdBy,
      groupName: request.name
    };
    return this.post<Group>('/groups/create', payload);
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
    const payload: BackendJoinGroupRequest = { userId: userId, invitationCode: groupId };
    return this.post<void>('/groups/join', payload);
  }

  /**
   * 离开群组
   */
  async leaveGroup(groupId: string, userId: string): Promise<ApiResponse<void>> {
    const payload: BackendLeaveGroupRequest = { userId: userId };
    return this.post<void>(`/groups/${groupId}/leave`, payload);
  }

  /**
   * 发送消息到指定群组（用于转发）
   */
  async sendMessageToGroup(groupId: string, userId: string, userName: string, content: string): Promise<ApiResponse<void>> {
    const payload: SendMessageRequestBody = {
      groupId: groupId,
      userId: userId,
      userName: userName,
      content: content
    };
    return this.post<void>('/api/messages/send', payload);
  }

  // ==================== 用户设置 API ====================

  /**
   * 获取用户设置
   */
  async getUserSettings(userId: string): Promise<ApiResponse<UserSettingsResponse>> {
    return this.get<UserSettingsResponse>(`/users/${userId}/settings`);
  }

  /**
   * 更新用户设置
   */
  async updateUserSettings(userId: string, settings: UserSettings): Promise<ApiResponse<UserSettingsResponse>> {
    const payload: UpdateSettingsRequestBody = {
      userId: userId,
      language: settings.language,
      defaultAgentInstruction: settings.defaultAgentInstruction
    };
    return this.put<UserSettingsResponse>(`/users/${userId}/settings`, payload);
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
  async getLatestVersion(): Promise<ApiResponse<LatestVersionInfo>> {
    return this.get<LatestVersionInfo>('/api/version/latest');
  }

  static getInstance(): ApiClient {
    return apiClient;
  }
}

// 导出单例
export const apiClient = new ApiClient();
