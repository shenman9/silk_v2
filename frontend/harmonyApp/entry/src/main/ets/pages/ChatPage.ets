/**
 * ChatPage - 聊天页面
 * 使用 ChatStore + WebSocket 进行实时聊天
 */
import { Message } from '../models/Message';
import { Group } from '../models/Group';
import { MessageBubble } from '../components';
import { chatStore as globalChatStore, ConnectionState } from '../stores/ChatStore';
import { appStore as globalAppStore } from '../stores/AppStore';
import { SilkColors } from '../common/Theme';
import { router as appRouter } from '../common/Router';
import { apiClient as globalApiClient } from '../api/ApiClient';
import pasteboard from '@ohos.pasteboard';
import promptAction from '@ohos.promptAction';

@Component
export struct ChatPage {
  @State private connectionState: ConnectionState = ConnectionState.DISCONNECTED;
  @State private messageInput: string = '';
  @State private groupName: string = '聊天室';
  @State private messages: Message[] = [];
  @State private showBottomSheet: boolean = false;
  @State private bottomSheetMode: string = 'actions';
  @State private forwardTargets: Group[] = [];
  @State private forwardingContent: string = '';
  @State private isForwarding: boolean = false;
  @State private isLoadingForwardTargets: boolean = false;
  @State private forwardingError: string = '';
  @State private messageStatusMap: Record<string, string> = {};
  @State private selectedActionMessage: Message | null = null;
  @State private isSelectionMode: boolean = false;
  @State private selectedMessageIds: string[] = [];

  private groupId: string = 'default_room';
  private userId: string = '';
  private userName: string = '';
  private isInitialized: boolean = false;
  private readonly apiClient = globalApiClient;

  aboutToAppear(): void {
    if (this.isInitialized) {
      return;
    }
    this.isInitialized = true;
    setTimeout((): void => {
      this.initChat();
    }, 50);
  }

  aboutToDisappear(): void {
    globalChatStore.onMessagesChanged = null;
    globalChatStore.onConnectionStateChanged = null;
    globalChatStore.onError = null;
    globalChatStore.disconnect();
    this.isInitialized = false;
  }

  private initChat(): void {
    const storedUserId = AppStorage.get<string>('currentUserId');
    const storedUserName = AppStorage.get<string>('currentUserName');
    const storedGroupId = AppStorage.get<string>('currentGroupId');
    const storedGroupName = AppStorage.get<string>('currentGroupName');

    const currentUser = globalAppStore.currentUser;
    const currentGroup = globalAppStore.currentGroup;

    this.userId = storedUserId ? storedUserId : (currentUser ? currentUser.id : '');
    this.userName = storedUserName ? storedUserName : (currentUser ? currentUser.name : '');
    this.groupId = storedGroupId ? storedGroupId : (currentGroup ? currentGroup.id : 'default_room');
    this.groupName = storedGroupName ? storedGroupName : (currentGroup ? currentGroup.name : '聊天室');

    if (this.userId === '') {
      appRouter.goToLogin();
      return;
    }

    AppStorage.setOrCreate<string>('currentUserId', this.userId);
    AppStorage.setOrCreate<string>('currentUserName', this.userName);
    AppStorage.setOrCreate<string>('currentGroupId', this.groupId);
    AppStorage.setOrCreate<string>('currentGroupName', this.groupName);

    globalChatStore.onMessagesChanged = (): void => {
      this.syncMessages();
    };
    globalChatStore.onConnectionStateChanged = (): void => {
      this.connectionState = globalChatStore.connectionState;
      if (this.connectionState === ConnectionState.DISCONNECTED) {
        this.markAllSendingMessagesFailed();
      }
    };
    globalChatStore.onError = (error: string): void => {
      console.error(`聊天连接错误: ${error}`);
      this.showToast(error);
    };

    globalChatStore.clearMessages();
    this.connectionState = ConnectionState.CONNECTING;
    globalChatStore.connect(this.userId, this.userName, this.groupId);
  }

