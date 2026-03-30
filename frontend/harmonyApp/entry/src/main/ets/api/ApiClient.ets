/**
 * Silk API Client - HTTP 网络请求封装
 * 使用 @ohos.net.http 实现 RESTful API 调用
 */
import http from '@ohos.net.http';
import { User, LoginResponse, RegisterResponse } from '../models/User';
import { Group, GroupMember } from '../models/Group';
import { UserSettings, UserSettingsResponse } from '../models/UserSettings';
import { UserTodoItem } from '../models/UserTodoItem';
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

export interface GroupMemberProfile {
  id: string;
  fullName: string;
  phone?: string;
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

interface MarkReadRequest {
  userId: string;
  groupId: string;
}

interface StartSilkPrivateChatRequest {
  userId: string;
}

interface SendMessageRequestBody {
  groupId: string;
  userId: string;
  userName: string;
  content: string;
}

interface RecallMessageRequestBody {
  groupId: string;
  messageId: string;
  userId: string;
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

interface UserTodosEnvelope {
  success: boolean;
  message: string;
  items?: UserTodoItem[];
}

export interface UserTodoRefreshStatus {
  running: boolean;
  lastStartedAt?: number;
  lastFinishedAt?: number;
  lastError?: string;
}

export interface UserTodoExtractionDiagnostics {
  userId: string;
  updatedAt: number;
  source: string;
  totalGroups: number;
  transcriptChars: number;
  llmDraftCount: number;
  heuristicDraftCount: number;
  forcedRecurringCount: number;
  finalDraftCount: number;
  matchedRecurringLines: string[];
  note: string;
}

interface UserTodoRefreshStatusEnvelope {
  success: boolean;
  message: string;
  running?: boolean;
  lastStartedAt?: number;
  lastFinishedAt?: number;
  lastError?: string;
}

interface UserTodoExtractionDiagnosticsEnvelope {
  success: boolean;
  message: string;
  userId?: string;
  updatedAt?: number;
  source?: string;
  totalGroups?: number;
  transcriptChars?: number;
  llmDraftCount?: number;
  heuristicDraftCount?: number;
  forcedRecurringCount?: number;
  finalDraftCount?: number;
  matchedRecurringLines?: string[];
  note?: string;
}

interface CalendarWorkdayEnvelope {
  success: boolean;
  message: string;
  date?: string;
  isWorkday?: boolean;
}

/** 待办 PATCH（传给 updateUserTodoItem，仅设置需要修改的字段） */
export interface UserTodoPatch {
  done?: boolean;
  title?: string;
  actionType?: string;
  actionDetail?: string;
  executedAt?: number;
  reminderId?: number;
  clearReminderId?: boolean;
  taskKind?: string;
  repeatRule?: string;
  repeatAnchor?: string;
  activeFrom?: number;
  activeTo?: number;
  templateId?: string;
  lifecycleState?: string;
  closedAt?: number;
  lastEvidenceAt?: number;
  explicitIntent?: boolean;
  dateBucket?: string;
  reopenCount?: number;
}

interface UpdateUserTodoItemBody {
  userId: string;
  itemId: string;
  done?: boolean;
  title?: string;
  actionType?: string;
  actionDetail?: string;
  executedAt?: number;
  reminderId?: number;
  clearReminderId?: boolean;
  taskKind?: string;
  repeatRule?: string;
  repeatAnchor?: string;
  activeFrom?: number;
  activeTo?: number;
  templateId?: string;
  lifecycleState?: string;
  closedAt?: number;
  lastEvidenceAt?: number;
  explicitIntent?: boolean;
  dateBucket?: string;
  reopenCount?: number;
}

interface DeleteUserTodoItemBody {
  userId: string;
  itemId: string;
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
   * 对 loopback 地址做回退，兼容模拟器/真机网络差异：
   * - 10.0.2.2 <-> 127.0.0.1 <-> localhost
   */
  private getFallbackBaseUrls(): string[] {
    const out: string[] = [this.baseUrl];
    if (this.baseUrl.indexOf('10.0.2.2') >= 0) {
      out.push(this.baseUrl.replace('10.0.2.2', '127.0.0.1'));
      out.push(this.baseUrl.replace('10.0.2.2', 'localhost'));
    } else if (this.baseUrl.indexOf('127.0.0.1') >= 0) {
      out.push(this.baseUrl.replace('127.0.0.1', '10.0.2.2'));
      out.push(this.baseUrl.replace('127.0.0.1', 'localhost'));
    } else if (this.baseUrl.indexOf('localhost') >= 0) {
      out.push(this.baseUrl.replace('localhost', '10.0.2.2'));
      out.push(this.baseUrl.replace('localhost', '127.0.0.1'));
    }
    const uniq: string[] = [];
    for (let i = 0; i < out.length; i++) {
      const item = out[i];
      if (uniq.indexOf(item) < 0) {
        uniq.push(item);
      }
    }
    return uniq;
  }

