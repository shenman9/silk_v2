import { Group } from '../models/Group';
import { GroupCard } from '../components/GroupCard';
import { LoadingIndicator } from '../components/LoadingIndicator';
import { SilkColors } from '../common/Theme';
import { apiClient as globalApiClient } from '../api/ApiClient';
import { router as appRouter } from '../common/Router';
import { appStore as globalAppStore } from '../stores/AppStore';
import promptAction from '@ohos.promptAction';

@Component
export struct GroupListPage {
  @State groups: Group[] = [];
  @State isLoading: boolean = false;
  @State showActionSheet: boolean = false;
  @State actionSheetMode: string = 'create';
  @State newGroupName: string = '';
  @State joinInvitationCode: string = '';
  @State isJoiningGroup: boolean = false;
  @State isCreatingGroup: boolean = false;
  @State isOpeningSilkChat: boolean = false;
  @State isLeaveMode: boolean = false;
  @State selectedLeaveGroupIds: string[] = [];
  @State isLeavingGroups: boolean = false;
  @State errorMessage: string = '';

  private unreadRefreshTimer: number = -1;
  private readonly appStore = globalAppStore;
  private readonly apiClient = globalApiClient;

  async aboutToAppear(): Promise<void> {
    // 检查登录状态
    if (!this.appStore.isLoggedIn) {
      appRouter.goToLogin();
      return;
    }
    await this.loadGroups();
    this.startUnreadRefresh();
  }

  aboutToDisappear(): void {
    this.stopUnreadRefresh();
  }

  private getCurrentUserId(): string {
    const user = this.appStore.currentUser;
    if (user && user.id && user.id.length > 0) {
      return user.id;
    }
    const storedUserId = AppStorage.get<string>('currentUserId');
    return storedUserId ? storedUserId : '';
  }

  async loadGroups(): Promise<void> {
    this.isLoading = true;
    try {
      const userId = this.getCurrentUserId();
      if (userId.length === 0) {
        this.groups = [];
        return;
      }

      const response = await this.apiClient.getGroups(userId);
      const unreadCounts = await this.apiClient.getUnreadCounts(userId);

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
      this.errorMessage = '加载项目失败';
    } finally {
      this.isLoading = false;
    }
  }

  private startUnreadRefresh(): void {
    this.stopUnreadRefresh();
    this.unreadRefreshTimer = setInterval((): void => {
      void this.refreshUnreadCounts();
    }, 30000);
  }

  private stopUnreadRefresh(): void {
    if (this.unreadRefreshTimer >= 0) {
      clearInterval(this.unreadRefreshTimer);
      this.unreadRefreshTimer = -1;
    }
  }

  async refreshUnreadCounts(): Promise<void> {
    const userId = this.getCurrentUserId();
    if (userId.length === 0 || this.groups.length === 0) {
      return;
    }
    try {
      const unreadCounts = await this.apiClient.getUnreadCounts(userId);
      const nextGroups: Group[] = [];
      for (let i = 0; i < this.groups.length; i++) {
        const source = this.groups[i];
        const count = unreadCounts[source.id];
        const merged: Group = {
          id: source.id,
          name: source.name,
          ownerId: source.ownerId,
          memberCount: source.memberCount,
          createdAt: source.createdAt,
          updatedAt: source.updatedAt,
          unreadCount: count ? count : 0
        };
        if (source.description !== undefined) {
          merged.description = source.description;
        }
        if (source.invitationCode !== undefined) {
          merged.invitationCode = source.invitationCode;
        }
        if (source.avatar !== undefined) {
          merged.avatar = source.avatar;
        }
        if (source.lastMessage !== undefined) {
          merged.lastMessage = source.lastMessage;
        }
        if (source.lastMessageTime !== undefined) {
          merged.lastMessageTime = source.lastMessageTime;
        }
        nextGroups.push(merged);
      }
      this.groups = nextGroups;
    } catch (_) {
      // 定时刷新静默失败，避免打扰用户
    }
  }

