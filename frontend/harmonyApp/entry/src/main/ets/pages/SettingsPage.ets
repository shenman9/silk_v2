import { AppStore, appStore as globalAppStore } from '../stores/AppStore';
import { SilkColors } from '../common/Theme';
import { UserSettings, Language } from '../models/UserSettings';
import { apiClient as globalApiClient } from '../api/ApiClient';
import { router as appRouter } from '../common/Router';

@Component
export struct SettingsPage {
  @State appStore: AppStore = globalAppStore;
  @State isLoading: boolean = false;
  @State defaultAgentInstruction: string = '';
  @State language: Language = Language.CHINESE;
  @State statusMessage: string = '';
  
  private readonly apiClient = globalApiClient;

  aboutToAppear() {
    // 加载当前设置
    const settings = this.appStore.getUserSettings();
    this.defaultAgentInstruction = settings.defaultAgentInstruction;
    this.language = settings.language;
  }

  build() {
    Column() {
      // 顶部导航栏
      Row() {
        Button() {
          Row() {
            Text('<')
              .fontSize(24)
              .fontColor(SilkColors.textPrimary)
          }
        }
        .width(50)
        .height(50)
        .backgroundColor(Color.Transparent)
        .onClick(() => {
          appRouter.goBack();
        })

        Text('设置')
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontColor(SilkColors.textPrimary)
          .layoutWeight(1)
          .textAlign(TextAlign.Center)

        Blank().width(50)
      }
      .width('100%')
      .height(56)
      .padding({ left: 8, right: 8 })
      .backgroundColor(SilkColors.surface)

      Divider().color(SilkColors.border)

      // 设置内容
      Scroll() {
        Column() {
          // 用户信息卡片
          this.UserInfoCard()

          // 语言设置
          this.LanguageSetting()

          // Agent 设置
          this.AgentSetting()

          // 其他选项
          this.OtherOptions()

          if (this.statusMessage) {
            Text(this.statusMessage)
              .fontSize(12)
              .fontColor(SilkColors.textSecondary)
              .width('100%')
              .margin({ bottom: 12 })
          }

          // 退出登录按钮
          this.LogoutButton()
        }
        .width('100%')
        .padding(16)
      }
      .layoutWeight(1)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(SilkColors.background)
  }

  @Builder
  UserInfoCard() {
    Column() {
      Row() {
        // 头像
        Column() {
          Text(this.appStore.getCurrentUser()?.name?.charAt(0)?.toUpperCase() || '?')
            .fontSize(32)
            .fontColor(Color.White)
        }
        .width(64)
        .height(64)
        .borderRadius(32)
        .backgroundColor(SilkColors.primary)

        Column() {
          Text(this.appStore.getCurrentUser()?.name || '用户')
            .fontSize(18)
            .fontWeight(FontWeight.Bold)
            .fontColor(SilkColors.textPrimary)

          Text(this.appStore.getCurrentUser()?.id || '')
            .fontSize(14)
            .fontColor(SilkColors.textSecondary)
            .margin({ top: 4 })
        }
        .alignItems(HorizontalAlign.Start)
        .margin({ left: 16 })
        .layoutWeight(1)
      }
      .width('100%')
      .padding(16)
    }
    .width('100%')
    .borderRadius(12)
    .backgroundColor(SilkColors.surface)
    .margin({ bottom: 16 })
  }

  @Builder
  LanguageSetting() {
    Column() {
      Text('语言设置')
        .fontSize(16)
        .fontWeight(FontWeight.Bold)
        .fontColor(SilkColors.textPrimary)
        .width('100%')
        .margin({ bottom: 12 })

      Row() {
        Text('界面语言')
          .fontSize(16)
          .fontColor(SilkColors.textPrimary)
          .layoutWeight(1)

        Toggle({ type: ToggleType.Switch, isOn: this.language === Language.ENGLISH })
          .onChange((isOn: boolean) => {
            this.language = isOn ? Language.ENGLISH : Language.CHINESE;
            this.saveSettings();
          })
      }
      .width('100%')
      .padding({ top: 8, bottom: 8 })
    }
    .width('100%')
    .padding(16)
    .borderRadius(12)
    .backgroundColor(SilkColors.surface)
    .margin({ bottom: 16 })
  }

  @Builder
  AgentSetting() {
    Column() {
      Text('AI 助手设置')
        .fontSize(16)
        .fontWeight(FontWeight.Bold)
        .fontColor(SilkColors.textPrimary)
        .width('100%')
        .margin({ bottom: 12 })

      Text('默认指令')
        .fontSize(14)
        .fontColor(SilkColors.textSecondary)
        .width('100%')
        .margin({ bottom: 8 })

      TextArea({ placeholder: '输入 AI 默认指令...', text: $$defaultAgentInstruction })
        .width('100%')
        .height(120)
        .fontSize(14)
        .fontColor(SilkColors.textPrimary)
        .backgroundColor(SilkColors.background)
        .borderRadius(8)
        .onChange((value: string) => {
          this.defaultAgentInstruction = value;
        })

      Button('保存设置')
        .width('100%')
        .height(44)
        .margin({ top: 12 })
        .backgroundColor(SilkColors.primary)
        .fontColor(Color.White)
        .enabled(!this.isLoading)
        .onClick(() => {
          this.saveSettings();
        })
    }
    .width('100%')
    .padding(16)
    .borderRadius(12)
    .backgroundColor(SilkColors.surface)
    .margin({ bottom: 16 })
  }

  @Builder
  OtherOptions() {
    Column() {
      this.SettingItem('关于', '版本 1.0.0', () => {
        this.statusMessage = 'Silk 鸿蒙版 v1.0.0';
      })

      Divider().color(SilkColors.border).margin({ left: 16 })

      this.SettingItem('清除缓存', '', () => {
        this.statusMessage = '缓存已清除';
      })
    }
    .width('100%')
    .borderRadius(12)
    .backgroundColor(SilkColors.surface)
    .margin({ bottom: 16 })
  }

  @Builder
  SettingItem(title: string, subtitle: string, action: () => void) {
    Row() {
      Text(title)
        .fontSize(16)
        .fontColor(SilkColors.textPrimary)
        .layoutWeight(1)

      if (subtitle) {
        Text(subtitle)
          .fontSize(14)
          .fontColor(SilkColors.textSecondary)
      }

      Text('>')
        .fontSize(16)
        .fontColor(SilkColors.textLight)
    }
    .width('100%')
    .height(56)
    .padding({ left: 16, right: 16 })
    .onClick(action)
  }

  @Builder
  LogoutButton() {
    Button('退出登录')
      .width('100%')
      .height(50)
      .backgroundColor(SilkColors.error)
      .fontColor(Color.White)
      .borderRadius(12)
      .onClick(() => {
        this.logout();
      })
  }

  private async saveSettings() {
    this.isLoading = true;
    try {
      const settings: UserSettings = {
        language: this.language,
        defaultAgentInstruction: this.defaultAgentInstruction
      };
      await this.appStore.updateUserSettings(settings);
      
      // 同步到服务器
      const userId = this.appStore.getCurrentUser()?.id;
      if (userId) {
        await this.apiClient.updateUserSettings(userId, settings);
      }
      
      this.statusMessage = '设置已保存';
    } catch (e) {
      const typedError = e as Error;
      this.statusMessage = `保存失败: ${typedError.message}`;
    } finally {
      this.isLoading = false;
    }
  }

  private async logout(): Promise<void> {
    await this.appStore.clearSession();
    appRouter.goToLogin();
  }
}