  private async requestWithFallback(
    httpRequest: http.HttpRequest,
    endpoint: string,
    options: http.HttpRequestOptions
  ): Promise<http.HttpResponse> {
    const bases: string[] = this.getFallbackBaseUrls();
    let lastErrorMessage: string = 'request failed';
    for (let i = 0; i < bases.length; i++) {
      const base = bases[i];
      const url = `${base}${endpoint}`;
      try {
        const response = await httpRequest.request(url, options);
        if (base !== this.baseUrl) {
          this.baseUrl = base;
        }
        return response;
      } catch (error) {
        lastErrorMessage = this.stringifyError(error as Object);
      }
    }
    throw new Error(lastErrorMessage);
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
    const normalized: Group = {
      id: group.id,
      name: group.name,
      ownerId: group.hostId ? group.hostId : '',
      memberCount: 0,
      createdAt: now,
      updatedAt: now,
      lastMessage: group.lastMessage,
      lastMessageTime: group.lastMessageTime
    };
    if (group.invitationCode !== undefined) {
      normalized.invitationCode = group.invitationCode;
    }
    return normalized;
  }

  private stringifyError(error: Object): string {
    const maybeError = error as Error;
    if (maybeError && maybeError.message) {
      return maybeError.message;
    }
    return 'unknown error';
  }

  /**
   * 统一补齐待办字段，避免反序列化丢字段导致长期/短期分区错误。
   */
  private normalizeUserTodoItem(src: UserTodoItem): UserTodoItem {
    const out: UserTodoItem = {
      id: src.id,
      title: src.title,
      createdAt: src.createdAt,
      updatedAt: src.updatedAt,
      done: src.done === true
    };
    const sourceGroupId: string = src.sourceGroupId ? src.sourceGroupId : '';
    if (sourceGroupId.length > 0) {
      out.sourceGroupId = src.sourceGroupId;
    }
    const sourceGroupName: string = src.sourceGroupName ? src.sourceGroupName : '';
    if (sourceGroupName.length > 0) {
      out.sourceGroupName = src.sourceGroupName;
    }
    const actionType: string = src.actionType ? src.actionType : '';
    if (actionType.length > 0) {
      out.actionType = src.actionType;
    }
    const actionDetail: string = src.actionDetail ? src.actionDetail : '';
    if (actionDetail.length > 0) {
      out.actionDetail = src.actionDetail;
    }
    if (src.executedAt !== undefined) {
      out.executedAt = src.executedAt;
    }
    if (src.reminderId !== undefined) {
      out.reminderId = src.reminderId;
    }
    let taskKind: string = src.taskKind ? src.taskKind : '';
    const repeatRule: string = src.repeatRule ? src.repeatRule : '';
    if (taskKind.length === 0) {
      if (repeatRule.length > 0) {
        taskKind = 'long_term_template';
      } else {
        taskKind = 'short_term_instance';
      }
    }
    out.taskKind = taskKind;
    if (repeatRule.length > 0) {
      out.repeatRule = src.repeatRule;
    }
    const repeatAnchor: string = src.repeatAnchor ? src.repeatAnchor : '';
    if (repeatAnchor.length > 0) {
      out.repeatAnchor = src.repeatAnchor;
    }
    if (src.activeFrom !== undefined) {
      out.activeFrom = src.activeFrom;
    }
    if (src.activeTo !== undefined) {
      out.activeTo = src.activeTo;
    }
    const templateId: string = src.templateId ? src.templateId : '';
    if (templateId.length > 0) {
      out.templateId = src.templateId;
    }
    const lifecycleState: string = src.lifecycleState ? src.lifecycleState : '';
    if (lifecycleState.length > 0) {
      out.lifecycleState = src.lifecycleState;
    }
    if (src.closedAt !== undefined) {
      out.closedAt = src.closedAt;
    }
    if (src.lastEvidenceAt !== undefined) {
      out.lastEvidenceAt = src.lastEvidenceAt;
    }
    if (src.explicitIntent === true) {
      out.explicitIntent = true;
    }
    const dateBucket: string = src.dateBucket ? src.dateBucket : '';
    if (dateBucket.length > 0) {
      out.dateBucket = src.dateBucket;
    }
    if (src.reopenCount !== undefined && src.reopenCount > 0) {
      out.reopenCount = src.reopenCount;
    }
    return out;
  }

