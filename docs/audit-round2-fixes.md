# 全局审查（第二轮）修复记录

对应分支 `fix/global-audit-round2-2026-10-17`。本轮只修复**已由源码证实**的问题，
设备/框架相关的推测项保持原样并单独列出。

## 1. 消息正确性

| 问题 | 修复 |
|---|---|
| `StructurableText` 是 Kotlin data class，`equals()` 按内容比较；用 `ConcurrentHashMap<Object,String>` 做反查时，两条正文相同的消息会互相覆盖，译文可能套到错误消息 | 新增 `hooks/IdentityMessageMap`，按**引用身份**（弱引用键 + identity hash + `==` 比较）反查；`HookBridge.contentIdentityToMessageId` 改用它 |
| 回复引用只会发起翻译，译文完成后没有登记的重渲染目标（正文的登记点在展开式 `setMessageContent`，回复引用不经过它） | `RerenderRegistry` 改为保存「正文视图 + 调用目标 + 方法 + 原始实参」；`configureReply` Hook 增加后置登记，用同一组实参重跑即可刷新 |
| 同一 messageId 在途时，编辑后的新内容被去重逻辑静默丢弃，且不会补发 | `Scheduler` 任务键改为 `messageId + contentHash + generation`；旧结果仍由 `Acceptance` 丢弃，新内容可正常排队 |
| 等待并发许可期间内容已失效，却仍会发出请求 | `Scheduler` 在 `permits.acquire()` **之后**才做 `shutdown`/中断/`isStillValid` 校验 |
| 行内代码与代码块原文会发给模型，模型常把代码复述进译文，而节点树里代码本来就保留，导致重复显示 | `TextExtractor` 不再把代码内容放进请求正文，只计为不可翻译结构；补充测试断言正文不含代码 |
| `BulletListContentNode.items` 实际是 `List<List<ContentNode>>`，原实现按单层节点处理，列表正文既不提取也不替换 | `TextExtractor` / `ContentNodes` 支持嵌套 `List` 递归；`ContentNodesTest` 改用真实两层结构并覆盖嵌套列表、样式、提及、代码保留 |

## 2. 热重载与安装安全

| 问题 | 修复 |
|---|---|
| 热重载不会重放 `onPackageReady`，而 `targetClassLoader` 只在其中赋值，新代次可能拿不到宿主 ClassLoader 而静默停用 | `onHotReloaded` 从 `getOldHookHandles()` 的目标 `Executable` 反推宿主 ClassLoader（`ModuleEntry.classLoaderFromOldHooks`） |
| `ModuleResources.reset()` 清空框架引用后没有重新 bind，热重载后图标资源必然解析失败 | 在 reset 之后重新 `ModuleResources.attach(this)` |
| Hook 部分安装成功时 `installed` 仍被置为 `true`，之后不会再补装 | 低于 `MessageHooks.MIN_REQUIRED_HOOKS`（4）时回滚已装 Hook 并抛错；`install()` 内部任一步抛出也回滚 |
| 旧的 `TranslationCache` 单例未随热重载清理 | `ModuleEntry` 在 `onHotReloaded`/`release` 中调用 `TranslationCache.reset()` |
| 关闭线程池后 `MainThread.post` 在 Handler 为空时会**在工作线程直接执行 UI 回调** | `MainThread.post` 在无 Handler 时直接丢弃；单测显式安装直投 dispatcher，不再依赖生产兜底 |
| `HttpURLConnection` 阻塞读不会因线程中断立即返回，旧代次请求会继续跑到超时 | `Http` 记录每线程在途连接，`Scheduler.shutdown()` 经 `Engine.cancel(Thread)` → `Http.cancelRequestOn` 关闭连接 |

## 3. 隐私与日志

| 问题 | 修复 |
|---|---|
| 配置日志输出完整 API URL（可能含查询参数或内嵌凭证） | 新增 `XLog.safeUrl`，只记录 scheme + host（含端口） |
| `XLog.redact` 输出密钥前 3 个字符与长度 | 改为只输出 `<configured>` / `<empty>` |
| 非法请求头行被完整写入 debug 日志（可能含未正确分隔的 Authorization 值） | 只记录行号 |
| 密钥/额外请求头变更不会改变配置版本，旧缓存与旧在途结果仍被复用 | `Config.Snapshot.version()` 追加凭据的 SHA-256 指纹（不含原始值） |

## 4. 设置界面

- 用进程级 `XposedServiceConnectionHolder` 承担唯一的 service 监听（`XposedServiceHelper` 只有静态强引用 listener、无注销接口），Activity 以弱引用绑定/解绑；
- 所有 service 回调切主线程，旧 service 的 death 不再清掉新 service 的状态；
- 网络测试改用本页受控的单任务 `ExecutorService` + `FutureTask`，请求中禁用按钮，`onDestroy` 取消任务并用 generation 丢弃旧结果；
- 配置检查为纯离线操作，不再被测试锁阻塞；
- 保存改用 `commit()` 并在后台执行，按返回值报告成功/失败；提示语不再声称目标进程已即时生效。

## 5. 冗余清理

删除确认无调用者的代码：`ContentNodes.PKG` 与 `CONTAINER_SIMPLE`、`copyWith` 未用参数、
`ModuleEntry.isTargetProcess()` 及其字段、`RerenderRegistry.viewOf`、`HookBridge.originalRenderArgs`
（重渲染实参改由 `RerenderRegistry` 持有）。

## 6. 未处理项（按要求或需设备验证）

1. **首次未配置时仍会向默认 API 发送消息正文**：按用户明确决定保留，不修改。
2. 热重载时旧 Hook 句柄是否总能提供可用的目标方法（依赖框架实现）：已按 API 102 文档实现恢复路径，实际行为需真机验证。
3. 共享 Drawable 的颜色过滤是否会串色、`HttpURLConnection` 在具体 ROM 上的中断行为、remote preferences 的跨进程通知：均需真机确认。
4. 目标进程侧尚未注册 remote preferences 变更监听；设置页保存后运行中的 Discord 可能仍需重启才生效（提示语已如实说明）。

## 7. 验证方式

- 单元测试（CI `testDebugUnitTest`）：`SchedulerTest` 新增同 ID 新内容、等待许可期间失效、关闭后不回调、提交被拒回报失败；`IdentityMessageMapTest` 锁定相同内容不同对象不互相覆盖；`XLogTest` 锁定密钥/URL 脱敏；`ConfigTest` 新增凭据参与配置版本；`ContentNodesTest` 覆盖真实两层列表结构与代码不外发。
- 设备侧仍需人工复测：图标状态、点击切换、滚动持久化、回复引用内译文显示、热重载后 Hook 是否重建。
