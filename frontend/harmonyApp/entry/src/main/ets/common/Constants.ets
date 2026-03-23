/**
 * 应用常量配置
 * 配置后端 API 地址和其他常量
 */

export class Constants {
  // 后端 API 基础地址 (开发环境)
  // 鸿蒙真机需要使用电脑的局域网 IP
  static readonly DEFAULT_BACKEND_HOST: string = '192.168.1.100'; // 请根据实际环境修改
  static readonly DEFAULT_BACKEND_PORT: string = '8006';
  
  // WebSocket 基础地址
  static readonly WS_PROTOCOL: string = 'ws';
  
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
