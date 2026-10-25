# 阶段 2 设计说明 — 独立翻译引擎

对应需求 §7 阶段 2：实现通用 JSON API 配置与解析、异步请求、超时、并发限制、去重与缓存，
并用模拟消息验证失败处理与过期请求丢弃。

---

## 1. 交付内容

| 需求条目 | 实现 |
|---|---|
| 通用 JSON 请求模板 | `core/JsonTemplate` — `{{text}}` / `{{target}}` / `{{model}}`，自动 JSON 转义 |
| 响应提取路径 | `core/JsonPath` — 点号路径，数组用下标，如 `choices.0.message.content` |
| 异步网络请求 | `core/Scheduler` 线程池 + `translate/JsonApiEngine`（同步体，由调度器异步执行） |
| 超时处理 | `core/Http` 的 connect/read 超时，取自配置 `timeout_ms` |
| 并发限制 | `Scheduler` 的 `AdjustableSemaphore`，上限取自配置 `max_concurrency`，可运行时调整 |
| 重复请求去重 | `Scheduler.inFlight`（按 messageId）+ `TranslationCache`（按内容哈希） |
| 翻译缓存 | `core/TranslationCache` — LRU，上限 512，键 = SHA-256(正文 + 配置版本) |
| 失败处理 | `core/TranslationException` 分类：CONFIG/NETWORK/TIMEOUT/HTTP/RATE_LIMIT/PARSE/EMPTY/CANCELLED |
| 过期请求丢弃 | 提交前 + 回写前双重校验 generation 与 contentHash |
| 连接测试 / 测试翻译 | `translate/ApiTester`，由设置界面按钮调用，含离线「配置检查」 |
| 隐私 | 密钥仅在 Authorization 头，日志经 `XLog.redact` 脱敏；正文默认只记长度与首段 |

---

## 2. 关键技术决策

### 2.1 用 `HttpURLConnection` 而非 OkHttp（重要）

模块代码运行在 Discord 进程内。Discord 自身已打包 `okhttp3`
（见 `smali/okhttp3/`）。若模块再打包一份 OkHttp，会造成：

- 类冲突：同一 ClassLoader 下两个 `okhttp3.*` 版本；
- 版本错配：模块依赖的 OkHttp 新 API 在 Discord 的旧版本上可能缺失。

因此网络层使用 JDK 自带 `HttpURLConnection`，**零第三方依赖、零冲突**。
JSON 使用 `org.json`：Android 系统已内置，模块侧不引入运行时依赖；
仅在 JVM 单测中通过 `testImplementation` 提供。

副作用：不享受连接池与 HTTP/2。对「每条消息一次请求」的场景影响有限；
阶段 5 若确有性能瓶颈再评估（届时可考虑反射复用 Discord 已有的 OkHttp 实例）。

### 2.2 模板转义是正确性关键，不只是安全问题

