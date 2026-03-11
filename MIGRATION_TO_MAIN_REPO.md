# 从 Fork 迁移为主仓库 (silk_v2)

本项目已脱离原 fork 关系，作为独立主仓库使用，仓库名为 **silk_v2**，仓库地址：https://github.com/lin-shui/silk_v2

## 已完成的修改

- 已移除原 fork 的 `upstream` 远程
- 项目显示名已改为 `silk_v2`（`settings.gradle.kts`、README）
- **origin 已指向** `https://github.com/lin-shui/silk_v2.git`
- 敏感信息已清理（见下方「敏感信息检查」）

## 你需要做的步骤

### 1. 在 GitHub 上新建仓库（若尚未创建）

1. 打开 https://github.com/new
2. **Repository name** 填：`silk_v2`
3. 选择 **Public**
4. **不要**勾选 "Add a README"（避免产生无关提交）
5. 创建仓库

### 2. 推送代码到 silk_v2 仓库

本地 origin 已配置为 `https://github.com/lin-shui/silk_v2.git`，在项目根目录执行：

```bash
cd /path/to/silk_v2   # 你的本地项目根目录

git push -u origin master
```

若使用 SSH，可改为：
```bash
git remote set-url origin git@github.com:lin-shui/silk_v2.git
git push -u origin master
```

### 3. （可选）本地目录改名

若希望本地目录名也是 `silk_v2`，可在上一级目录执行：

```bash
cd /path/to/parent
mv silk silk_v2
```

之后脚本、文档中的路径如需写死，请使用 `silk_v2` 或相对路径。

---

若需保留原 fork 为备份，可先添加为其他远程再改 origin：

```bash
git remote add backup https://github.com/lin-shui/silk.git
git remote set-url origin https://github.com/lin-shui/silk_v2.git
```
