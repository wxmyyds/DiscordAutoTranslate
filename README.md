<div align="center">

# Discord AutoTranslate

**Discord 消息自动翻译（多语言 → 可配置目标语言）的 LSPosed 模块**

基于 [LibXposed API 102](https://github.com/libxposed/api)（现代 Xposed API）

[![GitHub release](https://img.shields.io/github/v/release/wxmyyds/DiscordAutoTranslate)](https://github.com/wxmyyds/DiscordAutoTranslate/releases)
[![GitHub Actions](https://img.shields.io/github/actions/workflow/status/wxmyyds/DiscordAutoTranslate/build.yml?label=build)](https://github.com/wxmyyds/DiscordAutoTranslate/actions)

</div>

---

## 功能

- **自动翻译**：英文、俄语、日语、韩语等非目标语言消息可翻译到设置的目标语言，完成后自动替换正文
- **保留 Markdown**：标题 / 列表 / 提及 / 表情 / 代码等结构完整保留
- **行内翻译图标**：翻译中 / 成功 / 失败三态，点击切换原文 / 译文，失败可重试
- **回复引用（352.17）**：只复用被引用消息已有译文，保留原生两行省略和用户名避让；普通及带图片引用已由用户复测确认
- **版本化适配**：各版本只安装已核实的 Hook，具体能力见下表
- **编辑重译**：消息被编辑后自动重新翻译
- **供应商协议**：支持 OpenAI-Compatible / OpenAI-Responses / Anthropic Messages，
  可在设置中为支持的协议切换流式（SSE）请求
- **独立设置界面**：配置检查 / 测试连接 / 测试翻译
- **隐私友好**：消息正文与译文不写入日志，API 密钥仅保存在本机

## 系统要求

- Android 8.0+（API 26+）
- 已 Root 且安装 [LSPosed](https://github.com/LSPosed/LSPosed)（支持 API 102）
- Discord 必须有精确匹配的版本配置；未知版本不安装消息 Hook，不复用旧版配置。

| Discord | 当前能力与验证边界 |
|---|---|
| **352.17 Stable**（352017） | 正文翻译；普通及带图片回复预览由用户复测确认。富 Embed 与投票问题/选项已静态适配并启用，设备回归尚未确认；帖子预览未接入 |
| **340.13 Stable**（340013） | 保留该版本正文和富 Embed 配置；不具备当前统一回复消费者映射及已核实点击接口，帖子预览/投票不支持。本次未设备复测 |

## 富 Embed 适配边界

- `352017` 的现有 `embed.bind` 直接匹配 `q9.q.b` 实例方法：34 个 Java 参数、返回 `void`，模型和消息 ID 分别来自参数 1 和 3，宿主为当前 View。`long`/`double` 各计一个参数，不按 smali 寄存器槽位计数。
- 描述、标题、字段及字段名/值通过 profile 的语义字段映射读取结构化文本，复用统一富 Embed 链路，不新增独立解析或渲染入口。该版本 `embed.bind` 为必需 Hook。
- `340013` 保留原 `EmbedViewHolder.bind` 目标，使用参数 1 的 accessory、`itemView` 宿主及配置中的语义 getter。未找到旧模型源码，未新增未经核实的 Embed 类型别名。
- 以上 Embed 变更已完成静态适配，不代表设备回归已通过；多卡片、字段、编辑、切换及视图复用仍需实机验证。

## 投票适配边界

- 352017 的问题绑定为 `ea.r0.v`，选项绑定为 `ba.h.f`。问题/选项是普通字符串，不经过 `B0`；仅在绑定作用域内按精确视图身份与原始输入拦截原生 `setText`，图片选项拦截其 `setContentDescription`。
- 原始文本包装为 Primitive 输入，复用现有语言检测、StateStore、Scheduler、缓存、图标点击和重渲染；不修改投票模型、答案 ID、选中状态、票数、百分比和宿主投票回调。
- 图标触摸只消费图标区域，其余选项点击仍交给原生投票；图片选项只翻译无障碍文本，不改图片内容。340013 未接入投票。
- 构建/单测与设备显示是不同验证；投票、结果更新、图标点击、长译文及滚动复用仍需实机确认。

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
消息正文、译文、消息 ID 和内容哈希不写入日志。诊断记录安装、状态和异常类别；临时布局/空白统计已清理，当前没有详细日志开关。

## 常见问题

**翻译后列表项不在原位？**
列表按“每项一行”对齐回填；若模型把多项合并成一行，会保留原文而不是错位塞入。

**图标显示成功但正文仍是英文？**
说明译文结构与原文对不上被拒绝回填（保留原文），请提供复现步骤和 LSPosed 模块日志；日志不包含消息正文。

**切换协议后译文还是旧的？**
协议参与配置版本计算，切换后旧缓存自动失效并重新请求。

## 相关文档

- [架构总览](docs/architecture.md) / [渲染与回填](docs/rendering.md)
- [翻译引擎与配置](docs/translation-engine.md)
- [行内图标与布局](docs/icons-and-layout.md) / [维护经验](docs/maintenance-notes.md)

## 免责声明

本项目与 Discord Inc. 无关联。Discord 为 Discord Inc. 的商标。
使用本模块可能违反 Discord 服务条款，请自行评估风险。