import { Group } from '../models/Group';
import { GroupCard } from '../components/GroupCard';
import { LoadingIndicator } from '../components/LoadingIndicator';
import { SilkColors } from '../common/Theme';
import { apiClient as globalApiClient } from '../api/ApiClient';
import { router as appRouter } from '../common/Router';
import { appStore as globalAppStore } from '../stores/AppStore';

@Component
export struct GroupListPage {
  @State groups: Group[] = [];
  @State isLoading: boolean = false;
  @State showCreateDialog: boolean = false;
  @State newGroupName: string = '';
  @State errorMessage: string = '';
  
  private readonly appStore = globalAppStore;
  private readonly apiClient = globalApiClient;

  async aboutToAppear(): Promise<void> {
    // 检查登录状态
    if (!this.appStore.isLoggedIn) {
      appRouter.goToLogin();
      return;
    }
    await this.loadGroups();
  }

  async loadGroups(): Promise<void> {
    this.isLoading = true;
    try {
      const user = this.appStore.currentUser;
      if (!user) {
        this.groups = [];
        return;
      }

      const response = await this.apiClient.getGroups(user.id);
      const unreadCounts = await this.apiClient.getUnreadCounts(user.id);

      const sourceGroups: Group[] = response.data ? response.data : [];
      const nextGroups: Group[] = [];
      for (let i = 0; i < sourceGroups.length; i++) {
        const group = sourceGroups[i];
        const count = unreadCounts[group.id];
        group.unreadCount = count ? count : 0;
        nextGroups.push(group);
      }
      this.groups = nextGroups;
    } catch (error) {
      this.errorMessage = '加载群组失败';
    } finally {
      this.isLoading = false;
    }
  }

  async createGroup(): Promise<void> {
    if (!this.newGroupName.trim()) return;
    
    try {
      const response = await this.apiClient.createGroup({
        name: this.newGroupName.trim(),
        createdBy: this.appStore.currentUser!.id
      });
      if (response.success && response.data) {
        this.groups = this.groups.concat([response.data]);
        this.showCreateDialog = false;
        this.newGroupName = '';
      } else {
        this.errorMessage = response.message ? response.message : '创建群组失败';
      }
    } catch (error) {
      this.errorMessage = '创建群组失败';
    }
  }

  enterChat(group: Group): void {
    // 使用 AppStorage 传递数据，避免单例复制问题
    AppStorage.setOrCreate<string>('currentGroupId', group.id);
    AppStorage.setOrCreate<string>('currentGroupName', group.name);
    const user = this.appStore.currentUser;
    if (user) {
      AppStorage.setOrCreate<string>('currentUserId', user.id);
      AppStorage.setOrCreate<string>('currentUserName', user.name);
    }
    appRouter.goToChat(group.id, group.name);
  }

  goToSettings(): void {
    appRouter.goToSettings();
  }

  async logout(): Promise<void> {
    await this.appStore.logout();
    appRouter.goToLogin();
  }

  build() {
    Column() {
      // 顶部导航栏
      Row() {
        Text('SILK')
          .fontSize(24)
          .fontWeight(FontWeight.Bold)
          .fontColor(SilkColors.primary)
        
        Blank()
        
        // 设置按钮
        Button({ type: ButtonType.Circle }) {
          Text('⚙️')
            .fontSize(20)
        }
        .width(40)
        .height(40)
        .backgroundColor(SilkColors.surface)
        .onClick(() => this.goToSettings())
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      .backgroundColor(SilkColors.background)

      // 用户信息栏
      Row() {
        Text(`${this.appStore.currentUser?.name || '用户'}`)
          .fontSize(16)
          .fontColor(SilkColors.textPrimary)
        
        Blank()
        
        Button('退出')
          .fontSize(14)
          .backgroundColor(SilkColors.surface)
          .fontColor(SilkColors.textSecondary)
          .onClick(() => {
            this.logout();
          })
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 8, bottom: 8 })
      .backgroundColor(SilkColors.surface)

      // 分隔线
      Divider().color(SilkColors.border)

      if (this.isLoading) {
        // 加载中
        Column() {
          LoadingIndicator()
        }
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
      } else if (this.groups.length === 0) {
        // 空状态
        Column() {
          Text('还没有群组')
            .fontSize(18)
            .fontColor(SilkColors.textSecondary)
          Text('点击下方按钮创建一个群组')
            .fontSize(14)
            .fontColor(SilkColors.textLight)
            .margin({ top: 8 })
        }
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
      } else {
        // 群组列表
        List({ space: 8 }) {
          ForEach(this.groups, (group: Group) => {
            ListItem() {
              GroupCard({ 
                group: group,
                onGroupClick: (selectedGroup: Group): void => this.enterChat(selectedGroup)
              })
            }
          }, (group: Group) => group.id)
        }
        .width('100%')
        .layoutWeight(1)
        .padding({ left: 16, right: 16, top: 8, bottom: 8 })
      }

      // 错误提示
      if (this.errorMessage) {
        Text(this.errorMessage)
          .fontSize(14)
          .fontColor(SilkColors.error)
          .padding(8)
      }

      // 底部创建按钮
      Row() {
        Button('创建群组')
          .width('100%')
          .height(48)
          .backgroundColor(SilkColors.primary)
          .fontColor(Color.White)
          .onClick(() => this.showCreateDialog = true)
      }
      .width('100%')
      .padding({ left: 16, right: 16, bottom: 16 })
    }
    .width('100%')
    .height('100%')
    .backgroundColor(SilkColors.background)
    .bindSheet($$this.showCreateDialog, this.buildCreateSheet(), {
      height: 200,
      backgroundColor: SilkColors.surface
    })
  }

  @Builder
  buildCreateSheet() {
    Column() {
      Text('创建新群组')
        .fontSize(18)
        .fontWeight(FontWeight.Bold)
        .fontColor(SilkColors.textPrimary)
        .margin({ bottom: 16 })

      TextInput({ placeholder: '群组名称' })
        .width('100%')
        .height(48)
        .backgroundColor(SilkColors.background)
        .onChange((value: string) => this.newGroupName = value)

      Row() {
        Button('取消')
          .width('45%')
          .backgroundColor(SilkColors.surface)
          .fontColor(SilkColors.textSecondary)
          .onClick(() => this.showCreateDialog = false)

        Button('创建')
          .width('45%')
          .backgroundColor(SilkColors.primary)
          .fontColor(Color.White)
          .onClick(() => this.createGroup())
      }
      .width('100%')
      .justifyContent(FlexAlign.SpaceBetween)
      .margin({ top: 16 })
    }
    .width('100%')
    .padding(16)
  }
}
