# 维护经验与边界

本页集中记录开发过程中确认过的教训、易踩的坑与当前已知边界，供后续修改参考。

## 版本化 Hook 配置

- Discord Hook 资料位于 `app/src/main/assets/hook-config/<versionCode>.json`；运行时从
  Discord `PackageInfo.versionCode` 精确读取对应文件，不存在匹配文件时不安装消息 Hook。
- `hooks.*.target` 描述类、方法、静态/实例类型、参数数量和可选参数签名；绑定参数索引、
  混淆类型语义名、访问器与字段别名也应放在该版本 JSON 中。
- `requiredHooks` 只列出该版本已通过 smali 核实且启动必须成功的 Hook。未核实的回复、Embed、
  帖子预览或 Poll 入口保持 `enabled: false`，不能用其他版本的选择器作为默认值。
- 静态渲染方法将 View 和正文都作为显式参数；对应 profile 要设置 `viewArgument` 和
  `contentArgument`，重渲染登记会以静态调用方式执行它。
- 新增或修改 profile 后，先做 JSON/签名核对；编译与单元测试通过 GitHub Actions 运行，
  不在本地运行 Gradle 或 `javac`。

## 已确认的架构教训（改代码前先读）

1. **文本转换目标按 profile 变化**：`340013` 使用 `TextUtilsKt.toSpannable`，
   `352017` 使用已核对的 `kl.b.B0` 静态方法。绑定和转换目标由 profile 指定。
2. **扩展 Hook 按版本配置**：回复、Embed、帖子预览与 Poll 必须先核实目标。`352017` 的回复正文由 `af.i` 直接调用 `kl.b.B0`（不是 `MessageContentView.c`），必须复用引用消息 ID 的状态，不另加并行回复渲染链；原生首行避让依赖 `LeadingMarginSpan.Standard(前导区实测宽度, 0)`，不能用普通 `setText` 覆盖渲染结果；引用模型正文可能与完整正文不同，回复必须以消息 ID/编辑版本选取规范正文及已有译文，由同一个转换器生成预览，不按投影文本哈希拒绝规范译文。`583ffbd` 设备日志确认引用模型压平了规范正文换行，故译文必须在规范节点回填和原生转换之后、首行避让添加之前，对同一 Drawee builder 等长压平换行；不能先压平再回填或手动删 margin span。原生 `singleLine` 渲染参数不会清除文本内换行/显式 NewLine；Embed、帖子预览与 Poll 仍须分别核实。
3. **替换对象类型**：必须是 `AnnotatedStructurableText` 或
   `PrimitiveStructurableText` 之一，否则渲染器抛 `RuntimeException`；
   Discord 自带的 simpleast Markdown 解析器在 APK 内无调用方，不能复用。
4. **节点重建按 profile 选择**：`340013` 使用 Kotlin data class 的
   `componentN()/copy(...)`；`352017` 混淆节点仅按 `constructorFallbackTypes`
   白名单及已核实字段、构造器顺序重建。未知类型不通过猜字段或方法回退。
5. **正文存储绝不能依赖 View**：RecyclerView 复用视图，译文/状态一律以
   messageId 为键存 `StateStore`；同正文不同对象需身份映射（`IdentityMessageMap`）。
6. **旧默认值会静默失效**：配置读取是「已存值优先于默认值」。曾出现用户在
   设置页填了新提示词、但手机里存的是旧版默认模板（硬编码提示词、不含
   `{{system}}`），新配置项根本没被发送。教训：新增配置项时应考虑已安装用户
   的旧值，保存时可检查关键占位符并提示。
7. **热重载后不重放包生命周期**：`onHotReloaded` 必须自行重建状态、重装 Hook、
   重新 attach 模块资源、清理缓存；目标方法从旧 Hook 句柄反推。

## 排查与验证要点

- `android.util.Log` 使用 `DiscordAutoTranslate` 标签输出启动 Hook 错误、未知版本及配置安装失败；当前代码不输出旧版 `XLog` 或 `hook pipeline armed` 诊断行。
- 用户导出的 LSPosed 日志可能**不含 Discord 进程的 logcat**；若要确认上述启动错误，需检查 Discord 进程日志。
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
- **富文本无法完整重建时保留原文并标记翻译失败**：无效译文不写入缓存。
- **富文本映射残余风险**：仅验证源结构和译文的标记、片段数量及固定边界；
  若模型在数量不变的情况下改写文本边界，纯文本无法证明语义位置，规则会拒绝无法一一对应的结构。
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