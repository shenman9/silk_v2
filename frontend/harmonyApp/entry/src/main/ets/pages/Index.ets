/**
 * 主页面 - 路由入口
 * 根据登录状态显示不同页面
 */
import { AppStore } from '../stores/AppStore';
import { Router, PageName } from '../common/Router';
import { LoginPage } from './LoginPage';
import { GroupListPage } from './GroupListPage';
import { ChatPage } from './ChatPage';
import { SettingsPage } from './SettingsPage';

@Entry
@Component
struct Index {
  @StorageLink('appStore') appStore: AppStore = AppStore.getInstance();
  @State currentPage: PageName = PageName.Login;

  aboutToAppear() {
    // 初始化检查登录状态
    this.checkLoginStatus();
    
    // 订阅路由变化
    Router.getInstance().onPageChange((page: PageName) => {
      this.currentPage = page;
    });
  }

  checkLoginStatus() {
    if (this.appStore.isLoggedIn) {
      this.currentPage = PageName.GroupList;
    } else {
      this.currentPage = PageName.Login;
    }
  }

  build() {
    Column() {
      if (this.currentPage === PageName.Login) {
        LoginPage();
      } else if (this.currentPage === PageName.GroupList) {
        GroupListPage();
      } else if (this.currentPage === PageName.Chat) {
        ChatPage();
      } else if (this.currentPage === PageName.Settings) {
        SettingsPage();
      }
    }
    .width('100%')
    .height('100%');
  }
}
