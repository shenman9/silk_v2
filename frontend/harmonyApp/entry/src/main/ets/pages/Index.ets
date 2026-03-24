/**
 * 根页面 — Navigation 导航宿主
 * 启动时检查用户持久化状态，决定进入登录页或群组列表页
 */
import { hasUser } from '../store/UserStore';
import { LoginPage } from './LoginPage';
import { GroupListPage } from './GroupListPage';
import { ChatPage } from './ChatPage';
import { ContactsPage } from './ContactsPage';
import { SettingsPage } from './SettingsPage';

@Entry
@Component
struct Index {
  @Provide('navPathStack') navPathStack: NavPathStack = new NavPathStack();

  aboutToAppear(): void {
    if (hasUser()) {
      this.navPathStack.pushPath({ name: 'groupList' });
    } else {
      this.navPathStack.pushPath({ name: 'login' });
    }
  }

  @Builder
  routeMap(name: string): void {
    if (name === 'login') {
      LoginPage()
    } else if (name === 'groupList') {
      GroupListPage()
    } else if (name === 'chat') {
      ChatPage()
    } else if (name === 'contacts') {
      ContactsPage()
    } else if (name === 'settings') {
      SettingsPage()
    }
  }

  build() {
    Navigation(this.navPathStack) {
    }
    .navDestination(this.routeMap)
    .hideTitleBar(true)
    .hideToolBar(true)
  }
}
