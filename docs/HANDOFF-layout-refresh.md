# 交接文档：译文布局不刷新问题（未解决）

> 本文件用于交接给下一位接手者。所有"事实"均在代码或 smali 中可核对；
> "推测"已明确标注。请勿把推测当结论继续叠加修改。

---

## 一、项目与当前状态

- 仓库：`/home/WXM/AllProjects/DiscordAutoTranslate`
- 远端：`https://github.com/wxmyyds/DiscordAutoTranslate.git`
- 当前分支：`fix/translation-trailing-whitespace`
- PR：**#9（OPEN，未合并）** https://github.com/wxmyyds/DiscordAutoTranslate/pull/9
- HEAD：`07b5020`（`diag: write both channels, always record, add channel heartbeat`）
- base：`main`，`main` 最新合并提交 `54aa545`（PR #8）

### 分支上的提交（按时间正序，共 20 个）

| 提交 | 内容 | 设备结果 |
| --- | --- | --- |
| `7cf3cbf` | 修剪译文尾部空白行 | 有效（问题 1 已解决） |
| `8b785d7` | 对齐测试断言 | — |
| `7ae8681` | 重渲染延后到布局之后（`View.post`）+ 执行时重校验绑定 | **设备无效** |
| `37ef58a` | 补 `Nullable` import | — |
| `7985911` | 仿宿主 `forceLayout()+requestLayout()`，沿父链刷 3 层 | **设备无效** |
| `aee3e07` | 记录宿主 relayout 行为的文档 | — |
| `33a4ae5` | 加入 `layout-diag` 日志 | 日志抓不到 |
| `f600b1e` | 诊断改走设置页显示（远程配置回写） | 曾短暂可用 |
| `55f5ba7` | 对 `RecyclerView` 调 `notifyItemChanged(position)` | **设备无效** |
| `5a6710b` | 补 `Reflect` import | — |
| `5a3ff47` | 修 `SchedulerTest` 不稳定用例（只改测试） | CI 稳定 |
| `f3ac31d` | 诊断始终开启 + 屏幕 Toast | Toast 刷屏 |
| `f63b462` | Toast lambda 的 `Context` 改 effectively final | — |
| `a2d1c66` | Toast 加序号、暴露 `dispatch` / `invoke-failed` | 用户只看到 1 条 |
| `38b25cc` | 移除 Toast，设置页加"复制诊断" | 设置页读到空 |
| `20e873f` | 恢复被误删的 `logLayoutFact` | — |
| `2bc75c5` | 远程配置改 `commit()` + 设置页每次重取 `prefs` | 仍读不到 |
| `5a40a9e` | 诊断写入模块私有目录 | 文件未生成（权限） |
| `4f7f489` | 补 `Diagnostics` import | — |
| `07b5020` | 两通道同时写 + 诊断不再受开关控制 + 心跳 | **仍未验证** |

---

## 二、要解决的问题

### 问题 1（已解决）：译文尾部残留空白行
翻译后行数少于原文时留下空行。已在 `ContentNodes.trimTrailingBlank` 修复，
见 `docs/translation-layout-whitespace.md`。

### 问题 2（**未解决，本次交接核心**）：行高/空白行不立即更新

**现象**（用户原话）：
- 翻译完成时、点击"原文/译文"切换时，**文字立即变化**，但**行高与残留空行不立即修正**；
- **滚动之后**才恢复正确。

**已排除**：
- 文本替换本身是成功的（用户能看到译文）。
- 不是 `trimTrailingBlank` 的问题（那是数据层，已修）。

---

## 三、已经尝试且**在真机无效**的三类修复

对应实现均在当前分支，未回滚。评估下一步前请先读这些代码。

### 1. 延后重渲染（`7ae8681`）
`HookBridge.requestRerender` 改用 `View.post(task)`，把重渲染移出宿主布局遍历，
并在执行时重新校验绑定（`RerenderRegistry.stillBound`）。

**为什么这个想法合理**：`RerenderRegistry.rerender` 的调用发生在
`MessageHooks` 对 `setMessageContent` 的拦截里，即 RecyclerView 的 measure/layout 期间；
在布局过程中再次请求布局不会生效。

**结果**：设备上无效。

### 2. 仿宿主配方强制 relayout（`7985911`）
smali 事实：`ChatListChildView$DefaultImpls.onMeasureConsiderFrozen$lambda$0`
的实现正是 `forceLayout()` + `requestLayout()`。本模块复制该配方并沿父链刷 3 层。

