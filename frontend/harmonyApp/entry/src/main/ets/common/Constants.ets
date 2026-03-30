/**
 * 应用常量配置
 * 配置后端 API 地址和其他常量
 */
import { BACKEND_HOST, BACKEND_HTTP_PORT } from './EnvConfig';

/**
 * Web 浏览器上 127.0.0.1 是开发机本机；鸿蒙本地模拟器里 127.0.0.1 指向模拟器自身，请求会失败。
 * 与常见 Android/QEMU 模拟器一致，经 10.0.2.2 访问宿主上的后端（后端需监听 0.0.0.0，本项目已满足）。
 * 真机请使用局域网 IP：在 .env 设置 BACKEND_HOST 或 HARMONY_BACKEND_HOST（由 hvigor 写入 EnvConfig）。
 */
function resolveBackendHostForDevice(rawHost: string): string {
  const trimmed: string = rawHost.trim();
  const lower: string = trimmed.toLowerCase();
  if (lower === '127.0.0.1' || lower === 'localhost' || lower === '::1') {
    return '10.0.2.2';
  }
  return trimmed;
}

export class Constants {
  // 后端 API 基础地址 (开发环境，已做模拟器 loopback 映射)
  static readonly DEFAULT_BACKEND_HOST: string = resolveBackendHostForDevice(BACKEND_HOST);
  static readonly DEFAULT_BACKEND_PORT: string = BACKEND_HTTP_PORT;
  
  // WebSocket 基础地址
  static readonly WS_PROTOCOL: string = 'ws';
  static readonly API_BASE_URL: string = `http://${Constants.DEFAULT_BACKEND_HOST}:${Constants.DEFAULT_BACKEND_PORT}`;
  static readonly WS_BASE_URL: string = `${Constants.WS_PROTOCOL}://${Constants.DEFAULT_BACKEND_HOST}:${Constants.DEFAULT_BACKEND_PORT}`;
  
  // API 超时时间 (毫秒)
  static readonly API_TIMEOUT: number = 30000;
  
  // WebSocket 重连配置
  static readonly WS_RECONNECT_INTERVAL: number = 3000; // 重连间隔
  static readonly WS_MAX_RECONNECT_ATTEMPTS: number = 5; // 最大重连次数
  static readonly WS_PING_INTERVAL: number = 30000; // 心跳间隔
  
  // 本地存储键名
  static readonly STORAGE_KEY_USER: string = 'silk_user';
  static readonly STORAGE_KEY_TOKEN: string = 'silk_token';
  static readonly STORAGE_KEY_SETTINGS: string = 'silk_settings';
  static readonly STORAGE_KEY_BACKEND_URL: string = 'silk_backend_url';
  
  // 消息类型
  static readonly MESSAGE_TYPE_TEXT: string = 'TEXT';
  static readonly MESSAGE_TYPE_JOIN: string = 'JOIN';
  static readonly MESSAGE_TYPE_LEAVE: string = 'LEAVE';
  static readonly MESSAGE_TYPE_SYSTEM: string = 'SYSTEM';
  static readonly MESSAGE_TYPE_FILE: string = 'FILE';
  static readonly MESSAGE_TYPE_RECALL: string = 'RECALL';
  
  // 消息类别
  static readonly MESSAGE_CATEGORY_NORMAL: string = 'NORMAL';
  static readonly MESSAGE_CATEGORY_TODO_LIST: string = 'TODO_LIST';
  static readonly MESSAGE_CATEGORY_STEP_PROCESS: string = 'STEP_PROCESS';
  static readonly MESSAGE_CATEGORY_FINAL_REPORT: string = 'FINAL_REPORT';
  static readonly MESSAGE_CATEGORY_AGENT_STATUS: string = 'AGENT_STATUS';
}

export const API_BASE_URL: string = Constants.API_BASE_URL;
export const WS_BASE_URL: string = Constants.WS_BASE_URL;
export const API_TIMEOUT: number = Constants.API_TIMEOUT;

/**
 * API 端点路径
 */
export class ApiEndpoints {
  // 认证相关
  static readonly LOGIN: string = '/api/auth/login';
  static readonly REGISTER: string = '/api/auth/register';
  static readonly LOGOUT: string = '/api/auth/logout';
  static readonly CHECK_SESSION: string = '/api/auth/check-session';
  
  // 用户相关
  static readonly GET_USER_SETTINGS: string = '/api/user/settings';
  static readonly UPDATE_USER_SETTINGS: string = '/api/user/settings/update';
  static readonly GET_CONTACTS: string = '/api/contacts';
  
  // 群组相关
  static readonly GET_GROUPS: string = '/api/groups';
  static readonly CREATE_GROUP: string = '/api/groups/create';
  static readonly JOIN_GROUP: string = '/api/groups/join';
  static readonly LEAVE_GROUP: string = '/api/groups/leave';
  static readonly DELETE_GROUP: string = '/api/groups/delete';
  
  // 聊天相关
  static readonly CHAT_WS: string = '/chat';
  static readonly GET_CHAT_HISTORY: string = '/api/chat/history';
  
  // 搜索相关
  static readonly SEARCH_FILES: string = '/api/search';
  static readonly INDEX_FILE: string = '/api/index';
  
  // 文件相关
  static readonly UPLOAD_FILE: string = '/api/files/upload';
  static readonly DOWNLOAD_FILE: string = '/api/files/download';
  static readonly DOWNLOAD_APK: string = '/api/files/download-apk';
}