  async createGroup(): Promise<void> {
    if (this.isCreatingGroup) {
      return;
    }
    const trimmedName = this.newGroupName.trim();
    if (!trimmedName) {
      this.showToast('请输入项目名称');
      return;
    }
    const user = this.appStore.currentUser;
    if (!user) {
      this.showToast('登录状态已失效，请重新登录');
      appRouter.goToLogin();
      return;
    }

    this.isCreatingGroup = true;
    this.errorMessage = '';
    const previousGroupIds: string[] = [];
    for (let i = 0; i < this.groups.length; i++) {
      previousGroupIds.push(this.groups[i].id);
    }
    try {
      const response = await this.apiClient.createGroup({
        name: trimmedName,
        createdBy: user.id
      });
      if (response.success && response.data) {
        this.groups = this.groups.concat([response.data]);
        this.showActionSheet = false;
        this.newGroupName = '';
        this.showToast('项目创建成功');
      } else {
        const recovered = await this.recoverCreatedGroupAfterFailure(trimmedName, previousGroupIds);
        if (!recovered) {
          this.errorMessage = response.message ? response.message : '创建项目失败';
          this.showToast(this.errorMessage);
        }
      }
    } catch (error) {
      const recovered = await this.recoverCreatedGroupAfterFailure(trimmedName, previousGroupIds);
      if (!recovered) {
        this.errorMessage = '创建项目失败（网络超时）';
        this.showToast(this.errorMessage);
      }
    } finally {
      this.isCreatingGroup = false;
    }
  }

  /**
   * 服务端偶发“已创建但响应超时”时，回查群组列表做一致性修复。
   */
  private async recoverCreatedGroupAfterFailure(groupName: string, previousIds: string[]): Promise<boolean> {
    const userId = this.getCurrentUserId();
    if (userId.length === 0) {
      return false;
    }

    try {
      const response = await this.apiClient.getGroups(userId);
      const sourceGroups: Group[] = response.data ? response.data : [];
      const unreadCounts = await this.apiClient.getUnreadCounts(userId);
      const refreshed: Group[] = [];
      for (let i = 0; i < sourceGroups.length; i++) {
        const group = sourceGroups[i];
        const count = unreadCounts[group.id];
        group.unreadCount = count ? count : 0;
        refreshed.push(group);
      }
      this.groups = refreshed;

      for (let i = 0; i < refreshed.length; i++) {
        const group = refreshed[i];
        const sameName = group.name.trim() === groupName;
        const isNew = !this.containsGroupId(previousIds, group.id);
        if (sameName && isNew) {
          this.showActionSheet = false;
          this.newGroupName = '';
          this.showToast('项目已创建（请求超时，已自动同步）');
          return true;
        }
      }
      return false;
    } catch (_) {
      return false;
    }
  }

  private containsGroupId(ids: string[], targetId: string): boolean {
    for (let i = 0; i < ids.length; i++) {
      if (ids[i] === targetId) {
        return true;
      }
    }
    return false;
  }

  async joinGroupByCode(): Promise<void> {
    const code = this.joinInvitationCode.trim().toUpperCase();
    const userId = this.getCurrentUserId();
    if (userId.length === 0 || code.length !== 6 || this.isJoiningGroup) {
      return;
    }
    this.isJoiningGroup = true;
    this.errorMessage = '';
    try {
      const response = await this.apiClient.joinGroup(code, userId);
      if (response.success) {
        await this.loadGroups();
        this.showActionSheet = false;
        this.joinInvitationCode = '';
        this.showToast('加入项目成功');
      } else {
        this.errorMessage = response.message ? response.message : '加入项目失败';
      }
    } catch (_) {
      this.errorMessage = '加入项目失败';
    } finally {
      this.isJoiningGroup = false;
    }
  }

  private isGroupSelectedForLeave(groupId: string): boolean {
    for (let i = 0; i < this.selectedLeaveGroupIds.length; i++) {
      if (this.selectedLeaveGroupIds[i] === groupId) {
        return true;
      }
    }
    return false;
  }

  private toggleLeaveSelection(groupId: string): void {
    const next: string[] = [];
    let exists = false;
    for (let i = 0; i < this.selectedLeaveGroupIds.length; i++) {
      const currentId = this.selectedLeaveGroupIds[i];
      if (currentId === groupId) {
        exists = true;
      } else {
        next.push(currentId);
      }
    }
    if (!exists) {
      next.push(groupId);
    }
    this.selectedLeaveGroupIds = next;
  }

