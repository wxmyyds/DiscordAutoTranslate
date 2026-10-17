# Discord AutoTranslate（LSPosed 模块）— 阶段 1 设计说明

本文记录阶段 1（可行性原型）的结论、Hook 点依据与实现边界。
所有结论都标注了依据来源；推测项明确标注为推测。

---

## 1. 目标与范围

阶段 1 只做**可行性验证**，通过条件（需求 §7 阶段 1）：

- 验证 LibXposed API 102 模块能被 LSPosed 加载，并走通热重载生命周期。
- 验证真实消息的正文替换 + 右下角图标 + 原文/译文切换。
- 验证列表滚动与组件复用时不会串位、不会重复插入图标。

**明确不在阶段 1 范围**：真实翻译引擎、通用 JSON 模板解析、缓存策略、设置界面完整功能。
阶段 1 使用桩引擎（返回 `【原型译文】` 前缀）验证整条链路。

---

## 2. 目标环境（事实，非推测）

依据 `smali/com/discord/BuildConfig.smali`：

| 项 | 值 |
|---|---|
| `VERSION_NAME` | `340.13 - Stable` |
| `VERSION_CODE` | `0x5302d`（340013） |
| `APPLICATION_ID` | `com.discord` |
| `FLAVOR` | `google` |
| `IS_HERMES_ENABLED` | `true` |
| `IS_NEW_ARCHITECTURE_ENABLED` | `true` |

模块入口在运行时读取 `com.discord.BuildConfig.VERSION_CODE` 并记录日志；
与 340013 不一致时打印警告（需求 §5.1 版本检查）。

---

## 3. LibXposed API 102（事实）

以下内容逐条核对了 Maven Central 上的 `api-102.0.0-sources.jar` 源码，不是凭记忆：

- **依赖**：`io.github.libxposed:api:102.0.0`（`compileOnly`）、
  `io.github.libxposed:service:102.0.0`（设置 Activity 需要，`implementation`）。
- **入口类**：`extends io.github.libxposed.api.XposedModule`，**必须公开无参构造**。
- **入口声明**：不使用 `assets/xposed_init`，也不使用 AndroidManifest 的 meta-data，
  而是 `META-INF/xposed/` 下的三个文件：
  - `java_init.list`：每行一个入口类全限定名。
  - `module.prop`：`minApiVersion` / `targetApiVersion` 必填；`autoHotReload` 为 API 102+ 可选。
  - `scope.list`：每行一个包名。
- **生命周期回调**（`XposedModuleInterface`）：
  `onModuleLoaded`、`onPackageLoaded`、`onPackageReady`、
  `onSystemServerStarting`、`onHotReloading(HotReloadingParam)->boolean`、
  `onHotReloaded(HotReloadedParam)`。
- **热重载约束**：只支持**恰好一个** Java 入口类。
  `onHotReloading` 在**旧**代码中执行（返回 false 可拒绝热重载），
  `onHotReloaded` 在**新**代码中执行，其默认实现是
  `param.getOldHookHandles().forEach(HookHandle::unhook)`。
  热重载后**不会**重放包生命周期回调，必须自行重建状态并重新注册 Hook。
- **Hook 模型**：拦截器链，不是旧版的 before/after/`MethodHookParam`。
  `hook(Executable).setId(id).setExceptionMode(mode).intercept(chain -> {...})`；
  改参数用 `chain.proceed(Object[])`，短路返回则不调用 `proceed()`。
- **Remote preferences**：`XposedInterface.getRemotePreferences(String group)`（目标进程内）
  与 `XposedService.getRemotePreferences(String group)`（模块自身进程）读写同一份数据。

**注意**：`io.github.libxposed:service` 的 AAR **自带了 `XposedProvider` 的 manifest 声明**，
所以模块自己的 AndroidManifest **不能**重复声明该 provider，否则 authority 冲突。

### compileSdk 约束（事实）

