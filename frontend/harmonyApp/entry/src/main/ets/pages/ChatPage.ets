/**
 * 聊天室页面
 * 对齐 androidApp/ChatScreen.kt
 * 功能: WebSocket 实时消息、@mention 自动补全、消息撤回、成员查看
 */
import { SilkColors } from '../common/SilkColors';
import { Message, MessageType, MessageCategory, createTextMessage } from '../model/MessageModel';
import { WebSocketService } from '../service/WebSocketService';
import { ChatBubble } from '../components/ChatBubble';
import { ChatInput } from '../components/ChatInput';
import { ConnectionStatus } from '../components/ConnectionStatus';
import { loadUser } from '../store/UserStore';
import { User } from '../model/UserModel';
import { GroupMember, GroupMembersResponse } from '../model/ContactModel';
import { getGroupMembers, recallMessage } from '../service/ApiService';

interface ChatParams {
  groupId: string;
  groupName: string;
}

interface MentionItem {
  id: string;
  name: string;
  isSilk: boolean;
}

@Component
export struct ChatPage {
  @Consume('navPathStack') navPathStack: NavPathStack;
  @State messages: Message[] = [];
  @State transientMessage: Message | null = null;
  @State connectionState: string = 'DISCONNECTED';
  @State inputText: string = '';
  @State groupName: string = '';

  private groupId: string = '';
  private currentUser: User | null = null;
  private wsService: WebSocketService = new WebSocketService();
  private listScroller: Scroller = new Scroller();

  // @mention 相关
  @State showMentionMenu: boolean = false;
  @State mentionItems: MentionItem[] = [];
  @State filteredMentionItems: MentionItem[] = [];
  @State mentionSearchText: string = '';
  private mentionStartIndex: number = -1;

  // 消息撤回
  @State recallingIds: string[] = [];
  @State showRecallConfirm: boolean = false;
  @State recallTargetMessage: Message | null = null;
  @State recallMessage_: string = '';

  // AI 等待状态
  @State isWaitingForAI: boolean = false;

  // 成员对话框
  @State showMembersDialog: boolean = false;
  @State members: GroupMember[] = [];
  @State membersLoading: boolean = false;

  aboutToAppear(): void {
    this.currentUser = loadUser();

    // 从导航参数获取群组信息
    const params = this.navPathStack.getParamByName('chat') as object[];
    if (params && params.length > 0) {
      const chatParam = params[0] as ChatParams;
      this.groupId = chatParam.groupId ?? '';
      this.groupName = chatParam.groupName ?? '';
    }

    if (this.currentUser && this.groupId !== '') {
      this.setupWebSocket();
      this.wsService.connect(this.currentUser.id, this.currentUser.fullName, this.groupId);
      this.connectionState = 'CONNECTING';
      this.loadGroupMembers();
    }
  }

  aboutToDisappear(): void {
    this.wsService.disconnect();
  }

  private async loadGroupMembers(): Promise<void> {
    const res: GroupMembersResponse = await getGroupMembers(this.groupId);
    if (res.success && res.members) {
      // 构建 mention 列表
      const items: MentionItem[] = [];
      // 添加 Silk AI
      const silkItem: MentionItem = { id: 'silk', name: 'Silk', isSilk: true };
      items.push(silkItem);
      for (let i = 0; i < res.members.length; i++) {
        const m = res.members[i];
        if (this.currentUser !== null && m.id !== this.currentUser.id) {
          const item: MentionItem = { id: m.id, name: m.fullName, isSilk: false };
          items.push(item);
        }
      }
      this.mentionItems = items;
      this.members = res.members;
    }
  }

  private setupWebSocket(): void {
    this.wsService.onConnected = () => {
      this.connectionState = 'CONNECTED';
    };

    this.wsService.onDisconnected = () => {
      this.connectionState = 'DISCONNECTED';
    };

    this.wsService.onError = (error: string) => {
      console.error('[ChatPage] WebSocket 错误:', error);
    };

    this.wsService.onMessage = (message: Message) => {
      this.handleIncomingMessage(message);
    };
  }

