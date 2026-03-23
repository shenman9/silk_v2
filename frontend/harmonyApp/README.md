# Silk 鸿蒙原生应用

基于 HarmonyOS Next (纯血鸿蒙) 的原生聊天应用，使用 ArkTS + ArkUI 开发。

## 技术栈

- **语言**: ArkTS (TypeScript 严格类型超集)
- **UI 框架**: ArkUI (声明式 UI)
- **网络**: @ohos.net.http + @ohos.net.webSocket
- **状态管理**: @State, @Link, @Prop 装饰器

## 项目结构

```
harmonyApp/
├── entry/                          # 应用入口模块
│   ├── src/main/
│   │   ├── ets/                    # ArkTS 源码
│   │   │   ├── api/                # 网络层
│   │   │   │   ├── ApiClient.ets   # HTTP 客户端
│   │   │   │   └── WebSocketClient.ets  # WebSocket 客户端
│   │   │   ├── common/             # 通用工具
│   │   │   │   ├── Constants.ets   # 常量配置
│   │   │   │   ├── Theme.ets       # 主题样式
│   │   │   │   └── Router.ets      # 路由管理
│   │   │   ├── components/         # UI 组件
│   │   │   │   ├── MessageBubble.ets
│   │   │   │   ├── GroupCard.ets
│   │   │   │   ├── ChatInput.ets
│   │   │   │   └── LoadingIndicator.ets
│   │   │   ├── models/             # 数据模型
│   │   │   │   ├── Message.ets
│   │   │   │   ├── User.ets
│   │   │   │   ├── Group.ets
│   │   │   │   └── UserSettings.ets
│   │   │   ├── pages/              # 页面
│   │   │   │   ├── Index.ets       # 入口页面
│   │   │   │   ├── LoginPage.ets   # 登录/注册
│   │   │   │   ├── GroupListPage.ets  # 群组列表
│   │   │   │   ├── ChatPage.ets    # 聊天界面
│   │   │   │   └── SettingsPage.ets  # 设置页面
│   │   │   ├── stores/             # 状态管理
│   │   │   │   ├── AppStore.ets    # 应用状态
│   │   │   │   └── ChatStore.ets   # 聊天状态
│   │   │   └── entryability/
│   │   │       └── EntryAbility.ets  # 应用入口能力
│   │   ├── resources/              # 资源文件
│   │   │   ├── base/               # 基础资源
│   │   │   ├── en_US/              # 英文
│   │   │   └── zh_CN/              # 中文
│   │   └── module.json5            # 模块配置
│   ├── build-profile.json5         # 构建配置
│   ├── hvigorfile.ts               # 构建脚本
│   └── oh-package.json5            # 依赖配置
├── build-profile.json5             # 项目构建配置
├── hvigorfile.ts                   # 项目构建脚本
└── oh-package.json5                # 项目依赖配置
```

## 开发环境

### 必需工具

1. **DevEco Studio** (华为官方 IDE)
   - 下载地址: https://developer.huawei.com/consumer/cn/deveco-studio/
   - 版本要求: 4.0+ (支持 HarmonyOS Next)

2. **HarmonyOS SDK**
   - API 版本: 12+ (纯血鸿蒙)
   - 通过 DevEco Studio SDK Manager 安装

### 配置后端地址

修改 `entry/src/main/ets/common/Constants.ets`:

```typescript
export class Constants {
  // 开发环境使用本地地址
  static readonly BACKEND_HOST: string = 'YOUR_BACKEND_HOST';
  static readonly BACKEND_PORT: string = '8003';
  // ...
}
```

## 构建与运行

### 方式一: 使用 silk.sh 脚本

```bash
# 构建 HAP 包
./silk.sh build-hap

# 或使用简写
./silk.sh bh
```

### 方式二: 使用 DevEco Studio

1. 用 DevEco Studio 打开 `frontend/harmonyApp` 目录
2. 等待项目索引完成
3. 点击 Run 按钮或使用快捷键运行

### 构建输出

- Debug HAP: `entry/build/default/outputs/default/entry-default-*.hap`
- Release HAP: `entry/build/default/outputs/default/entry-default-release-*.hap`

## 后端 API 对接

本应用复用 silk 后端 API:

| API | 方法 | 路径 | 说明 |
|-----|------|------|------|
| 登录 | POST | /api/auth/login | 用户登录 |
| 注册 | POST | /api/auth/register | 用户注册 |
| 群组列表 | GET | /api/groups | 获取用户群组 |
| 创建群组 | POST | /api/groups | 创建新群组 |
| WebSocket | WS | /chat?userId=...&groupId=... | 实时通信 |
| 文件搜索 | POST | /api/search | 文件搜索 |
| 用户设置 | GET/PUT | /api/user/settings | 用户设置 |

## 功能特性

- 用户登录/注册
- 群组管理 (创建、加入、离开)
- 实时聊天 (WebSocket)
- AI 对话支持
- 文件搜索
- 多语言支持 (中英文)
- 消息撤回
- 离线消息同步

## 与 Android/Web 版本的差异

| 方面 | Android/Web | 鸿蒙 |
|------|-------------|------|
| 语言 | Kotlin | ArkTS |
| UI 框架 | Compose | ArkUI |
| 网络库 | Ktor | @ohos.net.* |
| 状态管理 | StateFlow | @State 装饰器 |
| 共享代码 | KMP | 无法直接复用 |

## 注意事项

1. **纯血鸿蒙限制**: 
   - 不支持 AOSP，无法运行 Android APK
   - 必须使用 ArkTS 开发

2. **开发调试**:
   - 需要真机或 DevEco Studio 模拟器
   - 建议使用真机进行 WebSocket 测试

3. **签名发布**:
   - Debug 模式可使用自动签名
   - Release 需要申请华为开发者账号

## 相关文档

- [HarmonyOS 开发文档](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/)
- [ArkTS 语言指南](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/arkts-get-started-V5)
- [ArkUI 组件参考](https://developer.huawei.com/consumer/cn/doc/harmonyos-references-V5/arkui-ts-V5)
