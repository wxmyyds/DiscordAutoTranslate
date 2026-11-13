# 行内图标、点击与布局刷新

## 行内翻译图标（正式功能）

图标作为文本流的一部分注入版本配置选定的文本转换结果尾部，随文字自然换行、可精确点击。

- **注入点**：统一文本转换 Hook 拿到 `SpannableStringBuilder` 后，在末尾追加 `InlineIconSpan`。
- **挂载时机**：正文渲染和 Embed 绑定期间通过调用链传递宿主 View；回复引用无宿主，不注入。
- **三态**（`@drawable` + `setColorFilter` 着色）：
  - 翻译中：`progress_activity_24`（旋转，灰 `#B9BBBE`）；
  - 翻译成功：`translate_24`（主题蓝 `#5865F2`）；
  - 显示原文：`translate_24`（灰 `#72767D`）；
  - 失败：`error_24`（红 `#ED4245`）。
  - 待翻译不显示图标。
- **旋转动画**：翻译中状态对 ImageView/span 反复重绘（`postOnAnimation`
  驱动，曾修复卡死），其他状态停止并归零。
- **点击**：图标 span 由 `IconClickSpanFactory` 反射创建 Discord 自身的 `lh.d`，提供宿主 `zf.b` 标记接口及 `Function1` 点击回调；`zf/f` 只查询 `zf.b` 并将命中项强转为 `lh/d`。回调转交 `HookBridge.onIconClicked` 切换原文/译文或重试。
- **注入范围**：只处理正常消息正文（文本节点数 > 0 且非 IDLE）；回复引用
  内不注入；富 Embed 每卡片一个（主段优先）；帖子预览挂在 footer；
  投票卡片挂在问题文本末尾。
- 图标资源是模块自带（非 Discord 的），使用 `PackageManager.getResourcesForApplication`
  解析一次模块资源；失败后该进程不再尝试其它资源 API，图标保持缺失。

## 布局刷新（译文/切换后行高立即更新）

根因与下列已核实路径有关（Discord `340013`）：

1. 重渲染原本在宿主布局过程（measure/layout）中同步执行，此时再请求布局无效；
2. 旧 `RerenderRegistry.rerender` 把「绑定未知」当作「已复用」永久删除目标，长列表滚动（LRU 512）后旧条目被淘汰，消息不再刷新；
3. 宿主刷新单行只走 `notifyItemChanged`，且 `MessageContentViewHolder.bind` 有内容相等早退，正文渲染与文本转换不再执行。

352017 使用 profile 中声明的静态正文渲染方法；其重渲染参数由配置提供。其外层列表由 `ChatListViewManager.createViewInstance` 创建为混淆类 `f9.g0`，不是 `com.discord.chat.presentation.list.ChatListView`。因此刷新时应从 `MessageView` 向上查找其最近的外层 RecyclerView，而不能依赖未实际存在的类名。

修复路径：

- `HookBridge.requestRerender` 统一投递到主线程消息队列，把执行移出宿主布局遍历；
  执行前用 `RerenderRegistry.stillBound(expected, actual)` 再校验一次视图绑定，
  仅确认绑定到其他消息才拒绝执行。
- 重渲染后从真实视图层级定位内层正文 item 与 `MessageView` 外层 row，通知对应 adapter 刷新；不以对子视图执行 `UNSPECIFIED` 测量和手动 `layout()` 代替 RecyclerView 行测量。
- 352017 的 RecyclerView holder 查询方法在运行时不可反射调用；本机 LSPosed 日志实测从 LayoutParams 成功解析正文 holder 后，旧代码又在 `notifyItemChangedMethod` 处失败。该版本 adapter 不暴露此方法：`k3.m0.a` 持有 `k3.n0`，其 `d(position, 1, null)` 是已核实的单项变更通知；消息行的所属 RecyclerView 则从 holder 的 `r` 字段取得，避免依赖可能不完整的 ViewParent 链。
- AndroidX RecyclerView/ViewGroup 的公共继承 API 用 `Class.getMethod` 查找；Android
  运行时的 `getDeclaredMethods()` 可能不列出父类 Framework 方法，不能拿它们做这类 API 查找。
- 行刷新通知延迟到主线程布局遍历结束后执行，并按消息 ID 同一帧去重，避免重入布局抛异常。