  private syncMessages(): void {
    const source: Message[] = globalChatStore.messages;
    const result: Message[] = [];
    const confirmedOwnIds: string[] = [];
    for (let i = 0; i < source.length; i++) {
      const item = source[i];
      result.push(item);
      if (item.userId === this.userId) {
        confirmedOwnIds.push(item.id);
      }
    }

    const statusMessages = globalChatStore.statusMessages;
    for (let i = 0; i < statusMessages.length; i++) {
      result.push(statusMessages[i]);
    }

    const transient = globalChatStore.transientMessage;
    if (transient) {
      result.push(transient);
    }

    this.messages = result;
    this.markMessagesAsSent(confirmedOwnIds);
  }

  private handleSend(message: string): void {
    const content = message.trim();
    if (content === '') {
      return;
    }
    const sentMessage = globalChatStore.sendMessage(content);
    if (!sentMessage) {
      return;
    }
    this.setMessageStatus(sentMessage.id, 'sending');
    // Harmony 端目前无独立 ACK 事件，短延时后切换为已发送，断线时会转失败
    setTimeout(() => {
      if (this.getMessageStatus(sentMessage.id) === 'sending') {
        this.setMessageStatus(sentMessage.id, 'sent');
      }
    }, 1200);
  }

  private getMessageStatus(messageId: string): string {
    const value = this.messageStatusMap[messageId];
    return value ? value : 'sent';
  }

  private setMessageStatus(messageId: string, status: string): void {
    const next: Record<string, string> = {};
    const currentKeys: string[] = Object.keys(this.messageStatusMap);
    for (let i = 0; i < currentKeys.length; i++) {
      const id = currentKeys[i];
      next[id] = this.messageStatusMap[id];
    }
    next[messageId] = status;
    this.messageStatusMap = next;
  }

  private markAllSendingMessagesFailed(): void {
    const next: Record<string, string> = {};
    const currentKeys: string[] = Object.keys(this.messageStatusMap);
    for (let i = 0; i < currentKeys.length; i++) {
      const id = currentKeys[i];
      next[id] = this.messageStatusMap[id];
    }
    let changed = false;
    const nextKeys: string[] = Object.keys(next);
    for (let i = 0; i < nextKeys.length; i++) {
      const id = nextKeys[i];
      if (next[id] === 'sending') {
        next[id] = 'failed';
        changed = true;
      }
    }
    if (changed) {
      this.messageStatusMap = next;
    }
  }

  private retryMessage(message: Message): void {
    const ok = globalChatStore.retryMessage(message);
    if (!ok) {
      this.setMessageStatus(message.id, 'failed');
      return;
    }
    this.setMessageStatus(message.id, 'sending');
    setTimeout(() => {
      if (this.getMessageStatus(message.id) === 'sending') {
        this.setMessageStatus(message.id, 'sent');
      }
    }, 1200);
  }

  private openMessageActions(message: Message): void {
    this.selectedActionMessage = message;
    this.bottomSheetMode = 'actions';
    this.showBottomSheet = true;
  }

  private handleMessageLongPress(message: Message): void {
    if (this.isSelectionMode) {
      this.toggleSelectMessage(message.id);
      return;
    }
    this.openMessageActions(message);
  }

  private startForwardFromActions(message: Message): void {
    this.selectedActionMessage = message;
    this.handleForward(message);
  }

  private markMessagesAsSent(messageIds: string[]): void {
    if (messageIds.length === 0) {
      return;
    }
    const next: Record<string, string> = {};
    const currentKeys: string[] = Object.keys(this.messageStatusMap);
    for (let i = 0; i < currentKeys.length; i++) {
      const id = currentKeys[i];
      next[id] = this.messageStatusMap[id];
    }

    let changed = false;
    for (let i = 0; i < messageIds.length; i++) {
      const id = messageIds[i];
      if (next[id] && next[id] !== 'sent') {
        next[id] = 'sent';
        changed = true;
      }
    }

    if (changed) {
      this.messageStatusMap = next;
    }
  }

