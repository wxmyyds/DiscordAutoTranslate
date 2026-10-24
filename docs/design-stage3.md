# 阶段 3 设计说明 — 集成真实消息（保留 Markdown）

对应需求 §7 阶段 3：接入自动英文识别、完成编辑后重新翻译、
覆盖频道 / 私聊 / 群聊 / 回复引用。

**用户决策：保留 Markdown**（因此走 `AnnotatedStructurableText` 节点树重建，而非降级为纯文本）。

---

## 0. 阶段 1 错误判断的修正（重要）

阶段 1 曾断言「6 参 `setMessageContent` 是所有正文渲染的唯一漏斗」。
**该断言是错的**，导致真机实测时既不显示译文也无图标。

核对后的真实调用链：

```
消息列表  MessageContentViewHolder.bind
            → MessageContentView.setMessageContent-hYrISQc$default
            → MessageContentView.setMessageContent-hYrISQc   (展开式，34 参)
            → TextUtilsKt.toSpannable                         (结构 → Spannable)
回复引用  MessageViewReplyPreview → TextUtilsKt.toSpannable   (不经 setMessageContent)
```

- 6 参 `setMessageContent`（L1587）**只被 `TextDisplayComponentView` 调用**，
  不在消息路径上；它自己也会转发到 `-hYrISQc$default`。
- 真正的唯一收敛点是 **`TextUtilsKt.toSpannable`**。

修正后的 Hook 布局：

| Hook | 作用 |
|---|---|
| `MessageContentViewHolder.bind` | 唯一可靠的「视图 → 消息 ID」来源 |
| `MessageContentView` 展开式 `setMessageContent` | 提供「视图 + 内容」：挂图标、登记重渲染、触发翻译 |
| `TextUtilsKt.toSpannable` | 所有文本渲染的唯一汇聚点：**翻译替换在此完成** |
| `MessageViewReplyPreview.configureReply` | 回复引用内的内容关联到被引用消息 |

展开式方法用「名字前缀 + 参数最多 + 首参为 `MessageMargins`」定位，
而不是匹配方法名哈希后缀（`-hYrISQc` 会随版本变化）。

---

## 1. 关键发现：文本渲染的唯一漏斗与类型约束（事实）

核对 `smali/com/discord/chat/presentation/textutils/TextUtilsKt.smali` 的
`toSpannable(StructurableText, ...)`（第 2532 行起），其分支逻辑为：

```
instance-of AnnotatedStructurableText  -> 遍历 List<ContentNode> 渲染
else instance-of PrimitiveStructurableText -> 整体当纯文本
else -> new RuntimeException(); throw          // 类型不支持
```

两点结论：

1. **`toSpannable` 是所有文本渲染的唯一入口**。全仓 13 个文件、
   62 处调用它（含 `MessageContentView`、`MessageViewReplyPreview`、`EmbedView` 等）。
   因此替换 `StructurableText` 就能统一影响所有消息渲染路径。
2. **替换对象必须是 `AnnotatedStructurableText` 或 `PrimitiveStructurableText` 之一**，
   传其他类型会被渲染器抛 `RuntimeException`。
   这一点直接否定了「自定义子类实现 `StructurableText`」的做法。

## 2. 为何不采用更省事的方案

| 方案 | 结论 |
|---|---|
| 自定义 `StructurableText` 子类 | **不可行**：渲染器 `else` 分支直接抛异常 |
| 把带 Markdown 的内容替换为 `PrimitiveStructurableText` | 可行但**丢失全部 Markdown 格式**（加粗/标题/列表/链接样式）。与用户「保留 Markdown」的要求不符；仅作为重建失败时的兜底 |
| 复用 Discord 自带 `com.discord.simpleast` Markdown 解析器 | **不可行**：已核对，该包在整个 APK 内**无任何调用方**（属遗留代码），且它的输出是 `Node`/`TextNode`，**不能产出 `ContentNode`**，与渲染路径不兼容 |
| 重建 `AnnotatedStructurableText` 节点树，只替换文本节点 | **采用**。保留类型与格式 |

## 3. 实现方式：用 Kotlin data class 的通用能力重建，而非逐类型写死

`ContentNodes` 不逐个枚举约 35 种节点类型，而是利用 Discord 节点是
**Kotlin data class** 这一事实（已核对每种节点都具备这些方法）：

