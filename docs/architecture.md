# 架构总览

Discord AutoTranslate 是运行在 Discord 进程内的 LSPosed 模块（LibXposed API **102**），
为 Discord Android 官方稳定版提供英文 → 简体中文的消息自动翻译。

## 模块入口与生命周期

- 入口：`app/src/main/java/io/github/wxmyyds/discordautotranslate/ModuleEntry.java`
  （`extends io.github.libxposed.api.XposedModule`，必须有公开无参构造）。
- 入口声明在 `app/src/main/resources/META-INF/xposed/`：
  - `java_init.list`：入口类全限定名（每行一个，模块只支持一个 Java 入口）；
  - `module.prop`：`minApiVersion=102` / `targetApiVersion=102` / `autoHotReload=true`；
  - `scope.list`：`com.discord`（作用域）。
- 生命周期回调：`onModuleLoaded`、`onPackageLoaded`、`onPackageReady`、
  `onHotReloading`（旧代码，返回 false 可拒绝重载）、`onHotReloaded`（新代码，
  默认 unhook 旧句柄后必须自行重建状态并重新安装 Hook）。
- Hook 模型是**拦截器链**：`hook(Executable).setId(..).intercept(chain -> ...)`，
  改参用 `chain.proceed(Object[])`，短路则直接返回。
- 配置读写用 remote preferences：目标进程 `getRemotePreferences(group)`，
  设置页进程 `XposedService.getRemotePreferences(group)`，两端读写同一份数据。

## 包结构

```
io.github.wxmyyds.discordautotranslate
  ModuleEntry.java            模块唯一入口：生命周期、Hook 安装、状态重建
  core/                       与 Discord 无关的纯逻辑
    Config / MemoryPrefs      配置读取与回落默认值
    StateStore / Scheduler / TranslationCache / IdentityMessageMap
    TextExtractor / BlankLines / Segments / EnglishDetector
    Http / Protocols / JsonPath / JsonApiEngine（JSON 请求与响应提取）
    Reflect / Sha256 / ModuleResources / TranslationException
  hooks/                      Discord Hook 与渲染替换
    MessageHooks / HookBridge / EmbedHooks / PollHooks
    ContentNodes / StructurableTextFactory / RerenderRegistry / RowRefresher
    InlineIconInjector / InlineIconSpan / InlineIconGeometry / IconClickSpanFactory
    IdentityMessageMap / RowRefresher
  translate/                  ApiTester（连接测试 / 测试翻译）
  ui/                         SettingsActivity / XposedServiceConnectionHolder
```

## Hook 体系（目标 Discord 340.13 / versionCode 340013）

| Hook | 作用 | 说明 |
|---|---|---|
| `MessageContentViewHolder.bind`（17 参） | 视图 → 消息 ID 的唯一可靠来源 | 登记 `viewToMessageId`；正文渲染前记录原文参数 |
| `MessageView.setMessage`（7 参） | 行控件 + 消息 ID | `this` 即行控件；绑定时机稳定，图标在此挂载（主路径） |
| `MessageView.onInterceptTouchEvent` | 放行图标点击 | 触点落在图标范围内返回 false，事件下发给图标 |
| `MessageContentView` 展开式 `setMessageContent`（34 参，首参 `MessageMargins`） | 电台正文渲染 | 用「名字前缀 + 参数最多 + 首参类型」定位，避免匹配哈希后缀 |
| `TextUtilsKt.toSpannable`（29 参） | **所有文本渲染的唯一汇聚点** | 全部正文/引用/Embed 都经此，翻译替换在此完成 |
| `MessageViewReplyPreview.configureReply`（3 参） | 回复引用 | 把引用视图登记到被引用消息 ID，译文就绪后重渲染 |

`MessageHooks.MIN_REQUIRED_HOOKS = 4`：低于该数回滚已装 Hook 并抛错，
避免“部分安装成功”的静默失效。

## 状态与并发（防串位 / 防旧结果覆盖）

- 译文与状态全部存 `StateStore`，以 **messageId** 为键，**绝不存在 View 上**
  （RecyclerView 复用视图，存 View 会串位）。
- `view → messageId` 映射存 `HookBridge`；重渲染前用
  `RerenderRegistry.stillBound` 校验“视图当前绑定是否仍是目标消息”。
- 同正文不同对象用 `IdentityMessageMap`（弱引用 + identity hash）反查，避免
  Kotlin data class 按内容 equal 造成两条相同消息互相覆盖。
- 异步结果回写前双重校验：
  - `contentHash = SHA-256(正文 + 配置版本)` —— 消息被编辑后旧结果不落位；
  - `generation` —— 热重载/配置变更后旧结果不落位。
- 提交前、重试前、回写前三道校验，过期请求丢弃并把状态回退为可重试。

## 热重载

- `onHotReloaded` 从 `getOldHookHandles()` 的目标 `Executable` 反推宿主
  ClassLoader（`ModuleEntry.classLoaderFromOldHooks`），旧代次只持有引用，
  新代次必须自行从方法反推。
- `ModuleResources.reset()` 后重新 `attach(this)`，否则图标资源解析失败。
- `TranslationCache.reset()` 清理旧单例；`Scheduler.shutdown()` 经
  `Engine.cancel(Thread)` → `Http.cancelRequestOn` 关闭在途连接。

## 版本适配

模块启动时读取 `com.discord.BuildConfig.VERSION_CODE`，与 340013 不一致打印警告，
但仍尝试安装 Hook（不承诺兼容其他版本）。

## 能力范围（当前 main）

- 正文（含 Markdown/提及/表情/代码/链接/标题/列表）；回复引用；
  编辑后重新翻译；富 Embed（标题/描述/字段/原生帖子预览标题）与投票卡片。
- 失败后不自动重试（避免请求风暴），由用户点击图标重试。
- 行内翻译图标：三态（翻译中/成功/原文）、旋转动画、点击切换/重试。