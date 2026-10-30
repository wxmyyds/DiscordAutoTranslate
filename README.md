# Discord AutoTranslate — LSPosed 模块

为 Discord Android 官方稳定版提供消息自动翻译（英文 → 简体中文）的 LSPosed 模块。

基于 [LibXposed API 102](https://github.com/libxposed/api)（现代 Xposed API），
**不使用**旧的 `de.robv.android.xposed` API。

## 当前状态

**模块功能已完整，可日常使用**

- 模块骨架、API 102 入口与热重载生命周期
- 通用 JSON 翻译引擎：支持 OpenAI-Compatible / OpenAI-Responses / Anthropic
  Messages 三种供应商协议、流式（SSE）响应、并发调度、重试与缓存
- 保留 Markdown 的正文替换与行内图标交互：标题/列表/提及/表情/代码保留，
  含列表消息逐项回填、文字保留与尾部空行修剪
- 独立设置 Activity（配置检查 / 测试连接 / 测试翻译、供应商协议与系统提示词）
- Embed 翻译（富 Embed 标题/描述/字段与原生帖子预览标题）与投票卡片翻译
- 稳定性与隐私加固：防串位身份映射、热重载重建与在途请求取消、日志脱敏
- 布局即时刷新：重渲染移出宿主布局遍历、行高立即更新
- 正式签名发布流程：keystore 经 GitHub Secrets 注入，Debug/Release 均签名
- GitHub Actions 构建（APK + 单元测试 + 签名校验）

详见 [docs/architecture.md](docs/architecture.md) 与 [docs/rendering.md](docs/rendering.md)。

## 目标版本

Discord **340.13 Stable**（`versionCode` 340013，`com.discord`）。
其他版本不承诺兼容；模块启动时会比对版本并打印警告。

## 构建

**本仓库禁止本地构建。** 所有编译与打包通过 GitHub Actions 执行：
`.github/workflows/build.yml`（手动触发或推送分支时运行），产物为 APK artifact。

## 安装与使用

1. 安装构建产物 APK。
2. 在 LSPosed 中启用模块，作用域勾选 `com.discord`。
3. 强制停止并重启 Discord。
4. 打开模块的设置界面，填写 API URL、密钥、模型等配置。

> 需要支持 API 102 的 LSPosed 版本。

## 隐私

启用后消息正文会发送到你在设置中配置的第三方翻译服务。
API 密钥仅保存在本机（LSPosed remote preferences），不会写入日志。
消息正文与译文始终不会写入日志；详细日志开关只影响诊断信息的详细程度。

## 目录结构

```
app/src/main/java/io/github/wxmyyds/discordautotranslate/
  ModuleEntry.java                 模块唯一入口（API 102 生命周期 + 热重载）
  core/                            配置、状态、调度、文本提取、英文识别、反射工具
  hooks/                           Discord Hook 与图标
  ui/SettingsActivity.java         独立设置界面
app/src/main/resources/META-INF/xposed/
  java_init.list                   入口类声明
  module.prop                      模块元数据（minApiVersion/targetApiVersion/autoHotReload）
  scope.list                       作用域（com.discord）
docs/architecture.md              架构总览（入口/Hook/状态/热重载）
docs/rendering.md                 渲染与回填（toSpannable 漏斗/对齐/节点重建）
docs/translation-engine.md        翻译引擎与配置（协议/调度/缓存/隐私）
docs/icons-and-layout.md          行内图标/点击/布局刷新
docs/maintenance-notes.md         维护经验与边界
smali/                             Discord APK 的反编译产物（仅供本地分析，不纳入版本控制）
```

## 分支与发布约定

仓库只保留 `main` 作为长期分支。所有修改先建短期分支，
经 GitHub Actions 验证与人工确认后，再通过 PR 合并回 `main`。

## 免责声明

本项目与 Discord Inc. 无关联。Discord 为 Discord Inc. 的商标。
使用本模块可能违反 Discord 服务条款，请自行评估风险。