```
getContent() / getItems()   -> 取子列表
componentN()                -> 取第 N 个字段（从 1 开始）
copy(...)                   -> 重建节点
<init>(String)              -> 新建文本节点
```

重建步骤：

1. 递归收集所有 `TextContentNode`（即真正的自然语言片段），跳过代码类节点；
2. 把译文按原始文本节点数量切分（优先按换行，尽量保持段落结构）；
3. 自底向上重建：**只替换内容列表**，其余字段通过 `componentN()` 原值回填；
4. 结构化节点（表情、提及、时间戳等，无子列表）**原样复用同一实例**。

因此这些字段都会被完整保留（已用单测锁定）：

| 节点 | 需保留的额外字段 |
|---|---|
| `HeadingContentNode` | `level` |
| `LinkContentNode` | `LinkContextData` |
| `CodeBlockContentNode` | `lang`、`inQuote` |
| `BulletListContentNode` | `start`、`ordered` |

新增节点类型时无需改代码：无 `copy` 方法时该节点按原样保留，不会破坏渲染。

### 已知约束

`copyWith` 取「**第一个 `List` 类型参数**」作为内容字段。这是基于对 340.13 全部节点
`copy` 签名的核对（内容字段均为首个 List 参数，`BulletListContentNode` 的 `items`
为唯一 List 参数）。若未来版本出现「首个 List 参数不是内容字段」的节点，
需要改为按 `componentN` 的字段名匹配。

## 4. 回复引用（需求 §3.1）

回复引用内也含一个 `MessageContentView`（binding 字段 `replyText`），
但它**不在** `MessageContentViewHolder.bind` 流程内，因此原先没有 messageId 映射、无法翻译。

新增 Hook：`MessageViewReplyPreview.configureReply(ReferencedMessage, boolean, Function0)`。

- 前置：若参数是 `LoadedReferencedMessage`，取其 `getMessage().getId()`，
  把 `replyText` 视图登记到**被引用消息自己的 ID**。
  （`SystemReferencedMessage` 是系统文案，无真实消息 ID，跳过。）
- 后置：内容已渲染，触发被引用消息的翻译。

**图标策略**：回复引用内**不挂图标**（`findRowContainer` 遇 `MessageViewReplyPreview`
返回 null）。图标只挂被引用消息自己所在的行，避免在引用行内叠加 UI。

## 5. 一条消息多个视图（重要修正）

同一条消息的正文可能同时存在于多处：它自己的行，以及其他消息引用它的回复预览。
阶段 1/2 的 `RerenderRegistry` 每个 messageId 只存一个视图，会导致另一处不更新。

阶段 3 改为每个 messageId 维护**视图集合**：
译文就绪后逐一重渲染；渲染前逐个校验「视图当前绑定的消息 ID 是否仍是目标消息」，
不是则移除（防串位，需求 §风险 2）。视图用弱引用，避免持有已回收的列表项。

## 6. 编辑后重新翻译（需求 §2.8）

消息编辑后 Discord 会重新走渲染链 → 展开式 Hook 记录新的原文参数 →
`onMessageBound` 计算新 `contentHash` → `StateStore.obtain` 检测到哈希变化，
立即清空旧译文与状态 → 重新发起翻译。
回写前再次校验 `contentHash`，旧请求结果被丢弃。

## 6.1 失败后不自动重试（重要）

`toSpannable` 会被频繁调用（动画、滚动、主题变化等都会重绘）。
若在 `FAILED` 状态下无条件地重试，会造成请求风暴并可能触发 API 限流。

因此：`onMessageBound` 遇到 `FAILED` 直接返回，不自动重试；
失败后统一由用户点击图标重试（需求 §2.6）。
重渲染（本模块自行发起）时会设置标记，渲染 Hook 不再重复触发翻译。

## 7. 验证

### 7.0 真机失败与核验修正记录

用户实测反馈「无图标、无翻译」，继而「可翻译但无图标」，
继而「图标不稳定」。逐个 Hook 点与 smali 对照核验后，
发现并修复了以下真实错误。根因与修正汇总：

