/**
 * Silk 鸿蒙应用 - 全局状态管理
 * 
 * 使用 ArkUI 的 @Observed 装饰器实现响应式状态
 */
import { User } from '../models/User';
import { Group } from '../models/Group';
import { UserSettings, createDefaultUserSettings } from '../models/UserSettings';
import { CreateGroupRequest } from '../api/ApiClient';
import { apiClient as globalApiClient } from '../api/ApiClient';
import { PreferencesUtil } from '../common/PreferencesUtil';

/**
 * 连接状态枚举
 */
export enum ConnectionState {
  DISCONNECTED = 'disconnected',
  CONNECTING = 'connecting',
  CONNECTED = 'connected'
}

/**
 * 应用全局状态
 * 使用 @Observed 装饰器使状态变化可被 UI 观察
 */
@Observed
export class AppStore {
  // 用户状态
  private _currentUser: User | null = null;
  private _isLoggedIn: boolean = false;
  private _token: string = '';
  
  // 群组状态
  private _groups: Group[] = [];
  private _currentGroup: Group | null = null;
  
  // 设置状态
  private _settings: UserSettings = createDefaultUserSettings();
  
  // 连接状态
  private _connectionState: ConnectionState = ConnectionState.DISCONNECTED;
  
  // UI 状态
  private _isLoading: boolean = false;
  private _errorMessage: string = '';
  
  // API 客户端
  private readonly apiClient = globalApiClient;
  
  // ==================== 用户相关 ====================
  
  get currentUser(): User | null {
    return this._currentUser;
  }
  
  get isLoggedIn(): boolean {
    return this._isLoggedIn;
  }
  
  get token(): string {
    return this._token;
  }
  
  /**
   * 用户登录
   */
  async login(username: string, password: string): Promise<boolean> {
    this._isLoading = true;
    this._errorMessage = '';
    
    try {
      const response = await this.apiClient.login(username, password);
      
      const loginData = response.data;
      if (response.success && loginData && loginData.user) {
        this._currentUser = loginData.user;
        this._token = loginData.token ? loginData.token : '';
        this._isLoggedIn = true;
        
        // 保存登录状态到本地存储
        await PreferencesUtil.putString('userId', loginData.user.id);
        await PreferencesUtil.putString('userName', loginData.user.name);
        await PreferencesUtil.putString('token', this._token);
        
        // 加载用户设置
        await this.loadSettings();
        
        return true;
      } else {
        this._errorMessage = response.message || '登录失败';
        return false;
      }
    } catch (error) {
      this._errorMessage = `登录错误: ${this.errorToString(error)}`;
      return false;
    } finally {
      this._isLoading = false;
    }
  }
  
  /**
   * 用户注册
   */
  async register(username: string, password: string, fullName: string, phoneNumber: string): Promise<boolean> {
    this._isLoading = true;
    this._errorMessage = '';
    
    try {
      const response = await this.apiClient.register({
        loginName: username,
        password: password,
        fullName: fullName,
        phoneNumber: phoneNumber
      });
      
      if (response.success) {
        // 注册成功后自动登录
        return await this.login(username, password);
      } else {
        this._errorMessage = response.message || '注册失败';
        return false;
      }
    } catch (error) {
      this._errorMessage = `注册错误: ${this.errorToString(error)}`;
      return false;
    } finally {
      this._isLoading = false;
    }
  }
  
  /**
   * 用户登出
   */
  async logout(): Promise<void> {
    this._currentUser = null;
    this._token = '';
    this._isLoggedIn = false;
    this._groups = [];
    this._currentGroup = null;
    
    // 清除本地存储
    await PreferencesUtil.remove('userId');
    await PreferencesUtil.remove('userName');
    await PreferencesUtil.remove('token');
  }
  
  /**
   * 尝试恢复会话
   */
  async restoreSession(): Promise<boolean> {
    const userId = await PreferencesUtil.getString('userId');
    const userName = await PreferencesUtil.getString('userName');
    const token = await PreferencesUtil.getString('token');
    
    if (userId && userName && token) {
      this._currentUser = {
        id: userId,
        name: userName
      };
      this._token = token;
      this._isLoggedIn = true;
      await this.loadSettings();
      return true;
    }
    
    return false;
  }
  
