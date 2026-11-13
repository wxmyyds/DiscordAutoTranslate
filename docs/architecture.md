# 架构总览

Discord AutoTranslate 是运行在 Discord 进程内的 LSPosed 模块（LibXposed API **102**），
为 Discord Android 官方稳定版提供多语言消息到可配置目标语言的自动翻译。

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
    Config / MemoryPrefs      默认值初始化与显式配置错误
    StateStore / Scheduler / TranslationCache / IdentityMessageMap
    TextExtractor / BlankLines / Segments / EnglishDetector
    Http / Protocols / JsonPath / JsonApiEngine（JSON 请求与响应提取）
    Reflect / Sha256 / ModuleResources / TranslationException
  hooks/                      Discord Hook 与渲染替换
    MessageHooks / HookBridge / EmbedHooks / PollHooks
    ContentNodes / StructurableTextFactory / RerenderRegistry / RowRefresher
    InlineIconInjector / InlineIconSpan / InlineIconGeometry / IconClickSpanFactory
  translate/                  ApiTester（连接测试 / 测试翻译）
  ui/                         SettingsActivity / XposedServiceConnectionHolder
```

## Hook 体系（受支持的版本配置）

每个 Discord `versionCode` 只加载同名 profile；没有精确匹配的 JSON，或必需目标无法解析时，消息 Hook 不会安装。具体 selector、参数索引和混淆字段见 `app/src/main/assets/hook-config/`。

| `versionCode` | 必需消息 Hook | 可选 Hook | 正文渲染与转换 |
|---|---|---|---|
| `340013` | 消息绑定、正文渲染、文本转换、回复引用 | Embed、帖子预览、Poll | `MessageContentView` 实例方法；`TextUtilsKt.toSpannable` |
| `352017` | 消息绑定、正文渲染、文本转换 | 回复、Embed、帖子预览、Poll 未核实并禁用 | `MessageContentView` 静态方法（View 为显式参数）；`kl.b.B0` 静态转换方法 |

必需 Hook ID 与数量由 profile 声明；安装前逐项解析并检查所有必需目标，安装后逐项确认这些 ID 均已创建 Hook，缺少任一项时回滚已创建的 Hook。


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
- 提交、获得并发许可后及回写前校验；每项任务只调用一次引擎，过期结果丢弃。

## 热重载

- `onHotReloaded` 从 `getOldHookHandles()` 的目标 `Executable` 反推宿主 ClassLoader；若未能恢复版本配置或 ClassLoader，则重新安装启动 Hook。
- `ModuleResources.reset()` 后重新 `attach(this)`，否则图标资源解析失败。
- `TranslationCache.reset()` 清理旧单例；`Scheduler.shutdown()` 经
  `Engine.cancel(Thread)` → `Http.cancelRequestOn` 关闭在途连接。

## 版本适配

模块读取 Discord `PackageInfo.versionCode`，只加载 `hook-config/<versionCode>.json`。未知版本、JSON 无效或任一必需 Hook 目标无法解析时，不安装消息 Hook；不会退回到其他版本配置。可选 Hook 只有在对应 profile 已核实并启用后才安装。

## 能力范围（按 profile）

- `340013`：正文（含 Markdown/提及/表情/代码/链接/标题/列表）、回复引用、富 Embed、帖子预览标题与投票卡片。
- `352017`：正文翻译与重渲染；回复引用、Embed、帖子预览和 Poll 仍禁用，待目标方法核实后再单独启用。
- 两个 profile 都保留失败时的原始节点树；用户可通过图标重试或切换原文/译文。