| # | 位置 | 错误 | 后果 |
|---|---|---|---|
| 1 | `toSpannable` | 阶段 1 误判 6 参 `setMessageContent` 为唯一漏斗；实际它是 `TextDisplayComponentView` 专用，不在消息路径上 | 翻译从未启动 |
| 2 | `findRowContainer` | 找 `DecoratedMessageView`；实际行控件是 `MessageView` | 图标从未被添加 |
| 3 | `TranslationIconView.attachTo` | 只处理 `FrameLayout`；`MessageView` 是 `RelativeLayout` | 图标会跑到左上角 |
| 4 | 图标点击 | `MessageView.onInterceptTouchEvent` 返回 `!allowChildGestures`，会接管手势 | 图标点不动 |
| 5 | `toSpannable` 重载选择 | 同名重载两个（29 参 / 4 参），取第一个匹配则顺序不确定 | 可能挂错方法 |
| 6 | `XLog` | 有框架时只写框架日志，**不写 logcat** | `adb logcat` 什么都看不到，无法排障 |
| 7 | 图标挂载时机（**不稳定根因**） | 靠从正文视图沿 `getParent()` 上溯找行控件；正文是 RecyclerView 的 itemView，绑定阶段尚未 `addView`，且回收/重建时序多变 | 图标时有时无；且往往等翻译完成后才出现、看不到旋转动画 |
| 8 | `bindPre` 中的 `onRowRecycled` | 在 bind 阶段就把刚刚可能挂上的图标隐藏 | 图标被自己清掉 |
| 9 | 图标外观 | 原为纯自绘 `View` | 用户要求改用 `ImageView` + `@drawable/translate_24` |

#### 7.0.1 不稳定根因与最终修法

问题 7 是“不稳定”的根因：**沿父链上溯本身就不是可靠的挂载时机**。
即使延迟到 `post`，仍会受列表回收、频道切换、配置变更（暗色/字号变化触发重测）
等因素影响。

最终改为 Hook **另一个同时提供“行控件 + 消息 ID”的方法**：

```
MessageView.setMessage(Message, MessageContext, ChatEventHandler,
                       ComponentProvider, Function0, Z, Z)   // 340.13：7 参
```

- 它的 `this` **就是行控件本身**（`MessageView extends ChatListRelativeLayout
  extends RelativeLayout`），无需任何父链遍历。
- 首参 `Message` 提供 `getId-3Eiw7ao()`，即权威消息 ID。
- 调用时机在 `RegularMessageDelegate.bindView` 中，紧随行被确定之后，
  且先于字幕/正文渲染，因此翻译一开始就能看到旋转图标。

在 `chain.proceed()` **之后**执行挂载：此时 `setMessage` 内部已清空并重建
`accessoriesView`，子视图状态稳定。同时在此处处理**行复用到新消息**的清理，
从根本上避免图标串位。

原来的 `findRowContainer` 保留为兼容回退（若 `setMessage` Hook 未匹配），
但不再作为主路径。

### 7.1 已核验正确的 Hook 点（逐项对照 smali）

| Hook | 核验内容 | 结果 |
|---|---|---|
| `MessageContentViewHolder.bind` | 17 参、首参 `MessageContentAccessory`；字段 `messageContentView`；`getMessageId-3Eiw7ao()` | ✅ |
| `MessageView.setMessage` | 7 参、首参 `Message`（`getId-3Eiw7ao` 存在）；`this` 为行控件 | ✅ |
| 展开式 `setMessageContent` | 34 参、首参 `MessageMargins`、`getArg(1)` 为 `StructurableText`；`$default` 因首参不是 `MessageMargins` 被正确排除 | ✅ |
| `configureReply` | 3 参、首参 `ReferencedMessage`；`LoadedReferencedMessage.getMessage()` 存在 | ✅ |
| 回复引用渲染路径 | 确实**直接**调 `toSpannable`（全文件 3 处，不经 `setMessageContent`） | ✅ `toSpannable` Hook 可覆盖 |
| 消息正文本路径 | `-hYrISQc`（1835 行起）内含 `toSpannable` 调用（2213 行） | ✅ 修复方向正确 |
| 聊天列表实现 | `RegularMessageDelegate.createView()` 返回 `MessageView`；由 `ChatListAdapterConfiguratorKt` 装配，原生 RecyclerView（非 RN） | ✅ 原生 Hook 路线成立 |
| `MessageView` 不覆盖布局 | `MessageView` 无 `onLayout`/`onMeasure`/`addView` 重写；`ChatListRelativeLayout.onMeasure` 仅做冻结优化，不忽略子视图 | ✅ 添加子视图安全 |
| `MessageView` 不清空子视图 | 全文件无 `removeAllViews`/`removeView` | ✅ 图标不会被内部清除 |

