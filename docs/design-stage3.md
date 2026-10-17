# 阶段 3 设计说明 — 集成真实消息（保留 Markdown）

对应需求 §7 阶段 3：接入自动英文识别、完成编辑后重新翻译、
覆盖频道 / 私聊 / 群聊 / 回复引用。

**用户决策：保留 Markdown**（因此走 `AnnotatedStructurableText` 节点树重建，而非降级为纯文本）。

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

已有机制在阶段 3 得到完整覆盖：消息编辑后 Discord 会重新调用
`setMessageContent` → `interceptRender` 记录新的原文参数 → `onMessageBound` 计算新
`contentHash` → `StateStore.obtain` 检测到哈希变化，立即清空旧译文与状态 →
重新发起翻译。回写前再次校验 `contentHash`，旧请求结果被丢弃。

## 7. 验证

### 7.1 单元测试（`ContentNodesTest`）

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

1. **Embed 内正文**：`EmbedView` 等也调用 `toSpannable`，但它们不在
   `MessageContentViewHolder` 流程内、没有消息 ID 映射，因此当前不替换。
   需要时可仿照回复引用的做法，从 Embed 的上游数据取 messageId。
2. **流式（SSE）响应**：仍只支持一次性 JSON 响应。
3. **`ColorString` 等样式细节**：译文继承原节点的样式字段，但模型返回的
   译文不含 Markdown 语法时，格式由原结构（而非译文内容）决定，属预期行为。
