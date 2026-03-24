# Silk 鸿蒙版 (HarmonyOS) 构建与安装指南

## 一、环境准备

### 1.1 安装 DevEco Studio

- 下载地址：https://developer.huawei.com/consumer/cn/deveco-studio/
- 版本要求：DevEco Studio 5.0 或以上（支持 HarmonyOS NEXT / API 12+）
- 安装过程中会自动下载 HarmonyOS SDK

### 1.2 确认 SDK 安装

打开 DevEco Studio → File → Settings → SDK Manager，确保已安装：
- HarmonyOS SDK (API 12)
- Build Tools
- Toolchains（包含 hdc 命令行工具）

### 1.3 配置 hdc 环境变量（可选）

`hdc` 是鸿蒙的设备连接工具（等同于 Android 的 adb）。

默认路径（Windows）：
```
C:\Users\<用户名>\AppData\Local\Huawei\Sdk\openharmony\<版本号>\toolchains\
```

将该路径添加到系统 PATH 环境变量，即可在终端直接使用 `hdc` 命令。

如果不配置，也可以在 DevEco Studio 内置 Terminal（`Alt + F12`）中直接使用。

---

## 二、获取源码

### 2.1 从代码仓获取

只需要复制 `frontend/harmonyApp/` 目录即可，不依赖仓库中的其他代码。

```bash
# 示例：从服务器复制到本地
scp -r 用户名@服务器地址:/path/to/silk_v2/frontend/harmonyApp D:\work\silk\harmonyApp
```

### 2.2 目录结构

```
harmonyApp/
├── AppScope/                          # 应用级配置
│   ├── app.json5                      # 应用元数据 (bundleName, versionCode 等)
│   └── resources/                     # 应用级资源
├── entry/                             # 主模块
│   ├── src/main/
│   │   ├── ets/                       # ArkTS 源码（全部业务代码在此）
│   │   │   ├── pages/                 # 页面 (Index, Login, GroupList, Chat, Contacts, Settings)
│   │   │   ├── components/            # 组件 (ChatBubble, ChatInput, GroupCard, ConnectionStatus)
│   │   │   ├── model/                 # 数据模型
│   │   │   ├── service/               # 网络服务 (HTTP, WebSocket, API)
│   │   │   ├── store/                 # 本地存储
│   │   │   ├── common/                # 颜色、字符串、工具函数
│   │   │   ├── config/                # 后端地址配置
│   │   │   └── entryability/          # 应用入口 Ability
│   │   ├── resources/                 # 模块资源 (颜色、字符串、图标)
│   │   └── module.json5              # 模块声明 (权限、桌面入口)
│   ├── build-profile.json5
│   └── oh-package.json5
├── build-profile.json5                # ★ 构建配置（含后端地址）
├── oh-package.json5                   # 包管理配置
└── hvigor/
    └── hvigor-config.json5            # 构建工具配置
```

---

## 三、配置项目

### 3.1 修改后端地址（必须）

打开项目根目录的 `build-profile.json5`，找到 `buildProfileFields`：

```json5
"buildProfileFields": {
  "backendBaseUrl": "http://10.0.2.2:8006"
}
```

根据实际情况修改：

| 场景 | 地址 |
|------|------|
| 模拟器访问本机后端 | `http://10.0.2.2:8006` |
| 真机访问局域网后端 | `http://192.168.x.x:8006` |
| 访问公网后端 | `http://你的域名:8006` |

> 说明：`10.0.2.2` 是鸿蒙/安卓模拟器中访问宿主机 localhost 的特殊地址。

### 3.2 修改图标资源（必须）

`entry/src/main/module.json5` 中引用了应用图标：

```json5
"icon": "$media:app_icon",
"startWindowIcon": "$media:app_icon",
```

请确保 `entry/src/main/resources/base/media/` 下有对应的图标文件。

**如果没有 `app_icon` 资源**，有两种解决方式：

**方式 A**：放一张 PNG 图片到 `entry/src/main/resources/base/media/app_icon.png`

**方式 B**：改成项目已有的图标（如 DevEco 模板自带的 `startIcon`）：
```json5
"icon": "$media:startIcon",
"startWindowIcon": "$media:startIcon",
```

### 3.3 确认桌面启动入口

同样在 `module.json5` 中，确保 EntryAbility 包含 `skills` 配置：

```json5
"abilities": [
  {
    "name": "EntryAbility",
    "exported": true,
    ...
    "skills": [
      {
        "entities": ["entity.system.home"],
        "actions": ["action.system.home"]
      }
    ]
  }
]
```

没有这段配置，应用安装后不会出现在桌面上。

---

## 四、打开项目并同步

### 4.1 用 DevEco Studio 打开

File → Open → 选择 `harmonyApp` 目录 → OK

### 4.2 Sync 项目

打开后 DevEco Studio 会自动执行 Sync。如果没有自动触发：

File → Sync and Refresh Project

等待 Sync 成功（底部状态栏显示 BUILD SUCCESSFUL）。

### 4.3 常见 Sync 问题

