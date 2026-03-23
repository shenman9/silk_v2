/**
 * 消息气泡组件
 * 用于在聊天界面显示单条消息
 */
import { Message, MessageType, MessageCategory } from '../models/Message';
import { Theme } from '../common/Theme';

@Component
export struct MessageBubble {
  @Prop message: Message;
  @Prop isOwn: boolean = false;

  build() {
    Column() {
      // 用户名和时间
      Row() {
        Text(this.message.userName)
          .fontSize(12)
          .fontColor(this.isOwn ? Theme.colors.primary : Theme.colors.textSecondary)
          .fontWeight(FontWeight.Medium)
        
        Blank()
        
        Text(this.formatTime(this.message.timestamp))
          .fontSize(10)
          .fontColor(Theme.colors.textLight)
      }
      .width('100%')
      .margin({ bottom: 4 })

      // 消息内容
      Row() {
        Text(this.message.content)
          .fontSize(14)
          .fontColor(this.getMessageColor())
          .padding(12)
          .borderRadius({
            topLeft: 12,
            topRight: 12,
            bottomLeft: this.isOwn ? 12 : 4,
            bottomRight: this.isOwn ? 4 : 12
          })
          .backgroundColor(this.getMessageBackground())
          .maxLines(100)
          .textOverflow({ overflow: TextOverflow.Clip })
      }
      .width('100%')
      .justifyContent(this.isOwn ? FlexAlign.End : FlexAlign.Start)
    }
    .width('100%')
    .padding({ left: 12, right: 12, top: 4, bottom: 4 })
    .alignItems(this.isOwn ? HorizontalAlign.End : HorizontalAlign.Start)
  }

  private getMessageColor(): ResourceColor {
    if (this.message.category === MessageCategory.AGENT_STATUS) {
      return Theme.colors.textLight;
    }
    if (this.message.category === MessageCategory.FINAL_REPORT) {
      return Theme.colors.primary;
    }
    return Theme.colors.textPrimary;
  }

  private getMessageBackground(): ResourceColor {
    if (this.isOwn) {
      return Theme.colors.primaryLight;
    }
    return Theme.colors.cardBackground;
  }

  private formatTime(timestamp: number): string {
    const date = new Date(timestamp);
    const hours = date.getHours().toString().padStart(2, '0');
    const minutes = date.getMinutes().toString().padStart(2, '0');
    return `${hours}:${minutes}`;
  }
}
