# 行内翻译图标（正式功能）实现说明

**分支**：`feat/inline-translate-icon`（从 `main` 新建）
**前置验证**：`docs/inline-icon-device-verification.md`（真机确认绘制 / 点击 / 原生交互三项全部通过）

本轮把已验证的原型集成为正式功能，**未扩大需求范围**。

---

## 一、实现方式

### 1.1 图标如何进入文本流

注入点在 `TextUtilsKt.toSpannable`（所有正文渲染的唯一汇聚点）。拿到返回的
`DraweeSpanStringBuilder`（继承 `SpannableStringBuilder`）后：

```java
int start = ssb.length();
ssb.append("\u00A0");                 // 占位字符：不换行空格，提供真实字符位置
int end = start + 1;
ssb.setSpan(iconSpan,  start, end, SPAN_EXCLUSIVE_EXCLUSIVE);  // 绘制
ssb.setSpan(clickSpan, start, end, SPAN_EXCLUSIVE_EXCLUSIVE);  // 点击
```

图标因此是**文本流的一部分**：紧跟正文最后一字，最后一行空间不足时由 `Layout`
自然换到下一行。**不使用任何屏幕绝对坐标或负边距。**

### 1.2 宿主 View 如何传给图标（旋转动画的关键）

`toSpannable` 是静态方法，拿不到渲染它的 `TextView`；而旋转动画必须有宿主才能触发重绘。

`setMessageContent` Hook 在调用链的**同步范围内**把宿主写入
`InlineIconInjector.CURRENT_HOST`（`ThreadLocal<View>`），`finally` 中清理：

```java
InlineIconInjector.enterHost(view);
try { return chain.proceed(); }
finally { InlineIconInjector.exitHost(); }
```

`setMessageContent → toSpannable` 同线程同步，因此该传递可靠。

### 1.3 三态与旋转
| 状态 | 图标 | 配色 | 动画 |
|---|---|---|---|
| `TRANSLATING` | `progress_activity_24` | `#B9BBBE` | 原地旋转 |
| `TRANSLATED`（显示译文） | `translate_24` | `#5865F2` | 无 |
| `TRANSLATED`（显示原文） | `translate_24` | `#72767D` | 无 |
| `FAILED` | `error_24` | `#ED4245` | 无 |
| `IDLE` | 不注入 | — | — |

**旋转的可靠重绘与停止**：`ReplacementSpan` 不会自行触发重绘，因此
`InlineIconSpan` 用 `host.postOnAnimation` 自驱动，每次 `invalidate()`。

「翻译中图标卡死不动」有四条已知成因，均已防御：

| 成因 | 修复 |
|---|---|
| **瞬时 detach 永久终止动画**（主因）。列表布局/复用会 detach 再 attach；原实现一旦发现 `!isAttachedToWindow()` 就把运行标记永久置为终止 | 区分 `active`（动画应存在）与 `running`（正在排帧）：detach 只置 `running=false`，`active` 不变；`View.OnAttachStateChangeListener` 在重新附着时恢复排帧 |
| **不旋转的新 span 不停止旧的旋转 span**。翻译完成时新 span `spinning=false`，原实现直接 return，旧 ticker 永远 invalidate | `bindHost` **无条件接管**：不旋转时从登记中移除该视图的旧 span 并终止它 |
| **转速与刷新率耦合、恢复后跳变**。原实现每帧固定 +24°，`SPIN_DURATION_MS` 声明未用 | 按 `SystemClock.uptimeMillis()` 换算角度；恢复时按已转角度回推起点；换算抽为纯函数 `angleForElapsed` |
| **旧链条无法作废**，可能重复投递。`View.removeCallbacks` 无法撤销 `postOnAnimation` 已交给 Choreographer 的回调 | 每次重新启动新建 ticker 实例并登记为当前链条；旧链条醒来发现已非当前链条即退出 |

另修复 `WeakHashMap` 的 value→key 强引用（值为 span、span 强引用作为键的 View，
弱键永不回收）：`host` 改为弱引用，并改用私有锁 + 显式同步以支持复合操作。

动画只在 `TRANSLATING` 状态运行，**不存在无意义的持续刷新**。

诊断日志：翻译中启动旋转时输出 `icon spin start <id>`。

### 1.4 点击（切换 / 重试）

