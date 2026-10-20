# 翻译后多余空行修复

## 问题

用户反馈：原文两行、译文一行时，译文下方仍残留一行空白。要求按“当前显示内容”计算布局，不保留原文遗留的空行。

## 已确认的代码机制（事实）

1. **位置补空**：`ContentNodes.splitTranslation` 在译文可用行数少于原文文本节点数时，首段承载全部译文，其余位置补空串。原文 `行1 / 换行 / 行2` 有两个 `TextContentNode`，译文一行即得到 `["译文", ""]`。
2. **换行是独立结构节点**：宿主 `TextUtilsKt` 把 `NewLineContentNode`、`LineBreakContentNode` 渲染为 `"\n"`（`TextUtilsKt.smali` 约 3665–3673 行）；`ParagraphContentNode` 在非最后一个节点时还会追加 `"\n\n"`（约 5759–5762 行）。这些节点不承载译文，替换后仍保留。
3. **宿主不裁剪尾部空白**：`TextUtilsKt` 中没有对应 `trim` 的收尾处理，`SpannableStringBuilder` 的长度就是布局高度，因此尾部空文本节点与换行节点都会占一行高度。
4. **视图更新不是原因**：切换原文/译文走 `RerenderRegistry.rerender` → `HookBridge.requestRerender`，重新执行 `setMessageContent` / `configureReply`，`TextUtilsKt.toSpannable` 每次重新生成 spannable，长度变化会由宿主 `setText` 触发重新布局。未发现缓存旧 spannable 的路径。

## 未确认项

宿主折叠/截断（`MessageContentView` 尾部删除逻辑）是否也参与该布局，需要真机样例观察。本次不修改宿主 smali，也不改 Hook 时序。

## 最小修复

只改模块侧，不新增依赖：

- `ContentNodes.normalize`：`\r\n`、`\r` 统一为 `\n`，并去掉首尾空白，避免模型返回结尾换行再叠加一行。
- `ContentNodes.trimTrailingBlank`：替换成功后自末尾向前删除纯空白节点，包括空/空白 `TextContentNode`、`NewLineContentNode`、`LineBreakContentNode`，以及内容全为空白的格式容器。
  - 只裁尾部；正文中间的空行可能是有意留白，保持不变。
  - 提及、emoji、代码等可见结构结尾时立即停止，不会被当作空白删掉。
  - `BulletListContentNode` 逐项裁剪内部尾部空白，但保留项目符号；只有整项内容全空时才去掉该尾项，全部为空时整个列表不渲染。
  - 裁剪中的 `copy(...)` 失败只放弃该次裁剪，保留原节点，**不会**让已翻译的消息退回英文。
- 裁剪作用于重建后的新列表，原始节点树不变。

### 影响范围

仅影响译文替换后的节点树；原文路径、纯文本路径、请求协议、缓存键、Hook 时序均不变。裁剪是视觉收尾，若某条译文刻意以空行结尾，会少一行空白。

## 验证

- `ContentNodesTest` 新增：两行→一行的尾部裁剪、`NewLineContentNode` / `LineBreakContentNode` / 空白文本节点三种变体、`normalize` 的 CRLF 与首尾换行、尾部空段落容器被整体移除、以 emoji/可见文本收尾时不被裁剪、列表项尾部空白裁剪但项目保留、容器无法复制时保留译文并保持原节点。
- 既有断言同步调整：共享文本实例与共享容器的尾部空片段过去断言为 `""`，现断言为被裁剪（`ContentNodesTest`、`StructurableTextFactoryTest`）。
- 构建与单元测试：**该操作需要通过 GitHub Actions 执行。** 本地只做静态词法检查，未运行 Gradle、javac 或测试。

### 设备复核（待用户执行）

1. 安装本分支 GitHub Actions 产出的 APK，并让 Discord 加载更新后的模块。
2. 用“两行英文、译文一行”的消息复测：译文下方不应再有空白行。
3. 检查含 emoji / 提及收尾、列表、段落的消息，确认尾部空白消失但可见内容完整。
4. 切换原文/译文，再滚动回看、重新进入频道，确认布局随内容变化而不是保留原文高度。
5. 若仍有多余空白，提供脱敏样例（原文行数与译文行数）、Discord 与模块版本，以及是否在折叠/展开状态下出现。
