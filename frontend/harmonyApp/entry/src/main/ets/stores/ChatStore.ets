/**
 * ChatStore - 聊天状态管理
 * 管理消息列表、连接状态、当前群组等
 */
import { Message, MessageType, MessageCategory } from '../models/Message';
import { Group } from '../models/Group';
import { WebSocketClient, ConnectionState } from '../api/WebSocketClient';
import { Constants } from '../common/Constants';

export class ChatStore {
  // 当前群组
  private _currentGroup: Group | null = null;
  get currentGroup(): Group | null {
    return this._currentGroup;
  }
  set currentGroup(group: Group | null) {
    this._currentGroup = group;
  }

  // 消息列表
  private _messages: Message[] = [];
  get messages(): Message[] {
    return this._messages;
  }

  // 临时消息（AI 正在输入）
  private _transientMessage: Message | null = null;
  get transientMessage(): Message | null {
    return this._transientMessage;
  }

  // 状态消息（Agent 工作状态）
  private _statusMessages: Message[] = [];
  get statusMessages(): Message[] {
    return this._statusMessages;
  }

  // 连接状态
  private _connectionState: ConnectionState = ConnectionState.DISCONNECTED;
  get connectionState(): ConnectionState {
    return this._connectionState;
  }

  // WebSocket 客户端
  private wsClient: WebSocketClient | null = null;

  // 当前用户信息
  private currentUserId: string = '';
  private currentUserName: string = '';

  // 回调函数
  public onMessagesChanged: (() => void) | null = null;
  public onConnectionStateChanged: (() => void) | null = null;
  public onError: ((error: string) => void) | null = null;

  /**
   * 连接到聊天服务器
   */
  connect(userId: string, userName: string, groupId: string): void {
    this.currentUserId = userId;
    this.currentUserName = userName;

    // 断开现有连接
    this.disconnect();

    // 创建新的 WebSocket 客户端
    const wsUrl = Constants.WS_BASE_URL;
    this.wsClient = new WebSocketClient(wsUrl);

    // 设置回调
    this.wsClient.onMessage = (data: string) => this.handleMessage(data);
    this.wsClient.onConnected = () => {
      this._connectionState = ConnectionState.CONNECTED;
      this.onConnectionStateChanged?.();
    };
    this.wsClient.onDisconnected = () => {
      this._connectionState = ConnectionState.DISCONNECTED;
      this.onConnectionStateChanged?.();
    };
    this.wsClient.onError = (error: string) => {
      this.onError?.(error);
    };

    // 开始连接
    this._connectionState = ConnectionState.CONNECTING;
    this.onConnectionStateChanged?.();
    this.wsClient.connect(userId, userName, groupId);
  }

  /**
   * 断开连接
   */
  disconnect(): void {
    if (this.wsClient) {
      this.wsClient.disconnect();
      this.wsClient = null;
    }
    this._connectionState = ConnectionState.DISCONNECTED;
    this.onConnectionStateChanged?.();
  }

  /**
   * 发送消息
   */
  sendMessage(content: string): void {
    if (!this.wsClient || this._connectionState !== ConnectionState.CONNECTED) {
      this.onError?.('未连接到服务器');
      return;
    }

    const message: Message = {
      id: this.generateId(),
      userId: this.currentUserId,
      userName: this.currentUserName,
      content: content,
      timestamp: Date.now(),
      type: MessageType.TEXT,
      isTransient: false,
      category: MessageCategory.NORMAL
    };

    // 乐观更新：立即显示消息
    this._messages = [...this._messages, message];
    this.onMessagesChanged?.();

    // 发送到服务器
    this.wsClient.send(JSON.stringify(message));
  }

  /**
   * 处理收到的消息
   */
  private handleMessage(data: string): void {
    try {
      const message: Message = JSON.parse(data) as Message;

      // 撤回消息
      if (message.type === MessageType.RECALL) {
        const idsToRemove = message.content.split(',').map(id => id.trim());
        this._messages = this._messages.filter(msg => !idsToRemove.includes(msg.id));
        this.onMessagesChanged?.();
        return;
      }

      // Agent 状态消息
      if (message.category === MessageCategory.AGENT_STATUS) {
        if (message.content.startsWith('CLEAR_STATUS')) {
          this._statusMessages = [];
        } else {
          this._statusMessages = [...this._statusMessages, message].slice(-10);
        }
        this.onMessagesChanged?.();
        return;
      }

      // 增量临时消息
      if (message.isTransient && message.isIncremental) {
        const existing = this._transientMessage;
        if (existing && existing.userId === message.userId && existing.type === message.type) {
          this._transientMessage = {
            ...existing,
            content: existing.content + message.content,
            timestamp: message.timestamp,
            currentStep: message.currentStep,
            totalSteps: message.totalSteps
          };
        } else {
          this._transientMessage = message;
        }
        this.onMessagesChanged?.();
        return;
      }

      // 完整临时消息
      if (message.isTransient) {
        this._transientMessage = message;
        this.onMessagesChanged?.();
        return;
      }

      // 普通消息
      const exists = this._messages.some(msg => msg.id === message.id);
      if (!exists) {
        this._messages = [...this._messages, message];
      }
      this._transientMessage = null;
      this._statusMessages = [];
      this.onMessagesChanged?.();

    } catch (e) {
      console.error('解析消息失败:', e);
    }
  }

  /**
   * 清空消息
   */
  clearMessages(): void {
    this._messages = [];
    this._transientMessage = null;
    this._statusMessages = [];
    this.onMessagesChanged?.();
  }

  /**
   * 生成唯一 ID
   */
  private generateId(): string {
    return `${Date.now()}_${Math.floor(Math.random() * 10000)}`;
  }
}

// 全局单例
export const chatStore = new ChatStore();