**结果**：设备上无效。

### 3. `notifyItemChanged(position)`（`55f5ba7`）
smali 事实：宿主刷新单行内容时，`ChannelChatListAdapter` 处理
`ListOperation$Change` 走的正是 `notifyItemChanged(index)`；`ChatListManager`
在行内容变化时先 `List.set(index,row)` 再生成 `ListOperation$Change`。
本模块改为对承载该消息的 `RecyclerView` 调 `notifyItemChanged(bindingAdapterPosition)`，
投递到下一轮消息队列，并按消息 ID 同帧去重。

**为什么这个想法合理**：滚动能恢复，说明问题在于"该行没有被重新 bind/测量"。

**结果**：设备上无效。

### 一个可解释 `notifyItemChanged` 为何无效的 smali 事实

`MessageContentViewHolder.bind` 开头有
`Intrinsics.areEqual(this.lastMessageContent, 新构造的 MessageContentBindData)` 的早退：
```smali
iget-object v4, v0, ...->lastMessageContent:...MessageContentBindData;
invoke-static {v4, v1}, Lkotlin/jvm/internal/Intrinsics;->areEqual(...)Z
if-eqz v4, :cond_99
return-void          # ← 内容相等直接返回
:cond_99
iput-object v1, v0, ...->lastMessageContent:...
```
`lastMessageContent` 由 `MessageContentAccessory` 等参数构造。重绑时若 accessory 未变，
`BindData` 相等 → `bind` 直接 `return-void` → `setMessageContent` 与 `toSpannable`
**都不会再执行**。所以 `notifyItemChanged` 只是让 RecyclerView 用同一份内容重测一次。

**推测（未验证）**：模块把译文状态存在自己进程的 `StateStore` 里，而不在
`MessageContentBindData` 里，所以宿主认为"内容没变"。若如此，真正需要的是让宿主
认为内容变了——但本模块**不应**去篡改宿主的 `BindData`，这是高风险操作。
**接手者需要自行判断这条推测是否成立，不要直接照做。**

---

## 四、诊断通道：反复失败的过程与当前状态

这是本次交接最需要注意的部分——**浪费了大量时间在诊断通道上**。

### 失败的通道

| 通道 | 结果 | 原因 |
| --- | --- | --- |
| `XLog.d` → logcat | 抓不到 | `XLog.d` 仅在 `sVerbose` 为 true 时输出，而 `verbose_log` 默认 `false` |
| LSPosed 导出日志 | 无模块行 | 用户导出的 `full.log`（4MB）**不含 Discord 进程的 logcat**，`com.discord` PID 在 main 段出现 0 次 |
| 屏幕 Toast | **能用，但刷屏**｜用户明确拒绝（"一秒弹好几个"） |
| 设置页远程配置 | 有时能读到，后来一直空 | 见下 |
| 模块私有目录文件 | 文件从未生成 | **设计错误**：`getModuleApplicationInfo().dataDir` 指向模块包 `/data/user/0/io.github.wxmyyds.discordautotranslate/files`，而模块运行在 `com.discord` 进程，跨应用 dataDir 写入被 SELinux/权限拒绝，异常被 `catch (Throwable ignored)` 吞掉 |

### 关于远程配置通道的关键分析

`Config` 用 libxposed 的远程配置：目标进程 `XInterface.getRemotePreferences(group)`，
设置页 `XposedService.getRemotePreferences(group)`。

**重要事实**：用户**曾经**在设置页看到过完整诊断行
```
msg=1562029352026247171 view=null outcome=target(h=786,attached=true)
```
注意这条是**未压缩格式**，说明它来自设置页/诊断缓冲区，而不是来自被 `compactDiag()`
压缩过的 Toast。**因此远程配置回写通道曾经是通的。**

之后读不到，可能原因（**均为推测，未验证**）：
1. `apply()` 异步落盘，Discord 被切后台/杀死时未执行（已改 `commit()`）；
2. 设置页缓存的 `SharedPreferences` 实例持有内存快照，跨进程写入不刷新（已加 `freshPrefs()` 每次重取）；
3. 目标进程内远程配置只读（**这一条未验证，但若成立，2 的修法无效**）；
4. 用户安装/重启流程未使新模块代码生效。

