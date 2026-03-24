/**
 * 设置页面
 * 对齐 androidApp/SettingsScreen.kt
 * 功能: 语言切换 (EN/ZH)、默认 AI 指令、保存/加载设置
 */
import { SilkColors } from '../common/SilkColors';
import { loadUser } from '../store/UserStore';
import { User } from '../model/UserModel';
import { Language, UserSettings } from '../model/SettingsModel';
import { getUserSettings, updateUserSettings } from '../service/ApiService';

@Component
export struct SettingsPage {
  @Consume('navPathStack') navPathStack: NavPathStack;
  @State currentUser: User | null = null;
  @State isLoading: boolean = false;
  @State isSaving: boolean = false;
  @State selectedLanguage: Language = Language.CHINESE;
  @State defaultInstruction: string = '';
  @State saveMessage: string = '';
  @State saveSuccess: boolean = false;

  aboutToAppear(): void {
    this.currentUser = loadUser();
    if (this.currentUser) {
      this.loadSettings();
    }
  }

  async loadSettings(): Promise<void> {
    if (!this.currentUser) return;
    this.isLoading = true;

    const res = await getUserSettings(this.currentUser.id);
    if (res.success && res.settings) {
      const settings: UserSettings = res.settings;
      this.selectedLanguage = settings.language;
      this.defaultInstruction = settings.defaultAgentInstruction;
    }
    this.isLoading = false;
  }

  async handleSave(): Promise<void> {
    if (!this.currentUser) return;
    this.isSaving = true;
    this.saveMessage = '';

    const res = await updateUserSettings(
      this.currentUser.id, this.selectedLanguage, this.defaultInstruction
    );
    this.isSaving = false;

    if (res.success) {
      this.saveMessage = '设置保存成功';
      this.saveSuccess = true;
    } else {
      this.saveMessage = res.message || '保存设置失败';
      this.saveSuccess = false;
    }
  }

  build() {
    NavDestination() {
      Column() {
        // 顶栏
        Row() {
          Button('< 返回')
            .fontSize(14)
            .fontColor(SilkColors.primary)
            .backgroundColor(Color.Transparent)
            .onClick(() => { this.navPathStack.pop(); })

          Text('设置')
            .fontSize(18)
            .fontWeight(FontWeight.Bold)
            .fontColor(SilkColors.textPrimary)
            .layoutWeight(1)
            .textAlign(TextAlign.Center)

          // 占位，保持标题居中
          Text('')
            .width(60)
        }
        .width('100%')
        .padding({ left: 8, right: 12, top: 10, bottom: 10 })
        .backgroundColor(SilkColors.surface)

        Divider().color(SilkColors.divider)

        if (this.isLoading) {
          Column() {
            LoadingProgress().width(40).height(40).color(SilkColors.primary)
            Text('加载中...')
              .fontSize(14)
              .fontColor(SilkColors.textSecondary)
              .margin({ top: 12 })
          }
          .width('100%')
          .layoutWeight(1)
          .justifyContent(FlexAlign.Center)
        } else {
          Scroll() {
            Column() {
              // 语言设置
              Column() {
                Text('语言')
                  .fontSize(16)
                  .fontWeight(FontWeight.Bold)
                  .fontColor(SilkColors.textPrimary)
                  .margin({ bottom: 12 })

                Row() {
                  // English 选项
                  Row() {
                    Radio({ value: 'en', group: 'language' })
                      .checked(this.selectedLanguage === Language.ENGLISH)
                      .onChange((isChecked: boolean) => {
                        if (isChecked) {
                          this.selectedLanguage = Language.ENGLISH;
                        }
                      })
                    Text('English')
                      .fontSize(15)
                      .fontColor(this.selectedLanguage === Language.ENGLISH ?
                        SilkColors.primary : SilkColors.textPrimary)
                      .fontWeight(this.selectedLanguage === Language.ENGLISH ?
                        FontWeight.Bold : FontWeight.Normal)
                      .margin({ left: 8 })
                  }
                  .padding({ left: 12, right: 16, top: 10, bottom: 10 })
                  .backgroundColor(this.selectedLanguage === Language.ENGLISH ?
                    SilkColors.surfaceVariant : Color.Transparent)
                  .borderRadius(20)
                  .onClick(() => { this.selectedLanguage = Language.ENGLISH; })

                  // 中文选项
                  Row() {
                    Radio({ value: 'zh', group: 'language' })
                      .checked(this.selectedLanguage === Language.CHINESE)
                      .onChange((isChecked: boolean) => {
                        if (isChecked) {
                          this.selectedLanguage = Language.CHINESE;
                        }
                      })
                    Text('中文')
                      .fontSize(15)
                      .fontColor(this.selectedLanguage === Language.CHINESE ?
                        SilkColors.primary : SilkColors.textPrimary)
                      .fontWeight(this.selectedLanguage === Language.CHINESE ?
                        FontWeight.Bold : FontWeight.Normal)
                      .margin({ left: 8 })
                  }
                  .padding({ left: 12, right: 16, top: 10, bottom: 10 })
                  .backgroundColor(this.selectedLanguage === Language.CHINESE ?
                    SilkColors.surfaceVariant : Color.Transparent)
                  .borderRadius(20)
                  .margin({ left: 12 })
                  .onClick(() => { this.selectedLanguage = Language.CHINESE; })
                }
              }
              .width('100%')
              .padding(20)
              .backgroundColor(SilkColors.surface)
              .borderRadius(12)
              .alignItems(HorizontalAlign.Start)

              // 默认代理指令
              Column() {
                Text('默认代理指令')
                  .fontSize(16)
                  .fontWeight(FontWeight.Bold)
                  .fontColor(SilkColors.textPrimary)
                  .margin({ bottom: 12 })

                TextArea({ placeholder: '输入默认代理指令...', text: this.defaultInstruction })
                  .onChange((value: string) => { this.defaultInstruction = value; })
                  .width('100%')
                  .height(150)
                  .borderRadius(8)
                  .backgroundColor(SilkColors.surfaceVariant)
                  .padding(12)
              }
              .width('100%')
              .padding(20)
              .backgroundColor(SilkColors.surface)
              .borderRadius(12)
              .alignItems(HorizontalAlign.Start)
              .margin({ top: 12 })

              // 保存状态消息
              if (this.saveMessage !== '') {
                Text(this.saveMessage)
                  .fontSize(14)
                  .fontColor(this.saveSuccess ? SilkColors.success : SilkColors.error)
                  .margin({ top: 12 })
              }

              // 按钮区
              Row() {
                Button('取消')
                  .fontSize(14)
                  .fontColor(SilkColors.textSecondary)
                  .backgroundColor(Color.Transparent)
                  .borderWidth(1)
                  .borderColor(SilkColors.divider)
                  .borderRadius(8)
                  .height(44)
                  .layoutWeight(1)
                  .onClick(() => { this.navPathStack.pop(); })

                Button(this.isSaving ? '保存中...' : '保存')
                  .fontSize(14)
                  .fontColor(SilkColors.textOnPrimary)
                  .backgroundColor(SilkColors.primary)
                  .borderRadius(8)
                  .height(44)
                  .layoutWeight(1)
                  .margin({ left: 12 })
                  .enabled(!this.isSaving)
                  .onClick(() => { this.handleSave(); })
              }
              .width('100%')
              .margin({ top: 24 })
            }
            .padding(16)
          }
          .layoutWeight(1)
        }
      }
      .width('100%')
      .height('100%')
      .backgroundColor(SilkColors.background)
    }
    .hideTitleBar(true)
  }
}