### 7.2 图标（阶段 3 补充）

- 图标实现为 **`ImageView`**（`TranslationIconView extends ImageView`），
  图片资源为模块自带的矢量图。三种状态各一张（用户指定）：

  | 状态 | 资源 | 着色 |
  |---|---|---|
  | 翻译中 | `drawable/progress_activity_24`（旋转） | 灰 `#B9BBBE` |
  | 翻译成功 | `drawable/translate_24` | 主题蓝 `#5865F2` |
  | 显示原文 | `drawable/translate_24` | 灰 `#72767D` |
  | 失败 | `drawable/error_24` | 红 `#ED4245` |

- 旋转动画：翻译中时对 ImageView 本体做 `animate().rotation(360f)`
  无限循环（`LinearInterpolator`，900ms）；其他状态 `cancel()` 并重置为 0°。
- 这些资源 **不是 Discord 的**（已 grep 全部 `R$drawable` 确认）。
  因此必须在模块 APK 内自带，并在 Discord 进程内加载。
- 加载方式：`ModuleEntry` 在 `onModuleLoaded` 调用
  `ModuleResources.attach(this)`，安装时用 `getModuleApplicationInfo()` +
  `PackageManager.getResourcesForApplication()` 一次解析出模块资源表，
  再分别取三张图交给 `TranslationIconView.setIconDrawables()`。
  若预加载失败，图标构造时会自行再取一次（已缓存，不会重复解析）。
- 三张矢量图均已移除 `android:tint="?attr/colorControlNormal"`
  （避免跨包解析主题属性失败），`fillColor` 为纯白占位色，
  实际颜色由代码 `setColorFilter` 控制。
- **待翻译不显示图标**（用户确认）。

### 7.2.1 图标点击（重要：不能靠图标自身的 onTouchEvent）

实测发现：译文已正常显示，但图标点不动，因此无法切换回原文。

**根因（已核对 smali）**：

```smali
.method public onInterceptTouchEvent(Landroid/view/MotionEvent;)Z
    iget-boolean p1, p0, MessageView;->allowChildGestures:Z
    xor-int/lit8 p1, p1, 0x1     # 返回 !allowChildGestures
```

`ViewGroup` 的派发顺序是：**先调 `onInterceptTouchEvent`，返回 true 就由该
ViewGroup 接管整个手势序列，子视图收不到任何事件**。
因此当 `allowChildGestures == false` 时，行控件在 `ACTION_DOWN`
就抢走了手势。

这意味着在图标自己的 `onTouchEvent` 里调
`requestDisallowInterceptTouchEvent(true)` 是**无效的** ——
那一刻图标还没拿到任何事件。

**修法**：Hook `MessageView.onInterceptTouchEvent`，当触点落在
图标范围内时直接返回 `false`（不拦截），事件才会下发给图标。
命中判定用 `TranslationIconView.hitTestIcon`：拿到行内的图标，
用事件坐标（已经是相对行控件的坐标，与图标 `getLeft/getTop` 同系）
做范围比较，并加 8dp 容差方便点按。

> 教训：涉及 ViewGroup 手势时，先确认哪一层在 `onInterceptTouchEvent`
> 里拦截，而不是在叶子视图上想办法。

### 7.3 诊断日志

排障期已开启以下机制（见 `XLog` 与 `MessageHooks.install`）：

- 日志**同时**写 `android.util.Log` 与框架日志，`adb logcat` 可见。
- 启动时输出单行摘要：`hook pipeline armed: N/6`
  （bind / row / rowTouch / expandedRender / toSpannable / configureReply）。
  期望为 4；每个 Hook 失败时上方均有具体原因日志。
- 关键流程日志：`bind <id>` → `translate start <id>` → `translate done <id>`；
  以及 `toSpannable substituted for <id>`。
- 详细日志默认开启（`Config.DEF_VERBOSE_LOG = true`）。

抓日志：

```
adb logcat -s DiscordAutoTranslate
```

### 7.3 单元测试（`ContentNodesTest`）

Discord 真实节点类在单测环境不存在，但 `ContentNodes` 完全通过反射
（`getContent`/`getItems`/`componentN`/`copy`/`<init>(String)`）操作节点。
因此测试定义**同名**假节点类模拟 Kotlin data class 结构，
从而真正执行被测的反射代码路径（而非只测表面）。