  private sanitizeForInput(text: string): string {
    const source = text ? text : '';
    // 去掉可能导致输入框异常的控制字符（保留换行）
    const cleaned = source.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '');
    const maxLength = 800;
    if (cleaned.length > maxLength) {
      return `${cleaned.substring(0, maxLength)}...`;
    }
    return cleaned;
  }

  private showToast(message: string): void {
    try {
      promptAction.showToast({
        message: message,
        duration: 1500
      });
    } catch (error) {
      console.error(`Toast failed: ${error}`);
    }
  }

  private handleCopy(content: string): void {
    const text = this.sanitizeForInput(content);
    try {
      const systemPasteboard = pasteboard.getSystemPasteboard();
      const pasteData = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text);
      systemPasteboard.setData(pasteData).then(() => {
        this.showToast('已复制到剪贴板');
      }).catch((error: Error) => {
        console.error(`Copy failed: ${error.message}`);
        this.showToast('复制失败');
      });
    } catch (error) {
      console.error(`Copy exception: ${error}`);
      this.showToast('复制失败');
    }
  }

  private handleForward(message: Message): void {
    this.bottomSheetMode = 'forward';
    const content = message.content ? message.content : '';
    const payload = this.sanitizeForInput(`📨 转发自【${this.groupName}】:\n\n${content}`);
    this.forwardingContent = payload;
    this.forwardingError = '';
    this.isLoadingForwardTargets = true;
    this.forwardTargets = [];
    this.showBottomSheet = true;
    this.loadForwardTargets();
  }

  private handleShare(message: Message): void {
    // 与“复制”区分：分享表示走系统分享能力（当前先保留入口与提示）
    this.showToast('系统分享功能开发中');
  }

  private toggleSelectMessage(messageId: string): void {
    const next: string[] = [];
    let exists = false;
    for (let i = 0; i < this.selectedMessageIds.length; i++) {
      const id = this.selectedMessageIds[i];
      if (id === messageId) {
        exists = true;
      } else {
        next.push(id);
      }
    }
    if (!exists) {
      next.push(messageId);
    }
    this.selectedMessageIds = next;
    this.showToast(`已选择 ${this.selectedMessageIds.length} 条`);
  }

  private isMessageSelected(messageId: string): boolean {
    for (let i = 0; i < this.selectedMessageIds.length; i++) {
      if (this.selectedMessageIds[i] === messageId) {
        return true;
      }
    }
    return false;
  }

  private getSelectedMessages(): Message[] {
    const result: Message[] = [];
    for (let i = 0; i < this.messages.length; i++) {
      const message = this.messages[i];
      if (this.isMessageSelected(message.id)) {
        result.push(message);
      }
    }
    return result;
  }

  private handleBatchCopy(): void {
    const selected = this.getSelectedMessages();
    if (selected.length === 0) {
      this.showToast('请先选择消息');
      return;
    }
    let merged = '';
    for (let i = 0; i < selected.length; i++) {
      const item = selected[i];
      const name = item.userName ? item.userName : '用户';
      const content = item.content ? item.content : '';
      if (merged !== '') {
        merged += '\n\n';
      }
      merged += `${name}: ${content}`;
    }
    this.handleCopy(merged);
  }

  private handleBatchForward(): void {
    const selected = this.getSelectedMessages();
    if (selected.length === 0) {
      this.showToast('请先选择消息');
      return;
    }
    let merged = '';
    for (let i = 0; i < selected.length; i++) {
      const item = selected[i];
      const name = item.userName ? item.userName : '用户';
      const content = item.content ? item.content : '';
      if (merged !== '') {
        merged += '\n\n';
      }
      merged += `${name}: ${content}`;
    }
    this.bottomSheetMode = 'forward';
    this.forwardingContent = this.sanitizeForInput(`📨 批量转发自【${this.groupName}】:\n\n${merged}`);
    this.forwardingError = '';
    this.isLoadingForwardTargets = true;
    this.forwardTargets = [];
    this.showBottomSheet = true;
    this.loadForwardTargets();
  }

  private openMultiSelectEntry(): void {
    this.isSelectionMode = true;
    if (this.selectedActionMessage) {
      this.toggleSelectMessage(this.selectedActionMessage.id);
    } else {
      this.showToast('已进入多选模式');
    }
  }

  private exitMultiSelectMode(): void {
    this.isSelectionMode = false;
    this.selectedMessageIds = [];
  }

  private async loadForwardTargets(): Promise<void> {
    if (this.userId === '') {
      this.isLoadingForwardTargets = false;
      this.showToast('用户未登录');
      return;
    }

    try {
      const response = await this.apiClient.getGroups(this.userId);
      const groups = response.data ? response.data : [];
      const result: Group[] = [];
      for (let i = 0; i < groups.length; i++) {
        const group = groups[i];
        if (group.id !== this.groupId) {
          result.push(group);
        }
      }
      this.forwardTargets = result;
      this.isLoadingForwardTargets = false;
      if (result.length === 0) {
        this.forwardingError = '暂无可转发群组';
      }
    } catch (error) {
      console.error(`Load forward targets failed: ${error}`);
      this.isLoadingForwardTargets = false;
      this.forwardingError = '加载群组失败';
      this.showToast('加载群组失败');
    }
  }

  private async forwardToGroup(group: Group): Promise<void> {
    if (this.isForwarding || this.forwardingContent.trim() === '') {
      return;
    }

    this.isForwarding = true;
    this.forwardingError = '';
    try {
      const response = await this.apiClient.sendMessageToGroup(
        group.id,
        this.userId,
        this.userName,
        this.forwardingContent
      );
      if (response.success) {
        this.showBottomSheet = false;
        this.showToast(`已转发到 ${group.name}`);
      } else {
        this.forwardingError = response.message ? response.message : '转发失败';
      }
    } catch (error) {
      console.error(`Forward failed: ${error}`);
      this.forwardingError = '转发失败';
    } finally {
      this.isForwarding = false;
    }
  }

  private reconnect(): void {
    if (this.userId === '') {
      return;
    }
    globalChatStore.clearMessages();
    this.connectionState = ConnectionState.CONNECTING;
    globalChatStore.connect(this.userId, this.userName, this.groupId);
  }

  build() {
    Column() {
      // 顶部导航栏
      Row() {
        Text('<')
          .fontSize(22)
          .fontColor(SilkColors.textPrimary)
          .margin({ left: 16 })
          .onClick((): void => {
            appRouter.goToGroupList();
          })

        Text(this.groupName)
          .fontSize(18)
          .fontWeight(FontWeight.Medium)
          .fontColor(SilkColors.textPrimary)
          .margin({ left: 12 })
          .layoutWeight(1)
      }
      .width('100%')
      .height(56)
      .backgroundColor(SilkColors.surface)

      // 连接状态条
      if (this.connectionState !== ConnectionState.CONNECTED) {
        Row() {
          LoadingProgress()
            .width(16)
            .height(16)
            .color(SilkColors.primary)

          Text(this.connectionState === ConnectionState.CONNECTING ? '连接中...' : '连接断开')
            .fontSize(12)
            .fontColor(SilkColors.textSecondary)
            .margin({ left: 8 })

          if (this.connectionState === ConnectionState.DISCONNECTED) {
            Button('重连')
              .height(24)
              .fontSize(12)
              .backgroundColor(SilkColors.primary)
              .fontColor(SilkColors.onPrimary)
              .margin({ left: 12 })
              .onClick((): void => this.reconnect())
          }
        }
        .width('100%')
        .height(32)
        .justifyContent(FlexAlign.Center)
        .backgroundColor('#FDF3E0')
      }

      // 消息区域
      if (this.isSelectionMode) {
        Row() {
          Text(`多选模式（已选 ${this.selectedMessageIds.length} 条）`)
            .fontSize(12)
            .fontColor(SilkColors.textPrimary)
          Blank()
          Text('退出')
            .fontSize(12)
            .fontColor(SilkColors.primary)
            .onClick(() => {
              this.exitMultiSelectMode();
            })
        }
        .width('100%')
        .padding({ left: 12, right: 12, top: 6, bottom: 6 })
        .backgroundColor('#F3EFE6')
      }

      if (this.messages.length === 0) {
        Column() {
          Text('暂无消息，开始聊天吧')
            .fontSize(14)
            .fontColor(SilkColors.textSecondary)
        }
        .width('100%')
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
      } else {
        List({ space: 4 }) {
          ForEach(this.messages, (message: Message) => {
            ListItem() {
              Row() {
                if (this.isSelectionMode) {
                  Text(this.isMessageSelected(message.id) ? '☑' : '☐')
                    .fontSize(18)
                    .fontColor(this.isMessageSelected(message.id) ? SilkColors.primary : SilkColors.textLight)
                    .margin({ left: 8, right: 4 })
                    .onClick(() => {
                      this.toggleSelectMessage(message.id);
                    })
                }
                MessageBubble({
                  message: message,
                  isOwn: message.userId === this.userId,
                  currentUserId: this.userId,
                  actionsEnabled: !this.isSelectionMode,
                  messageStatus: this.getMessageStatus(message.id),
                  onCopy: (content: string): void => this.handleCopy(content),
                  onForward: (msg: Message): void => this.handleForward(msg),
                  onRetry: (msg: Message): void => this.retryMessage(msg),
                  onOpenActions: (msg: Message): void => this.openMessageActions(msg)
                })
              }
              .width('100%')
              .onClick(() => {
                if (this.isSelectionMode) {
                  this.toggleSelectMessage(message.id);
                }
              })
              .gesture(
                LongPressGesture({ repeat: false })
                  .onAction(() => {
                    this.handleMessageLongPress(message);
                  })
              )
            }
          }, (message: Message) => `${message.id}_${message.timestamp}`)
        }
        .width('100%')
        .layoutWeight(1)
        .padding({ top: 8, bottom: 8 })
      }

      // 输入区域
      Row() {
        TextInput({ placeholder: '输入消息...', text: $$this.messageInput })
          .layoutWeight(1)
          .height(44)
          .backgroundColor(SilkColors.surface)
          .borderRadius(22)
          .padding({ left: 16, right: 16 })
          .fontColor(SilkColors.textPrimary)
          .placeholderColor(SilkColors.textLight)
          .fontSize(16)

        Button('发送')
          .width(60)
          .height(44)
          .backgroundColor(this.messageInput.trim() ? SilkColors.primary : SilkColors.border)
          .fontColor(SilkColors.onPrimary)
          .borderRadius(22)
          .margin({ left: 8 })
          .enabled(this.messageInput.trim().length > 0)
          .onClick((): void => {
            this.handleSend(this.messageInput);
            this.messageInput = '';
          })
      }
      .width('100%')
      .padding({ left: 12, right: 12, top: 8, bottom: 8 })
      .backgroundColor(SilkColors.background)

      if (this.isSelectionMode) {
        Row() {
          Button(`复制(${this.selectedMessageIds.length})`)
            .height(36)
            .fontSize(13)
            .backgroundColor(SilkColors.surfaceVariant)
            .fontColor(SilkColors.textPrimary)
            .enabled(this.selectedMessageIds.length > 0)
            .onClick(() => {
              this.handleBatchCopy();
            })

          Button(`转发(${this.selectedMessageIds.length})`)
            .height(36)
            .fontSize(13)
            .backgroundColor(SilkColors.primary)
            .fontColor(SilkColors.onPrimary)
            .margin({ left: 8 })
            .enabled(this.selectedMessageIds.length > 0)
            .onClick(() => {
              this.handleBatchForward();
            })
        }
        .width('100%')
        .padding({ left: 12, right: 12, bottom: 10 })
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(SilkColors.background)
    .bindSheet($$this.showBottomSheet, this.buildBottomSheet(), {
      height: this.bottomSheetMode === 'forward' ? '55%' : '36%',
      backgroundColor: SilkColors.surface
    })
  }

  @Builder
  private buildBottomSheet() {
    if (this.bottomSheetMode === 'forward') {
      this.buildForwardSheet();
    } else {
      this.buildMessageActionsSheet();
    }
  }

  @Builder
  private buildForwardSheet() {
    Column() {
      Text('选择转发目标群组')
        .fontSize(18)
        .fontWeight(FontWeight.Medium)
        .fontColor(SilkColors.textPrimary)
        .margin({ bottom: 12 })

      if (this.forwardingContent) {
        Text(this.forwardingContent)
          .fontSize(12)
          .fontColor(SilkColors.textSecondary)
          .maxLines(3)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .padding(10)
          .backgroundColor(SilkColors.background)
          .borderRadius(8)
          .margin({ bottom: 12 })
      }

      if (this.forwardTargets.length === 0) {
        if (this.isLoadingForwardTargets) {
          Row() {
            LoadingProgress()
              .width(16)
              .height(16)
              .color(SilkColors.primary)
            Text('加载可转发群组...')
              .fontSize(14)
              .fontColor(SilkColors.textSecondary)
              .margin({ left: 8 })
          }
          .margin({ top: 16 })
        } else {
          Text('暂无可转发的群组')
            .fontSize(14)
            .fontColor(SilkColors.textSecondary)
            .margin({ top: 16 })
        }
      } else {
        List({ space: 8 }) {
          ForEach(this.forwardTargets, (group: Group) => {
            ListItem() {
              Row() {
                Text(group.name)
                  .fontSize(15)
                  .fontColor(SilkColors.textPrimary)
                  .layoutWeight(1)

                if (this.isForwarding) {
                  LoadingProgress()
                    .width(16)
                    .height(16)
                    .color(SilkColors.primary)
                } else {
                  Text('发送')
                    .fontSize(13)
                    .fontColor(SilkColors.primary)
                }
              }
              .width('100%')
              .padding(12)
              .backgroundColor(SilkColors.background)
              .borderRadius(8)
              .onClick(() => {
                this.forwardToGroup(group);
              })
            }
          }, (group: Group) => group.id)
        }
        .layoutWeight(1)
        .width('100%')
      }

      if (this.forwardingError) {
        Text(this.forwardingError)
          .fontSize(12)
          .fontColor(SilkColors.error)
          .margin({ top: 8, bottom: 8 })
      }

      Button('取消')
        .width('100%')
        .height(42)
        .backgroundColor(SilkColors.surfaceVariant)
        .fontColor(SilkColors.textSecondary)
        .margin({ top: 8 })
        .onClick(() => {
          this.showBottomSheet = false;
        })
    }
    .width('100%')
    .height('100%')
    .padding(16)
  }

  @Builder
  private buildMessageActionsSheet() {
    Column() {
      Text('消息操作')
        .fontSize(18)
        .fontWeight(FontWeight.Medium)
        .fontColor(SilkColors.textPrimary)
        .margin({ bottom: 12 })

      if (this.selectedActionMessage) {
        Text(this.selectedActionMessage.content ? this.selectedActionMessage.content : '')
          .fontSize(12)
          .fontColor(SilkColors.textSecondary)
          .maxLines(3)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .padding(10)
          .backgroundColor(SilkColors.background)
          .borderRadius(8)
          .margin({ bottom: 12 })
      }

      Button('复制')
        .width('100%')
        .height(42)
        .backgroundColor(SilkColors.surfaceVariant)
        .fontColor(SilkColors.textPrimary)
        .onClick(() => {
          if (this.selectedActionMessage) {
            this.handleCopy(this.selectedActionMessage.content ? this.selectedActionMessage.content : '');
          }
          this.showBottomSheet = false;
        })

      Button('转发')
        .width('100%')
        .height(42)
        .backgroundColor(SilkColors.surfaceVariant)
        .fontColor(SilkColors.textPrimary)
        .margin({ top: 8 })
        .onClick(() => {
          if (this.selectedActionMessage) {
            this.startForwardFromActions(this.selectedActionMessage);
            return;
          }
          this.showBottomSheet = false;
          this.selectedActionMessage = null;
        })

      Button('分享')
        .width('100%')
        .height(42)
        .backgroundColor(SilkColors.surfaceVariant)
        .fontColor(SilkColors.textPrimary)
        .margin({ top: 8 })
        .onClick(() => {
          if (this.selectedActionMessage) {
            this.handleShare(this.selectedActionMessage);
          }
          this.showBottomSheet = false;
          this.selectedActionMessage = null;
        })

      Button('多选')
        .width('100%')
        .height(42)
        .backgroundColor(SilkColors.surfaceVariant)
        .fontColor(SilkColors.textPrimary)
        .margin({ top: 8 })
        .onClick(() => {
          this.openMultiSelectEntry();
          this.showBottomSheet = false;
          this.selectedActionMessage = null;
        })

      Button('取消')
        .width('100%')
        .height(42)
        .backgroundColor(SilkColors.surfaceVariant)
        .fontColor(SilkColors.textSecondary)
        .margin({ top: 8 })
        .onClick(() => {
          this.showBottomSheet = false;
          this.selectedActionMessage = null;
        })
    }
    .width('100%')
    .height('100%')
    .padding(16)
  }
}
