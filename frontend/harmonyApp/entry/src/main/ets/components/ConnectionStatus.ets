/**
 * WebSocket 连接状态指示器
 */
import { ConnectionState } from '../model/MessageModel';
import { SilkColors } from '../common/SilkColors';

@Component
export struct ConnectionStatus {
  @Prop state: string = 'DISCONNECTED';

  build() {
    Row() {
      Circle()
        .width(8)
        .height(8)
        .fill(this.state === 'CONNECTED' ? SilkColors.success :
              this.state === 'CONNECTING' ? '#F0AD4E' : SilkColors.error)
        .margin({ right: 6 })

      Text(this.state === 'CONNECTED' ? '已连接' :
           this.state === 'CONNECTING' ? '连接中...' : '未连接')
        .fontSize(12)
        .fontColor(this.state === 'CONNECTED' ? SilkColors.success :
                   this.state === 'CONNECTING' ? '#F0AD4E' : SilkColors.error)
    }
  }
}
