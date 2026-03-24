/**
 * 消息气泡组件
 * 用于在聊天界面显示单条消息
 * 严格对标 Android 端 ChatScreen.kt 的 MessageItem 逻辑
 */
import { Message, MessageType, MessageCategory } from '../models/Message';
import { Theme, SilkColors } from '../common/Theme';

@Component
export struct MessageBubble {
  @Prop message: Message;
  @Prop isOwn: boolean = false;
  @Prop currentUserId: string = '';
  @Prop actionsEnabled: boolean = true;
  onCopy?: (content: string) => void;
  onForward?: (message: Message) => void;
  onShare?: (message: Message) => void;
  onOpenActions?: (message: Message) => void;
  @Prop messageStatus: string = 'sent';
  onRetry?: (message: Message) => void;
  @State private isAIExpanded: boolean = false;

  build() {
    Column() {
      // 1. AI 消息特殊处理
      if (this.isAIMessage()) {
        this.buildInlineAIMessageCard()
      } else if (this.message.type === MessageType.SYSTEM) {
        // 2. 系统消息
        this.buildSystemMessage()
      } else {
        // 3. 普通消息
        this.buildNormalMessage()
      }
    }
    .width('100%')
  }

  private isAIMessage(): boolean {
    return this.message.userId === 'silk_ai_agent' && 
           this.message.category !== MessageCategory.AGENT_STATUS;
  }

  private getSafeAIContent(): string {
    return this.message.content ? this.message.content : '';
  }

  private isLongAIContent(): boolean {
    return this.getSafeAIContent().length > 500;
  }

  private getAIPreview(): string {
    const content = this.getSafeAIContent();
    return content.length > 220 ? `${content.substring(0, 220)}...` : content;
  }

  private canShowAIActions(): boolean {
    return this.actionsEnabled && !this.message.isTransient;
  }

  private canShowNormalActions(): boolean {
    return this.actionsEnabled && this.message.type === MessageType.TEXT && !this.message.isTransient;
  }

  @Builder
  private buildQuickAction(label: string, onTap: () => void) {
    Row() {
      Text(label)
        .fontSize(11)
        .fontColor(Theme.colors.textLight)
    }
    .padding({ left: 8, right: 8, top: 4, bottom: 4 })
    .backgroundColor('#14000000')
    .borderRadius(10)
    .onClick(() => onTap())
  }

  @Builder
  private buildInlineAIMessageCard() {
    Row() {
      Column() {
        Row() {
          Text('🤖')
            .fontSize(16)
          Text(' Silk AI')
            .fontSize(14)
            .fontWeight(FontWeight.Medium)
            .fontColor('#C9A86C')
            .margin({ left: 4 })

          Text(' · ' + this.formatTime(this.message.timestamp))
            .fontSize(12)
            .fontColor(Theme.colors.textSecondary)
            .margin({ left: 8 })

          if (this.isLongAIContent()) {
            Blank()
            Text(this.isAIExpanded ? '▼ 收起' : '▶ 展开')
              .fontSize(12)
              .fontColor(Theme.colors.primary)
              .padding({ left: 8, right: 8, top: 4, bottom: 4 })
              .backgroundColor(this.isAIExpanded ? Color.Transparent : '#1AC9A86C')
              .borderRadius(4)
              .onClick(() => {
                this.isAIExpanded = !this.isAIExpanded;
              })
          }
        }
        .width('100%')

        Blank().height(10)

        Row()
          .width('100%')
          .height(1)
          .backgroundColor('#E8E0D4')

        Blank().height(10)

        Text(this.isAIExpanded || !this.isLongAIContent() ? this.getSafeAIContent() : this.getAIPreview())
          .fontSize(14)
          .fontColor(Theme.colors.textPrimary)
          .maxLines(this.isAIExpanded || !this.isLongAIContent() ? 999 : 8)
          .textOverflow({ overflow: TextOverflow.Ellipsis })

        if (this.canShowAIActions()) {
          Blank().height(10)

          Row()
            .width('100%')
            .height(0.5)
            .backgroundColor('#E8E0D4')

          Blank().height(6)

          Row() {
            Blank()

            Row() {
              Text('📋')
                .fontSize(14)
              Blank().width(4)
              Text('复制')
                .fontSize(12)
                .fontColor(Theme.colors.textSecondary)
            }
            .padding({ left: 8, right: 8, top: 4, bottom: 4 })
            .onClick(() => {
              if (this.onCopy) {
                this.onCopy(this.getSafeAIContent());
              }
            })

            Blank().width(8)

            Row() {
              Text('⋯')
                .fontSize(15)
              Blank().width(4)
              Text('更多')
                .fontSize(12)
                .fontColor(Theme.colors.textSecondary)
            }
            .padding({ left: 8, right: 8, top: 4, bottom: 4 })
            .onClick(() => {
              if (this.onOpenActions) {
                this.onOpenActions(this.message);
              }
            })
          }
          .width('100%')
        }

        if (this.message.isTransient) {
          Blank().height(8)
          Row() {
            Text('⏳ 生成中...')
              .fontSize(12)
              .fontColor('#E8B86C')
          }
        }
      }
      .width('95%')
      .padding(14)
      .borderRadius(14)
      .backgroundColor('#F8F6F0')
    }
    .width('100%')
    .justifyContent(FlexAlign.Start)
    .padding({ left: 8, right: 8, top: 4, bottom: 4 })
  }

