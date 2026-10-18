# 全局审查第三轮修复

## 已修复

- `MessageHooks` 的 `toSpannable` 回调分离准备、宿主渲染和图标注入步骤。宿主方法只调用一次；替换准备失败时使用原内容，图标注入失败时返回已渲染结果。
- `HookBridge.retry` 在清除失败状态和重渲染之前确认原始消息结构仍可用，避免缓存淘汰后把失败态清掉却不发起重试。
- `TranslationException` 保存 429 的 `Retry-After` 秒数。`Scheduler` 采用服务端建议值与指数退避两者较大值，最长等待限制为 60 秒；没有有效建议值时沿用已有退避策略。
- `Config.Snapshot` 对字符串、布尔和整数偏好按字段处理读取类型异常，单个坏字段只回落自身默认值。
- `MemoryPrefs` 对类型不匹配抛出 `ClassCastException`，使 JVM 配置测试模拟 Android 偏好的类型读取行为。
- `ModuleResources` 仅在资源成功解析后缓存完成状态；首次失败后后续调用仍可重试。

## 冗余清理与边界调整

- 删除仓内无调用方的 `TextExtractor.textOf()` 和 `StructurableTextFactory.isRenderableType()`，以及只覆盖后者的测试。
- 移除未使用的 `ACCESS_NETWORK_STATE` 权限，保留 HTTP 请求所需的 `INTERNET`。
- `MessageHooks` 的渲染回调按阶段隔离错误处理，避免插件逻辑异常影响宿主渲染调用次数；没有引入新的模块或依赖。

## 验证

- 新增配置类型错误隔离测试和 429 退避边界测试。
- 构建与自动化测试必须由 GitHub Actions 执行；本地未运行构建或测试。
- Hook 运行时行为和模块资源恢复仍需在 CI 及目标设备上验证。

## 审查限制

本次审查针对当前仓库源码及仓内调用点，不证明仓库外没有 `TextExtractor.textOf()` 或 `StructurableTextFactory.isRenderableType()` 的反射式消费者。Discord 宿主的 Hook 调用时序仍需设备验证。
