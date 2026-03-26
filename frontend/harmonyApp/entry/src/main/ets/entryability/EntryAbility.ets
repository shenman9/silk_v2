import UIAbility from '@ohos.app.ability.UIAbility';
import hilog from '@ohos.hilog';
import window from '@ohos.window';
import Want from '@ohos.app.ability.Want';
import AbilityConstant from '@ohos.app.ability.AbilityConstant';
import common from '@ohos.app.ability.common';
import { Logger } from '../common/Logger';

const TAG = 'SilkApp';
const DOMAIN = 0xFF00;

// 全局 context 引用，供 AlarmService 等使用
let globalContext: common.UIAbilityContext | null = null;

export function getGlobalContext(): common.UIAbilityContext | null {
  return globalContext;
}

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(DOMAIN, TAG, 'Silk App onCreate');
    Logger.init(this.context.filesDir);
    globalContext = this.context;
    
    // 初始化应用存储
    // AppStore 会在页面中通过 @StorageLink 自动初始化
  }

  onDestroy(): void {
    hilog.info(DOMAIN, TAG, 'Silk App onDestroy');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    hilog.info(DOMAIN, TAG, 'onWindowStageCreate');

    // 设置主窗口
    windowStage.loadContent('pages/Index', (err, data) => {
      if (err.code) {
        hilog.error(DOMAIN, TAG, 'Failed to load content. Cause: %{public}s', 
          JSON.stringify(err) ?? '');
        return;
      }
      hilog.info(DOMAIN, TAG, 'Succeeded in loading content. Data: %{public}s', 
        JSON.stringify(data) ?? '');
    });
  }

  onWindowStageDestroy(): void {
    hilog.info(DOMAIN, TAG, 'onWindowStageDestroy');
  }

  onForeground(): void {
    hilog.info(DOMAIN, TAG, 'onForeground');
  }

  onBackground(): void {
    hilog.info(DOMAIN, TAG, 'onBackground');
  }
}
