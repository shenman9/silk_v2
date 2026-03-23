import { Message } from '../models/Message';
import { chatStore as globalChatStore, ConnectionState } from '../stores/ChatStore';
import { AppStore, appStore as globalAppStore } from '../stores/AppStore';
import { ChatStore } from '../stores/ChatStore';
import { SilkColors } from '../common/Theme';
import { MessageBubble } from '../components/MessageBubble';
import { ChatInput } from '../components/ChatInput';
import { LoadingIndicator } from '../components/LoadingIndicator';
import { router as appRouter } from '../common/Router';

@Component
export struct ChatPage {
  @State chatStore: ChatStore = globalChatStore;
  @State appStore: AppStore = globalAppStore;
  @State messageInput: string = '';
  
  private scroller: Scroller = new Scroller();
  private groupId: string = '';
  private groupName: string = '';

  aboutToAppear(): void {
    const currentGroup = this.appStore.currentGroup;
    this.groupId = currentGroup ? currentGroup.id : 'default_room';
    this.groupName = currentGroup ? currentGroup.name : '聊天室';
    this.connectChat();
  }

  aboutToDisappear(): void {
    this.disconnectChat();
  }

  private connectChat(): void {
    const user = this.appStore.currentUser;
    if (!user) {
      appRouter.goToLogin();
      return;
    }

    this.chatStore.onMessagesChanged = (): void => {
      this.scrollToBottom();
    };
    this.chatStore.connect(user.id, user.name, this.groupId);
  }

  private disconnectChat(): void {
    this.chatStore.disconnect();
    this.chatStore.onMessagesChanged = null;
  }

  private sendMessage(message: string): void {
    if (!message.trim()) {
      return;
    }
    this.chatStore.sendMessage(message);
    this.scrollToBottom();
  }

  private scrollToBottom(): void {
    setTimeout((): void => {
      this.scroller.scrollEdge(Edge.Bottom);
    }, 80);
  }

  build() {
    Column() {
      // 顶部栏
      this.buildHeader()
      
      // 连接状态提示
      if (this.chatStore.connectionState !== ConnectionState.CONNECTED) {
        this.buildConnectionStatus()
      }
      
      // 消息列表
      this.buildMessageList()
      
      // 输入区域
      this.buildInputArea()
    }
    .width('100%')
    .height('100%')
    .backgroundColor(SilkColors.background)
  }

  @Builder
  buildHeader() {
    Row() {
      // 返回按钮
      Text('<')
        .fontSize(22)
        .fontColor(SilkColors.textPrimary)
        .margin({ left: 16 })
        .onClick((): void => {
          appRouter.goToGroupList();
        })
      
      // 群组名称
      Text(this.groupName)
        .fontSize(18)
        .fontWeight(FontWeight.Medium)
        .fontColor(SilkColors.textPrimary)
        .margin({ left: 12 })
        .layoutWeight(1)
      
      // 设置按钮
      Text('⚙')
        .fontSize(20)
        .fontColor(SilkColors.textSecondary)
        .margin({ right: 16 })
        .onClick((): void => {
          appRouter.goToSettings();
        })
    }
    .width('100%')
    .height(56)
    .backgroundColor(SilkColors.surface)
  }

  @Builder
  buildConnectionStatus() {
    Row() {
      LoadingIndicator()
        .width(16)
        .height(16)
      
      Text(this.chatStore.connectionState === ConnectionState.CONNECTING ? '连接中...' : '已断开')
        .fontSize(12)
        .fontColor(SilkColors.textSecondary)
        .margin({ left: 8 })
    }
    .width('100%')
    .height(32)
    .justifyContent(FlexAlign.Center)
    .backgroundColor('#FDF3E0')
  }

  @Builder
  buildMessageList() {
    List({ scroller: this.scroller }) {
      ForEach(this.chatStore.messages, (message: Message) => {
        ListItem() {
          MessageBubble({ message: message })
        }
        .margin({ top: 4, bottom: 4 })
      }, (message: Message) => message.id)
    }
    .width('100%')
    .layoutWeight(1)
    .padding({ left: 12, right: 12, top: 8, bottom: 8 })
    .edgeEffect(EdgeEffect.Spring)
  }

  @Builder
  buildInputArea() {
    ChatInput({
      messageText: $messageInput,
      placeholder: '输入消息...',
      onSend: (message: string): void => this.sendMessage(message)
    })
  }
}
