# 布局未刷新：诊断通道

## 背景

现象：译文替换成功、文字立即变化，但行高/空白行要滚动后才正确。

已尝试且**在设备上无效**的方案：

1. `View.post` 延后重渲染 + 执行时重新校验绑定；
2. 仿宿主 `onMeasureConsiderFrozen$lambda$0` 的 `forceLayout()+requestLayout()`，并沿父链刷新；
3. 对承载该消息的 `RecyclerView` 调用 `notifyItemChanged(bindingAdapterPosition)`。

三次都失败，说明失败点不在这些刷新手段上。继续按推测改只会重复失败，
因此先建立一条**不依赖 logcat** 的诊断通道取事实。

## 已知事实

- `MessageContentViewHolder.bind` 开头有
  `Intrinsics.areEqual(lastMessageContent, newBindData)` 的早退守卫
  （smali 中位于 `lastMessageContent` 读取与写回之间）。
  重绑时若内容未变会直接 `return-void`，`setMessageContent` 与 `toSpannable`
  都不再执行——这解释了 `notifyItemChanged` 为什么无效：
  它只是让 RecyclerView 用同一份内容重测一次。
- 布局诊断原先与 `Config.KEY_VERBOSE_LOG` 共用开关，而该开关默认 `false`。
  未勾选“详细诊断日志”时诊断一行都不会产生，设置页永远是空的。
- `RerenderRegistry.rerender` 在 `TARGETS.get(messageId) == null` 时直接返回，
  不产生任何记录。**译文完成时的重渲染有没有找到登记项，此前无法观察。**

## 本通道做了什么

- 布局诊断**始终开启**，不再依赖 `verbose_log`。
- `RerenderRegistry.rerender` 记录登记项命中情况：
  - `no-target`：译文已完成但找不到登记项（重渲染从未被安排）；
  - `target(h=…,attached=…)`：找到目标，记录当前高度与附着状态。
- 行刷新执行前后各记一次 `height`：
  - `invoked(before=…)`、`row-refresh(before=…,after=…)`。
- 记录 `rebound`（绑定已被换成别的消息）与 `post-rejected`（`View.post` 失败）。
- 投递环节记录 `dispatch`；`setMessageContent` 抛异常时记录
  `invoke-failed:异常类名`（此前该分支只写 `XLog`，导致“有 target 但没有
  invoked”无从判断）。
- 输出两条通道：`XLog` 与 `Diagnostics` 缓冲区。缓冲区内容在设置页
  「刷新布局诊断」可查看、「复制布局诊断」可复制到剪贴板。

> 曾短暂用屏幕 Toast 展示，但一次渲染会连续产生多条记录，
> 一秒内弹出数个 Toast 完全无法使用，因此已移除，改为设置页查看/复制。

## 判读

| 观察到的记录 | 结论 |
| --- | --- |
| 完全没有 Toast / 无 `no-target` 也无 `target(...)` | 重渲染根本没被触发，问题在登记环节 |
| `no-target` | 译文完成时找不到登记项，需修登记/查找 |
| `target(...)` 后无 `invoked` | 投递环节丢失（`View.post` 失败或校验拒绝） |
| `row-refresh(before=X,after=X)` | 通知执行了但高度未变，刷新手段本身无效 |
| `row-refresh(before=X,after=Y)` 但界面仍旧 | 高度已变、绘制未跟上 |

隐私：记录只含消息 ID、视图类名、高度、布尔状态与结论标签，
不含消息正文与 API 密钥。
