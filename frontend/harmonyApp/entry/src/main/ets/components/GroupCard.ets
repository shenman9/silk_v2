/**
 * 群组卡片组件
 * 显示群名、邀请码、创建时间、未读徽章
 */
import { SilkColors } from '../common/SilkColors';

@Component
export struct GroupCard {
  @Prop groupName: string = '';
  @Prop groupId: string = '';
  @Prop hostName: string = '';
  @Prop invitationCode: string = '';
  @Prop createdAt: string = '';
  @Prop unreadCount: number = 0;
  onTap: () => void = () => {};

  build() {
    Row() {
      // 头像圈（首字母）
      Text(this.groupName.charAt(0).toUpperCase())
        .fontSize(20)
        .fontWeight(FontWeight.Bold)
        .fontColor(SilkColors.textOnPrimary)
        .textAlign(TextAlign.Center)
        .width(48)
        .height(48)
        .borderRadius(24)
        .backgroundColor(SilkColors.primary)
        .margin({ right: 12 })

      // 群名 + 信息
      Column() {
        Row() {
          Text(this.groupName)
            .fontSize(16)
            .fontWeight(FontWeight.Medium)
            .fontColor(SilkColors.textPrimary)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)

          if (this.invitationCode !== '') {
            Text('[' + this.invitationCode + ']')
              .fontSize(11)
              .fontColor(SilkColors.textSecondary)
              .margin({ left: 4 })
          }
        }
        .width('100%')

        if (this.hostName !== '') {
          Text('群主: ' + this.hostName)
            .fontSize(12)
            .fontColor(SilkColors.textSecondary)
            .margin({ top: 4 })
        }
      }
      .layoutWeight(1)
      .alignItems(HorizontalAlign.Start)

      // 未读徽章
      if (this.unreadCount > 0) {
        Text(this.unreadCount > 99 ? '99+' : this.unreadCount.toString())
          .fontSize(12)
          .fontColor(SilkColors.textOnPrimary)
          .textAlign(TextAlign.Center)
          .width(24)
          .height(20)
          .borderRadius(10)
          .backgroundColor(SilkColors.unreadBadge)
          .padding({ left: 4, right: 4 })
      }
    }
    .width('100%')
    .padding(16)
    .backgroundColor(SilkColors.surface)
    .borderRadius(12)
    .borderWidth(this.unreadCount > 0 ? 1 : 0)
    .borderColor(this.unreadCount > 0 ? SilkColors.primary : Color.Transparent)
    .onClick(() => { this.onTap(); })
  }
}
