# Backend Tests

当前快检分成三类：

- `BackendHttpContractTest`：注册、登录、用户设置、群组创建/加入、Todo HTTP 合同。
- `UserTodoStoreTest`：待办去重、重开、模板实例化等核心生命周期逻辑。
- `claudecode/*Test`：Claude Code stream parser 与 session store 单测。

运行方式：

```bash
./gradlew :backend:test
```

新增测试时保持两点：

- 优先写能直接拦截回归的真实接口/逻辑测试，避免字符串占位测试。
- 使用 `TestWorkspace` 隔离 SQLite 与 `chat_history`，不要把测试产物写回仓库根目录。