当前代码（`07b5020`）同时写远程配置与私有文件，且**加了心跳**：
`ModuleEntry.reloadConfig()` 末尾调用 `HookBridge.logDiagnosticHeartbeat(...)`，
无条件写一行 `diag-channel-ok target=com.discord enabled=…`。
诊断也不再受 `verboseLog` 开关控制。

**心跳的意义**：若设置页连这行都看不到 → 通道问题；能看到 → 通道正常，
"没有 layout-diag" 才是真实结论。**这一版尚未在设备上验证。**

### 诊断代码位置（当前分支）

- `core/Diagnostics.java`：有界 40 行 `Deque` + `Sink` 接口
- `ModuleEntry.reloadConfig()`：安装 Sink（写远程配置 + 写文件）、发心跳
- `HookBridge.logRerender()` / `logLayoutFact()`：记录点
- `RerenderRegistry.rerender()`：记录 `no-target` / `target(h=…,attached=…)`
- `HookBridge.requestRerender()`：记录 `dispatch` / `rebound` / `post-rejected`
- `HookBridge.invokeRerender()`：记录 `invoked(before=…)` / `invoke-failed:异常类名`
- `HookBridge.postInvalidateRow()`：记录 `row-refresh(before=X,after=Y)`
- `ui/SettingsActivity`：`refreshDiagnostics()` / `copyDiagnostics()` / `readDiagnosticsFile()`

### 唯一已确认的一次设备数据

```
msg=1562029352026247171 view=null outcome=target(h=786,attached=true)
```
可推出：`RerenderRegistry` **找到了**登记项，视图附着、可见、高 786px。
**之后没有任何 `invoked` 记录**——但当时 `invokeRerender` 的 `catch` 分支只写 `XLog`
不写诊断，所以"抛异常静默返回"与"根本没执行到"无法区分。
该盲区已在 `a2d1c66` 补上（`invoke-failed`），但**之后没有再拿到设备数据**。

---

## 五、关键 smali 事实汇总（可直接引用，无需重新分析）

路径前缀 `smali/com/discord/`。

| 类 | 事实 |
| --- | --- |
| `chat/presentation/list/ChatListChildView$DefaultImpls.smali` | `onMeasureConsiderFrozen`（L61-197）：父视图为 `ChatListView` 且宽度 spec EXACTLY 且 `ChatListView.getMeasuredWidth()==0` 时，用 `makeMeasureSpec(getMeasuredWidth(),EXACTLY)` / `makeMeasureSpec(getMeasuredHeight(),EXACTLY)` **沿用旧尺寸**测量，随后若 `currentKey != lastKey` 则 `post(new ab.b(8,this))` |
| 同上 | `onMeasureConsiderFrozen$lambda$0`（L199）= `forceLayout(); requestLayout();` |
| 同上 | `bindKey` = `setCurrentKey(key)`（L37） |
| `chat/presentation/list/ChatListRelativeLayout.smali` | `.super Landroid/widget/RelativeLayout;`；`onMeasure` 直接转调 `onMeasureConsiderFrozen`（L148） |
| `chat/presentation/list/ChatListConstraintLayout.smali` | `.super Landroidx/constraintlayout/widget/ConstraintLayout;`，结构同上；另有 `ChatListFrameLayout`(FrameLayout)、`ChatListLinearLayout`(LinearLayout) 同构 |
| `chat/presentation/message/MessageView.smali` | L6748 `bindKey(MessageId.box(getId-3Eiw7ao()))` —— 行的 key 是 **MessageId**，内容变化不改变 key |
| `chat/presentation/list/ChannelChatListAdapter.smali` | L~835 处理 `ListOperation$Change` → `notifyItemChanged(getIndex())` |
| `chat/listmanager/ChatListManager.smali` | L262-278 行内容变化：`List.set(index,row)` 后生成 `ListOperation$Change(row.getIndex())` |
| `chat/presentation/message/viewholder/MessageContentViewHolder.smali` | `lastMessageContent` 字段 L71；`bind` L255 内 L534-546 的 `areEqual` 早退（见上文 smali 片段） |
| `chat/presentation/message/view/MessageContentView.smali` | 内**无** `areEqual`；`setMessageContent(Margins,StructurableText,…,Z)V` L1587；`setMessageContent-hYrISQc` L1835；`getShadowView()` L658；`truncate-l3ndG-o` L1106（用 `getShadowView()` 的 `DraweeSpanStringBuilder` 算行数/截断） |
| `chat/presentation/list/ChatListView.smali` | `.super Lcom/discord/device/views/DiscordRecyclerView;` |

