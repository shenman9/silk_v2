/**
 * 用户设置相关数据模型
 */

export enum Language {
  ENGLISH = 'ENGLISH',
  CHINESE = 'CHINESE'
}

export interface UserSettings {
  language: Language;
  defaultAgentInstruction: string;
}

export interface UserSettingsResponse {
  success: boolean;
  message: string;
  settings?: UserSettings;
}

export interface UpdateUserSettingsRequest {
  userId: string;
  language: Language;
  defaultAgentInstruction: string;
}