  private handleIncomingMessage(message: Message): void {
    if (message.type === MessageType.RECALL) {
      const idsToRemove = message.content.split(',').map((s: string) => s.trim());
      this.messages = this.messages.filter((m: Message) => !idsToRemove.includes(m.id));
      return;
    }

    if (message.isTransient && message.isIncremental) {
      this.isWaitingForAI = false;
      const existing = this.transientMessage;
      if (existing !== null && existing.userId === message.userId) {
        const updated: Message = {
          id: existing.id,
          userId: existing.userId,
          userName: existing.userName,
          content: existing.content + message.content,
          timestamp: message.timestamp,
          type: existing.type,
          isTransient: true,
          currentStep: message.currentStep,
          totalSteps: message.totalSteps,
          isIncremental: true,
          category: existing.category,
        };
        this.transientMessage = updated;
      } else {
        this.transientMessage = message;
      }
      this.scrollToBottom();
      return;
    }

    if (message.isTransient) {
      this.isWaitingForAI = false;
      this.transientMessage = message;
      this.scrollToBottom();
      return;
    }

    // 普通消息：去重后加入
    const exists = this.messages.some((m: Message) => m.id === message.id);
    if (!exists) {
      this.isWaitingForAI = false;
      this.messages = [...this.messages, message];
      this.transientMessage = null;
      this.scrollToBottom();
    }
  }

  private sendMessage(): void {
    if (!this.currentUser || this.inputText.trim() === '') return;

    const trimmed = this.inputText.trim();

    // 检查是否 @silk
    if (trimmed.toLowerCase().indexOf('@silk') >= 0) {
      this.isWaitingForAI = true;
    }

    const message = createTextMessage(
      this.currentUser.id,
      this.currentUser.fullName,
      trimmed
    );

    this.messages = [...this.messages, message];
    this.scrollToBottom();
    this.wsService.send(JSON.stringify(message));
    this.inputText = '';
    this.showMentionMenu = false;
  }

  private handleInputChange(value: string): void {
    this.inputText = value;

    // 检查 @mention 触发
    const lastAtIndex = value.lastIndexOf('@');
    if (lastAtIndex >= 0) {
      const afterAt = value.substring(lastAtIndex + 1);
      // 如果 @ 后面没有空格，认为正在输入 mention
      if (afterAt.indexOf(' ') < 0) {
        this.mentionStartIndex = lastAtIndex;
        this.mentionSearchText = afterAt.toLowerCase();
        this.filterMentionItems();
        this.showMentionMenu = this.filteredMentionItems.length > 0;
        return;
      }
    }
    this.showMentionMenu = false;
  }

  private filterMentionItems(): void {
    if (this.mentionSearchText === '') {
      this.filteredMentionItems = this.mentionItems;
    } else {
      const search = this.mentionSearchText;
      const filtered: MentionItem[] = [];
      for (let i = 0; i < this.mentionItems.length; i++) {
        const item = this.mentionItems[i];
        if (item.name.toLowerCase().indexOf(search) >= 0) {
          filtered.push(item);
        }
      }
      this.filteredMentionItems = filtered;
    }
  }

  private selectMention(item: MentionItem): void {
    // 替换 @ 之后的文字为选中的名字
    const before = this.inputText.substring(0, this.mentionStartIndex);
    this.inputText = before + '@' + item.name + ' ';
    this.showMentionMenu = false;
  }

  async handleRecall(message: Message): Promise<void> {
    if (!this.currentUser) return;
    this.recallingIds = [...this.recallingIds, message.id];

    const res = await recallMessage(this.groupId, message.id, this.currentUser.id);

    // 移除 recallingIds
    const newIds: string[] = [];
    for (let i = 0; i < this.recallingIds.length; i++) {
      if (this.recallingIds[i] !== message.id) {
        newIds.push(this.recallingIds[i]);
      }
    }
    this.recallingIds = newIds;

    if (res.success) {
      this.messages = this.messages.filter((m: Message) => m.id !== message.id);
      this.recallMessage_ = '消息已撤回';
    } else {
      this.recallMessage_ = res.message || '撤回失败';
    }
    // 清除提示信息
    setTimeout(() => { this.recallMessage_ = ''; }, 2000);
  }

