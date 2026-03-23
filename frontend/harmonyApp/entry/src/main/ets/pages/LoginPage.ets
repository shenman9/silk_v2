import { router } from '@kit.ArkUI';
import { promptAction } from '@kit.ArkUI';
import { ApiClient } from '../api/ApiClient';
import { AppStore } from '../stores/AppStore';
import { Theme, SilkColors } from '../common/Theme';

/**
 * 登录页面
 * 支持登录和注册两种模式
 */
@Entry
@Component
struct LoginPage {
  // 状态
  @State isLogin: boolean = true; // true = 登录, false = 注册
  @State username: string = '';
  @State password: string = '';
  @State fullName: string = '';
  @State phoneNumber: string = '';
  @State isLoading: boolean = false;
  @State errorMessage: string = '';

  private apiClient: ApiClient = new ApiClient();

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

      // 密码输入
      TextInput({ placeholder: '密码' })
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

      if (response.success && response.user) {
        // 保存用户信息到全局状态
        AppStore.getInstance().setCurrentUser(response.user);

        // 保存登录状态
        await this.saveLoginState(response.user);

        // 跳转到群组列表页面
        router.replaceUrl({
          url: 'pages/GroupListPage'
        });
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

  // 处理注册
  private async handleRegister(): Promise<void> {
    this.isLoading = true;
    this.errorMessage = '';

    try {
      const response = await this.apiClient.register(
        this.username,
        this.password,
        this.fullName,
        this.phoneNumber
      );

      if (response.success) {
        // 注册成功，切换到登录
        this.isLogin = true;
        promptAction.showToast({
          message: '注册成功，请登录',
          duration: 2000
        });
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
  private async saveLoginState(user: any): Promise<void> {
    // 使用 Preferences 保存用户信息
    // 这里简化处理，实际应用中需要使用持久化存储
    console.log('保存用户信息:', user);
  }
}
