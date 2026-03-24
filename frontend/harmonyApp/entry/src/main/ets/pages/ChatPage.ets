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
  @State private showForwardSheet: boolean = false;
  @State private forwardTargets: Group[] = [];
  @State private forwardingContent: string = '';
  @State private isForwarding: boolean = false;
  @State private forwardingError: string = '';

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
    };
    globalChatStore.onError = (error: string): void => {
      console.error(`聊天连接错误: ${error}`);
    };

    globalChatStore.clearMessages();
    this.connectionState = ConnectionState.CONNECTING;
    globalChatStore.connect(this.userId, this.userName, this.groupId);
  }

  private syncMessages(): void {
    const source: Message[] = globalChatStore.messages;
    const result: Message[] = [];
    for (let i = 0; i < source.length; i++) {
      result.push(source[i]);
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
  }

  private handleSend(message: string): void {
    const content = message.trim();
    if (content === '') {
      return;
    }
    globalChatStore.sendMessage(content);
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
    const content = message.content ? message.content : '';
    const sender = message.userName ? message.userName : '用户';
    const payload = this.sanitizeForInput(`[转发] ${sender}: ${content}`);
    this.forwardingContent = payload;
    this.forwardingError = '';
    this.loadForwardTargets();
  }

  private async loadForwardTargets(): Promise<void> {
    if (this.userId === '') {
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
      this.showForwardSheet = true;
    } catch (error) {
      console.error(`Load forward targets failed: ${error}`);
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
        this.showForwardSheet = false;
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
              MessageBubble({
                message: message,
                isOwn: message.userId === this.userId,
                currentUserId: this.userId,
                onCopy: (content: string): void => this.handleCopy(content),
                onForward: (msg: Message): void => this.handleForward(msg)
              })
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
    }
    .width('100%')
    .height('100%')
    .backgroundColor(SilkColors.background)
    .bindSheet($$this.showForwardSheet, this.buildForwardSheet(), {
      height: '55%',
      backgroundColor: SilkColors.surface
    })
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
        Text('暂无可转发的群组')
          .fontSize(14)
          .fontColor(SilkColors.textSecondary)
          .margin({ top: 16 })
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
          this.showForwardSheet = false;
        })
    }
    .width('100%')
    .height('100%')
    .padding(16)
  }
}