  private scrollToBottom(): void {
    setTimeout(() => {
      const total = this.messages.length + (this.transientMessage !== null ? 1 : 0);
      if (total > 0) {
        this.listScroller.scrollToIndex(total - 1);
      }
    }, 50);
  }

  private getAllDisplayMessages(): Message[] {
    if (this.transientMessage !== null) {
      const result: Message[] = [...this.messages, this.transientMessage];
      return result;
    }
    return this.messages;
  }

  canRecallMessage(msg: Message): boolean {
    if (this.currentUser === null) return false;
    if (msg.userId !== this.currentUser.id) return false;
    if (msg.type !== MessageType.TEXT) return false;
    if (msg.isTransient) return false;
    return true;
  }

  async showMembers(): Promise<void> {
    this.showMembersDialog = true;
    this.membersLoading = true;
    const res: GroupMembersResponse = await getGroupMembers(this.groupId);
    if (res.success) {
      this.members = res.members;
    }
    this.membersLoading = false;
  }

  build() {
    NavDestination() {
      Stack() {
        Column() {
          // 顶栏
          Row() {
            Button('< 返回')
              .fontSize(14)
              .fontColor(SilkColors.primary)
              .backgroundColor(Color.Transparent)
              .onClick(() => { this.navPathStack.pop(); })

            Text(this.groupName)
              .fontSize(18)
              .fontWeight(FontWeight.Bold)
              .fontColor(SilkColors.textPrimary)
              .layoutWeight(1)
              .textAlign(TextAlign.Center)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })

            // 成员按钮
            Text('👥')
              .fontSize(18)
              .width(36)
              .height(36)
              .textAlign(TextAlign.Center)
              .borderRadius(18)
              .backgroundColor(SilkColors.surfaceVariant)
              .margin({ right: 4 })
              .onClick(() => { this.showMembers(); })

            ConnectionStatus({ state: this.connectionState })
          }
          .width('100%')
          .padding({ left: 8, right: 12, top: 10, bottom: 10 })
          .backgroundColor(SilkColors.surface)

          Divider().color(SilkColors.divider)

          // 撤回提示
          if (this.recallMessage_ !== '') {
            Row() {
              Text(this.recallMessage_)
                .fontSize(13)
                .fontColor(SilkColors.success)
            }
            .width('100%')
            .justifyContent(FlexAlign.Center)
            .padding(6)
            .backgroundColor(SilkColors.surfaceVariant)
          }

          // AI 等待提示
          if (this.isWaitingForAI) {
            Row() {
              Text('🤖 Silk 正在思考...')
                .fontSize(13)
                .fontColor('#7BA8C9')
            }
            .width('100%')
            .justifyContent(FlexAlign.Center)
            .padding(6)
            .backgroundColor(SilkColors.surfaceVariant)
          }

          // 消息列表
          List({ scroller: this.listScroller, space: 0 }) {
            ForEach(this.getAllDisplayMessages(), (msg: Message) => {
              ListItem() {
                ChatBubble({
                  messageContent: msg.content,
                  messageUserName: msg.userName,
                  messageTimestamp: msg.timestamp,
                  messageType: msg.type as string,
                  messageCategory: msg.category as string,
                  isOwnMessage: msg.userId === (this.currentUser?.id ?? ''),
                  isTransient: msg.isTransient,
                  canRecall: this.canRecallMessage(msg),
                  onRecall: () => { this.handleRecall(msg); },
                })
              }
            }, (msg: Message, index: number) => msg.id + '_' + index.toString())
          }
          .width('100%')
          .layoutWeight(1)
          .backgroundColor(SilkColors.background)

          Divider().color(SilkColors.divider)

          // @mention 提示菜单
          if (this.showMentionMenu) {
            Column() {
              ForEach(this.filteredMentionItems, (item: MentionItem) => {
                Row() {
                  Text(item.isSilk ? '🤖' : item.name.charAt(0).toUpperCase())
                    .fontSize(item.isSilk ? 18 : 14)
                    .fontWeight(FontWeight.Bold)
                    .fontColor(item.isSilk ? Color.Black : SilkColors.textOnPrimary)
                    .textAlign(TextAlign.Center)
                    .width(32)
                    .height(32)
                    .borderRadius(16)
                    .backgroundColor(item.isSilk ? SilkColors.surfaceVariant : SilkColors.primary)
                    .margin({ right: 10 })

                  Text(item.isSilk ? '🤖 Silk' : item.name)
                    .fontSize(15)
                    .fontColor(SilkColors.textPrimary)
                }
                .width('100%')
                .padding({ left: 12, right: 12, top: 8, bottom: 8 })
                .backgroundColor(SilkColors.surface)
                .onClick(() => { this.selectMention(item); })
              }, (item: MentionItem) => item.id)
            }
            .width('100%')
            .backgroundColor(SilkColors.surface)
            .borderWidth(1)
            .borderColor(SilkColors.divider)
          }

          // 输入栏
          ChatInput({
            inputText: $inputText,
            isConnected: this.connectionState === 'CONNECTED',
            onSend: () => { this.sendMessage(); },
            onInputChange: (value: string) => { this.handleInputChange(value); },
          })
        }
        .width('100%')
        .height('100%')
        .backgroundColor(SilkColors.background)

        // 成员列表对话框
        if (this.showMembersDialog) {
          Column() {
            Column() {
              Text('群组成员')
                .fontSize(18)
                .fontWeight(FontWeight.Bold)
                .fontColor(SilkColors.textPrimary)
                .margin({ bottom: 12 })

              if (this.membersLoading) {
                LoadingProgress().width(32).height(32).color(SilkColors.primary)
                  .margin({ top: 16, bottom: 16 })
              } else {
                List({ space: 0 }) {
                  ForEach(this.members, (member: GroupMember) => {
                    ListItem() {
                      Row() {
                        Text(member.fullName === 'Silk' ? '🤖' : member.fullName.charAt(0).toUpperCase())
                          .fontSize(member.fullName === 'Silk' ? 20 : 16)
                          .fontWeight(FontWeight.Bold)
                          .fontColor(member.fullName === 'Silk' ? Color.Black : SilkColors.textOnPrimary)
                          .textAlign(TextAlign.Center)
                          .width(36)
                          .height(36)
                          .borderRadius(18)
                          .backgroundColor(SilkColors.surfaceVariant)
                          .margin({ right: 10 })

                        Column() {
                          Row() {
                            Text(member.fullName)
                              .fontSize(15)
                              .fontColor(SilkColors.textPrimary)

                            if (this.currentUser !== null && member.id === this.currentUser.id) {
                              Text(' (我)')
                                .fontSize(13)
                                .fontColor(SilkColors.textSecondary)
                            }
                          }
                          if (member.phone !== undefined && member.phone !== '') {
                            Text(member.phone as string)
                              .fontSize(12)
                              .fontColor(SilkColors.textSecondary)
                          }
                        }
                        .layoutWeight(1)
                        .alignItems(HorizontalAlign.Start)
                      }
                      .width('100%')
                      .padding({ top: 8, bottom: 8 })
                    }
                  }, (member: GroupMember) => member.id)
                }
                .height(this.members.length > 6 ? 280 : undefined)
              }

              Button('关闭')
                .fontColor(SilkColors.textSecondary)
                .backgroundColor(Color.Transparent)
                .margin({ top: 12 })
                .onClick(() => { this.showMembersDialog = false; })
            }
            .width('85%')
            .padding(24)
            .backgroundColor(SilkColors.surface)
            .borderRadius(16)
          }
          .width('100%')
          .height('100%')
          .backgroundColor('#80000000')
          .justifyContent(FlexAlign.Center)
          .alignItems(HorizontalAlign.Center)
        }
      }
    }
    .hideTitleBar(true)
  }
}
