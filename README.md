<div align="center">

# Discord AutoTranslate

**Discord 消息自动翻译（多语言 → 可配置目标语言）的 LSPosed 模块**

基于 [LibXposed API 102](https://github.com/libxposed/api)（现代 Xposed API），
**不使用**旧的 `de.robv.android.xposed` API。

[![GitHub release](https://img.shields.io/github/v/release/wxmyyds/DiscordAutoTranslate)](https://github.com/wxmyyds/DiscordAutoTranslate/releases)
[![GitHub Actions](https://img.shields.io/github/actions/workflow/status/wxmyyds/DiscordAutoTranslate/build.yml?label=build)](https://github.com/wxmyyds/DiscordAutoTranslate/actions)

</div>

---

## 功能

- **自动翻译**：英文、俄语、日语、韩语等非目标语言消息可翻译到设置的目标语言，完成后自动替换正文
- **保留 Markdown**：标题 / 列表 / 提及 / 表情 / 代码等结构完整保留
- **行内翻译图标**：翻译中 / 成功 / 失败三态，点击切换原文 / 译文，失败可重试
- **回复引用**：被引用消息的译文同步显示在回复预览中
- **Embed 与投票**：富 Embed（标题 / 描述 / 字段 / 原生帖子预览）与投票卡片可翻译
- **编辑重译**：消息被编辑后自动重新翻译
- **供应商协议**：支持 OpenAI-Compatible / OpenAI-Responses / Anthropic Messages，
  可在设置中为支持的协议切换流式（SSE）请求
- **独立设置界面**：配置检查 / 测试连接 / 测试翻译
- **隐私友好**：消息正文与译文不写入日志，API 密钥仅保存在本机

## 系统要求

- Android 8.0+（API 26+）
- 已 Root 且安装 [LSPosed](https://github.com/LSPosed/LSPosed)（支持 API 102）
- Discord **340.13 Stable**（`versionCode` 340013）。其他版本不承诺兼容；
  模块启动时会比对版本并打印警告。

## 安装与激活

1. 在本仓库 [Releases](https://github.com/wxmyyds/DiscordAutoTranslate/releases) 下载 APK 并安装。
2. 打开 LSPosed，启用 **Discord AutoTranslate**，作用域勾选 `com.discord`。
3. 强制停止并重启 Discord。
4. 打开模块设置，填写 API URL、密钥和模型。配置也可在启用模块前保存，启用后自动同步。

> 需要支持 API 102 的 LSPosed 版本。

## 使用说明

- 翻译默认开启；可在设置界面调整目标语言、供应商协议、流式请求、系统提示词、并发与超时等参数。
- 打开模块设置 →「测试连接」可验证 API 配置，「测试翻译」可预览翻译效果。
- 消息右下角 / 行内图标：翻译中为旋转状态，完成后可点击在原文 / 译文间切换。

## 构建

**本仓库禁止本地构建。** 所有编译与打包通过 GitHub Actions 执行：
`.github/workflows/build.yml`（手动触发或推送分支时运行），产物为 APK artifact。

## 隐私

启用后消息正文会发送到你配置的翻译服务。API 密钥保存在本机私有配置中，并同步到 LSPosed remote preferences；不会写入日志。
消息正文与译文始终不会写入日志；详细日志开关只影响诊断信息的详细程度。

## 常见问题

**翻译后列表项不在原位？**
列表按“每项一行”对齐回填；若模型把多项合并成一行，会保留原文而不是错位塞入。

**图标显示成功但正文仍是英文？**
说明译文结构与原文对不上被拒绝回填（保留原文），请用设置页「复制诊断」反馈。

**切换协议后译文还是旧的？**
协议参与配置版本计算，切换后旧缓存自动失效并重新请求。

## 相关文档

- [架构总览](docs/architecture.md) / [渲染与回填](docs/rendering.md)
- [翻译引擎与配置](docs/translation-engine.md)
- [行内图标与布局](docs/icons-and-layout.md) / [维护经验](docs/maintenance-notes.md)

## 免责声明

本项目与 Discord Inc. 无关联。Discord 为 Discord Inc. 的商标。
使用本模块可能违反 Discord 服务条款，请自行评估风险。