点击 span 由 `IconClickSpanFactory` 用 `java.lang.reflect.Proxy` 实现宿主的
`NestedClickableSpan` 接口（编译期不依赖 Discord 类）。`onClick` 转发到
`HookBridge.onIconClicked(messageId)`：

```java
switch (状态) {
    case TRANSLATED: toggleShowingOriginal(); break;   // 切换原文/译文
    case FAILED:     retry();                 break;   // 重试
    default:         break;                            // 翻译中忽略，避免重复触发
}
```

点击**每次都重新读取当前状态**，因此列表复用不会造成状态串消息。

### 1.5 注入范围（只处理正常消息正文）

两道条件，任一不满足即不注入：

1. **必须有渲染宿主**：`MessageContentView.setMessageContent` 路径与 Embed 绑定
   （`EmbedHooks`，富 Embed 的描述/标题/首字段与帖子预览的 footer）都会在调用链内
   设置 `CURRENT_HOST`。回复引用（`MessageViewReplyPreview`）直接调 `toSpannable`，
   不设置宿主 → 天然被排除（需求：回复引用默认不注入）。Embed 每张卡片只挂
   一个图标（`HookBridge.markEmbedIconKey` 标记主段）。
2. **必须有可翻译文本**：`TextExtractor.Result.textNodeCount > 0`。
   纯图片 / 纯表情 / 纯提及消息为 0 → 不注入。

另外 `IDLE` 状态不注入，因此非英文消息与未触发翻译的消息不会出现图标。

### 1.6 点击区域尺寸

字符命中是水平的（`Layout.getOffsetForHorizontal`），因此 **span 宽度即点击宽度**。
图标保持 18dp 不变，把补足到 32dp 所需的宽度全部放在**尾部留白**，
从而既满足「视觉 18–20dp、间距 3–5dp」，又满足「点击区 32–40dp」，
且不改变图标与文字的视觉间距。

| 密度 | 前间距 | 图标 | 尾部留白 | span 总宽（=点击宽度） |
|---|---|---|---|---|
| 1.0x | 3px | 18px | 11px | 32px |
| 2.0x | 6px | 36px | 22px | 64px |
| 3.0x | 9px | 54px | 33px | 96px |

---

## 二、改动文件

### 新增

| 文件 | 作用 |
|---|---|
| `hooks/InlineIconSpan.java` | 行内图标 `ReplacementSpan`（`ImageSpan` 子类）；自绘、三态配色、旋转动画与停止 |
| `hooks/InlineIconInjector.java` | 注入入口：宿主传递、范围限制、span 挂载 |
| `hooks/IconClickSpanFactory.java` | 动态代理实现宿主 `NestedClickableSpan`；点击转发 |
| `hooks/InlineIconGeometry.java` | 与框架无关的纯几何计算（尺寸 / 间距 / 垂直居中 / 点击宽度） |
| `test/.../InlineIconGeometryTest.java` | 上述计算与时间→角度的离线断言（含点击区 32–40dp、整圈回绕、异常输入） |

### 修改

| 文件 | 改动 |
|---|---|
| `hooks/MessageHooks.java` | `setMessageContent` 传入/清理宿主；`toSpannable` 调用注入器；**删除** `installRowHook`、`installRowTouchHook`、`bindPost`、`onRowBound`、`findRowContainer` 及相关常量；启动摘要 6→4 |
| `hooks/HookBridge.java` | 新增 `onIconClicked(messageId)`；所有 `IconRegistry.refresh` 改为 `RerenderRegistry.rerender` |
| `ModuleEntry.java` | 删除 `IconRegistry.clear()`、`TranslationIconView.clearIconDrawables()`、`loadIconDrawable()`、`currentApplication()`；新增 `InlineIconSpan.resetAll()` |

### 删除

| 文件 | 删除理由 |
|---|---|
| `hooks/IconRegistry.java` | 仅服务于右下角图标（messageId → 图标视图登记） |
| `hooks/TranslationIconView.java` | 仅服务于右下角图标（`ImageView` 停靠右下角 + 命中测试） |

**删除前的调用方检查**（见「三、验证」）：删除后全仓库对
`IconRegistry`、`TranslationIconView`、`hitTestIcon` 的引用为 0。

---

## 三、验证

### 3.1 静态检查（本地，未构建）

