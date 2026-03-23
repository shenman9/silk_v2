/**
 * WebSocket 客户端封装
 * 使用 @ohos.net.webSocket 实现实时通信
 */
import webSocket from '@ohos.net.webSocket';
import { Message, MessageType, MessageCategory, parseMessage } from '../models/Message';
import { ConnectionLogger, ConnectionListener } from '../models/ConnectionState';

/**
 * WebSocket 连接状态
 */
export enum ConnectionState {
  DISCONNECTED = 'disconnected',
  CONNECTING = 'connecting',
  CONNECTED = 'connected'
}

/**
 * WebSocket 客户端类
 * 负责与后端建立 WebSocket 连接，接收和发送消息
 */
export class WebSocketClient {
  private ws: webSocket.WebSocket | null = null;
  private connectionState: ConnectionState = ConnectionState.DISCONNECTED;
  private logger: ConnectionLogger | null = null;
  private listeners: ConnectionListener[] = [];
  
  // 重连配置
  private reconnectAttempts: number = 0;
  private maxReconnectAttempts: number = 5;
  private baseReconnectDelay: number = 1000; // 1秒
  private reconnectTimer: number = -1;
  
  // 保活心跳
  private keepAliveTimer: number = -1;
  private keepAliveInterval: number = 30000; // 30秒
  
  // 当前连接信息（用于重连）
  private currentUserId: string = '';
  private currentUserName: string = '';
  private currentGroupId: string = '';
  private serverUrl: string = '';

  constructor(logger?: ConnectionLogger) {
    this.logger = logger || null;
  }

  /**
   * 添加连接监听器
   */
  addListener(listener: ConnectionListener): void {
    this.listeners.push(listener);
  }

  /**
   * 移除连接监听器
   */
  removeListener(listener: ConnectionListener): void {
    const index = this.listeners.indexOf(listener);
    if (index >= 0) {
      this.listeners.splice(index, 1);
    }
  }

  /**
   * 获取当前连接状态
   */
  getState(): ConnectionState {
    return this.connectionState;
  }

  /**
   * 是否已连接
   */
  isConnected(): boolean {
    return this.connectionState === ConnectionState.CONNECTED && this.ws !== null;
  }

  /**
   * 连接到服务器
   */
  connect(serverUrl: string, userId: string, userName: string, groupId: string = 'default_room'): Promise<boolean> {
    return new Promise<boolean>((resolve, reject) => {
      if (this.connectionState === ConnectionState.CONNECTING) {
        this.log('⚠️ [WebSocket] 已有连接正在进行中');
        resolve(false);
        return;
      }

      if (this.connectionState === ConnectionState.CONNECTED) {
        this.log('⚠️ [WebSocket] 已经连接，先断开再重连');
        this.disconnect();
      }

      // 保存连接信息
      this.serverUrl = serverUrl;
      this.currentUserId = userId;
      this.currentUserName = userName;
      this.currentGroupId = groupId;
      this.connectionState = ConnectionState.CONNECTING;
      this.notifyStateChanged();

      // 创建 WebSocket
      this.ws = webSocket.createWebSocket();
      
      // 构建完整 URL
      const safeUserName = userName.replace(/ /g, '_').replace(/&/g, '_').replace(/=/g, '_');
      const safeGroupId = groupId.replace(/ /g, '_').replace(/&/g, '_').replace(/=/g, '_');
      const fullUrl = `${serverUrl}/chat?userId=${userId}&userName=${safeUserName}&groupId=${safeGroupId}`;

      this.log(`🔗 [WebSocket] 连接到: ${fullUrl}`);

      // 注册事件处理
      this.setupEventHandlers();

      // 发起连接
      this.ws.connect(fullUrl, (err, value) => {
        if (err) {
          this.log(`❌ [WebSocket] 连接失败: ${err.message}`);
          this.connectionState = ConnectionState.DISCONNECTED;
          this.notifyStateChanged();
          this.attemptReconnect();
          resolve(false);
          return;
        }

        this.log(`✅ [WebSocket] 连接成功`);
        this.connectionState = ConnectionState.CONNECTED;
        this.reconnectAttempts = 0;
        this.notifyStateChanged();
        this.startKeepAlive();
        resolve(true);
      });
    });
  }

