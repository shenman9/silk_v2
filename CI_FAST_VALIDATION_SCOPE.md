# CI Fast Validation Scope

## 目标

这个 CI 用于 `push`、`pull_request`、`merge_group`、`workflow_dispatch` 的快速拦截。

只放三类检查：

- GitHub-hosted runner 上稳定可跑的检查
- 能在较短时间内发现高概率回归的检查
- 对“新提交不要明显破坏已有功能”有直接价值的检查

不把它做成全量发布流水线；慢、重、依赖专用环境的项放到后续专用 CI。

## 当前基线（2026-04-14）

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
- [x] WebSocket 入群鉴权
- [x] WebSocket 最近 50 条历史回放
- [x] WebSocket 文本广播与持久化
- [x] 未读计数与 `mark-read` 链路
- [x] AI 工具暴露面过滤（禁用工具不暴露给模型）
- [x] AI 工具会话作用域拒绝（空作用域 / 非当前会话）
- [x] AI 工具路径拒绝与审计结果一致
- [x] 消息撤回 HTTP 合同（发送者权限、普通消息撤回、`@silk` 连带回复删除）
- [x] 用户 Todo 列表 / 更新 / 删除 HTTP 合同
- [x] Todo 生命周期：done 重开、cancelled 重开门槛、逻辑去重、月度模板实例化
- [x] Claude Code stream parser 单测
- [x] Claude Code session store 单测

### 本次补齐的点

- 删除了几组只校验字符串/JSON 形状的占位测试，改为真实后端合同测试。
- 把消息撤回从 JSON 形状占位校验改成真实路由合同测试，并修正了测试环境的 `chat_history` 隔离。
- 测试现在使用临时 SQLite 和临时 `chat_history` 目录，避免把测试产物写回仓库根目录。
- 新增 WebSocket 合同测试，覆盖群成员鉴权、最近 50 条历史回放、实时广播持久化和未读计数主链路。
- 新增 AI 工具权限测试，锁定禁用工具暴露面、会话作用域拒绝，以及路径拒绝时的审计结果。
- 把 `silk.sh` 的基础语法校验和只读 `status` smoke 接进了快检。

### 明确未覆盖

- [ ] WebSocket AI/Claude Code 触发链路、断线重连恢复、异常网络行为
- [ ] AI 工具完整端到端 tool-calling（真实模型响应、外部搜索、Weaviate）
- [ ] 文件上传下载、URL/PDF 抽取、Weaviate 索引链路
- [ ] backend `shadowJar` / 交付产物装配校验
- [ ] Harmony HAP 构建
- [ ] `silk.sh build/start/deploy` 级别的完整脚本 smoke

## 运行备注

- Android job 会显式安装 SDK 并生成 `local.properties`，避免 runner 环境差异导致评估期直接失败。
- Gradle wrapper 在 GitHub-hosted runner 上会临时改回 `services.gradle.org`，避免镜像可达性造成非业务失败。
- 目前 `:backend:test` 已经是有意义的快检入口；后续新增 backend 能力时，优先往这里补真实测试，不要再加占位测试。

## 下一步建议

1. 补一个不依赖外部在线服务的文件/索引 smoke，优先覆盖最容易被改坏的本地路径分支。
2. backend 增加 `shadowJar` / 产物装配快检，避免交付链路在主分支后置失败。
3. AI 端到端另起一层可选 smoke，专门验证模型 tool-calling 回路，不阻塞基础快检。
4. Harmony HAP 另起独立 workflow，放到自托管或预置 DevEco/hvigor 环境的 runner。
