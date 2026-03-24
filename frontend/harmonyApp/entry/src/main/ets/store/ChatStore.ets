/**
 * 聊天消息状态管理
 * 移植自 shared/ChatClient.kt 中的 handleMessage 逻辑
 * 使用 @Observed 使 UI 可以感知变化
 */
import { Message, MessageType, MessageCategory, ConnectionState, parseMessage } from '../model/MessageModel';

@Observed
export class ChatStore {
  messages: Message[] = [];
  transientMessage: Message | null = null;
  statusMessages: Message[] = [];
  connectionState: ConnectionState = ConnectionState.DISCONNECTED;

  /**
   * 处理 WebSocket 收到的原始消息文本
   * 完全对齐 ChatClient.kt handleMessage() 的分支逻辑
   */
  handleIncoming(text: string): void {
    let message: Message;
    try {
      const jsonObj = JSON.parse(text) as object;
      message = parseMessage(jsonObj);
    } catch (e) {
      console.error('[ChatStore] 解析消息失败:', text);
      return;
    }

    if (message.type === MessageType.RECALL) {
      // 撤回消息：按 ID 从列表中移除
      const idsToRemove = message.content.split(',').map((s: string) => s.trim());
      this.messages = this.messages.filter((m: Message) => !idsToRemove.includes(m.id));
      return;
    }

    if (message.category === MessageCategory.AGENT_STATUS) {
      // Agent 状态消息
      if (message.content.startsWith('CLEAR_STATUS')) {
        this.statusMessages = [];
      } else {
        this.statusMessages = [...this.statusMessages, message].slice(-10);
      }
      return;
    }

    if (message.isTransient && message.isIncremental) {
      // 增量临时消息：追加到已有内容
      const existing = this.transientMessage;
      if (existing !== null && existing.userId === message.userId && existing.type === message.type) {
        const updated: Message = {
          id: existing.id,
          userId: existing.userId,
          userName: existing.userName,
          content: existing.content + message.content,
          timestamp: message.timestamp,
          type: existing.type,
          isTransient: existing.isTransient,
          currentStep: message.currentStep,
          totalSteps: message.totalSteps,
          isIncremental: existing.isIncremental,
          category: existing.category,
        };
        this.transientMessage = updated;
      } else {
        this.transientMessage = message;
      }
      return;
    }

    if (message.isTransient) {
      // 完整临时消息：直接替换
      this.transientMessage = message;
      return;
    }

    // 普通消息：加入列表（按 ID 去重）
    const exists = this.messages.some((m: Message) => m.id === message.id);
    if (!exists) {
      this.messages = [...this.messages, message];
    }
    this.transientMessage = null;
    this.statusMessages = [];
  }

  /**
   * 添加本地消息（乐观更新，发送前立即显示）
   */
  addLocalMessage(message: Message): void {
    this.messages = [...this.messages, message];
  }

  clearMessages(): void {
    this.messages = [];
    this.transientMessage = null;
    this.statusMessages = [];
  }
}