适配版本：Discord **340.13 / 340013**（`ModuleEntry.ADAPTED_VERSION_*`）。
**注意**：若用户设备上的 Discord 版本与 `340.13` 不同，Hook 可能整体未匹配 ——
这是"诊断一行都没有"的一个重要候选原因，**尚未核实用户实际版本**。

---

## 六、当前分支改动清单

```
ModuleEntry.java        |  64 +++-    诊断 Sink、心跳、私有目录写入
core/Config.java        |   8 ++     KEY_DIAGNOSTICS
core/Diagnostics.java   | 102 +++++++ 新增
hooks/ContentNodes.java | 129 ++++++- 尾部空白修剪（问题 1，有效）
hooks/HookBridge.java   | 247 ++++++- 重渲染投递/relayout/notifyItemChanged/诊断
hooks/RerenderRegistry.java | 31 +-    stillBound、非删除式 rerender、诊断
ui/SettingsActivity.java| 162 ++++++- 诊断 UI、freshPrefs、读文件
core/SchedulerTest.java |  12 +-     修不稳定用例（只改测试）
hooks/ContentNodesTest.java | 129 ++  7 个新用例
hooks/RerenderRegistryTest.java | 36  新增 4 个用例
hooks/StructurableTextFactoryTest.java | 3 +-
docs/layout-diagnosis-channel.md | 58 ++
docs/rerender-layout-refresh.md  | 78 ++
docs/translation-layout-whitespace.md | 46 ++
```

测试：**159 个，0 失败**（CI 验证）。

---

## 七、接手建议（按优先级）

### 第 0 步：先确认基础事实，不要再改代码
1. **用户的 Discord 实际版本号**。若 ≠ 340.13，`MessageHooks` 可能一个 Hook 都没装上，
   这能解释"连心跳/日志都没有"。检查方式：Discord 设置→关于，或让用户看模块设置页状态文字。
2. **模块是否真的生效**：让用户确认行内翻译图标是否出现。
   - 有图标 → Hook 已装、`toSpannable` 与 `setMessageContent` 链路是通的；
   - 无图标 → 只有 `toSpannable` 生效但 `setMessageContent` 没匹配，或都没装。
3. **心跳是否可见**（`07b5020` 新增）。这一条能一次性判定通道是否可用。

### 第 1 步：拿到一次可用的诊断
不要再发明新通道。可选：
- 手机开发者选项里的 **"日志记录器" / bug report**（多数 ROM 支持导出完整 logcat，
  含应用进程），然后用 `grep DiscordAutoTranslate` 过滤；
- LSPosed 管理器里对**模块**单独查看日志（若有该功能）；
- 安卓 11+ 可用 `adb shell logcat` 或 `adb bugreport`（用户此前说无 adb）。

**注意**：`XLog.d` 受 `sVerbose` 控制，但 `XLog.i` 不受。`hooked MessageContentViewHolder.bind`、
`config loaded`、`onModuleLoaded process=` 都是 `XLog.i`，**本应无条件出现在 logcat**。
用户导出的 `full.log` 里连这些都没有 → 那份日志根本没抓到 Discord 进程。
先解决"如何抓到 Discord 进程日志"，比继续改刷新逻辑更有价值。

### 第 2 步：若拿到日志，按 outcome 判读
| 记录 | 结论 |
| --- | --- |
| 无 `diag-channel-ok` | 模块未加载 / 诊断通道死 / Discord 版本不匹配 |
| `diag-channel-ok` 但无任何 `layout-diag` | 重渲染从未触发 → 查 `RerenderRegistry.register` 是否被调用（`setMessageContent` Hook 是否装上） |
| `no-target` | 译文完成时找不到登记项 → 登记/查找环节 |
| `target(...)` 无后续 `dispatch` | 投递被拦 → 查 `requestRerender` 入口 |
| `dispatch` 后无 `invoked` | `View.post` 排队或丢弃 |
| `invoke-failed:XXX` | 宿主 `setMessageContent` 抛 XXX |
| `row-refresh(before=X,after=X)` | 高度真的没变 → 刷新手段无效，需换策略 |
| `row-refresh(before=X,after=Y)` 但界面仍旧 | 高度变了但绘制/滚动位置没跟上 |