**"project structure requires upgrade"**
- 说明 Hvigor/SDK 版本不匹配
- 解决：用 DevEco Studio 新建一个空项目，将 `entry/src/main/ets/` 下的源码和 `module.json5` 等配置复制到新项目中

---

## 五、配置签名

### 5.1 自动签名（推荐）

1. File → Project Structure → Project → Signing Configs
2. 勾选 **Automatically generate signature**
3. 首次需要登录华为开发者账号
4. 点击 OK

### 5.2 注意事项

- 自动签名生成的是**调试证书**，只能在自己的设备/模拟器上安装
- 如果需要分发给其他人，需要在 AppGallery Connect 申请发布证书
- 每次换电脑签名都不同，需要先卸载旧版再安装：`hdc uninstall com.silk.harmony`

---

## 六、启动模拟器

### 6.1 创建模拟器

1. Tools → Device Manager
2. 点击 + 号创建新设备
3. 选择 Phone 类型，选择系统镜像（推荐 HarmonyOS NEXT）
4. 完成创建

### 6.2 启动模拟器

在 Device Manager 中点击设备旁的绿色启动按钮，等待模拟器完全启动。

### 6.3 网络配置（模拟器访问远程后端时）

如果后端运行在远程服务器上，需要通过 SSH 端口转发让模拟器能访问：

```bash
# 在本地笔记本上执行，将远程 8006 端口映射到本地
ssh -L 8006:localhost:8006 用户名@服务器地址
```

此时 `build-profile.json5` 中配置 `http://10.0.2.2:8006` 即可。

---

## 七、构建与安装

### 方式 A：通过 DevEco Studio 直接运行（开发调试用）

1. 确保模拟器已启动
2. 点击工具栏的绿色三角形 ▶ 按钮（Run 'entry'）
3. 自动编译、安装、启动应用

### 方式 B：构建 HAP 安装包

#### 7.1 构建

菜单栏：Build → Build Hap(s)/APP(s) → Build Hap(s)

构建成功后，HAP 文件位于：
```
entry\build\default\outputs\default\entry-default-signed.hap
```

#### 7.2 安装到模拟器/设备

打开 DevEco Studio Terminal（`Alt + F12`）：

```bash
# 查看已连接设备
hdc list targets

# 安装（首次）
hdc install entry\build\default\outputs\default\entry-default-signed.hap

# 覆盖安装（更新时用）
hdc install -r entry\build\default\outputs\default\entry-default-signed.hap
```

#### 7.3 安装后

回到模拟器主屏幕，找到 Silk 图标点击启动。

---

## 八、常用 hdc 命令

| 命令 | 作用 |
|------|------|
| `hdc list targets` | 查看已连接设备 |
| `hdc install xxx.hap` | 安装应用 |
| `hdc install -r xxx.hap` | 覆盖安装 |
| `hdc uninstall com.silk.harmony` | 卸载应用 |
| `hdc shell aa start -a EntryAbility -b com.silk.harmony` | 手动启动应用 |
| `hdc shell bm dump -n com.silk.harmony` | 查看应用安装信息 |
| `hdc hilog` | 查看设备日志 |
| `hdc file send 本地文件 /data/local/tmp/` | 推送文件到设备 |

---

## 九、常见问题

### Q: 安装后桌面看不到应用图标
- 检查 `module.json5` 中是否有 `skills` 配置（见 3.3 节）
- 检查 `icon` 引用的资源是否存在（见 3.2 节）
- 尝试：`hdc uninstall com.silk.harmony` 后重新安装
- 或重启模拟器

### Q: 安装报 "install sign info inconsistent"
- 之前安装的版本签名不同
- 先卸载：`hdc uninstall com.silk.harmony`，再重新安装

### Q: 安装报 "Ability is not visible"
- `module.json5` 中 EntryAbility 需要 `"exported": true`

### Q: 应用无法连接后端
- 确认 `build-profile.json5` 中的 `backendBaseUrl` 地址正确
- 模拟器用 `10.0.2.2` 访问宿主机
- 如果后端在远程服务器，需要 SSH 端口转发（见 6.3 节）
- 确认 `module.json5` 中已声明网络权限 `ohos.permission.INTERNET`

### Q: Run 按钮是灰色的
- 确认模拟器已启动并被识别（`hdc list targets` 有输出）
- 确认 Sync 已成功
- 尝试：File → Sync and Refresh Project

### Q: Sync 报版本不匹配
- 用当前 DevEco Studio 新建一个空 HarmonyOS 项目
- 将 `entry/src/main/ets/` 源码和配置文件复制到新项目中
- 在新项目中重新 Sync

---

## 十、给其他用户分发 HAP 包

| 方式 | 说明 |
|------|------|
| **调试包** | 用自动签名构建的 HAP，只能在自己设备上安装 |
| **源码分发** | 发送 `harmonyApp/` 目录，对方自行签名构建（推荐） |
| **发布证书包** | 在 AppGallery Connect 申请发布证书后签名，可安装到任意设备 |

对于团队内部使用，推荐**源码分发**方式：每人用自己的开发者账号签名，最简单可靠。
