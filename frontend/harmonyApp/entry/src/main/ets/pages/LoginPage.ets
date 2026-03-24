/**
 * 登录/注册页面
 * 对齐 androidApp/LoginScreen.kt
 */
import { SilkColors } from '../common/SilkColors';
import { login, register } from '../service/ApiService';
import { saveUser } from '../store/UserStore';
import { User } from '../model/UserModel';

@Component
export struct LoginPage {
  @Consume('navPathStack') navPathStack: NavPathStack;
  @State isLoginMode: boolean = true;
  @State loginName: string = '';
  @State password: string = '';
  @State fullName: string = '';
  @State phoneNumber: string = '';
  @State isLoading: boolean = false;
  @State errorMessage: string = '';

  async handleLogin(): Promise<void> {
    if (this.loginName.trim() === '' || this.password.trim() === '') {
      this.errorMessage = '请输入用户名和密码';
      return;
    }
    this.isLoading = true;
    this.errorMessage = '';

    const response = await login(this.loginName.trim(), this.password);
    this.isLoading = false;

    if (response.success && response.user) {
      await saveUser(response.user);
      // 清空栈并跳转到群组列表
      this.navPathStack.clear();
      this.navPathStack.pushPath({ name: 'groupList' });
    } else {
      this.errorMessage = response.message || '登录失败';
    }
  }

  async handleRegister(): Promise<void> {
    if (this.loginName.trim() === '' || this.fullName.trim() === '' ||
      this.phoneNumber.trim() === '' || this.password.trim() === '') {
      this.errorMessage = '请填写所有字段';
      return;
    }
    if (this.password.length < 6) {
      this.errorMessage = '密码至少 6 位';
      return;
    }
    this.isLoading = true;
    this.errorMessage = '';

    const response = await register(
      this.loginName.trim(), this.fullName.trim(),
      this.phoneNumber.trim(), this.password
    );
    this.isLoading = false;

    if (response.success && response.user) {
      await saveUser(response.user);
      this.navPathStack.clear();
      this.navPathStack.pushPath({ name: 'groupList' });
    } else {
      this.errorMessage = response.message || '注册失败';
    }
  }

  build() {
    NavDestination() {
      Column() {
        // Logo 区域
        Column() {
          Text('SILK')
            .fontSize(42)
            .fontWeight(FontWeight.Bold)
            .fontColor(SilkColors.primary)
            .margin({ bottom: 4 })

          Text('即时通讯')
            .fontSize(14)
            .fontColor(SilkColors.textSecondary)
        }
        .margin({ top: 80, bottom: 48 })

        // 切换 Tab
        Row() {
          Text('登录')
            .fontSize(16)
            .fontWeight(this.isLoginMode ? FontWeight.Bold : FontWeight.Normal)
            .fontColor(this.isLoginMode ? SilkColors.primary : SilkColors.textSecondary)
            .padding({ left: 20, right: 20, top: 10, bottom: 10 })
            .onClick(() => {
              this.isLoginMode = true;
              this.errorMessage = '';
            })

          Text('注册')
            .fontSize(16)
            .fontWeight(!this.isLoginMode ? FontWeight.Bold : FontWeight.Normal)
            .fontColor(!this.isLoginMode ? SilkColors.primary : SilkColors.textSecondary)
            .padding({ left: 20, right: 20, top: 10, bottom: 10 })
            .onClick(() => {
              this.isLoginMode = false;
              this.errorMessage = '';
            })
        }
        .margin({ bottom: 24 })

        // 表单
        Column() {
          TextInput({ placeholder: '用户名', text: this.loginName })
            .onChange((value: string) => { this.loginName = value; })
            .width('100%')
            .height(48)
            .backgroundColor(SilkColors.surface)
            .borderRadius(8)
            .margin({ bottom: 12 })

          if (!this.isLoginMode) {
            TextInput({ placeholder: '全名', text: this.fullName })
              .onChange((value: string) => { this.fullName = value; })
              .width('100%')
              .height(48)
              .backgroundColor(SilkColors.surface)
              .borderRadius(8)
              .margin({ bottom: 12 })

            TextInput({ placeholder: '手机号', text: this.phoneNumber })
              .onChange((value: string) => { this.phoneNumber = value; })
              .type(InputType.PhoneNumber)
              .width('100%')
              .height(48)
              .backgroundColor(SilkColors.surface)
              .borderRadius(8)
              .margin({ bottom: 12 })
          }

          TextInput({ placeholder: '密码', text: this.password })
            .onChange((value: string) => { this.password = value; })
            .type(InputType.Password)
            .width('100%')
            .height(48)
            .backgroundColor(SilkColors.surface)
            .borderRadius(8)
            .margin({ bottom: 20 })

          // 错误提示
          if (this.errorMessage !== '') {
            Text(this.errorMessage)
              .fontSize(14)
              .fontColor(SilkColors.error)
              .margin({ bottom: 12 })
          }

          // 提交按钮
          Button(this.isLoading ? '请稍候...' : (this.isLoginMode ? '登录' : '注册'))
            .width('100%')
            .height(48)
            .backgroundColor(SilkColors.primary)
            .fontColor(SilkColors.textOnPrimary)
            .borderRadius(8)
            .enabled(!this.isLoading)
            .onClick(() => {
              if (this.isLoginMode) {
                this.handleLogin();
              } else {
                this.handleRegister();
              }
            })
        }
        .width('85%')
      }
      .width('100%')
      .height('100%')
      .backgroundColor(SilkColors.background)
      .justifyContent(FlexAlign.Start)
      .alignItems(HorizontalAlign.Center)
    }
    .hideTitleBar(true)
  }
}
