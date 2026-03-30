import { UserTodoItem } from '../models/UserTodoItem';
import { SilkColors } from '../common/Theme';

/**
 * 待办列表单行。独立 @Component，避免 TodoPage 中 @Builder 嵌套 ForEach 时子节点不刷新。
 */
@Component
export struct TodoTaskCardRow {
  @Prop item: UserTodoItem = {
    id: '',
    title: '',
    createdAt: 0,
    updatedAt: 0,
    done: false
  };
  @Prop executing: boolean = false;
  @Prop isLongTerm: boolean = false;
  @Prop canExecute: boolean = false;
  @Prop needsTimeHint: boolean = false;
  onToggle: () => void = (): void => {};
  onRun: () => void = (): void => {};
  onMenu: () => void = (): void => {};

  build() {
    Row() {
      Text(this.item.done ? '☑' : '☐')
        .fontSize(18)
        .fontColor(this.item.done ? SilkColors.textLight : SilkColors.primary)
        .margin({ right: 10 })
        .padding({ top: 4 })
        .onClick((): void => {
          this.onToggle();
        })

      Column() {
        Text(this.item.title)
          .fontSize(15)
          .fontColor(this.item.done ? SilkColors.textLight : SilkColors.textPrimary)
          .maxLines(4)
          .textOverflow({ overflow: TextOverflow.Ellipsis })

        if (this.item.executedAt !== undefined && this.item.executedAt > 0) {
          Text('已执行系统提醒')
            .fontSize(11)
            .fontColor(SilkColors.textLight)
            .margin({ top: 4 })
        } else if (this.item.done && (this.item.executedAt === undefined || this.item.executedAt <= 0)) {
          Text(this.isLongTerm ? '长期模板（已暂停）' : '已勾选完成（未创建系统提醒）')
            .fontSize(11)
            .fontColor(SilkColors.textLight)
            .margin({ top: 4 })
        }

        if (this.isLongTerm) {
          Text(`长期模板 · ${this.item.repeatRule ? this.item.repeatRule : 'custom'}${this.item.repeatAnchor ? ` · ${this.item.repeatAnchor}` : ''}`)
            .fontSize(11)
            .fontColor('#8C8C8C')
            .margin({ top: 4 })
        } else {
          Text('短期实例')
            .fontSize(11)
            .fontColor('#8C8C8C')
            .margin({ top: 4 })
        }

        if (this.needsTimeHint) {
          Text('缺时间信息，标题需含如 7点、07:00')
            .fontSize(11)
            .fontColor('#D48806')
            .margin({ top: 4 })
        }

        if (this.item.sourceGroupName && this.item.sourceGroupName.length > 0) {
          Text(`来自：${this.item.sourceGroupName}`)
            .fontSize(11)
            .fontColor(SilkColors.textLight)
            .margin({ top: 4 })
        }
      }
      .layoutWeight(1)
      .alignItems(HorizontalAlign.Start)

      if (this.canExecute) {
        if (this.executing) {
          LoadingProgress()
            .width(32)
            .height(32)
            .color(SilkColors.onPrimary)
        } else {
          Button(this.item.executedAt !== undefined && this.item.executedAt > 0 ? '↻ 重设' : '▶ 运行')
            .height(32)
            .fontSize(13)
            .padding({ left: 12, right: 12 })
            .backgroundColor(this.item.executedAt !== undefined && this.item.executedAt > 0 ? '#1890FF' : SilkColors.primary)
            .fontColor(SilkColors.onPrimary)
            .borderRadius(8)
            .onClick((): void => {
              this.onRun();
            })
        }
      } else if (this.item.done && this.item.executedAt !== undefined && this.item.executedAt > 0) {
        Button('已执行')
          .height(32)
          .fontSize(13)
          .padding({ left: 12, right: 12 })
          .backgroundColor('#D9D9D9')
          .fontColor('#666666')
          .borderRadius(8)
      }

      Text('⋯')
        .fontSize(20)
        .fontColor(SilkColors.textLight)
        .margin({ left: 8 })
        .padding({ left: 4, right: 4 })
        .onClick((): void => {
          this.onMenu();
        })
    }
    .width('100%')
    .padding(12)
    .backgroundColor(SilkColors.background)
    .borderRadius(10)
  }
}
