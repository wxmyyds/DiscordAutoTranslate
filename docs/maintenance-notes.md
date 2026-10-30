# 维护经验与边界

本页集中记录开发过程中确认过的教训、易踩的坑与当前已知边界，供后续修改参考。

## 已确认的架构教训（改代码前先读）

1. **渲染漏斗是 `TextUtilsKt.toSpannable`，不是 `setMessageContent`**。
   阶段 1 曾断言「6 参 `setMessageContent` 是所有正文渲染的唯一漏斗」，
   实测既无译文也无图标。真实链路是
   `MessageContentViewHolder.bind → setMessageContent-hYrISQc$default →
   setMessageContent-hYrISQc(34 参) → TextUtilsKt.toSpannable`；
   回复引用直接走 `toSpannable`，不经 setMessageContent。
2. **替换对象类型**：必须是 `AnnotatedStructurableText` 或
   `PrimitiveStructurableText` 之一，否则渲染器抛 `RuntimeException`；
   Discord 自带的 simpleast Markdown 解析器在 APK 内无调用方，不能复用。
3. **节点重建用 Kotlin data class 通用能力**（`componentN`/`copy`），
   `copyWith` 取第一个 `List` 参数作为内容字段——基于 340.13 全部节点
   `copy` 签名核对；若未来出现「首个 List 参数不是内容字段」的节点需改按
   字段名匹配。
4. **正文存储绝不能依赖 View**：RecyclerView 复用视图，译文/状态一律以
   messageId 为键存 `StateStore`；同正文不同对象需身份映射（`IdentityMessageMap`）。
5. **旧默认值会静默失效**：配置读取是「已存值优先于默认值」。曾出现用户在
   设置页填了新提示词、但手机里存的是旧版默认模板（硬编码提示词、不含
   `{{system}}`），新配置项根本没被发送。教训：新增配置项时应考虑已安装用户
   的旧值，保存时可检查关键占位符并提示。
6. **热重载后不重放包生命周期**：`onHotReloaded` 必须自行重建状态、重装 Hook、
   重新 attach 模块资源、清理缓存；目标方法从旧 Hook 句柄反推。

## 排查与验证要点

- `XLog` 同时写 `android.util.Log` 与框架日志，`adb logcat -s DiscordAutoTranslate`
  可见；启动输出 `hook pipeline armed: N/MIN_REQUIRED_HOOKS`。
- 详细日志默认开启（`Config.DEF_VERBOSE_LOG = true`）；
  诊断记录（`no-target`/`target(...)`/`invoked`/`row-refresh` 等）在
  `docs/icons-and-layout.md` 判读表。
- 用户导出的 LSPosed 日志可能**不含 Discord 进程的 logcat**：
  判定前先确认能否抓到 Discord 进程日志，而不是继续加诊断输出。
- 单元测试无 Robolectric，**不能测 Android 类**；`ContentNodes` 的测试用
  同名假节点类模拟 Kotlin data class，才能真正执行反射代码路径。
- 构建与自动化测试**只能通过 GitHub Actions**（AGENTS.md 强制），
  本地不运行 Gradle / javac / 打包。

## 当前已知边界（设计内行为，不是 bug）

- **失败后不自动重试**：`FAILED` 状态由用户点击图标重试，避免滚动/动画
  触发请求风暴与 API 限流。
- **模型效果不承诺**：提示词约束（只翻译、不接话、不拒译侮辱性内容）只能降低
  扩写概率，不能保证远端模型一定服从；`Dumbass → 混蛋` 这类失败案例曾由
  默认提示词第 8/9 条缓解，仍不承诺彻底消除。
- **流式响应**：支持 SSE 增量（见 translation-engine.md），其余边界以
  `Protocols` 实现为准。
- **富文本无法重建时保留原文**：此时图标状态仍可能是翻译成功，图标不代表
  节点替换一定成功。
- **列表对齐的残余风险**（非空行 1:1 按行位置对应）：模型同时把一项拆
  成两行、又把相邻两项合并成一行（行数恰好不变）会出现行序错位，这种
  双向结构错误无法仅凭文本可靠识别；列表项内含多个内联片段时非空行数会
  少于片段数而被拒，保留原文（不会错位）；列表项译文本身以 `- `/`• `/`1. `
  开头会被当作回显符号剔除（列表项不渲染自己的符号文本，只能人工判断）。
- **折叠/截断与布局**：宿主折叠截断是否参与布局、Spoiler 隐藏等场景需
  真机样例观察，不修改宿主 smali。
- **跨进程配置生效**：目标进程未注册 remote preferences 变更监听，
  设置页保存后运行中的 Discord 可能需重启才生效（已如实提示）。

## 已归档/移除（勿再引用）

- `Diagnostics` 诊断通道与布局诊断相关的临时代码已删除
  （原 `core/Diagnostics.java`、`logRerender`/`logLayoutFact` 等）；
  当时的交接文档已并入本系列文档，不再单独维护。
- 实验用代码（`InlineIconExperiment` 等）与实验分支已删除；
  正式实现见 `docs/icons-and-layout.md`。
- 阶段 1 桩引擎（`【原型译文】` 前缀）已被真实引擎取代。