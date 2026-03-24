/**
 * 聊天气泡组件
 * 对齐 androidApp/ChatScreen.kt
 * 自己的消息右对齐 primary 色，他人左对齐浅色，系统消息居中灰色
 * 支持消息撤回、AI 消息样式、消息类别区分
 */
import { SilkColors } from '../common/SilkColors';
import { formatTimeHM } from '../common/DateUtils';

@Component
export struct ChatBubble {
  @Prop messageContent: string = '';
  @Prop messageUserName: string = '';
  @Prop messageTimestamp: number = 0;
  @Prop messageType: string = 'TEXT';
  @Prop messageCategory: string = 'NORMAL';
  @Prop isOwnMessage: boolean = false;
  @Prop isTransient: boolean = false;
  @Prop canRecall: boolean = false;
  onRecall: () => void = () => {};
  @State showActions: boolean = false;

  isSystemMessage(): boolean {
    return this.messageType === 'JOIN' || this.messageType === 'LEAVE' || this.messageType === 'SYSTEM';
  }

  isSilkMessage(): boolean {
    return this.messageUserName === 'Silk' || this.messageUserName === 'silk';
  }

  getOpacity(): number {
    if (this.isTransient) return 0.6;
    if (this.messageCategory === 'STEP_PROCESS' || this.messageCategory === 'TODO_LIST') return 0.7;
    return 1.0;
  }

  build() {
    if (this.isSystemMessage()) {
      // 系统消息：居中灰色
      Row() {
        Text(this.messageContent)
          .fontSize(12)
          .fontColor(SilkColors.systemMessage)
          .textAlign(TextAlign.Center)
      }
      .width('100%')
      .justifyContent(FlexAlign.Center)
      .padding({ top: 4, bottom: 4 })
    } else if (this.isSilkMessage()) {
      // AI 消息：特殊样式
      Column() {
        Row() {
          Text('🤖')
            .fontSize(16)
            .margin({ right: 4 })
          Text('Silk')
            .fontSize(12)
            .fontColor('#7BA8C9')
            .fontWeight(FontWeight.Medium)
          if (this.isTransient) {
            Text(' ⏳ 生成中...')
              .fontSize(11)
              .fontColor(SilkColors.textSecondary)
          }
        }
        .margin({ bottom: 2 })

        Column() {
          Text(this.messageContent)
            .fontSize(15)
            .fontColor(SilkColors.textPrimary)
            .wordBreak(WordBreak.BREAK_ALL)

          Text(formatTimeHM(this.messageTimestamp))
            .fontSize(10)
            .fontColor(SilkColors.textSecondary)
            .margin({ top: 4 })
            .alignSelf(ItemAlign.Start)
        }
        .padding(12)
        .borderRadius(12)
        .backgroundColor('#FFF8F0')
        .borderWidth(1)
        .borderColor('#E8DCC8')
        .constraintSize({ maxWidth: '85%' })
        .opacity(this.getOpacity())
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start)
      .padding({ left: 12, right: 12, top: 4, bottom: 4 })
    } else {
      // 普通消息
      Column() {
        // 用户名（非自己的消息显示）
        if (!this.isOwnMessage) {
          Text(this.messageUserName)
            .fontSize(12)
            .fontColor(SilkColors.textSecondary)
            .margin({ bottom: 2 })
        }

        Row() {
          if (this.isOwnMessage) {
            // 撤回按钮（自己的消息，点击显示）
            if (this.showActions && this.canRecall) {
              Button('撤回')
                .fontSize(11)
                .fontColor(SilkColors.error)
                .backgroundColor(SilkColors.surface)
                .borderWidth(1)
                .borderColor(SilkColors.divider)
                .borderRadius(12)
                .height(28)
                .margin({ right: 8 })
                .onClick(() => {
                  this.showActions = false;
                  this.onRecall();
                })
            }
            Blank()
          }

          Column() {
            // 消息内容
            Text(this.messageContent)
              .fontSize(15)
              .fontColor(this.isOwnMessage ? SilkColors.textOnPrimary : SilkColors.textPrimary)
              .wordBreak(WordBreak.BREAK_ALL)

            // 时间戳
            Text(formatTimeHM(this.messageTimestamp))
              .fontSize(10)
              .fontColor(this.isOwnMessage ? '#CCFFFFFF' : SilkColors.textSecondary)
              .margin({ top: 4 })
              .alignSelf(this.isOwnMessage ? ItemAlign.End : ItemAlign.Start)
          }
          .padding(12)
          .borderRadius(12)
          .backgroundColor(this.isOwnMessage ? SilkColors.bubbleSelf : SilkColors.bubbleOther)
          .constraintSize({ maxWidth: '75%' })
          .opacity(this.getOpacity())
          .onClick(() => {
            if (this.canRecall) {
              this.showActions = !this.showActions;
            }
          })

          if (!this.isOwnMessage) {
            Blank()
          }
        }
        .width('100%')
      }
      .width('100%')
      .alignItems(this.isOwnMessage ? HorizontalAlign.End : HorizontalAlign.Start)
      .padding({ left: 12, right: 12, top: 4, bottom: 4 })
    }
  }
}
