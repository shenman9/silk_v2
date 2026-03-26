# Silk HarmonyOS App Windows 部署指南

## 环境要求

- **DevEco Studio**: 默认路径 Windows `D:/app/DevEco Studio`，macOS `~/DevEco Studio`（可通过 `DEVECO_HOME` 环境变量覆盖）
- **HarmonyOS SDK**: `$DEVECO_HOME/sdk` (API 22, HarmonyOS 6.0.2)
- **模拟器**: 启动后通过 `hdc list targets` 确认连接，默认地址 `127.0.0.1:5555`
- **hdc**: 需在 PATH 中

## 一键部署

```bash
cd frontend/harmonyApp
bash scripts/deploy_win.sh                 # 完整流程: 安装依赖 → 构建 → 部署 → 启动
bash scripts/deploy_win.sh --skip-install  # 跳过 ohpm install（依赖没变时）
bash scripts/deploy_win.sh --log           # 部署后自动拉取日志
```

脚本自动检测运行平台（Windows / macOS / Linux），处理各平台的路径差异。

如果 DevEco Studio 不在默认路径，可以：
```bash
DEVECO_HOME="/your/path/to/DevEco Studio" bash scripts/deploy_win.sh
```

## 手动分步操作

整个流程 4 步（以 Windows 为例，macOS 将 `.bat` 后缀去掉）：

### 1. 安装依赖 (ohpm install)

```bash
cd frontend/harmonyApp
"$DEVECO_HOME/tools/ohpm/bin/ohpm.bat" install
```

### 2. 构建 HAP (hvigorw assembleHap)

```bash
cd frontend/harmonyApp
DEVECO_SDK_HOME="$DEVECO_HOME/sdk" \
  "$DEVECO_HOME/tools/hvigor/bin/hvigorw.bat" \
  assembleHap --mode module -p module=entry@default -p product=default --no-daemon
```

> 注意: Windows 上需要 Java 在 PATH 中（`$DEVECO_HOME/jbr/bin`），deploy_win.sh 已自动处理。

产物路径: `entry/build/default/outputs/default/entry-default-unsigned.hap`

### 3. 安装到模拟器 (hdc install)

```bash
hdc -t 127.0.0.1:5555 install <HAP路径>
```

> Windows 注意: hdc install 的路径需用反斜杠格式，且需在根目录执行避免路径拼接问题。

### 4. 启动 App (hdc shell aa start)

```bash
hdc -t 127.0.0.1:5555 shell aa start -a EntryAbility -b com.silk.harmony
```

## 查看日志

App 会将调试日志写入沙箱文件 `silk_debug.log`，用脚本拉取：

```bash
bash scripts/pull-log.sh           # 拉取并显示日志
bash scripts/pull-log.sh --clear   # 拉取后清空
```

## 注意事项

- HAP 为未签名包（模拟器可用，真机需配置签名）
- 后端地址 `10.0.2.2:8006` 是模拟器到宿主机的桥接地址，需确保后端运行中