  // ==================== 群组相关 ====================
  
  get groups(): Group[] {
    return this._groups;
  }
  
  get currentGroup(): Group | null {
    return this._currentGroup;
  }

  set currentGroup(group: Group | null) {
    this._currentGroup = group;
  }
  
  /**
   * 加载用户的群组列表
   */
  async loadGroups(): Promise<void> {
    if (!this._currentUser) return;
    
    this._isLoading = true;
    try {
      const response = await this.apiClient.getGroups(this._currentUser.id);
      if (response.success && response.data) {
        this._groups = response.data;
      }
    } catch (error) {
      console.error(`加载群组失败: ${this.errorToString(error)}`);
    } finally {
      this._isLoading = false;
    }
  }
  
  /**
   * 选择当前群组
   */
  setCurrentGroup(group: Group): void {
    this._currentGroup = group;
  }
  
  /**
   * 创建新群组
   */
  async createGroup(name: string, description: string): Promise<Group | null> {
    if (!this._currentUser) return null;
    
    this._isLoading = true;
    try {
      const request: CreateGroupRequest = {
        name: name,
        description: description,
        createdBy: this._currentUser.id
      };
      const response = await this.apiClient.createGroup(request);
      const group = response.data ? response.data : null;
      if (response.success && group) {
        const nextGroups: Group[] = this._groups.concat([group]);
        this._groups = nextGroups;
      }
      return group;
    } catch (error) {
      console.error(`创建群组失败: ${this.errorToString(error)}`);
      return null;
    } finally {
      this._isLoading = false;
    }
  }
  
  // ==================== 设置相关 ====================
  
  get settings(): UserSettings {
    return this._settings;
  }
  
  /**
   * 加载用户设置
   */
  async loadSettings(): Promise<void> {
    if (!this._currentUser) return;
    
    try {
      const response = await this.apiClient.getUserSettings(this._currentUser.id);
      if (response.success && response.data && response.data.settings) {
        this._settings = response.data.settings;
      }
    } catch (error) {
      console.error(`加载设置失败: ${this.errorToString(error)}`);
    }
  }
  
  /**
   * 更新用户设置
   */
  async updateSettings(settings: UserSettings): Promise<boolean> {
    if (!this._currentUser) return false;
    
    try {
      const response = await this.apiClient.updateUserSettings(this._currentUser.id, settings);
      if (response.success) {
        this._settings = settings;
        return true;
      }
      return false;
    } catch (error) {
      console.error(`更新设置失败: ${this.errorToString(error)}`);
      return false;
    }
  }
  
  // ==================== 连接状态 ====================
  
  get connectionState(): ConnectionState {
    return this._connectionState;
  }
  
  setConnectionState(state: ConnectionState): void {
    this._connectionState = state;
  }
  
  // ==================== UI 状态 ====================
  
  get isLoading(): boolean {
    return this._isLoading;
  }
  
  get errorMessage(): string {
    return this._errorMessage;
  }
  
  clearError(): void {
    this._errorMessage = '';
  }
  
  setLoading(loading: boolean): void {
    this._isLoading = loading;
  }

  // ==================== 兼容页面层调用 ====================

  getCurrentUser(): User | null {
    return this._currentUser;
  }

  getUserSettings(): UserSettings {
    return this._settings;
  }

  async updateUserSettings(settings: UserSettings): Promise<boolean> {
    return this.updateSettings(settings);
  }

  async clearSession(): Promise<void> {
    await this.logout();
  }

  setCurrentUser(user: User): void {
    this._currentUser = user;
    this._isLoggedIn = true;
  }

  private errorToString(error: Object): string {
    const typedError = error as Error;
    if (typedError && typedError.message) {
      return typedError.message;
    }
    return 'unknown error';
  }

  static getInstance(): AppStore {
    return appStore;
  }
}

// 全局单例
export const appStore = new AppStore();
