/**
 * 加载指示器组件
 * 用于显示加载状态
 */
import { SilkColors } from '../common/Theme';

@Component
export struct LoadingIndicator {
  @Prop message: string = '加载中...'
  @Prop indicatorSize: number = 48

  build() {
    Column() {
      LoadingProgress()
        .width(this.indicatorSize)
        .height(this.indicatorSize)
        .color(SilkColors.primary)

      if (this.message) {
        Text(this.message)
          .fontSize(14)
          .fontColor(SilkColors.textSecondary)
          .margin({ top: 16 })
      }
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .backgroundColor(SilkColors.background)
  }
}
