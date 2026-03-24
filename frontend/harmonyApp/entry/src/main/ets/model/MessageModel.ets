/**
 * 消息相关数据模型
 * 对齐 shared/models/Message.kt
 */

export enum MessageType {
  TEXT = 'TEXT',
  JOIN = 'JOIN',
  LEAVE = 'LEAVE',
  SYSTEM = 'SYSTEM',
  FILE = 'FILE',
  RECALL = 'RECALL'
}

export enum MessageCategory {
  NORMAL = 'NORMAL',
  TODO_LIST = 'TODO_LIST',
  STEP_PROCESS = 'STEP_PROCESS',
  FINAL_REPORT = 'FINAL_REPORT',
  AGENT_STATUS = 'AGENT_STATUS'
}

export enum ConnectionState {
  DISCONNECTED = 'DISCONNECTED',
  CONNECTING = 'CONNECTING',
  CONNECTED = 'CONNECTED'
}

export interface Message {
  id: string;
  userId: string;
  userName: string;
  content: string;
  timestamp: number;
  type: MessageType;
  isTransient: boolean;
  currentStep?: number;
  totalSteps?: number;
  isIncremental: boolean;
  category: MessageCategory;
}

/**
 * 解析服务器返回的 JSON 为 Message 对象
 * 处理缺省字段，确保所有字段都有默认值
 */
export function parseMessage(json: object): Message {
  const obj = json as Record<string, Object>;
  return {
    id: (obj['id'] as string) ?? '',
    userId: (obj['userId'] as string) ?? '',
    userName: (obj['userName'] as string) ?? '',
    content: (obj['content'] as string) ?? '',
    timestamp: (obj['timestamp'] as number) ?? 0,
    type: ((obj['type'] as string) ?? 'TEXT') as MessageType,
    isTransient: (obj['isTransient'] as boolean) ?? false,
    currentStep: obj['currentStep'] as number | undefined,
    totalSteps: obj['totalSteps'] as number | undefined,
    isIncremental: (obj['isIncremental'] as boolean) ?? false,
    category: ((obj['category'] as string) ?? 'NORMAL') as MessageCategory,
  };
}

/**
 * 构造一条待发送的文本消息
 */
export function createTextMessage(userId: string, userName: string, content: string): Message {
  const now = new Date().getTime();
  const rand = Math.floor(Math.random() * 10000);
  return {
    id: `${now}_${rand}`,
    userId: userId,
    userName: userName,
    content: content,
    timestamp: now,
    type: MessageType.TEXT,
    isTransient: false,
    isIncremental: false,
    category: MessageCategory.NORMAL,
  };
}
