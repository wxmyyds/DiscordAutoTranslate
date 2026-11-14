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
    StateStore / Scheduler / TranslationCache
    TextExtractor / BlankLines / Segments / EnglishDetector
    Http / Protocols / JsonPath（JSON 请求与响应提取）
    Reflect / Sha256 / ModuleResources / TranslationException
  hooks/                      Discord Hook 与渲染替换
    MessageHooks / HookBridge / EmbedHooks
    ContentNodes / StructurableTextFactory / RerenderRegistry / RowRefresher
    IdentityMessageMap / ReplyTextProjection / HookDiagnostics
    InlineIconInjector / InlineIconSpan / InlineIconGeometry / IconClickSpanFactory
  translate/                  JsonApiEngine / ApiTester（翻译请求与连接测试）
  ui/                         SettingsActivity / XposedServiceConnectionHolder
```

## Hook 体系（受支持的版本配置）

每个 Discord `versionCode` 只加载同名 profile；没有精确匹配的 JSON，或必需目标无法解析时，消息 Hook 不会安装。具体 selector、参数索引和混淆字段见 `app/src/main/assets/hook-config/`。

| `versionCode` | 必需消息 Hook | 可选 Hook | 正文渲染与转换 |
|---|---|---|---|
| `340013` | 消息绑定、正文渲染、文本转换（回复消费者映射未提供） | 富 Embed | `MessageContentView` 实例方法；`TextUtilsKt.toSpannable` |
| `352017` | `MessageView.f` 建立引用作用域并登记预览消费者；回复按消息 ID/编辑版本读取规范正文及已有状态，不以预览投影哈希代替规范正文、不创建状态或请求 | 独立 Embed 待核实；帖子预览/Poll 实现已移除 | 正文 `MessageContentView.c`；回复 `af.i` 直接调用 `kl.b.B0`，添加首行 `LeadingMarginSpan` 后交给 Drawee，模块不覆盖 `TextView` |

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

- `onHotReloaded` 从 `getOldHookHandles()` 的目标 `Executable` 取宿主 ClassLoader，并以保存的版本号从新模块 APK 读取当前 profile；不保存/复用旧代次 JSON。读取失败或 ClassLoader 缺失时明确报告需要重启，不重新挂载已经执行完的 `Application.attach` 伪装恢复。
- `ModuleResources.reset()` 后重新 `attach(this)`，否则图标资源解析失败。
- `TranslationCache.reset()` 清理旧单例；`Scheduler.shutdown()` 经
  `Engine.cancel(Thread)` → `Http.cancelRequestOn` 关闭在途连接。

## 版本适配

模块读取 Discord `PackageInfo.versionCode`，只加载 `hook-config/<versionCode>.json`。未知版本、JSON 无效或任一必需 Hook 目标无法解析时，不安装消息 Hook；不会退回到其他版本配置。可选 Hook 只有在对应 profile 已核实并启用后才安装。

## 能力范围（按 profile）

- `340013`：保留正文和富 Embed 配置；帖子预览绑定缺少模型参数、Poll 绑定缺少附件参数，其业务体无法执行，已连同配置和相关状态接口删除；旧独立回复 Hook 已移除，当前 profile 未提供统一回复消费者映射，不声称回复支持。
- `352017`：正文翻译与重渲染；回复预览复用引用消息 ID，经 `message.textConversion` 读取其翻译状态（预览不经过正文 `message.render`），不安装独立回复 Hook。普通回复译文复用及用户名避让已由用户设备复测确认；带图片/Embed 的引用投影与规范正文不同导致校验拒绝；`ca9713d` 的复用修正已由用户确认可显示译文，随后观察到空白/错位。`583ffbd` 日志确认原生预览模型已压平换行，但复用的正文译文渲染仍保留换行、提前终止首行。当前在同一个原生转换结果上、原生避让 span 添加之前等长压平行段分隔符；不改正文状态，不新增请求或渲染链，`27451e9` 的布局修正已由用户确认修复带图片引用的空白/错位。独立 Embed 待目标核实；帖子预览和 Poll 当前没有实现。
- 无可用译文或严格回填失败时显示宿主原始节点树，这是状态/失败处理，不是尝试另一套翻译实现。352017 的点击接口已核实；340013 点击配置禁用，本次未设备复测。

## 单链路与清理边界

- 正文 ID 来源由 `message.render.identitySource` 明确指定：352017 只取消息 ID 参数；340013 只取绑定正文视图，不逐级尝试内容或父级映射。
- 混淆容器只调用 `nodeConstructors` 指定的精确构造器，参数顺序和字段明确配置；失败不换构造器或改走 copy。未指定构造器的类型使用原 data class copy 规则。
- 点击 span 只有配置构造器/回调一种实现；无 interfaceProxy。
- 回复排障的 trace、逐条空白/布局监听和附件快照已删除；保留已验证的显示投影和必要错误日志。
- 设置读取错误不能用默认值填表并保存；页面锁定并保留存储，用户确认重置后才删除配置。