### 第 3 步：若无诊断可用，可考虑的**不同**方向（均未验证，需自行评估）
1. **`ChatListView` 的 key 机制**：`bindKey(MessageId)` 说明行 key 是消息 ID，内容变化不改 key。
   宿主自己刷新内容走 `notifyItemChanged`，但**宿主的数据源里那一行对象本身变了**。
   本模块只改渲染结果、不改数据源，可能是根本差异。
2. 观察宿主在**编辑消息**时的行为：编辑会改变消息内容，此时行高如何立即更新？
   沿该路径找宿主真正用来"刷新单行高度"的机制，比猜测更有依据。
3. 检查 `truncate-l3ndG-o`：宿主用 `getShadowView()`（一个离屏 `SimpleDraweeSpanTextView`）
   计算行数与截断。若本模块替换后的文本没进 `shadowView`，截断/行数计算可能仍基于旧文本。

### 不要做的事
- 不要继续叠加第 4 种"刷新视图"的猜测性修复；
- 不要修改宿主 smali（`smali/` 被 gitignore，且不可提交）；
- 不要用 Toast 做诊断（用户已明确拒绝）；
- 不要写模块私有目录（跨进程 dataDir 无权限，已验证失败）；
- 不要假设用户能提供 adb/logcat。

---

## 八、构建与协作约束（务必遵守）

- **构建只能通过 GitHub Actions**（`AGENTS.md` 强制）。禁止本地 `gradle`/`javac`/打包。
  流程：征得用户同意 → 新建分支 → 提交 → CI（`gh run watch`）→ 用户确认 →
  PR 合并到 `main` → 删除分支。
- 禁止直接改 `main`、禁止 `--force` push、禁止未经批准合并。
- `smali/` 目录被 gitignore，**不得提交或修改**。
- 隐私：日志不得包含消息正文与 API 密钥。现有诊断只含消息 ID、视图类名、
  高度、布尔状态、结论标签。
- `AGENTS.md` 要求：最小修改、保持架构、严格区分事实与推测、不编造日志/结果。
- 本地静态检查脚本 `/tmp/dat-static-gate.py`（未使用 import / 重复 `@Test` /
  花括号平衡）。**该文件在部分会话中会丢失，需要时按此说明重建**：
  - 剥离字符串、字符、注释后再数花括号
  - `import` 的简单名在文件其余部分不出现（Javadoc `{@link X}` 算使用）→ 报未使用
  - 同名 `@Test` 方法报重复
- CI：`.github/workflows/build.yml`，运行 `assembleDebug` + `testDebugUnitTest`，
  校验 `META-INF/xposed/*` 与 3 个 drawable，并发布 HTML 测试报告。
  下载报告：`gh run download <id> --pattern 'unit-test-report-*'`。

---

## 九、环境与工具

- 工具链：compileSdk 37 / targetSdk 37 / minSdk 26，AGP 9.4.1，Gradle 9.6.0，JDK 17
- LibXposed API **102**（`io.github.libxposed:api:102.0.0`），不是旧版 `de.robv.android.xposed`
- 单测依赖：`org.json:json` + `junit:junit:4.13.2`（**无 Robolectric**，不能测 Android 类）
- `gh` CLI 已认证（`wxmyyds`）
- 源码根：`app/src/main/java/com/wxmyyds/discordautotranslate/`，子包 `core` `hooks` `translate` `ui`

---

## 十、其他待办（低优先级）

1. `SchedulerTest.staleWhileWaitingForPermitDoesNotCallEngine` 曾两次随机失败。
   根因已确认：`Scheduler.submit()` 在 `permits.acquire()` **之前**就写入 `inFlight`，
   而测试用 `stats()` 的 `inFlight=1` 判断"第一个任务已持有许可"。
   `5a3ff47` 已改为等引擎真正进入（`holding` latch）。**只改测试，未动生产逻辑。**
2. PR #9 的标题仍是 `fix: trim trailing blank lines after translated content`，
   与实际内容（含 3 次失败的刷新尝试 + 诊断）不符。合并前应更新标题与描述，
   或**只挑 `7cf3cbf` 那个修剪空白的提交**单独合并，把刷新与诊断尝试留在分支上。
   **建议后者**：问题 1 已确认有效，问题 2 未解决不应混入 `main`。
