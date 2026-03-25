import { apiClient as globalApiClient } from '../api/ApiClient';
import { appStore } from '../stores/AppStore';
import { SilkColors } from '../common/Theme';
import { User } from '../models/User';
import { router as appRouter } from '../common/Router';
import promptAction from '@ohos.promptAction';
import dataPreferences from '@ohos.data.preferences';
import common from '@ohos.app.ability.common';

/**
 * 登录页面
 * 支持登录和注册两种模式
 */
@Component
export struct LoginPage {
  // 状态
  @State isLogin: boolean = true; // true = 登录, false = 注册
  @State username: string = '';
  @State password: string = '';
  @State fullName: string = '';
  @State phoneNumber: string = '';
  @State isLoading: boolean = false;
  @State errorMessage: string = '';
  private static readonly QUICK_LOGIN_STORE: string = 'silk_quick_login';
  private static readonly QUICK_LOGIN_USER_KEY: string = 'quickLoginUserName';
  private static readonly QUICK_LOGIN_PASS_KEY: string = 'quickLoginPassword';

  private readonly apiClient = globalApiClient;

  build() {
    Column() {
      // Logo 区域
      this.LogoSection()

      // 表单区域
      if (this.isLogin) {
        this.LoginForm()
      } else {
        this.RegisterForm()
      }

      // 切换按钮
      this.SwitchButton()
    }
    .width('100%')
    .height('100%')
    .linearGradient({
      angle: 180,
      colors: [
        [SilkColors.background, 0],
        [SilkColors.secondary, 0.5],
        [SilkColors.background, 1]
      ]
    })
    .padding({ left: 24, right: 24 })
  }

  // Logo 区域
  @Builder
  LogoSection() {
    Column() {
      Text('SILK')
        .fontSize(48)
        .fontColor(SilkColors.primary)
        .fontWeight(FontWeight.Bold)
        .letterSpacing(8)

      Text('智能协作平台')
        .fontSize(16)
        .fontColor(SilkColors.textSecondary)
        .margin({ top: 8 })
    }
    .margin({ top: 80, bottom: 48 })
  }

  // 登录表单
  @Builder
  LoginForm() {
    Column() {
      // 用户名输入
      TextInput({ placeholder: '用户名', text: $$this.username })
        .width('100%')
        .height(50)
        .backgroundColor(SilkColors.surface)
        .borderRadius(8)
        .padding({ left: 16, right: 16 })
        .fontColor(SilkColors.textPrimary)
        .placeholderColor(SilkColors.textLight)
        .onChange((value: string) => {
          this.username = value;
        })

      // 密码输入
      TextInput({ placeholder: '密码', text: $$this.password })
        .width('100%')
        .height(50)
        .type(InputType.Password)
        .backgroundColor(SilkColors.surface)
        .borderRadius(8)
        .padding({ left: 16, right: 16 })
        .margin({ top: 16 })
        .fontColor(SilkColors.textPrimary)
        .placeholderColor(SilkColors.textLight)
        .onChange((value: string) => {
          this.password = value;
        })

      // 错误消息
      if (this.errorMessage) {
        Text(this.errorMessage)
          .fontSize(14)
          .fontColor(SilkColors.error)
          .margin({ top: 16 })
      }

      // 登录按钮
      Button(this.isLoading ? '登录中...' : '登录')
        .width('100%')
        .height(50)
        .backgroundColor(SilkColors.primary)
        .borderRadius(8)
        .fontColor(Color.White)
        .fontSize(16)
        .fontWeight(FontWeight.Medium)
        .margin({ top: 24 })
        .enabled(!this.isLoading && this.username.length > 0 && this.password.length > 0)
        .opacity((!this.isLoading && this.username.length > 0 && this.password.length > 0) ? 1 : 0.5)
        .onClick(() => {
          this.handleLogin();
        })

      Row() {
        Button('保存为一键登录')
          .layoutWeight(1)
          .height(42)
          .backgroundColor(SilkColors.surfaceVariant)
          .borderRadius(8)
          .fontColor(SilkColors.textPrimary)
          .fontSize(14)
          .enabled(!this.isLoading && this.username.length > 0 && this.password.length > 0)
          .opacity((!this.isLoading && this.username.length > 0 && this.password.length > 0) ? 1 : 0.5)
          .onClick(() => {
            this.saveQuickLoginCredentials();
          })

        Button('一键登录')
          .layoutWeight(1)
          .height(42)
          .backgroundColor('#D7C3A0')
          .borderRadius(8)
          .fontColor(SilkColors.textPrimary)
          .fontSize(14)
          .margin({ left: 8 })
          .enabled(!this.isLoading)
          .opacity(!this.isLoading ? 1 : 0.5)
          .onClick(() => {
            this.handleQuickLogin();
          })
      }
      .width('100%')
      .margin({ top: 12 })
    }
    .width('100%')
    .padding({ left: 16, right: 16 })
  }

