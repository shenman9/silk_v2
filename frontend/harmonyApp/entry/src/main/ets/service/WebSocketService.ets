/**
 * WebSocket 服务
 * 基于 @ohos.net.webSocket，管理与后端 /chat 端点的实时通信
 */
import { webSocket } from '@kit.NetworkKit';
import { getWsBaseUrl } from '../config/BackendConfig';
import { Message, parseMessage } from '../model/MessageModel';

const MAX_RECONNECT_ATTEMPTS = 5;
const RECONNECT_BASE_DELAY = 1000;
const HEARTBEAT_INTERVAL = 30000;

export class WebSocketService {
  private ws: webSocket.WebSocket | null = null;
  private heartbeatTimer: number = -1;
  private reconnectAttempts: number = 0;
  private isManualDisconnect: boolean = false;
  private userId: string = '';
  private userName: string = '';
  private groupId: string = '';

  onMessage: (message: Message) => void = () => {};
  onConnected: () => void = () => {};
  onDisconnected: () => void = () => {};
  onError: (error: string) => void = () => {};

  connect(userId: string, userName: string, groupId: string): void {
    this.userId = userId;
    this.userName = userName;
    this.groupId = groupId;
    this.isManualDisconnect = false;
    this.reconnectAttempts = 0;
    this.doConnect();
  }

  private doConnect(): void {
    try {
      this.ws = webSocket.createWebSocket();

      this.ws.on('open', () => {
        console.info('[WebSocket] 连接成功');
        this.reconnectAttempts = 0;
        this.startHeartbeat();
        this.onConnected();
      });

      this.ws.on('message', (_err: Error, data: string | ArrayBuffer) => {
        if (typeof data === 'string') {
          this.handleRawMessage(data);
        }
      });

      this.ws.on('close', () => {
        console.info('[WebSocket] 连接关闭');
        this.stopHeartbeat();
        this.onDisconnected();
        if (!this.isManualDisconnect) {
          this.attemptReconnect();
        }
      });

      this.ws.on('error', (err: Error) => {
        console.error('[WebSocket] 错误:', JSON.stringify(err));
        this.onError(JSON.stringify(err));
      });

      const wsUrl = getWsBaseUrl() + '/chat?userId=' + encodeURIComponent(this.userId) +
        '&userName=' + encodeURIComponent(this.userName) +
        '&groupId=' + encodeURIComponent(this.groupId);
      console.info('[WebSocket] 正在连接: ' + wsUrl);
      this.ws.connect(wsUrl);
    } catch (err) {
      console.error('[WebSocket] 创建失败:', JSON.stringify(err));
      this.onError('创建 WebSocket 失败');
    }
  }

  private handleRawMessage(text: string): void {
    try {
      const jsonObj = JSON.parse(text) as object;
      const message = parseMessage(jsonObj);
      this.onMessage(message);
    } catch (e) {
      console.error('[WebSocket] 解析消息失败:', text);
    }
  }

  send(message: string): void {
    if (this.ws) {
      try {
        this.ws.send(message);
      } catch (e) {
        console.error('[WebSocket] 发送失败:', JSON.stringify(e));
      }
    } else {
      console.error('[WebSocket] 未连接，无法发送');
    }
  }

  disconnect(): void {
    this.isManualDisconnect = true;
    this.stopHeartbeat();
    if (this.ws) {
      try {
        this.ws.close();
      } catch (e) {
        console.error('[WebSocket] 关闭异常:', JSON.stringify(e));
      }
      this.ws = null;
    }
  }

  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      if (this.ws) {
        try {
          this.ws.send('ping');
        } catch (e) {
          console.error('[WebSocket] 心跳发送失败');
        }
      }
    }, HEARTBEAT_INTERVAL);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer !== -1) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = -1;
    }
  }

  private attemptReconnect(): void {
    if (this.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      console.error('[WebSocket] 达到最大重连次数，停止重连');
      return;
    }
    this.reconnectAttempts++;
    const delay = RECONNECT_BASE_DELAY * Math.pow(2, this.reconnectAttempts - 1);
    console.info('[WebSocket] ' + delay + 'ms 后进行第 ' + this.reconnectAttempts + ' 次重连');
    setTimeout(() => {
      if (!this.isManualDisconnect) {
        this.doConnect();
      }
    }, delay);
  }
}
