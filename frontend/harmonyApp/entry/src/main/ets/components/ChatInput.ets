/**
 * 聊天输入栏组件
 * 文本输入 + 发送按钮 + @mention 触发回调
 */
import { SilkColors } from '../common/SilkColors';

@Component
export struct ChatInput {
  @Link inputText: string;
  onSend: () => void = () => {};
  onInputChange: (value: string) => void = () => {};
  @Prop isConnected: boolean = false;

  build() {
    Row() {
      TextInput({ placeholder: '输入消息... (@silk 提问AI)', text: this.inputText })
        .onChange((value: string) => {
          this.inputText = value;
          this.onInputChange(value);
        })
        .layoutWeight(1)
        .height(44)
        .borderRadius(22)
        .backgroundColor(SilkColors.surface)
        .onSubmit(() => {
          if (this.inputText.trim() !== '' && this.isConnected) {
            this.onSend();
          }
        })

      Button('发送')
        .fontSize(14)
        .fontColor(SilkColors.textOnPrimary)
        .backgroundColor(this.inputText.trim() !== '' && this.isConnected ?
          SilkColors.primary : SilkColors.divider)
        .borderRadius(22)
        .height(44)
        .width(64)
        .margin({ left: 8 })
        .enabled(this.inputText.trim() !== '' && this.isConnected)
        .onClick(() => { this.onSend(); })
    }
    .width('100%')
    .padding({ left: 12, right: 12, top: 8, bottom: 8 })
    .backgroundColor(SilkColors.surface)
  }
}
