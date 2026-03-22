# Silk 后端自动化测试框架

## 概述

本目录包含 Silk 后端的自动化测试用例，用于验证核心功能的正确性。

## 测试文件

| 文件 | 描述 |
|------|------|
| `ApplicationTest.kt` | 基础应用测试 |
| `MessageRecallTest.kt` | 消息撤回功能测试 |
| `MessageForwardTest.kt` | 消息转发功能测试 |
| `MessageCopyTest.kt` | 消息复制功能测试 |

## 运行测试

### 使用 Gradle

```bash
cd /mi/sfs_turbo/lilin_v1/code/silk-fork
./gradlew :backend:test
```

### 使用测试脚本

```bash
cd /mi/sfs_turbo/lilin_v1/code/silk-fork
./run-tests.sh
```

## 添加新测试

1. 在 `com.silk.backend` 包下创建新的测试类
2. 使用 `@Test` 注解标记测试方法
3. 使用 `kotlin.test` 包中的断言方法

### 测试命名规范

- 测试类: `{功能名}Test.kt`
- 测试方法: `test{具体测试场景}`

### 示例

```kotlin
package com.silk.backend

import org.junit.Test
import kotlin.test.assertTrue

class ExampleTest {
    @Test
    fun testExample() {
        assertTrue(true, "示例测试应该通过")
    }
}
```

## 测试原则

1. **独立性**: 每个测试应该独立运行，不依赖其他测试
2. **可重复性**: 测试结果应该可重复
3. **自验证**: 测试应该自动判断成功或失败
4. **及时性**: 测试应该快速执行

## 持续集成

建议在以下情况运行测试:
- 提交代码前
- 合并分支前
- 新增功能后
- 修复 bug 后
