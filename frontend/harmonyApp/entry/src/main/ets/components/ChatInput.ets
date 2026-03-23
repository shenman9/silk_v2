/**
 * ChatInput - 聊天输入组件
 * 支持文本输入、发送按钮、附件按钮
 */
import { Theme } from '../common/Theme';

@Component
export struct ChatInput {
  @Link messageText: string;
  @Prop placeholder: string = '输入消息...';
  @Prop isSending: boolean = false;
  onSend?: (message: string) => void;
  onAttachment?: () => void;

  build() {
    Row() {
      // 附件按钮
      if (this.onAttachment) {
        Button() {
          Text('+')
            .fontSize(24)
            .fontColor(Theme.colors.textSecondary)
        }
        .width(44)
        .height(44)
        .backgroundColor(Theme.colors.surface)
        .borderRadius(22)
        .margin({ right: 8 })
        .onClick(() => {
          if (this.onAttachment) {
            this.onAttachment();
          }
        })
      }

      // 输入框
      TextInput({ placeholder: this.placeholder, text: $$this.messageText })
        .layoutWeight(1)
        .height(44)
        .backgroundColor(Theme.colors.surface)
        .borderRadius(22)
        .padding({ left: 16, right: 16 })
        .fontColor(Theme.colors.textPrimary)
        .placeholderColor(Theme.colors.textLight)
        .fontSize(16)
        .onSubmit(() => {
          this.handleSend();
        })

      // 发送按钮
      Button() {
        if (this.isSending) {
          LoadingProgress()
            .width(20)
            .height(20)
            .color(Theme.colors.onPrimary)
        } else {
          Text('发送')
            .fontSize(14)
            .fontColor(Theme.colors.onPrimary)
            .fontWeight(FontWeight.Medium)
        }
      }
      .width(60)
      .height(44)
      .backgroundColor(this.messageText.trim() ? Theme.colors.primary : Theme.colors.border)
      .borderRadius(22)
      .margin({ left: 8 })
      .enabled(!this.isSending && this.messageText.trim().length > 0)
      .onClick(() => {
        this.handleSend();
      })
    }
    .width('100%')
    .padding({ left: 12, right: 12, top: 8, bottom: 8 })
    .backgroundColor(Theme.colors.background)
  }

  private handleSend() {
    const text = this.messageText.trim();
    if (text && this.onSend && !this.isSending) {
      this.onSend(text);
      this.messageText = '';
    }
  }
}