  // 注册表单
  @Builder
  RegisterForm() {
    Column() {
      // 用户名
      TextInput({ placeholder: '用户名' })
        .width('100%')
        .height(50)
        .backgroundColor(SilkColors.surface)
        .borderRadius(8)
        .padding({ left: 16, right: 16 })
        .fontColor(SilkColors.textPrimary)
        .placeholderColor(SilkColors.textLight)
        .onChange((value: string) => {
          this.username = value;
        })

      // 密码
      TextInput({ placeholder: '密码' })
        .width('100%')
        .height(50)
        .type(InputType.Password)
        .backgroundColor(SilkColors.surface)
        .borderRadius(8)
        .padding({ left: 16, right: 16 })
        .margin({ top: 12 })
        .fontColor(SilkColors.textPrimary)
        .placeholderColor(SilkColors.textLight)
        .onChange((value: string) => {
          this.password = value;
        })

      // 全名
      TextInput({ placeholder: '全名' })
        .width('100%')
        .height(50)
        .backgroundColor(SilkColors.surface)
        .borderRadius(8)
        .padding({ left: 16, right: 16 })
        .margin({ top: 12 })
        .fontColor(SilkColors.textPrimary)
        .placeholderColor(SilkColors.textLight)
        .onChange((value: string) => {
          this.fullName = value;
        })

      // 手机号
      TextInput({ placeholder: '手机号' })
        .width('100%')
        .height(50)
        .type(InputType.PhoneNumber)
        .backgroundColor(SilkColors.surface)
        .borderRadius(8)
        .padding({ left: 16, right: 16 })
        .margin({ top: 12 })
        .fontColor(SilkColors.textPrimary)
        .placeholderColor(SilkColors.textLight)
        .onChange((value: string) => {
          this.phoneNumber = value;
        })

      // 错误消息
      if (this.errorMessage) {
        Text(this.errorMessage)
          .fontSize(14)
          .fontColor(SilkColors.error)
          .margin({ top: 16 })
      }

      // 注册按钮
      Button(this.isLoading ? '注册中...' : '注册')
        .width('100%')
        .height(50)
        .backgroundColor(SilkColors.primary)
        .borderRadius(8)
        .fontColor(Color.White)
        .fontSize(16)
        .fontWeight(FontWeight.Medium)
        .margin({ top: 24 })
        .enabled(!this.isLoading && this.username.length > 0 && this.password.length > 0)
        .opacity((!this.isLoading && this.username.length > 0 && this.password.length > 0) ? 1 : 0.5)
        .onClick(() => {
          this.handleRegister();
        })
    }
    .width('100%')
    .padding({ left: 16, right: 16 })
  }

  // 切换按钮
  @Builder
  SwitchButton() {
    Row() {
      Text(this.isLogin ? '没有账号？' : '已有账号？')
        .fontSize(14)
        .fontColor(SilkColors.textSecondary)

      Text(this.isLogin ? '立即注册' : '立即登录')
        .fontSize(14)
        .fontColor(SilkColors.primary)
        .fontWeight(FontWeight.Medium)
        .onClick(() => {
          this.isLogin = !this.isLogin;
          this.errorMessage = '';
        })
    }
    .margin({ top: 24 })
  }

