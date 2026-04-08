# Silk 飞书机器人

在飞书上与 Silk AI 对话的网关服务。用户在飞书私聊机器人即可获得与 Silk 前端（Web/鸿蒙/Android）完全相同的 AI 对话体验。

## 架构

```
飞书用户 ↔ 飞书服务器 ↔ [飞书网关服务] ↔ Silk 后端 (WebSocket/HTTP)
```

- 飞书侧：通过 `lark-oapi` SDK 的 WebSocket 长连接接收消息，无需公网地址
- Silk 侧：作为普通客户端连接 Silk 后端，复用已有的专属助手对话
- 对 Silk 后端**零侵入**，不需要修改任何 Silk 代码

## 快速开始

### 1. 创建飞书应用

1. 前往 [飞书开放平台](https://open.feishu.cn) 创建应用
2. 启用「机器人」能力
3. 启用 **WebSocket 长连接模式**（不是 HTTP 回调）
4. 添加权限：
   - `im:message` — 读取消息
   - `im:message:send_as_bot` — 以机器人身份发消息
   - `im:message:update` — 更新已发送的消息（流式回复需要）
5. 订阅事件：`im.message.receive_v1`
6. 发布应用，获取 App ID 和 App Secret

### 2. 配置

```bash
cd feishu_bot
cp config/config.yaml.example config/config.yaml
```

编辑 `config/config.yaml`：

```yaml
# 飞书应用凭证
app_id: "cli_xxxxxxxxxxxx"
app_secret: "your_app_secret"

# Silk 后端地址（与其他前端连接的是同一个后端和端口）
silk_host: "localhost"
silk_port: 8006
```

### 3. 安装依赖

```bash
pip install -r requirements.txt
```

### 4. 启动

```bash
./run.sh start     # 启动
./run.sh stop      # 停止
./run.sh restart   # 重启
./run.sh status    # 查看状态
./run.sh log       # 查看日志
```

## 使用方式

在飞书中找到机器人，私聊发送：

| 命令 | 说明 |
|------|------|
| `绑定 用户名 密码` | 绑定已有的 Silk 账号 |
| `解绑` | 解除绑定 |
| `帮助` | 查看帮助信息 |

绑定后直接发送文字即可与 Silk AI 对话，支持所有 Silk 功能，包括 `/cc` 进入 Claude Code 模式。

## 文件结构

```
feishu_bot/
├── main.py              # 入口
├── config.py            # 配置加载
├── feishu_handler.py    # 飞书事件接收、消息路由、飞书消息发送
├── silk_client.py       # Silk WebSocket/HTTP 客户端
├── streaming.py         # 流式 AI 回复 → 飞书卡片实时更新
├── message_adapter.py   # 消息格式转换
├── user_binding.py      # 飞书↔Silk 账号映射（JSON 持久化）
├── requirements.txt     # Python 依赖
├── run.sh               # 服务管理脚本
├── config/
│   ├── config.yaml.example  # 配置模板
│   └── config.yaml          # 实际配置（不提交到 git）
└── data/
    └── user_bindings.json   # 账号绑定数据（运行时生成）
```