`api` 与 `service` 的 AAR 元数据均为 `minCompileSdk=37`，因此 `compileSdk` 必须为 37。
依据官方 AGP 兼容表：AGP 9.4 支持 API 37，要求 Gradle ≥ 9.6.0、JDK 17、Build Tools 36.0.0。
本工程据此固定这些版本。

---

## 4. Discord 消息渲染链（事实 + 关键判断）

### 4.1 已确认的类结构

依据 `smali/com/discord/chat/` 下的真实代码：

| 角色 | 类 | 说明 |
|---|---|---|
| 消息数据 | `com.discord.chat.bridge.Message` | `extends MessageBase`；含 `id:String`、`content:StructurableText`、`edited:String` |
| 消息行容器 | `com.discord.chat.presentation.message.DecoratedMessageView` | `extends android.widget.FrameLayout`；binding 只有 `messageView` 一个字段 |
| 正文视图 | `com.discord.chat.presentation.message.view.MessageContentView` | `extends com.facebook.drawee.span.SimpleDraweeSpanTextView`（**是原生 TextView**） |
| 正文挂载 | `MessageContentViewHolder.bind(MessageContentAccessory, ...)` | 17 个参数；持 `messageContentView` 字段 |
| 回复引用 | `MessageViewReplyPreview` | 内部含 `MessageContentView`（binding 字段 `replyText`） |

### 4.2 正文渲染的唯一漏斗

`MessageContentView.setMessageContent` 有两个重载：

1. **6 参**（真正实现）：
   `(MessageMargins, StructurableText, MarkdownTextRenderOptions,
   MarkdownTextRenderEventHandlers, Float, boolean)`
2. 26+ 参（`-hYrISQc` 后缀，由 `MarkdownTextRenderOptions` 展开），另有
   `$default` 合成桥接方法。

**关键判断（事实支撑）**：6 参重载是唯一汇聚点。核对全部调用方：

| 调用方 | 使用的方法 |
|---|---|
| `MessageContentViewHolder`（消息列表） | `setMessageContent-hYrISQc$default` → 经 `$default` → **6 参** |
| `MessageViewReplyPreview` | 同上 |
| `TextDisplayComponentView`、4 个 Embed 视图 | 直接或经 4 参 → **6 参** |

因此**只 Hook 6 参重载**即可覆盖全部正文渲染。这样也避开了 `-hYrISQc` 与 `$default`
这两类带哈希后缀 / 合成的方法名——它们会随 Discord 版本或 Kotlin 编译器版本变化。

### 4.3 为什么不需要逆向 React Native / Hermes

需求 §风险 1 担心 Discord 用 RN 导致无法改 UI。实际核对结果：

- 消息正文是原生 `TextView` 子类，渲染发生在原生层。
- `MainActivity extends ReactActivity`（RN 只是外壳），但**聊天消息列表是原生 View 体系**
  （`DecoratedMessageView` / `RecyclerView` / databinding）。

因此消息文本替换与图标插入都可以在原生层完成，**不需要**触碰 Hermes bundle。

### 4.4 推测项（需原型验证）

- 在 `DecoratedMessageView`（FrameLayout）上以 `gravity=END|BOTTOM` 叠加图标
  **推测**不破坏 Discord 原有布局。该 FrameLayout 目前只有 `messageView` 一个子视图，
  且 `onMeasure` 走 `ReactSelfMeasurer`，叠加子视图的风险较低，但**必须实测**。
  - 若实测发现影响高度测量，回退方案：改为在 `MessageContentView` 的父级
    （`ChatListRelativeLayout` 层）挂图标，或改用 `setCompoundDrawables`。
- 图标若与 Discord 原生的“长按/滑动”手势冲突，需要调整其触摸区域。

---

## 5. 状态与并发设计

### 5.1 防串位（需求 §风险 2）

RecyclerView 会复用 `MessageContentView`，因此**绝不把译文存在 View 上**：

