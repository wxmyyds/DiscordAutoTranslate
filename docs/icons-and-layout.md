# 行内图标、点击与布局刷新

## 行内翻译图标（正式功能）

图标作为文本流的一部分注入 `TextUtilsKt.toSpannable` 返回的
`DraweeSpanStringBuilder` 尾部，随文字自然换行、可精确点击。

- **注入点**：`toSpannable`（所有正文渲染的唯一汇聚点）拿到
  `DraweeSpanStringBuilder` 后，在末尾追加一个 `InlineIconSpan`。
- **挂载时机（主路径）**：Hook `MessageView.setMessage`（7 参），其 `this`
  就是行控件本身，无需沿父链上溯；在 `chain.proceed()` 之后挂载（此时子视图
  状态稳定），并处理行复用到新消息的清理，避免图标串位。
  `MessageHooks.findRowContainer` 保留为兼容回退。
- **三态**（`@drawable` + `setColorFilter` 着色）：
  - 翻译中：`progress_activity_24`（旋转，灰 `#B9BBBE`）；
  - 翻译成功：`translate_24`（主题蓝 `#5865F2`）；
  - 显示原文：`translate_24`（灰 `#72767D`）；
  - 失败：`error_24`（红 `#ED4245`）。
  - 待翻译不显示图标。
- **旋转动画**：翻译中状态对 ImageView/span 反复重绘（`postOnAnimation`
  驱动，曾修复卡死），其他状态停止并归零。
- **点击**：`MessageView.onInterceptTouchEvent` 返回 `!allowChildGestures`，
  会在 `ACTION_DOWN` 抢走手势，图标自身的 onTouchEvent 收不到事件。
  因此 Hook `MessageView.onInterceptTouchEvent`：触点落在图标范围内时直接返回
  false（不拦截），用 `IconClickSpanFactory`/命中判定（范围比较 + 8dp 容差）。
  回调接入 `HookBridge.onIconClicked`：切换原文/译文、失败重试。
- **注入范围**：只处理正常消息正文（文本节点数 > 0 且非 IDLE）；回复引用
  内不注入；富 Embed 每卡片一个（主段优先）；帖子预览挂在 footer；
  投票卡片挂在问题文本末尾。
- 图标资源是模块自带（非 Discord 的），用 `ModuleResources` 在 Discord 进程
  内解析模块的资源表；预加载失败时图标构造时再取一次（已缓存）。

## 布局刷新（译文/切换后行高立即更新）

现象根因（均已在代码/smali 中核实）：

1. 重渲染原本在宿主布局过程（measure/layout）中同步执行，此时再请求布局无效；
2. 旧 `RerenderRegistry.rerender` 把「绑定未知」当作「已复用」永久删除目标，
   长列表滚动（LRU 512）后旧条目被淘汰 → 该消息不再刷新；
3. 宿主刷新单行只走 `notifyItemChanged`，且 `MessageContentViewHolder.bind`
   有 `Intrinsics.areEqual(lastMessageContent, newData)` 早退：内容相等直接
   return，`setMessageContent`/`toSpannable` 不再执行。

修复：

- `HookBridge.requestRerender` 改用 `View.post(...)` 把执行移出宿主布局遍历
  （post 失败回退同步执行）；执行前用 `RerenderRegistry.stillBound(expected,
  actual)` 再校验一次视图绑定，仅确认绑定到其他消息才拒绝执行。
- 重渲染成功后：`forceLayout()` + `requestLayout()` + `invalidate()`，并从正文
  视图沿父链向上刷新最多 3 层（行高由父容器测量决定，只对 TextView
  requestLayout 不足以重测）。
- 对承载该消息的 `RecyclerView` 调用 `notifyItemChanged(bindingAdapterPosition)`
  （宿主自身刷新单行的方式）；因渲染漏斗运行在宿主布局过程内，投递到下一轮
  消息队列，并按消息 ID 同一帧去重，避免重入布局抛异常。

## 判读记录（排查时用）

- `RerenderRegistry.rerender` 记录 `no-target` / `target(h=…,attached=…)`；
  `requestRerender` 记录 `dispatch` / `rebound` / `post-rejected`；
  `invokeRerender` 记录 `invoked(before=…)` / `invoke-failed:异常类名`；
  `postInvalidateRow` 记录 `row-refresh(before=X,after=Y)`。
- 隐私：记录只含消息 ID、视图类名、高度、布尔状态与结论标签。