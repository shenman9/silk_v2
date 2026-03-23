/**
 * 简单的路由管理器
 * 用于页面导航
 */

import { AppStore } from '../stores/AppStore';

export enum PageRoute {
  LOGIN = 'pages/LoginPage',
  GROUP_LIST = 'pages/GroupListPage',
  CHAT = 'pages/ChatPage',
  SETTINGS = 'pages/SettingsPage'
}

export class Router {
  private static instance: Router;
  private appStore: AppStore | null = null;
  
  private constructor() {}
  
  static getInstance(): Router {
    if (!Router.instance) {
      Router.instance = new Router();
    }
    return Router.instance;
  }
  
  setAppStore(store: AppStore) {
    this.appStore = store;
  }
  
  /**
   * 导航到登录页面
   */
  goToLogin(): void {
    // 清除用户状态
    if (this.appStore) {
      this.appStore.logout();
    }
    // 页面路由由 UI 层处理
  }
  
  /**
   * 导航到群组列表
   */
  goToGroupList(): void {
    // 页面路由由 UI 层处理
  }
  
  /**
   * 导航到聊天页面
   * @param groupId 群组ID
   * @param groupName 群组名称
   */
  goToChat(groupId: string, groupName: string): void {
    if (this.appStore) {
      this.appStore.setCurrentGroup(groupId, groupName);
    }
    // 页面路由由 UI 层处理
  }
  
  /**
   * 导航到设置页面
   */
  goToSettings(): void {
    // 页面路由由 UI 层处理
  }
  
  /**
   * 返回上一页
   */
  goBack(): void {
    // 页面路由由 UI 层处理
  }
}

// 导出单例
export const router = Router.getInstance();
