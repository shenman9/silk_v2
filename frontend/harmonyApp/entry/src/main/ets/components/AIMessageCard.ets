/**
 * AI 消息卡片组件
 * 极简稳定版：用于定位闪退
 */
import { Message } from '../models/Message';
import { Theme } from '../common/Theme';

@Component
export struct AIMessageCard {
  @Prop message: Message;
  @Prop timeString: string = '';
  @Prop isTransient: boolean = false;
  @Prop isExpanded: boolean = false;
  @Prop onExpandChange: (isExpanded: boolean) => void = (isExpanded: boolean) => {};
  @Prop onCopy: (content: string) => void = (content: string) => {};
  @Prop onForward: (message: Message) => void = (message: Message) => {};

  private getSafeContent(): string {
    return this.message.content ? this.message.content : '';
  }

  private isLongContent(): boolean {
    return this.getSafeContent().length > 500;
  }

  private getPreviewContent(): string {
    const content = this.getSafeContent();
    return content.length > 220 ? content.substring(0, 220) + '...' : content;
  }

  build() {
    Row() {
      Column() {
        Row() {
          Text('🤖 Silk AI')
            .fontSize(13)
            .fontWeight(FontWeight.Medium)
            .fontColor(Theme.colors.primary)

          Text(' · ' + this.timeString)
            .fontSize(12)
            .fontColor(Theme.colors.textLight)
            .margin({ left: 6 })

          if (this.isLongContent()) {
            Blank()
            Text(this.isExpanded ? '收起' : '展开')
              .fontSize(12)
              .fontColor(Theme.colors.primary)
              .onClick(() => {
                this.onExpandChange(!this.isExpanded);
              })
          }
        }
        .width('100%')

        Blank().height(6)

        Text(this.isExpanded || !this.isLongContent() ? this.getSafeContent() : this.getPreviewContent())
          .fontSize(14)
          .fontColor(Theme.colors.textPrimary)
          .maxLines(this.isExpanded || !this.isLongContent() ? 999 : 8)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      .width('95%')
      .padding(12)
      .borderRadius(12)
      .backgroundColor('#F8F6F0')
    }
    .width('100%')
    .justifyContent(FlexAlign.Start)
    .padding({ left: 8, right: 8, top: 4, bottom: 4 })
  }
}
