# 译文布局延迟刷新修复

## 问题

用户反馈：翻译完成时、点击切换原文/译文时，文字会变，但行高与残留空行不立即更新；**滚动后**才恢复正确。

## 已确认的代码事实

1. **重渲染在宿主布局过程中同步执行**

`MessageHooks` 的 `setMessageContent` 拦截直接调用 `HookBridge.onMessageBound(...)`；`onMessageBound` 在缓存命中、API 未配置、开始翻译三条路径上都同步调用 `RerenderRegistry.rerender(messageId)`。这段代码运行在宿主 `bind` 调用栈内，即 RecyclerView 的 measure/layout 期间。点击切换走 `onIconClicked` → `toggleShowingOriginal` → `rerender`，同样在主线程同步链路里。

2. **绑定未知会被当成“已复用”并永久删除目标**

旧 `RerenderRegistry.rerender` 的判断是 `if (!messageId.equals(bound)) { it.remove(); }`。`viewToMessageId` 是容量上限 512 的 LRU（`HookBridge.boundedMap(512)`），长列表滚动后旧条目被淘汰，`messageIdOf` 返回 null → 目标被永久移除 → 该消息此后只在重新 bind（滚动）时才会再登记。

3. **模块侧没有任何布局请求补偿**

替换文本经由 `SimpleDraweeSpanTextView.setDraweeSpanStringBuilder` → `TextView.setText(…, SPANNABLE)`，完全依赖 `TextView` 自行 `requestLayout`。模块没有显式请求重新测量，也没有把重渲染移出当前布局过程。

这三条与“文字变了、行高要等滚动才对”的现象一致。**不过，各条在真机上的具体贡献比例尚未通过日志确认**，不声称已定位唯一根因。

## 最小修复

- `HookBridge.requestRerender`
  - 改为通过调用目标视图的 `View.post(...)` 投递，把重渲染移出当前宿主布局遍历；`post` 失败时回退为原同步执行，不丢功能。
  - 新增带身份校验的重载：执行前再校验一次视图是否已绑定到其他消息；绑定未知不视为复用。
  - 重渲染成功后刷新布局：与宿主配方一致（`ChatListChildView$DefaultImpls.onMeasureConsiderFrozen$lambda$0` 就是 `forceLayout()` + `requestLayout()`），本模块改为 `forceLayout()` + `requestLayout()` + `invalidate()`，并从正文视图沿父链向上刷新最多 3 层 —— 行高由父容器测量决定，单纯对 TextView `requestLayout()` 不足以重新执行 `onMeasure`。
- `RerenderRegistry`
  - 抽出 `stillBound(expected, actual)`：`expected == null`、`actual == null` 或相等都视为可渲染，**只有确认绑定到其他消息才移除**。
  - `rerender` 把正文视图与消息 ID 一起传给 `requestRerender`，把复用校验放到真正执行时刻。
- 不改状态模型、缓存键、请求协议、Hook 安装时序；不修改宿主 smali。

### 追加事实：宿主会沿用旧测量尺寸

`ChatListRelativeLayout`（`MessageView` 的父类）的 `onMeasure` 调用
`ChatListChildView$DefaultImpls.onMeasureConsiderFrozen`。当父视图宽度测量为
EXACTLY 且 `ChatListView.getMeasuredWidth()==0` 时，该方法用
`makeMeasureSpec(getMeasuredWidth(), EXACTLY)` 与
`makeMeasureSpec(getMeasuredHeight(), EXACTLY)` **沿用旧尺寸**测量，然后 post 一次
`forceLayout()+requestLayout()` 补偿。这说明“没有 `forceLayout` 的 `requestLayout`”
在该列表里不保证重新测量，与“必须滚动才更新”的现象一致。

### 影响与风险

- 重渲染延后到下一个消息队列周期（通常下一帧），视觉上最多一帧延迟；按钮/图标点击不再在宿主布局中重入渲染。
- 视图未附着时 `View.post` 会排队到下一次 attach，行为与滚动后重新渲染一致。
- `requestLayout` 会额外触发一次测量；仅限单条消息的正文视图，未做全列表刷新。
- 日志仍不含消息正文与 API 密钥。

## 验证

- 单元测试 `RerenderRegistryTest`：绑定未知不视为复用、相同绑定有效、绑定他人被拒绝、缺少期望值时不校验。
- 构建与单元测试：**该操作需要通过 GitHub Actions 执行。** 本地只做静态词法检查，不运行 Gradle、javac 或测试。

### 设备复核（待用户执行）

1. 安装本分支 GitHub Actions 产出的 APK，让 Discord 加载更新后的模块。
2. 翻译完成时**不要滚动**，确认行高与空行立即正确。
3. 点击图标切换原文/译文，同样不滚动，确认立即正确。
4. 连续快速滚动超过 512 条消息后，回到早先的消息再切换，确认仍能刷新（验证 LRU 淘汰路径）。
5. 若仍有延迟，提供脱敏信息：哪条路径（翻译完成/点击切换）、延迟大致时长、是否需要滚动、滚动多远、Discord 与模块版本。可附 `rerender skipped` / `rerender failed` 附近的脱敏日志，不要提供 API 密钥或私聊正文。