  private normalizeUserTodos(items: UserTodoItem[]): UserTodoItem[] {
    const out: UserTodoItem[] = [];
    for (let i = 0; i < items.length; i++) {
      out.push(this.normalizeUserTodoItem(items[i]));
    }
    return out;
  }

  /**
   * 部分设备上 http 的 result 可能是对象而非 string；统一解析为待办信封。
   */
  private parseUserTodosEnvelopeFromResult(result: Object): UserTodosEnvelope | null {
    try {
      if (typeof result === 'string') {
        return JSON.parse(result as string) as UserTodosEnvelope;
      }
      return JSON.parse(JSON.stringify(result)) as UserTodosEnvelope;
    } catch (_) {
      return null;
    }
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
  async getGroupMembers(groupId: string): Promise<ApiResponse<GroupMemberProfile[]>> {
    const primary = await this.get<GroupMemberProfile[]>(`/groups/${groupId}/members`);
    if (primary.success) {
      return primary;
    }
    // 兼容历史后端路由
    return this.get<GroupMemberProfile[]>(`/api/groups/${groupId}/members`);
  }

  /**
   * 加入群组
   */
  async joinGroup(groupId: string, userId: string): Promise<ApiResponse<void>> {
    const payload: BackendJoinGroupRequest = { userId: userId, invitationCode: groupId };
    return this.post<void>('/groups/join', payload);
  }

  /**
   * 标记某个群组消息为已读
   */
  async markGroupAsRead(userId: string, groupId: string): Promise<boolean> {
    const payload: MarkReadRequest = {
      userId: userId,
      groupId: groupId
    };
    const response = await this.post<Object>('/api/unread/mark-read', payload);
    return response.success;
  }

  /**
   * 打开/创建与 Silk 的专属私聊会话
   */
  async startSilkPrivateChat(userId: string): Promise<ApiResponse<Group>> {
    const payload: StartSilkPrivateChatRequest = { userId: userId };
    return this.post<Group>('/api/silk-private-chat', payload);
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

  /**
   * 撤回消息（与 Android /api/messages/recall 对齐）
   */
  async recallMessage(groupId: string, messageId: string, userId: string): Promise<ApiResponse<void>> {
    const payload: RecallMessageRequestBody = {
      groupId: groupId,
      messageId: messageId,
      userId: userId
    };
    return this.post<void>('/api/messages/recall', payload);
  }

  /**
   * 获取当前用户的跨群待办列表
   */
  async getUserTodos(userId: string): Promise<ApiResponse<UserTodoItem[]>> {
    const httpRequest = this.createHttpRequest();
    try {
      const encodedId: string = encodeURIComponent(userId);
      const response = await this.requestWithFallback(
        httpRequest,
        `/api/user-todos/${encodedId}`,
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
        const parsed: UserTodosEnvelope | null = this.parseUserTodosEnvelopeFromResult(response.result as Object);
        if (parsed !== null && parsed.items !== undefined && parsed.items !== null) {
          const list: UserTodoItem[] = parsed.items;
          return {
            success: parsed.success,
            message: parsed.message,
            data: this.normalizeUserTodos(list)
          };
        }
      }
      return this.failedResponse<UserTodoItem[]>('加载待办失败');
    } catch (error) {
      return this.failedResponse<UserTodoItem[]>(`Request failed: ${this.stringifyError(error)}`);
    } finally {
      httpRequest.destroy();
    }
  }

  /**
   * 查询指定日期是否为中国法定工作日（含调休）。
   * date 格式：yyyy-MM-dd
   */
  async isCalendarWorkday(date: string): Promise<ApiResponse<boolean>> {
    const day = date.trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) {
      return this.failedResponse<boolean>('日期格式错误，应为 yyyy-MM-dd');
    }
    const httpRequest = this.createHttpRequest();
    try {
      const encoded = encodeURIComponent(day);
      const response = await this.requestWithFallback(
        httpRequest,
        `/api/calendar/workday/${encoded}`,
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
        const raw = response.result as Object;
        const parsed: CalendarWorkdayEnvelope = (typeof raw === 'string'
          ? JSON.parse(raw as string)
          : JSON.parse(JSON.stringify(raw))) as CalendarWorkdayEnvelope;
        if (parsed.success && parsed.isWorkday !== undefined) {
          return {
            success: true,
            message: parsed.message ? parsed.message : 'ok',
            data: parsed.isWorkday === true
          };
        }
      }
      return this.failedResponse<boolean>('查询工作日失败');
    } catch (error) {
      return this.failedResponse<boolean>(`Request failed: ${this.stringifyError(error as Object)}`);
    } finally {
      httpRequest.destroy();
    }
  }

  /**
   * 根据各群聊天记录刷新待办（可能较慢，使用较长读超时）。
   * 使用 GET + path 参数，避免部分设备上 POST extraData 未按 JSON 发送导致后端 400。
   */
  async refreshUserTodos(userId: string): Promise<ApiResponse<UserTodoItem[]>> {
    const readMs: number = 120000;
    const httpRequest = this.createHttpRequest();
    try {
      const encodedId: string = encodeURIComponent(userId);
      const response = await this.requestWithFallback(
        httpRequest,
        `/api/user-todos/${encodedId}/refresh`,
        {
          method: http.RequestMethod.GET,
          header: {
            'Content-Type': 'application/json'
          },
          connectTimeout: this.timeout,
          readTimeout: readMs
        }
      );
      if (response.responseCode === 200) {
        const parsed: UserTodosEnvelope | null = this.parseUserTodosEnvelopeFromResult(response.result as Object);
        if (parsed !== null && parsed.items !== undefined && parsed.items !== null) {
          const list: UserTodoItem[] = parsed.items;
          return {
            success: parsed.success,
            message: parsed.message,
            data: this.normalizeUserTodos(list)
          };
        }
      }
      return this.failedResponse<UserTodoItem[]>('刷新待办失败');
    } catch (error) {
      return this.failedResponse<UserTodoItem[]>(`Request failed: ${this.stringifyError(error)}`);
    } finally {
      httpRequest.destroy();
    }
  }

  /**
   * 启动待办后台异步刷新（快速返回，不阻塞前台）。
   */
  async startUserTodosRefreshAsync(userId: string): Promise<ApiResponse<UserTodoRefreshStatus>> {
    const httpRequest = this.createHttpRequest();
    try {
      const encodedId: string = encodeURIComponent(userId);
      const response = await this.requestWithFallback(
        httpRequest,
        `/api/user-todos/${encodedId}/refresh-async/start`,
        {
          method: http.RequestMethod.POST,
          header: {
            'Content-Type': 'application/json'
          },
          connectTimeout: this.timeout,
          readTimeout: this.timeout
        }
      );
      if (response.responseCode === 200) {
        const resultText: string = response.result as string;
        const parsed: UserTodoRefreshStatusEnvelope = JSON.parse(resultText) as UserTodoRefreshStatusEnvelope;
        const status: UserTodoRefreshStatus = {
          running: parsed.running === true
        };
        if (parsed.lastStartedAt !== undefined) {
          status.lastStartedAt = parsed.lastStartedAt;
        }
        if (parsed.lastFinishedAt !== undefined) {
          status.lastFinishedAt = parsed.lastFinishedAt;
        }
        if (parsed.lastError !== undefined) {
          status.lastError = parsed.lastError;
        }
        return {
          success: parsed.success,
          message: parsed.message,
          data: status
        };
      }
      return this.failedResponse<UserTodoRefreshStatus>('启动待办刷新失败');
    } catch (error) {
      return this.failedResponse<UserTodoRefreshStatus>(`Request failed: ${this.stringifyError(error)}`);
    } finally {
      httpRequest.destroy();
    }
  }

  /**
   * 查询待办后台异步刷新状态。
   */
  async getUserTodosRefreshAsyncStatus(userId: string): Promise<ApiResponse<UserTodoRefreshStatus>> {
    const httpRequest = this.createHttpRequest();
    try {
      const encodedId: string = encodeURIComponent(userId);
      const response = await this.requestWithFallback(
        httpRequest,
        `/api/user-todos/${encodedId}/refresh-async/status`,
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
        const parsed: UserTodoRefreshStatusEnvelope = JSON.parse(resultText) as UserTodoRefreshStatusEnvelope;
        const status: UserTodoRefreshStatus = {
          running: parsed.running === true
        };
        if (parsed.lastStartedAt !== undefined) {
          status.lastStartedAt = parsed.lastStartedAt;
        }
        if (parsed.lastFinishedAt !== undefined) {
          status.lastFinishedAt = parsed.lastFinishedAt;
        }
        if (parsed.lastError !== undefined) {
          status.lastError = parsed.lastError;
        }
        return {
          success: parsed.success,
          message: parsed.message,
          data: status
        };
      }
      return this.failedResponse<UserTodoRefreshStatus>('查询待办刷新状态失败');
    } catch (error) {
      return this.failedResponse<UserTodoRefreshStatus>(`Request failed: ${this.stringifyError(error)}`);
    } finally {
      httpRequest.destroy();
    }
  }

  /**
   * 查询最近一次待办抽取诊断信息（用于定位漏抽）。
   */
  async getUserTodosDiagnostics(userId: string): Promise<ApiResponse<UserTodoExtractionDiagnostics>> {
    const httpRequest = this.createHttpRequest();
    try {
      const encodedId: string = encodeURIComponent(userId);
      const response = await this.requestWithFallback(
        httpRequest,
        `/api/user-todos/${encodedId}/diagnostics`,
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
        const parsed: UserTodoExtractionDiagnosticsEnvelope = JSON.parse(resultText) as UserTodoExtractionDiagnosticsEnvelope;
        const diagnostics: UserTodoExtractionDiagnostics = {
          userId: parsed.userId ? parsed.userId : userId,
          updatedAt: parsed.updatedAt ? parsed.updatedAt : Date.now(),
          source: parsed.source ? parsed.source : 'none',
          totalGroups: parsed.totalGroups ? parsed.totalGroups : 0,
          transcriptChars: parsed.transcriptChars ? parsed.transcriptChars : 0,
          llmDraftCount: parsed.llmDraftCount ? parsed.llmDraftCount : 0,
          heuristicDraftCount: parsed.heuristicDraftCount ? parsed.heuristicDraftCount : 0,
          forcedRecurringCount: parsed.forcedRecurringCount ? parsed.forcedRecurringCount : 0,
          finalDraftCount: parsed.finalDraftCount ? parsed.finalDraftCount : 0,
          matchedRecurringLines: parsed.matchedRecurringLines ? parsed.matchedRecurringLines : [],
          note: parsed.note ? parsed.note : ''
        };
        return {
          success: parsed.success,
          message: parsed.message,
          data: diagnostics
        };
      }
      return this.failedResponse<UserTodoExtractionDiagnostics>('查询待办抽取诊断失败');
    } catch (error) {
      return this.failedResponse<UserTodoExtractionDiagnostics>(`Request failed: ${this.stringifyError(error)}`);
    } finally {
      httpRequest.destroy();
    }
  }

  /**
   * 更新待办项（支持部分字段）
   */
  async updateUserTodoItem(
    userId: string,
    itemId: string,
    options?: UserTodoPatch
  ): Promise<ApiResponse<UserTodoItem[]>> {
    const httpRequest = this.createHttpRequest();
    try {
      const updateBody: UpdateUserTodoItemBody = { userId: userId, itemId: itemId };
      if (options !== undefined) {
        if (options.done !== undefined) {
          updateBody.done = options.done;
        }
        if (options.title !== undefined) {
          updateBody.title = options.title;
        }
        if (options.actionType !== undefined) {
          updateBody.actionType = options.actionType;
        }
        if (options.actionDetail !== undefined) {
          updateBody.actionDetail = options.actionDetail;
        }
        if (options.executedAt !== undefined) {
          updateBody.executedAt = options.executedAt;
        }
        if (options.reminderId !== undefined) {
          updateBody.reminderId = options.reminderId;
        }
        if (options.clearReminderId !== undefined) {
          updateBody.clearReminderId = options.clearReminderId;
        }
        if (options.taskKind !== undefined) {
          updateBody.taskKind = options.taskKind;
        }
        if (options.repeatRule !== undefined) {
          updateBody.repeatRule = options.repeatRule;
        }
        if (options.repeatAnchor !== undefined) {
          updateBody.repeatAnchor = options.repeatAnchor;
        }
        if (options.activeFrom !== undefined) {
          updateBody.activeFrom = options.activeFrom;
        }
        if (options.activeTo !== undefined) {
          updateBody.activeTo = options.activeTo;
        }
        if (options.templateId !== undefined) {
          updateBody.templateId = options.templateId;
        }
        if (options.lifecycleState !== undefined) {
          updateBody.lifecycleState = options.lifecycleState;
        }
        if (options.closedAt !== undefined) {
          updateBody.closedAt = options.closedAt;
        }
        if (options.lastEvidenceAt !== undefined) {
          updateBody.lastEvidenceAt = options.lastEvidenceAt;
        }
        if (options.explicitIntent !== undefined) {
          updateBody.explicitIntent = options.explicitIntent;
        }
        if (options.dateBucket !== undefined) {
          updateBody.dateBucket = options.dateBucket;
        }
        if (options.reopenCount !== undefined) {
          updateBody.reopenCount = options.reopenCount;
        }
      }
      const response = await this.requestWithFallback(
        httpRequest,
        '/api/user-todos/item',
        {
          method: http.RequestMethod.PUT,
          header: {
            'Content-Type': 'application/json'
          },
          extraData: JSON.stringify(updateBody),
          connectTimeout: this.timeout,
          readTimeout: this.timeout
        }
      );
      if (response.responseCode === 200) {
        const parsed: UserTodosEnvelope | null = this.parseUserTodosEnvelopeFromResult(response.result as Object);
        if (parsed !== null && parsed.items !== undefined && parsed.items !== null) {
          const list: UserTodoItem[] = parsed.items;
          return {
            success: parsed.success,
            message: parsed.message,
            data: this.normalizeUserTodos(list)
          };
        }
      }
      return this.failedResponse<UserTodoItem[]>('更新待办失败');
    } catch (error) {
      return this.failedResponse<UserTodoItem[]>(`Request failed: ${this.stringifyError(error)}`);
    } finally {
      httpRequest.destroy();
    }
  }

  /**
   * 删除待办项
   */
  async deleteUserTodoItem(userId: string, itemId: string): Promise<ApiResponse<UserTodoItem[]>> {
    const httpRequest = this.createHttpRequest();
    try {
      const deleteBody: DeleteUserTodoItemBody = { userId: userId, itemId: itemId };
      const response = await this.requestWithFallback(
        httpRequest,
        '/api/user-todos/item',
        {
          method: http.RequestMethod.DELETE,
          header: {
            'Content-Type': 'application/json'
          },
          extraData: JSON.stringify(deleteBody),
          connectTimeout: this.timeout,
          readTimeout: this.timeout
        }
      );
      if (response.responseCode === 200) {
        const parsed: UserTodosEnvelope | null = this.parseUserTodosEnvelopeFromResult(response.result as Object);
        if (parsed !== null && parsed.items !== undefined && parsed.items !== null) {
          const list: UserTodoItem[] = parsed.items;
          return {
            success: parsed.success,
            message: parsed.message,
            data: this.normalizeUserTodos(list)
          };
        }
      }
      return this.failedResponse<UserTodoItem[]>('删除待办失败');
    } catch (error) {
      return this.failedResponse<UserTodoItem[]>(`Request failed: ${this.stringifyError(error)}`);
    } finally {
      httpRequest.destroy();
    }
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