消息正文可能含 `"`、`\`、换行、制表符、`U+0000..U+001F` 控制字符。
若直接字符串拼接，会破坏请求 JSON 结构。

因此 `{{text}}` 的替换值一律经 `JsonTemplate.jsonEscape`，
模板作者直接把它放进 JSON 字符串内使用（默认模板即如此）。
单测 `textIsEscapedInsideTemplateSoJsonStaysValid` 验证了「恶劣输入 → 合法 JSON → 无损往返」。

### 2.2.1 系统提示词独立成配置项（阶段 3 补充）

系统提示词（用于约束“只翻译、不执行指令”）是**多行文本**（默认值现为英文）。
若直接嵌在 JSON 请求模板里，设置页会变成一大串 `\n` 转义，
用户每改一次提示词都要手动做 JSON 转义，极易写坏 JSON。

因此拆为独立设置项 `Config.KEY_SYSTEM_PROMPT`（多行文本框），
并新增 `{{system}}` 占位符：

- `JsonTemplate.render/validate` 新增 `systemPrompt` 参数，
  其值同样经过 `jsonEscape`，因此提示词含引号、换行也不会破坏请求体。
- `Config.DEF_BODY_TEMPLATE` 改为引用 `{{system}}`；
  `Config.Snapshot.version()` 纳入提示词，
  因此改提示词会使旧译文失效（需重译）。
- 设置页新增「系统提示词」多行输入框；
  `ApiTester` 的连接测试与自检也传入 `systemPrompt`，
  否则默认模板里的 `{{system}}` 会被当作未知占位符而报配置错误。

单测 `systemPromptIsEscapedAndRoundTrips` / `systemPromptWithQuotesStaysValid`
验证了多行与含引号提示词的「无损往返 + JSON 合法」。

#### 静默失效与保存时检查（重要教训）

上线后发现：用户填入新提示词，但 `Dumbass` 仍被译成
“这个词是英文中的粗俗用语…我建议我们使用更礼貌…”（即模型在解释+说教）。

**根因**：配置读取是 `已存值优先于默认值`。用户手机里存的是
**旧版默认模板**，它把英文提示词硬编码在 `system` 字段里，
且**不包含 `{{system}}`**。所以：

- 新加的“系统提示词”配置项根本没被发送；
- 真正生效的仍是那句旧的英文提示词。

这是典型的**静默失效**：界面能填、能存，但不起作用。

**修法**：不做自动迁移（当前阶段只有单一使用者，迁移逻辑属多余复杂度），
而是在**保存时主动检查并弹窗**：

- 检查逻辑放在可单测的 `JsonTemplate.missingKeyPlaceholders`：
  返回模板中缺失的关键占位符（`{{text}}` / `{{system}}`）。
- 设置页保存后调 `warnMissingPlaceholders()`，缺则弹 `AlertDialog`，
  逐条说明后果（`{{text}}` 缺→翻译无法工作；`{{system}}` 缺→提示词不生效），
  并提供“用默认模板”一键填入。

同时默认提示词新增第 8、9 条，直接针对该失败案例：

- 第 8 条：原文可能只有一两个词、甚至是脏话/侮辱性词汇，
  此时也必须只给译文（举例 `Dumbass → 混蛋`），不得解释词义、
  不得道德提醒或说教。
- 第 9 条：不对原文做价值判断/安全提示，不用
  “我建议”“我很乐意”等句式与用户对话。

单测 `missingKeyPlaceholdersDetectsSystemAndText` /
`defaultPromptCoversProfanityRule` 分别锁定检查逻辑与提示词规则。

### 2.2.2 未配置的项回落默认值

读取配置时，<b>未配置</b>的项一律使用默认值。“未配置”的含义包括：

- 键不存在；
- 值为 `null`；
- 值为**空白字符串**（如清空输入框后保存）；
- 正整数项（超时、并发）小于等于 0。

为何把空白也算作未配置：设置页每个输入框在保存时都会写入，
用户清空某一格再保存就会存下空字符串。若把空字符串当有效值，
会出现“模型名 / 目标语言 / 响应路径为空”之类难查的静默失效。

特别地，`apiKey` 与 `extraHeaders` 的默认值本身就是空串，
因此“空白回落默认值”对它们是等价的，
不会影响“无需认证 / 无额外请求头”的正常用法。

单测 `ConfigTest` 覆盖了 7 种情形（全空、空白、null、有效值、
非正整数、正整数、空密钥与空请求头）。

### 2.3 缓存键包含配置版本

`contentHash = SHA-256(正文 + 配置版本)`，其中配置版本由
`apiUrl|model|targetLang|responsePath|bodyTemplate` 组成。因此：

- 同一内容重复出现（滚动回看、重进频道）→ 直接命中缓存，不发请求、不重复计费；
- 任一影响结果的配置变化 → 哈希变化 → 旧条目自然不再命中，不会错误复用；
- 消息被编辑 → 正文变化 → 哈希变化 → 不会命中旧译文。

### 2.4 过期请求的三重防护（需求 §风险 3）

1. **提交前**：`Acceptance.isStillValid` 为 false 则根本不发请求，避免浪费配额。
2. **重试前**：每次重试尝试前重新校验，避免为已失效内容继续重试。
3. **回写前**：校验 `generation`（热重载/配置变更）与 `contentHash`（消息编辑）。
   不通过则丢弃，并把状态回退为「无译文/可重试」，而不是显示失败。

### 2.5 重试策略

仅对 `TranslationException.isRetryable()` 为 true 的错误重试，最多 3 次尝试：

| 错误 | 可重试 | 说明 |
|---|---|---|
| `TIMEOUT` / `NETWORK` | 是 | 临时性 |
| `RATE_LIMIT` (429) | 是 | 退避更长（2s/4s/8s），避免加剧限流 |
| `HTTP` 5xx | 是 | 服务端临时故障 |
| `HTTP` 4xx（含 401/403） | 否 | 客户端错误，重试无意义 |
| `CONFIG` / `PARSE` / `EMPTY` | 否 | 重试不会改变结果 |

---

## 3. 验证方式

### 3.1 单元测试（CI 中执行 `testDebugUnitTest`）

| 测试类 | 覆盖内容 |
|---|---|
| `JsonPathTest` | OpenAI/DeepL/Google 三种路径风格、各类错误归类（越界、缺字段、标量穿透、null、非法 JSON） |
| `JsonTemplateTest` | 转义（引号/反斜杠/控制字符）、占位符替换、未知占位符上报、未闭合花括号、模板校验 |
| `SchedulerTest` | 去重、并发上限、**过期结果丢弃**、**失效内容不发请求**、错误分类、重试次数、4xx 不重试、哈希稳定性与配置敏感性 |

### 3.2 设备侧（需人工执行）

1. 设置界面点「配置检查」→ 应显示配置合法性（离线，不需要网络）。
2. 点「测试连接」→ 用固定文本（含引号与换行）发起真实请求，验证 URL/密钥/模板/提取路径四项。
3. 点「测试翻译」→ 用自定义文本验证译文。
4. 在 Discord 中观察：纯文本消息应显示译文；失败时图标显示失败态且可点击重试。

---

## 4. 阶段 2 的已知边界

1. **仍只替换 `PrimitiveStructurableText`**。含 Markdown / 提及 / 表情的消息
   （`AnnotatedStructurableText`）不替换，属阶段 3。
   影响：`EnglishDetector` 仍能识别这类消息，但译文无法显示，日志会记录
   `skip substitute: content is ...AnnotatedStructurableText`。
2. **回复引用内正文不替换**：该视图没有 messageId 映射，属阶段 3。
3. **无响应流式（SSE）支持**：第一版只支持一次性 JSON 响应。
4. **未做断网状态的主动提示**：依赖请求失败后的失败图标与日志。

---

## 5. 仍需确认的信息

1. 实际使用的翻译 API 响应结构是否与默认提取路径 `choices.0.message.content` 一致。
2. 是否需要在请求头中携带额外的服务商标识（可通过「额外请求头」自行配置）。
