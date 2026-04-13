# CI Fast Validation Scope

## 目标

这个 CI 用于 `push`、`pull_request`、`merge_group`、`workflow_dispatch` 的快速拦截。

只放三类检查：

- GitHub-hosted runner 上稳定可跑的检查
- 能在较短时间内发现高概率回归的检查
- 对“新提交不要明显破坏已有功能”有直接价值的检查

不把它做成全量发布流水线；慢、重、依赖专用环境的项放到后续专用 CI。

## 当前基线（2026-04-13）

工作流文件：`.github/workflows/ci-fast-validation.yml`

### 已覆盖

工程与构建：

- [x] Gradle 根工程可配置
- [x] `:backend:test`
- [x] `:frontend:webApp:compileProductionExecutableKotlinJs`
- [x] `:frontend:desktopApp:compileKotlin`
- [x] `:frontend:androidApp:compileDebugKotlin`
- [x] `bash -n silk.sh`
- [x] `./silk.sh status` smoke

backend 真实快检：

- [x] 注册 / 登录 / 用户校验 HTTP 合同
- [x] 用户设置读取 / 更新 HTTP 合同
- [x] 群组创建 / 入群 / 成员列表 HTTP 合同
- [x] 用户 Todo 列表 / 更新 / 删除 HTTP 合同
- [x] Todo 生命周期：done 重开、cancelled 重开门槛、逻辑去重、月度模板实例化
- [x] Claude Code stream parser 单测
- [x] Claude Code session store 单测

### 本次补齐的点

- 删除了几组只校验字符串/JSON 形状的占位测试，改为真实后端合同测试。
- 测试现在使用临时 SQLite 和临时 `chat_history` 目录，避免把测试产物写回仓库根目录。
- 把 `silk.sh` 的基础语法校验和只读 `status` smoke 接进了快检。

### 明确未覆盖

- [ ] WebSocket 消息语义与会话鉴权
- [ ] AI 工具策略 / 作用域 / 审计
- [ ] 文件上传下载、URL/PDF 抽取、Weaviate 索引链路
- [ ] backend `shadowJar` / 交付产物装配校验
- [ ] Harmony HAP 构建
- [ ] `silk.sh build/start/deploy` 级别的完整脚本 smoke

## 运行备注

- Android job 会显式安装 SDK 并生成 `local.properties`，避免 runner 环境差异导致评估期直接失败。
- Gradle wrapper 在 GitHub-hosted runner 上会临时改回 `services.gradle.org`，避免镜像可达性造成非业务失败。
- 目前 `:backend:test` 已经是有意义的快检入口；后续新增 backend 能力时，优先往这里补真实测试，不要再加占位测试。

## 下一步建议

1. 补 WebSocket 合同测试，覆盖入群鉴权、历史回放、广播、已读计数这条主链路。
2. 补 AI 工具与作用域测试，至少把权限边界和参数修复这两类高风险点锁住。
3. 评估一个不依赖外部在线服务的文件/索引 smoke，优先覆盖最容易被改坏的本地路径分支。
4. Harmony HAP 另起独立 workflow，放到自托管或预置 DevEco/hvigor 环境的 runner。