  /**
   * 设置事件处理器
   */
  private setupEventHandlers(): void {
    if (!this.ws) return;

    // 接收消息
    this.ws.on('message', (err, value) => {
      if (err) {
        this.log(`❌ [WebSocket] 消息错误: ${err.message}`);
        return;
      }

      const data = value as string;
      this.log(`📨 [WebSocket] 收到消息: ${data.substring(0, 100)}...`);
      
      try {
        const message = parseMessage(data);
        this.notifyMessageReceived(message);
      } catch (e) {
        this.log(`❌ [WebSocket] 解析消息失败: ${e}`);
      }
    });

    // 连接关闭
    this.ws.on('close', (err, value) => {
      this.log(`🔌 [WebSocket] 连接关闭: ${value}`);
      this.connectionState = ConnectionState.DISCONNECTED;
      this.stopKeepAlive();
      this.notifyStateChanged();
      
      // 非正常关闭时尝试重连
      if (err) {
        this.attemptReconnect();
      }
    });

    // 错误
    this.ws.on('error', (err) => {
      this.log(`❌ [WebSocket] 错误: ${err.message}`);
      this.connectionState = ConnectionState.DISCONNECTED;
      this.stopKeepAlive();
      this.notifyStateChanged();
      this.attemptReconnect();
    });
  }

  /**
   * 发送消息
   */
  send(message: Message): boolean {
    if (!this.isConnected() || !this.ws) {
      this.log('⚠️ [WebSocket] 未连接，无法发送');
      return false;
    }

    try {
      const jsonStr = JSON.stringify(message);
      this.ws.send(jsonStr);
      this.log(`📤 [WebSocket] 发送消息: ${jsonStr.substring(0, 100)}...`);
      return true;
    } catch (e) {
      this.log(`❌ [WebSocket] 发送失败: ${e}`);
      return false;
    }
  }

  /**
   * 发送文本消息
   */
  sendText(userId: string, userName: string, content: string): boolean {
    const message: Message = {
      id: this.generateId(),
      userId: userId,
      userName: userName,
      content: content,
      timestamp: Date.now(),
      type: MessageType.TEXT,
      isTransient: false,
      isIncremental: false,
      category: MessageCategory.NORMAL
    };
    
    return this.send(message);
  }

  /**
   * 断开连接
   */
  disconnect(): void {
    this.log('🔌 [WebSocket] 断开连接');
    
    this.stopKeepAlive();
    this.stopReconnectTimer();
    
    if (this.ws) {
      try {
        this.ws.close((err, value) => {
          if (err) {
            this.log(`⚠️ [WebSocket] 关闭异常: ${err.message}`);
          }
        });
      } catch (e) {
        this.log(`⚠️ [WebSocket] 关闭异常: ${e}`);
      }
      this.ws = null;
    }
    
    this.connectionState = ConnectionState.DISCONNECTED;
    this.notifyStateChanged();
  }

  /**
   * 启动保活心跳
   */
  private startKeepAlive(): void {
    this.stopKeepAlive();
    
    this.keepAliveTimer = setInterval(() => {
      if (this.isConnected() && this.ws) {
        try {
          // 发送心跳消息
          this.ws.send('❤️');
          this.log('💓 [WebSocket] 发送心跳');
        } catch (e) {
          this.log(`⚠️ [WebSocket] 心跳发送失败: ${e}`);
          this.stopKeepAlive();
        }
      }
    }, this.keepAliveInterval);
  }

  /**
   * 停止保活心跳
   */
  private stopKeepAlive(): void {
    if (this.keepAliveTimer !== -1) {
      clearInterval(this.keepAliveTimer);
      this.keepAliveTimer = -1;
    }
  }

  /**
   * 尝试重连
   */
  private attemptReconnect(): void {
    this.reconnectAttempts++;
    
    if (this.reconnectAttempts > this.maxReconnectAttempts) {
      this.log(`❌ [WebSocket] 重连次数超限 (${this.maxReconnectAttempts}次)`);
      return;
    }

    const delay = this.baseReconnectDelay * this.reconnectAttempts;
    this.log(`🔄 [WebSocket] 尝试重连 (${this.reconnectAttempts}/${this.maxReconnectAttempts})，延迟 ${delay}ms...`);

    this.stopReconnectTimer();
    this.reconnectTimer = setTimeout(() => {
      this.connect(this.serverUrl, this.currentUserId, this.currentUserName, this.currentGroupId);
    }, delay);
  }

  /**
   * 停止重连定时器
   */
  private stopReconnectTimer(): void {
    if (this.reconnectTimer !== -1) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = -1;
    }
  }

  /**
   * 通知状态变化
   */
  private notifyStateChanged(): void {
    for (const listener of this.listeners) {
      if (listener.onConnectionStateChanged) {
        listener.onConnectionStateChanged(this.connectionState);
      }
    }
  }

  /**
   * 通知收到消息
   */
  private notifyMessageReceived(message: Message): void {
    for (const listener of this.listeners) {
      if (listener.onMessageReceived) {
        listener.onMessageReceived(message);
      }
    }
  }

  /**
   * 日志输出
   */
  private log(message: string): void {
    console.info(message);
    if (this.logger && this.logger.log) {
      this.logger.log(message);
    }
  }

  /**
   * 生成消息 ID
   */
  private generateId(): string {
    return `${Date.now()}_${Math.floor(Math.random() * 10000)}`;
  }
}

/**
 * 创建全局 WebSocket 客户端实例
 */
export const wsClient = new WebSocketClient();