  private enterLeaveMode(): void {
    this.isLeaveMode = true;
    this.selectedLeaveGroupIds = [];
  }

  private exitLeaveMode(): void {
    this.isLeaveMode = false;
    this.selectedLeaveGroupIds = [];
    this.isLeavingGroups = false;
  }

  private async confirmLeaveSelectedGroups(): Promise<void> {
    const userId = this.getCurrentUserId();
    if (userId.length === 0 || this.isLeavingGroups || this.selectedLeaveGroupIds.length === 0) {
      return;
    }

    this.isLeavingGroups = true;
    this.errorMessage = '';
    let successCount = 0;
    for (let i = 0; i < this.selectedLeaveGroupIds.length; i++) {
      const groupId = this.selectedLeaveGroupIds[i];
      try {
        const response = await this.apiClient.leaveGroup(groupId, userId);
        if (response.success) {
          successCount += 1;
        }
      } catch (_) {
        // 单群退出失败时继续尝试其他群
      }
    }

    await this.loadGroups();
    this.isLeavingGroups = false;
    this.exitLeaveMode();
    if (successCount > 0) {
      this.showToast(`已退出 ${successCount} 个项目`);
    } else {
      this.showToast('退出项目失败');
    }
  }

  private async openSilkPrivateChat(): Promise<void> {
    const user = this.appStore.currentUser;
    if (!user || this.isOpeningSilkChat) {
      return;
    }
    this.isOpeningSilkChat = true;
    try {
      const response = await this.apiClient.startSilkPrivateChat(user.id);
      if (response.success && response.data) {
        await this.enterChat(response.data);
      } else {
        this.showToast(response.message ? response.message : '打开 Silk 对话失败');
      }
    } catch (_) {
      this.showToast('打开 Silk 对话失败');
    } finally {
      this.isOpeningSilkChat = false;
    }
  }

  async enterChat(group: Group): Promise<void> {
    if (this.isLeaveMode) {
      this.toggleLeaveSelection(group.id);
      return;
    }

    const user = this.appStore.currentUser;
    if (user) {
      void this.apiClient.markGroupAsRead(user.id, group.id);
    }

    // 使用 AppStorage 传递数据，避免单例复制问题
    AppStorage.setOrCreate<string>('currentGroupId', group.id);
    AppStorage.setOrCreate<string>('currentGroupName', group.name);
    AppStorage.setOrCreate<string>('currentGroupInvitationCode', group.invitationCode ? group.invitationCode : '');
    if (user) {
      AppStorage.setOrCreate<string>('currentUserId', user.id);
      AppStorage.setOrCreate<string>('currentUserName', user.name);
    }

    const nextGroups: Group[] = [];
    for (let i = 0; i < this.groups.length; i++) {
      const source = this.groups[i];
      if (source.id === group.id) {
        const merged: Group = {
          id: source.id,
          name: source.name,
          ownerId: source.ownerId,
          memberCount: source.memberCount,
          createdAt: source.createdAt,
          updatedAt: source.updatedAt,
          unreadCount: 0
        };
        if (source.description !== undefined) {
          merged.description = source.description;
        }
        if (source.invitationCode !== undefined) {
          merged.invitationCode = source.invitationCode;
        }
        if (source.avatar !== undefined) {
          merged.avatar = source.avatar;
        }
        if (source.lastMessage !== undefined) {
          merged.lastMessage = source.lastMessage;
        }
        if (source.lastMessageTime !== undefined) {
          merged.lastMessageTime = source.lastMessageTime;
        }
        nextGroups.push(merged);
      } else {
        nextGroups.push(source);
      }
    }
    this.groups = nextGroups;

    appRouter.goToChat(group.id, group.name);
  }

  goToSettings(): void {
    appRouter.goToSettings();
  }

  goToTodoPage(): void {
    appRouter.goToTodo();
  }

  async logout(): Promise<void> {
    await this.appStore.logout();
    appRouter.goToLogin();
  }

  private openCreateSheet(): void {
    this.actionSheetMode = 'create';
    this.newGroupName = '';
    this.showActionSheet = true;
  }

