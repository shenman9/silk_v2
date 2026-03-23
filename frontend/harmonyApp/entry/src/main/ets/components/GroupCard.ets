/**
 * 群组卡片组件
 */
import { Group } from '../models/Group';
import { SilkTheme } from '../common/Theme';

@Component
export struct GroupCard {
  @Prop group: Group | null = null;
  @Prop isActive: boolean = false;
  onGroupClick?: (group: Group) => void;

  build() {
    Column() {
      if (this.group) {
        Row() {
          // 群组头像
          Column() {
            Text(this.group.name.charAt(0).toUpperCase())
              .fontSize(24)
              .fontColor(Color.White)
              .fontWeight(FontWeight.Bold)
          }
          .width(56)
          .height(56)
          .borderRadius(28)
          .backgroundColor(SilkTheme.colors.primary)
          .justifyContent(FlexAlign.Center)

          // 群组信息
          Column() {
            Text(this.group.name)
              .fontSize(16)
              .fontColor(SilkTheme.colors.textPrimary)
              .fontWeight(FontWeight.Medium)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })

            Blank().height(4)

            if (this.group.lastMessage) {
              Text(this.group.lastMessage)
                .fontSize(14)
                .fontColor(SilkTheme.colors.textSecondary)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
            } else {
              Text('暂无消息')
                .fontSize(14)
                .fontColor(SilkTheme.colors.textLight)
            }
          }
          .layoutWeight(1)
          .alignItems(HorizontalAlign.Start)
          .padding({ left: 12 })

          // 时间和未读数
          Column() {
            if (this.group.lastMessageTime) {
              Text(this.formatTime(this.group.lastMessageTime))
                .fontSize(12)
                .fontColor(SilkTheme.colors.textLight)
            }

            if (this.group.unreadCount && this.group.unreadCount > 0) {
              Blank().height(4)
              
              Text(this.group.unreadCount.toString())
                .fontSize(12)
                .fontColor(Color.White)
                .backgroundColor(SilkTheme.colors.error)
                .borderRadius(10)
                .padding({ left: 6, right: 6, top: 2, bottom: 2 })
            }
          }
          .alignItems(HorizontalAlign.End)
        }
        .width('100%')
        .padding(16)
        .backgroundColor(this.isActive ? SilkTheme.colors.surfaceVariant : SilkTheme.colors.surface)
        .borderRadius(12)
        .onClick(() => {
          if (this.group && this.onGroupClick) {
            this.onGroupClick(this.group);
          }
        })
      }
    }
  }

  private formatTime(timestamp: number): string {
    const date = new Date(timestamp);
    const now = new Date();
    const diff = now.getTime() - date.getTime();
    
    if (diff < 60000) { // 1分钟内
      return '刚刚';
    } else if (diff < 3600000) { // 1小时内
      return Math.floor(diff / 60000) + '分钟前';
    } else if (diff < 86400000) { // 24小时内
      return Math.floor(diff / 3600000) + '小时前';
    } else {
      return (date.getMonth() + 1) + '/' + date.getDate();
    }
  }
}
