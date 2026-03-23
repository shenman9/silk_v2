/**
 * 用户设置数据模型
 * 与后端 UserSettings.kt 保持一致
 */

/**
 * 语言枚举
 */
export enum Language {
  ENGLISH = 'ENGLISH',
  CHINESE = 'CHINESE'
}

/**
 * 用户设置
 */
export interface UserSettings {
  language: Language;
  defaultAgentInstruction: string;
}

/**
 * 更新用户设置请求
 */
export interface UpdateUserSettingsRequest {
  userId: string;
  language: Language;
  defaultAgentInstruction: string;
}

/**
 * 用户设置响应
 */
export interface UserSettingsResponse {
  success: boolean;
  message: string;
  settings?: UserSettings;
}

/**
 * 创建默认用户设置
 */
export function createDefaultUserSettings(): UserSettings {
  return {
    language: Language.CHINESE,
    defaultAgentInstruction: 'You are a helpful technical research assistant. '
  };
}