  private openJoinSheet(): void {
    this.actionSheetMode = 'join';
    this.showActionSheet = true;
  }

  private showToast(message: string): void {
    try {
      promptAction.showToast({
        message: message,
        duration: 1200
      });
    } catch (_) {
      // ignore
    }
  }

  build() {
    Column() {
      // 顶部导航栏（对齐 Android：用户信息 + 快捷操作）
      Row() {
        Column() {
          Text('SILK')
            .fontSize(24)
            .fontWeight(FontWeight.Bold)
            .fontColor(Color.White)
            .letterSpacing(4)
          Text(this.appStore.currentUser?.name || '')
            .fontSize(13)
            .fontColor('#FDF8F0')
            .opacity(0.92)
            .margin({ top: 2 })
        }

        Blank()

        Button(this.isOpeningSilkChat ? '连接中' : 'Silk')
          .height(32)
          .fontSize(12)
          .backgroundColor('#337BA8C9')
          .fontColor(Color.White)
          .margin({ right: 8 })
          .enabled(!this.isOpeningSilkChat)
          .onClick(() => {
            void this.openSilkPrivateChat();
          })

        Button('待办')
          .height(32)
          .fontSize(12)
          .backgroundColor('#33FFFFFF')
          .fontColor(Color.White)
          .margin({ right: 8 })
          .onClick(() => this.goToTodoPage())

        Button('设置')
          .height(32)
          .fontSize(12)
          .backgroundColor('#33FFFFFF')
          .fontColor(Color.White)
          .margin({ right: 0 })
          .onClick(() => this.goToSettings())
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      .linearGradient({
        angle: 90,
        colors: [
          [SilkColors.primary, 0],
          [SilkColors.primaryDark, 1]
        ]
      })

      Row() {
        Button('创建项目')
          .layoutWeight(1)
          .height(40)
          .fontSize(13)
          .backgroundColor(SilkColors.primary)
          .fontColor(Color.White)
          .onClick(() => this.openCreateSheet())

        Button('加入项目')
          .layoutWeight(1)
          .height(40)
          .fontSize(13)
          .margin({ left: 8, right: 8 })
          .backgroundColor(SilkColors.surface)
          .fontColor(SilkColors.textPrimary)
          .onClick(() => this.openJoinSheet())

        if (!this.isLeaveMode) {
          Button('退出项目')
            .layoutWeight(1)
            .height(40)
            .fontSize(13)
            .backgroundColor('#FFF7E6')
            .fontColor('#D48806')
            .onClick(() => this.enterLeaveMode())
        } else {
          Button(this.isLeavingGroups ? '处理中...' : `确认退出(${this.selectedLeaveGroupIds.length})`)
            .layoutWeight(1)
            .height(40)
            .fontSize(13)
            .backgroundColor(this.selectedLeaveGroupIds.length > 0 ? SilkColors.error : '#FFCCC7')
            .fontColor(Color.White)
            .enabled(!this.isLeavingGroups && this.selectedLeaveGroupIds.length > 0)
            .opacity((!this.isLeavingGroups && this.selectedLeaveGroupIds.length > 0) ? 1 : 0.6)
            .onClick(() => {
              void this.confirmLeaveSelectedGroups();
            })
        }
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 10, bottom: 10 })
      .backgroundColor(SilkColors.surface)

      if (this.isLeaveMode) {
        Row() {
          Text(`已选择 ${this.selectedLeaveGroupIds.length} 个项目，点击项目可勾选/取消`)
            .fontSize(12)
            .fontColor('#D48806')
            .layoutWeight(1)
          Text('取消')
            .fontSize(12)
            .fontColor(SilkColors.primary)
            .onClick(() => this.exitLeaveMode())
        }
        .width('100%')
        .padding({ left: 16, right: 16, top: 6, bottom: 6 })
        .backgroundColor('#FFFBE6')
      } else {
        Row() {
          Button('退出登录')
            .width('100%')
            .height(40)
            .fontSize(13)
            .backgroundColor('#FFF2F0')
            .fontColor(SilkColors.error)
            .onClick(() => {
              void this.logout();
            })
        }
        .width('100%')
        .padding({ left: 16, right: 16, top: 0, bottom: 10 })
        .backgroundColor(SilkColors.surface)
      }

      Divider().color(SilkColors.border)

      if (this.isLoading) {
        Column() {
          LoadingIndicator()
        }
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
      } else if (this.groups.length === 0) {
        Column() {
          Text('您还没有任何项目')
            .fontSize(18)
            .fontColor(SilkColors.textSecondary)
          Text('点击上方按钮创建或加入项目')
            .fontSize(14)
            .fontColor(SilkColors.textLight)
            .margin({ top: 8 })
        }
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
      } else {
        List({ space: 8 }) {
          ForEach(this.groups, (group: Group) => {
            ListItem() {
              GroupCard({
                group: group,
                onGroupClick: (selectedGroup: Group): void => {
                  void this.enterChat(selectedGroup);
                }
              })
              .border({
                width: this.isGroupSelectedForLeave(group.id) ? 2 : 0,
                color: this.isGroupSelectedForLeave(group.id) ? SilkColors.error : SilkColors.surface
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
          .padding(10)
          .width('100%')
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(SilkColors.background)
    .bindSheet($$this.showActionSheet, this.buildActionSheet(), {
      height: 240,
      backgroundColor: SilkColors.surface
    })
  }

  @Builder
  buildActionSheet() {
    if (this.actionSheetMode === 'join') {
      this.buildJoinSheet();
    } else {
      this.buildCreateSheet();
    }
  }

  @Builder
  buildCreateSheet() {
    Column() {
      Text('创建新项目')
        .fontSize(18)
        .fontWeight(FontWeight.Bold)
        .fontColor(SilkColors.textPrimary)
        .margin({ bottom: 16 })

      TextInput({ placeholder: '项目名称', text: $$this.newGroupName })
        .width('100%')
        .height(48)
        .backgroundColor(SilkColors.background)
        .onChange((value: string) => this.newGroupName = value)

      Row() {
        Button('取消')
          .width('45%')
          .backgroundColor(SilkColors.surface)
          .fontColor(SilkColors.textSecondary)
          .onClick(() => this.showActionSheet = false)

        Button(this.isCreatingGroup ? '创建中...' : '创建')
          .width('45%')
          .backgroundColor(SilkColors.primary)
          .fontColor(Color.White)
          .enabled(!this.isCreatingGroup && this.newGroupName.trim().length > 0)
          .opacity((!this.isCreatingGroup && this.newGroupName.trim().length > 0) ? 1 : 0.6)
          .onClick(() => {
            void this.createGroup();
          })
      }
      .width('100%')
      .justifyContent(FlexAlign.SpaceBetween)
      .margin({ top: 16 })
    }
    .width('100%')
    .padding(16)
  }

  @Builder
  buildJoinSheet() {
    Column() {
      Text('加入项目')
        .fontSize(18)
        .fontWeight(FontWeight.Bold)
        .fontColor(SilkColors.textPrimary)
        .margin({ bottom: 16 })

      TextInput({ placeholder: '请输入 6 位邀请码', text: $$this.joinInvitationCode })
        .width('100%')
        .height(48)
        .maxLength(6)
        .backgroundColor(SilkColors.background)
        .onChange((value: string) => {
          this.joinInvitationCode = value.toUpperCase().trim();
        })

      Row() {
        Button('取消')
          .width('45%')
          .backgroundColor(SilkColors.surface)
          .fontColor(SilkColors.textSecondary)
          .onClick(() => this.showActionSheet = false)

        Button(this.isJoiningGroup ? '加入中...' : '加入')
          .width('45%')
          .backgroundColor(SilkColors.primary)
          .fontColor(Color.White)
          .enabled(!this.isJoiningGroup && this.joinInvitationCode.trim().length === 6)
          .opacity((!this.isJoiningGroup && this.joinInvitationCode.trim().length === 6) ? 1 : 0.6)
          .onClick(() => {
            void this.joinGroupByCode();
          })
      }
      .width('100%')
      .justifyContent(FlexAlign.SpaceBetween)
      .margin({ top: 16 })
    }
    .width('100%')
    .padding(16)
  }
}
