import { Router } from '@ohos.router';
import { Message, MessageType, MessageCategory } from '../models/Message';
import { ChatStore, ConnectionState } from '../stores/ChatStore';
import { AppStore } from '../stores/AppStore';
import { Theme, SilkColors } from '../common/Theme';
import { MessageBubble } from '../components/MessageBubble';
import { ChatInput } from '../components/ChatInput';
import { LoadingIndicator } from '../components/LoadingIndicator';
import { WebSocketClient } from '../api/WebSocketClient';
import { Constants } from '../common/Constants';

interface ChatPageParams {
  groupId: string;
  groupName: string;
}

@Entry
@Component
struct ChatPage {
  @State chatStore: ChatStore = ChatStore.getInstance();
  @State appStore: AppStore = AppStore.getInstance();
  @State messageInput: string = '';
  @State isLoading: boolean = false;
  @State showError: boolean = false;
  @State errorMessage: string = '';
  
  private scroller: Scroller = new Scroller();
  private webSocketClient: WebSocketClient | null = null;
  private groupId: string = '';
  private groupName: string = '';

  aboutToAppear() {
    const params = Router.getParams() as ChatPageParams;
    if (params) {
      this.groupId = params.groupId || 'default_room';
      this.groupName = params.groupName || '聊天室';
    }
    this.connectWebSocket();
  }

  aboutToDisappear() {
    this.disconnectWebSocket();
  }

  private connectWebSocket() {
    const user = this.appStore.currentUser;
    if (!user) {
      Router.replaceUrl({ url: 'pages/LoginPage' });
      return;
    }

    const wsUrl = `${Constants.WS_BASE_URL}/chat?userId=${user.id}&userName=${encodeURIComponent(user.name)}&groupId=${this.groupId}`;
    
    this.webSocketClient = new WebSocketClient();
    this.webSocketClient.connect(
      wsUrl,
      (message: string) => this.handleIncomingMessage(message),
      () => {
        this.chatStore.setConnectionState(ConnectionState.CONNECTED);
      },
      () => {
        this.chatStore.setConnectionState(ConnectionState.DISCONNECTED);
      },
      (error: string) => {
        this.showError = true;
        this.errorMessage = error;
      }
    );
    this.chatStore.setConnectionState(ConnectionState.CONNECTING);
  }

  private disconnectWebSocket() {
    if (this.webSocketClient) {
      this.webSocketClient.disconnect();
      this.webSocketClient = null;
    }
  }

  private handleIncomingMessage(messageJson: string) {
    try {
      const msg: Message = JSON.parse(messageJson);
      this.chatStore.addMessage(msg);
      
      // 滚动到底部
      setTimeout(() => {
        this.scroller.scrollEdge(Edge.Bottom);
      }, 100);
    } catch (e) {
      console.error('Failed to parse message:', e);
    }
  }

  private async sendMessage() {
    if (!this.messageInput.trim() || !this.webSocketClient) {
      return;
    }

    const user = this.appStore.currentUser;
    if (!user) return;

    const message: Message = {
      id: `${Date.now()}_${Math.floor(Math.random() * 10000)}`,
      userId: user.id,
      userName: user.name,
      content: this.messageInput.trim(),
      timestamp: Date.now(),
      type: MessageType.TEXT,
      isTransient: false,
      category: MessageCategory.NORMAL
    };

    // 立即显示消息
    this.chatStore.addMessage(message);
    
    // 发送到服务器
    this.webSocketClient.send(JSON.stringify(message));
    
    // 清空输入
    this.messageInput = '';
    
    // 滚动到底部
    setTimeout(() => {
      this.scroller.scrollEdge(Edge.Bottom);
    }, 100);
  }

  private async sendAiMessage() {
    if (!this.messageInput.trim() || !this.webSocketClient) {
      return;
    }

    // 先发送用户消息
    await this.sendMessage();
    
    // AI 消息发送后由服务端处理，无需额外操作
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
      Image($r('app.media.ic_back'))
        .width(24)
        .height(24)
        .margin({ left: 16 })
        .onClick(() => Router.back())
      
      // 群组名称
      Text(this.groupName)
        .fontSize(18)
        .fontWeight(FontWeight.Medium)
        .fontColor(SilkColors.textPrimary)
        .margin({ left: 12 })
        .layoutWeight(1)
      
      // 设置按钮
      Image($r('app.media.ic_settings'))
        .width(24)
        .height(24)
        .margin({ right: 16 })
        .onClick(() => {
          Router.pushUrl({
            url: 'pages/SettingsPage',
            params: { groupId: this.groupId }
          });
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
    .backgroundColor(SilkColors.warning.withOpacity(0.1))
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
      placeholder: '输入消息...',
      onSend: () => this.sendMessage(),
      onAiSend: () => this.sendAiMessage()
    })
  }
}