- `HookBridge` 维护 `View → messageId` 映射，在 `bind` 时更新（bind 携带权威消息 ID）。
- 译文、状态、原文全部存在 `StateStore` 中，以 **messageId** 为键。
- 重新渲染前校验“视图当前绑定的消息 ID 是否仍是目标消息”，不是则跳过。
- 图标挂载时若发现该图标此前代表别的消息，先解除旧登记。

### 5.2 防旧结果覆盖（需求 §风险 3）

`StateStore.Entry.contentHash = SHA-256(正文 + 配置版本)`：

- 消息被编辑 → 正文变化 → 哈希变化 → 旧译文立即清空、状态回到 IDLE。
- 异步结果回写前校验 `contentHash` 仍与提交时一致，否则丢弃。

### 5.3 热重载正确性（需求 §风险 6）

- `StateStore.generation` 每次新代次递增；异步任务携带提交时的 generation 快照，
  回写前校验，不一致则丢弃。
- `onHotReloading`（旧代码）：unhook 全部句柄、关闭线程池、清理视图映射。
- `onHotReloaded`（新代码）：重建状态、重新读配置、重新安装 Hook。
- Hook 使用 `.setId(...)`，便于诊断与将来用 `replaceHook` 做原子替换。

---

## 6. 阶段 1 的能力边界（重要）

以下为**当前实现的已知限制**，不是 bug：

1. **只替换纯文本结构**。若消息的 `StructurableText` 是 `AnnotatedStructurableText`
   （含 Markdown / 提及 / 表情 / 链接），当前**不替换**，仅记录日志。
   - 原因：需要重建 `List<ContentNode>` 节点树（约 35 种节点类型，
     见 `smali/com/discord/chat/bridge/contentnode/`），属于阶段 3 工作。
   - `PrimitiveStructurableText(String)` 可以直接构造，因此单行纯文本消息可被替换。
2. **桩引擎**：`HookBridge.StubEngine` 返回 `【原型译文】` + 原文，用于验证链路。
3. **设置界面已可编译但功能未验证**：`SettingsActivity` 通过 remote preferences 读写配置，
   字段齐全（URL/密钥/模型/目标语言/请求头/模板/提取路径/超时/并发），
   但"测试连接"与"测试翻译"未实现（阶段 4）。
4. **未接真实网络**：阶段 2 实现 OpenAI 兼容引擎（`/chat/completions`）。
5. **回复引用内的正文**：会走同一个渲染 Hook，但因 `HookBridge` 只映射
   `MessageContentViewHolder` 的视图，回复引用内的正文视图没有 messageId，
   因此**当前不会替换**。需要单独识别回复引用的消息 ID（阶段 3）。

---

## 7. 构建

**禁止本地构建。** 所有编译/打包经 `.github/workflows/build.yml` 执行：

- 触发：手动 `workflow_dispatch`（可选 Debug/Release）、PR 到 main、功能分支推送。
- 环境：JDK 17、Android SDK `platforms;android-37`、`build-tools;36.0.0`、Gradle 9.6.0。
- 产物：APK，并校验 APK 内确实包含 `META-INF/xposed/` 三个文件。

### LSPosed 使用前提

1. 模块 APK 安装后，在 LSPosed 中启用并勾选作用域 `com.discord`。
2. 需要 **LSPosed 版本支持 API 102**（当前 LSPosed 是否支持需在设备上确认，属未知项）。
3. 强制停止并重启 Discord（首次启用必须）。
4. 热重载验证：更新模块 APK 后观察 `onHotReloading` / `onHotReloaded` 日志，
   检查是否出现重复 Hook 或状态错乱。

---

## 8. 已知未知项（需用户提供或设备实测）

1. 设备上 LSPosed 的版本号及其对 API 102 热重载的实际支持情况。
2. 图标叠加是否影响 Discord 消息行高度测量（§4.4 推测项）。
3. 真实翻译 API 的响应结构是否与默认提取路径 `choices.0.message.content` 一致。
