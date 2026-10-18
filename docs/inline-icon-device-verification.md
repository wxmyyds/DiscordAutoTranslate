# 行内翻译图标 —— 真机可行性验证记录

**验证日期**：2026-10-18
**验证分支**：`exp/inline-icon-draw-test`（验证完成后该分支已删除）
**设备**：用户自有已 Root + LSPosed 设备，目标进程为 Discord 340.13
**目的**：确认「翻译图标作为文本流的一部分、随文字自然换行、可精确点击」在当前 Discord 版本上是否可行

> 本文件只记录**验证过程与结论**。正式实现见 `docs/inline-icon-feature.md`；
> 验证用的实验代码（`InlineIconExperiment` 等）已随实验分支删除，不在本仓库保留。

---

## 一、验证前的不确定点

静态分析（smali）能证明的最多到「通道存在」，有两个关键点当时**无法静态确认**：

1. **绘制**：`DraweeSpanStringBuilder` 自带一套基于 `DraweeHolder` 的自绘
   `ReplacementSpan` 体系（`smali/l9/a.smali`），因此无法确定标准
   `ImageSpan`/自定义 `ReplacementSpan` 是否会被 `SimpleDraweeSpanTextView`
   正常绘制。
2. **点击命中**：`NestedScrollOnTouchTracker.handleTouch` 会按字符位置查找
   `NestedClickableSpan`，但把自定义 span 放进文本流后能否被精确命中，只能实测。

这两点决定了整个方案是否可行，因此必须真机验证。

## 二、smali 依据（已核实，支撑方案成立）

| 证据 | 文件位置 |
|---|---|
| `DraweeSpanStringBuilder` 继承 `SpannableStringBuilder`，`append`/`setSpan` 可用 | `smali/com/facebook/drawee/span/DraweeSpanStringBuilder.smali` |
| `SimpleDraweeSpanTextView` 继承 `TextView` 且无 `onDraw` 重写；`setDraweeSpanStringBuilder` 内部就是 `super.setText(..., SPANNABLE)` | `smali/com/facebook/drawee/span/SimpleDraweeSpanTextView.smali` |
| 点击命中：`layout.getOffsetForHorizontal` 定位字符位置，再取该位置的 `NestedClickableSpan` span | `NestedScrollOnTouchTracker.smali`（`handleTouch$react_gesture_handler_release`） |
| 官方模式：`ClickableSpan` 同时继承标准 `ClickableSpan` 并实现 `NestedClickableSpan` | `smali/com/discord/span/utilities/spannable/ClickableSpan.smali` |
| `MessageContentView.setOnClickListener` 被重写为抛异常（因此不能用行级点击） | `smali/com/discord/chat/presentation/message/view/MessageContentView.smali` |
| `MessageView.onInterceptTouchEvent` 返回 `!allowChildGestures`；普通消息该字段默认 `true`，故行控件不抢子视图触摸 | `MessageView.smali`、`MessageRowKt.smali` |
| 展开式 `setMessageContent` 内 `toSpannable` 为同步调用（因此可用 ThreadLocal 传递宿主） | `smali/com/discord/chat/presentation/message/view/MessageContentView.smali` |

## 三、三个实验与真机结果

### 实验 1：绘制

**做法**：在 `toSpannable` 返回的 `DraweeSpanStringBuilder` 末尾追加一个不换行空格
`\u00A0` 作为占位字符，并在该字符范围上挂自绘 `ReplacementSpan`（`ImageSpan` 子类，
18dp，不修改 `FontMetricsInt`）。

**观测**：图标是否显示、尺寸是否正确、长消息时是否随文字自然换行。

**结果**：✅ **通过**
- 用户确认：**尺寸与位置都正常**；
- 用户确认：**长消息时会自然换行**，未见遮挡或截断正文。

### 实验 2：点击

**做法**：在**同一字符范围**上再叠加一个实现宿主 `NestedClickableSpan` 接口的点击 span
（用 `java.lang.reflect.Proxy` 动态实现，避免编译期依赖 Discord 类），`onClick`
打日志作为命中信号。

**观测**：点击图标是否触发回调。

**结果**：✅ **通过**

logcat 原始输出：

```
10-18 17:07:54.985 14730 14730 I DiscordAutoTranslate: exp: INLINE ICON CLICKED msg=1561302715492208803
```

证明 `NestedScrollOnTouchTracker` 能**精确命中**注入的 span 并回调 `onClick`。
同一字符范围叠加两个 span 不影响命中。

### 实验 3：原生交互

**观测**：注入后是否破坏 Discord 原有交互。

**结果**：✅ **通过**

| 项目 | 结果 |
|---|---|
| 链接点击 | ✅ 正常 |
| 提及点击 | ✅ 正常 |
| emoji 点击 | ✅ 正常 |
| 长按（消息菜单） | ✅ 仍能弹出 |
| 列表滚动 | ✅ 正常 |

## 四、结论

**「图标作为文本流的一部分、随文字自然换行、可精确点击」在当前 Discord 版本上可行。**

验证前排除了两个未知点：

1. `SimpleDraweeSpanTextView` + `DraweeSpanStringBuilder` **会正常绘制**自定义
   `ReplacementSpan`，自绘图标方案成立；
2. 注入的 span 能被 `NestedScrollOnTouchTracker` **精确命中**并回调 `onClick`；
3. 同一字符范围叠加「图标 span + 点击 span」不破坏链接/提及/emoji 点击、长按与滚动。

> 说明：验证初期曾基于静态分析给出「当前版本可能无法实现真正的行内图标」的**推测**，
> 该推测已被上述真机结果**推翻**。结论以实测为准。

## 五、验证代码的处置

验证用的实验代码（`InlineIconExperiment`、实验版 `InlineIconSpan`/`IconClickSpanFactory`）
与实验分支 `exp/inline-icon-draw-test` 均已删除。正式实现重新编写，见
`docs/inline-icon-feature.md`，两者不共享代码。

验证过程中另有在真机上**未覆盖**、留待集成阶段处理的事项（当时记录，现已处理）：

| 项 | 集成阶段的处理 |
|---|---|
| 点击后的实际行为 | 已接入 `HookBridge.onIconClicked`（切换原文/译文、失败重试） |
| 三态图标 | 已接入 `progress_activity_24` / `translate_24` / `error_24` |
| 翻译中旋转动画 | 已实现（`postOnAnimation` 驱动重绘，含卡死修复） |
| 注入范围 | 已限制为「正常消息正文 + 文本节点数 > 0 + 非 IDLE」 |
| 右下角旧图标 | 已移除 |
| 回复引用预览 | 已按要求不注入 |

## 六、验证的局限（如实说明）

1. 本次只在**一台设备、一个 Discord 版本（340.13）**上验证。
2. 未覆盖：极小屏幕 / 超大字体下的换行与点击命中、RTL 语言布局。
3. 未覆盖：同时有大量消息处于翻译中时的动画表现（集成阶段才加入三态与动画，
   本次实验的图标是静态的）。
4. 实验 2 的点击只验证了「回调被触发」，未验证回调内部行为（集成阶段才接入）。