覆盖：类型判断、文本替换、嵌套结构保留、额外字段保留（level / LinkContextData /
lang / ordered）、行内代码与代码块不被翻译、列表节点 items 处理、
无可翻译文本时返回 null、**不修改原始节点与列表**、译文切分策略。

### 7.2 设备侧（需人工执行）

| 场景 | 预期 |
|---|---|
| 纯文本英文消息 | 显示译文 |
| 含 **加粗/斜体** 的消息 | 显示译文且格式保留 |
| 含 `标题`、`列表` 的消息 | 显示译文且结构保留 |
| 含 **表情/提及** 的消息 | 译文显示，表情与提及原样保留 |
| 含 `行内代码`/代码块 的消息 | 代码部分保持原文，其余部分译出 |
| **回复引用**中的消息 | 引用内正文显示译文 |
| 编辑消息 | 旧译文立即消失，随后更新为新译文 |
| 快速滚动 | 无串位、无重复图标 |
| 连续两条引用同一条消息 | 两处同步更新 |

---

## 8. 阶段 3 仍未覆盖

1. **Embed 翻译**：已实现（见 `EmbedHooks`，smali 核对 Discord 340.13）。
   - 富 Embed：Hook `EmbedViewHolder.bind`（9 参，第 2 参 `EmbedMessageAccessory`
     自带 `getMessageId`），登记 `embed.getTitle()/getDescription()/getFields()`
     的各段 StructurableText——它们与 `EmbedView.setEmbed → toSpannable` 传入的是
     同一实例，身份映射与替换复用正文漏斗，无需改动 `toSpannable` Hook。
   - 原生帖子预览（X 等）：`PostPreviewEmbedViewHolder.bind` 登记标题；
     标题是普通 String，译文经 `setPostPreviewEmbed-4itWklQ`（第 0 参即消息 ID）
     的参数替换回填。
   - 每段文本用「行消息 ID + U+0000embed U+0000 + 内容哈希」作为独立状态键，
     与正文及同消息其他段互不覆盖；`RerenderRegistry.stillBound` 对同行 Embed
     键放宽（行 ID 变化仍然拒绝）。
   - 按需求不翻译：作者名、页脚、提供者/站点名（品牌）、URL、图标；
     帖子预览的 subtitle/footer 属品牌信息，不登记。语言检测器兜底过滤
     URL、过短与非拉丁文本。
   - 行内图标：富 Embed 每卡片一个（主段优先描述，回落标题/首个字段）；
     帖子预览挂在 footer（仅图标身份映射，不参与替换）。点击行为与正文一致：
     成功切换原文/译文、失败重试。回复引用仍不注入（无宿主，沿用既有行为）。
   - 帖子预览的 subtitle 为纯 String 且多为作者/品牌信息，暂不翻译。
2. **投票卡片翻译**：已实现（见 `PollHooks`，smali 核对 Discord 340.13）。
   - Hook `PollViewHolder.bind`（4 参，`PollMessageAccessory` 自带 messageId）；
     问题文本 `data.getQuestion().getText()` 与各选项
     `getAnswers()[i].getAnswer().getPollMedia().getText()` 均为纯 String，
     全程不经 toSpannable（问题直接 `TextView.setText`，选项经
     `PollMediaView.setMedia` 构造 DraweeSpanStringBuilder）。
   - 替换采用绑定参数深层重建：译文就绪的文本经 Kotlin data class 的
     copy/componentN 通用链换入（PollMessageAccessory → PollData → PollMedia 与
     PollAnswerAccessory → PollAnswer → PollMedia），未就绪保持原实例；
     译文完成后重渲染（重新 bind）生效。状态键复用 Embed 的
     「行消息 ID + 内容哈希」。
   - 行内图标挂在问题文本末尾（`pollView.binding.questionText`）：该视图不在
     宿主的 NestedClickableSpan 分发链上，命中判定由自建触摸监听完成
     （触点 → 字符位置 → span 区间），点击切换/重试与正文一致；
     旋转动画复用 InlineIconSpan。选项文本不挂图标。
2. **流式（SSE）响应**：仍只支持一次性 JSON 响应。
3. **`ColorString` 等样式细节**：译文继承原节点的样式字段，但模型返回的
   译文不含 Markdown 语法时，格式由原结构（而非译文内容）决定，属预期行为。