- 全量 Java 文件：无未使用 import、无重复 `@Test`、括号平衡；
- 全仓库搜索 `IconRegistry` / `TranslationIconView` / `hitTestIcon`：**0 处残留**；
- 纯几何计算与单测期望值逐项手工核对一致（尺寸 / 间距 / 点击宽度）。

### 3.2 构建与单测（GitHub Actions）

Run [39115884475](https://github.com/wxmyyds/DiscordAutoTranslate/actions/runs/39115884475)（commit `53384d1`）：

| 项目 | 结果 |
|---|---|
| `assembleDebug` | ✅ |
| `testDebugUnitTest` | ✅ 118 个用例，0 失败 |
| `InlineIconGeometryTest` | ✅ 15/15（含点击区 32–40dp 与旋转角度换算） |
| APK 产物（`META-INF/xposed/*` + 3 drawable） | ✅ 上传成功 |

> 失败记录：Run 39115778265 因 `InlineIconSpan.resetAll()` 非 public（`ModuleEntry` 在不同包）
> 编译失败，已在 `53384d1` 修复。修复卡死的提交为 `ac76cc7`
> （Run [39117238632](https://github.com/wxmyyds/DiscordAutoTranslate/actions/runs/39117238632)）。

> 仓库规定禁止本地构建，以上通过 GitHub Actions 执行。

### 3.3 真机测试（待执行）

需覆盖：成功切换、失败重试、翻译中动画、长消息换行、滚动复用、编辑消息、
纯表情消息、回复引用，以及链接/提及/表情点击、长按、滚动。

**测试步骤**：

1. 从 Run 39115884475 下载 `DiscordAutoTranslate-Debug-*` APK；
2. 安装并启用（作用域 `com.discord`），重启 Discord；
3. 确认 logcat 出现 `hook pipeline armed: 4/4 (bind, expandedRender, toSpannable, configureReply)`；
4. 逐项测试下列行为：

| # | 测试项 | 预期 |
|---|---|---|
| 1 | 成功切换 | 译文出现；点图标切回原文，再点切回译文 |
| 2 | 失败重试 | 配置错误 API → 图标变 `error_24`；修正后点图标重试成功 |
| 3 | 翻译中动画 | 图标原地旋转，无状态文字；**连续翻译多条也不卡死，滚动后不卡死** |
| 4 | 长消息换行 | 图标随文字换行，不遮挡、不截断正文 |
| 5 | 滚动复用 | 滚动后图标仍属于对应消息，不串位、无残留 |
| 6 | 编辑消息 | 编辑后图标跟新文本末尾，旧译文不残留 |
| 7 | 纯表情消息 | 不出现图标 |
| 8 | 回复引用 | 引用预览内不出现图标；被引用消息自身行有图标 |
| 9 | 链接/提及/表情点击 | 均正常，不被图标干扰 |
| 10 | 长按 | 仍弹出消息菜单 |
| 11 | 滚动 | 流畅，无卡顿或误触发 |

排障日志（`adb logcat -s DiscordAutoTranslate:I`）：

- `inline icon injected msg=... status=... widthPx=... clickable=true`（注入成功）
- `translate start <id>` / `translate done <id>`（翻译流程）
- `toggle <id> showingOriginal=...`（切换）

---

## 四、仍存在的限制

1. **图标对所有非 IDLE 且含文本的消息出现**，包括机器人嵌入文本等；如需更严格的
   「仅人类可翻译消息」判断，需要额外信息（当前无可靠依据，故未加）。
2. **回复引用内不注入图标**（按要求），但引用内的译文仍会更新（走重渲染登记）。
3. **占位字符 `\u00A0` 占 1 字符宽度**：长单词结尾时可能被挤到下一行，
   这是「自然换行」的正常代价。
4. **旋转动画按每帧 invalidate 驱动**：只在翻译中状态运行，但若同时有大量消息
   处于翻译中，会有多份动画。当前并发上限（默认 3）限制了同时翻译中的数量。
   转速已与刷新率解耦（按时间换算）。
5. **热重载后旧动画**：`resetAll()` 会停止所有在途动画；新代次重新注入时重建。
6. **`progress_activity_24` 图标本身非对称**，旋转时视觉中心略有偏移；如需像素级
   校正需另做处理（本轮未做）。
7. 未验证项：极小屏幕 / 超大字体下的换行与点击命中；RTL 语言布局。