  // 处理登录
  private async handleLogin(): Promise<void> {
    this.isLoading = true;
    this.errorMessage = '';

    try {
      const response = await this.apiClient.login(this.username, this.password);

      const loginData = response.data;
      if (response.success && loginData && loginData.user) {
        // 保存用户信息到全局状态
        appStore.setCurrentUser(loginData.user);

        // 保存登录状态
        await this.saveLoginState(loginData.user);

        appRouter.goToGroupList();
      } else {
        this.errorMessage = response.message || '登录失败，请检查用户名和密码';
      }
    } catch (error) {
      this.errorMessage = '网络错误，请稍后重试';
      console.error('登录错误:', error);
    } finally {
      this.isLoading = false;
    }
  }

  private async handleLoginWithCredentials(username: string, password: string): Promise<void> {
    this.username = username;
    this.password = password;
    await this.handleLogin();
  }

  private async saveQuickLoginCredentials(): Promise<void> {
    if (this.username.trim().length === 0 || this.password.length === 0) {
      this.showToast('请先输入用户名和密码');
      return;
    }
    try {
      const context = getContext(this) as common.UIAbilityContext;
      const pref = await dataPreferences.getPreferences(context, LoginPage.QUICK_LOGIN_STORE);
      await pref.put(LoginPage.QUICK_LOGIN_USER_KEY, this.username.trim());
      await pref.put(LoginPage.QUICK_LOGIN_PASS_KEY, this.password);
      await pref.flush();
      // 兼容旧流程：同时写入 AppStorage（仅会话内）
      AppStorage.setOrCreate<string>(LoginPage.QUICK_LOGIN_USER_KEY, this.username.trim());
      AppStorage.setOrCreate<string>(LoginPage.QUICK_LOGIN_PASS_KEY, this.password);
      this.showToast('已保存一键登录凭据');
    } catch (error) {
      console.error('保存一键登录失败:', error);
      this.showToast('保存失败，请重试');
    }
  }

  private async handleQuickLogin(): Promise<void> {
    let username = '';
    let password = '';
    try {
      const context = getContext(this) as common.UIAbilityContext;
      const pref = await dataPreferences.getPreferences(context, LoginPage.QUICK_LOGIN_STORE);
      const prefUser = await pref.get(LoginPage.QUICK_LOGIN_USER_KEY, '');
      const prefPass = await pref.get(LoginPage.QUICK_LOGIN_PASS_KEY, '');
      username = `${prefUser}`.trim();
      password = `${prefPass}`;
    } catch (error) {
      console.error('读取一键登录失败:', error);
    }

    // 兼容迁移：若持久化尚无数据，尝试读取旧的 AppStorage 一次性迁移
    if (username.length === 0 || password.length === 0) {
      const localUser = AppStorage.get<string>(LoginPage.QUICK_LOGIN_USER_KEY);
      const localPass = AppStorage.get<string>(LoginPage.QUICK_LOGIN_PASS_KEY);
      username = localUser ? localUser.trim() : '';
      password = localPass ? localPass : '';
      if (username.length > 0 && password.length > 0) {
        await this.saveQuickLoginCredentials();
      }
    }

    if (username.length === 0 || password.length === 0) {
      this.showToast('请先手动登录并点击“保存为一键登录”');
      return;
    }
    await this.handleLoginWithCredentials(username, password);
  }

  // 处理注册
  private async handleRegister(): Promise<void> {
    this.isLoading = true;
    this.errorMessage = '';

    try {
      const response = await this.apiClient.register(
        {
          loginName: this.username,
          password: this.password,
          fullName: this.fullName,
          phoneNumber: this.phoneNumber
        }
      );

      if (response.success) {
        // 注册成功，切换到登录
        this.isLogin = true;
        this.errorMessage = '注册成功，请登录';
      } else {
        this.errorMessage = response.message || '注册失败';
      }
    } catch (error) {
      this.errorMessage = '网络错误，请稍后重试';
      console.error('注册错误:', error);
    } finally {
      this.isLoading = false;
    }
  }

  // 保存登录状态
  private async saveLoginState(user: User): Promise<void> {
    // 存储到 AppStorage，供 ChatPage 使用
    AppStorage.setOrCreate<string>('currentUserId', user.id);
    AppStorage.setOrCreate<string>('currentUserName', user.name);
    console.log('保存用户信息:', user);
  }

  private showToast(message: string): void {
    try {
      promptAction.showToast({
        message: message,
        duration: 1200
      });
    } catch (error) {
      console.error('Toast failed:', error);
    }
  }
}