  @Builder
  buildSystemMessage() {
    Row() {
      Text(this.message.content ? this.message.content : '')
        .fontSize(12)
        .fontColor(Theme.colors.textSecondary)
        .padding({ left: 12, right: 12, top: 6, bottom: 6 })
        .backgroundColor('#F5F5F5')
        .borderRadius(4)
    }
    .width('100%')
    .justifyContent(FlexAlign.Center)
    .padding({ top: 4, bottom: 4 })
  }

  @Builder
  buildNormalMessage() {
    Column() {
      // 用户名和时间
      if (!this.isOwn) {
        Row() {
          Text(this.message.userName ? this.message.userName : '用户')
            .fontSize(12)
            .fontWeight(FontWeight.Medium)
            .fontColor(SilkColors.primary)
          
          Text(' · ' + this.formatTime(this.message.timestamp))
            .fontSize(12)
            .fontColor(Theme.colors.textLight)
        }
        .width('100%')
        .margin({ bottom: 4, left: 4 })
      }

      // 消息内容
      Row() {
        Column() {
          Text(this.message.content ? this.message.content : '')
            .fontSize(14)
            .fontColor(this.getMessageTextColor())
            .maxLines(100)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .padding(12)
        .borderRadius({
          topLeft: 12,
          topRight: 12,
          bottomLeft: this.isOwn ? 12 : 4,
          bottomRight: this.isOwn ? 4 : 12
        })
        .backgroundColor(this.getMessageBackground())
      }
      .width('100%')
      .justifyContent(this.isOwn ? FlexAlign.End : FlexAlign.Start)

      if (this.isOwn) {
        Row() {
          if (this.messageStatus === 'sending') {
            Text('发送中...')
              .fontSize(11)
              .fontColor(Theme.colors.textLight)
          } else if (this.messageStatus === 'failed') {
            Text('发送失败')
              .fontSize(11)
              .fontColor('#D9534F')
            Text(' · 重试')
              .fontSize(11)
              .fontColor(Theme.colors.primary)
              .onClick(() => {
                if (this.onRetry) {
                  this.onRetry(this.message);
                }
              })
          } else {
            Text(this.formatTime(this.message.timestamp))
              .fontSize(11)
              .fontColor(Theme.colors.textLight)
          }
        }
        .width('100%')
        .justifyContent(FlexAlign.End)
        .margin({ top: 4, right: 4 })
      }

      if (this.canShowNormalActions() && this.messageStatus !== 'sending') {
        Row() {
          Blank()
          this.buildQuickAction('复制', () => {
            if (this.onCopy) {
              this.onCopy(this.message.content ? this.message.content : '');
            }
          })
          Blank().width(6)
          this.buildQuickAction('更多', () => {
            if (this.onOpenActions) {
              this.onOpenActions(this.message);
            }
          })
        }
        .width('100%')
        .justifyContent(FlexAlign.End)
        .margin({ top: 2, right: 4 })
      }
    }
    .width('100%')
    .padding({ left: 12, right: 12, top: 4, bottom: 4 })
    .alignItems(this.isOwn ? HorizontalAlign.End : HorizontalAlign.Start)
  }

  private getMessageBackground(): ResourceColor {
    if (this.message.category === MessageCategory.FINAL_REPORT) {
      return this.isOwn ? Theme.colors.primary : Theme.colors.cardBackground;
    }
    if (this.message.category === MessageCategory.STEP_PROCESS ||
        this.message.category === MessageCategory.TODO_LIST) {
      return this.isOwn ? '#E8D5B5' : '#F5F5F5';
    }
    return this.isOwn ? Theme.colors.primaryLight : Theme.colors.cardBackground;
  }

  private getMessageTextColor(): ResourceColor {
    if (this.message.category === MessageCategory.AGENT_STATUS) {
      return Theme.colors.textLight;
    }
    if (this.message.category === MessageCategory.FINAL_REPORT) {
      return Theme.colors.primary;
    }
    return Theme.colors.textPrimary;
  }

  private formatTime(timestamp: number): string {
    const date = new Date(timestamp);
    const hours = date.getHours().toString().padStart(2, '0');
    const minutes = date.getMinutes().toString().padStart(2, '0');
    return `${hours}:${minutes}`;
  }
}
