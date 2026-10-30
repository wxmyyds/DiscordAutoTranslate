# 翻译引擎与配置

模块运行在 Discord 进程内，翻译引擎是纯逻辑层（不依赖 Android 框架），
单元测试在 JVM 上执行（`testDebugUnitTest`）。

## 网络层

- 用 JDK 自带 `HttpURLConnection`，**零第三方依赖**：Discord 已打包 okhttp3，
  模块再打包一份会类冲突/版本错配。
- JSON 用 `org.json`（Android 系统内置）；JVM 单测通过 `testImplementation`
  提供 `org.json:json`。
- 支持非流式（一次性 JSON）与流式（SSE，`Http.postJson(..., sse=true)`）两种
  响应：由设置中的“使用流式请求”开关选择，Compatible 与 Anthropic Messages
  支持切换，Responses 固定非流式。响应解析严格服从所选模式；格式不符时失败，不改走另一解析器。

## 供应商协议

配置项 `provider_protocol`，三种取值（`Config.PROTOCOL_*`）：

| 协议 | 请求端点/体 | 译文提取 |
|---|---|---|
| `openai-compatible`（默认） | `POST …/v1/chat/completions`：`model` + `messages`(system+user) + `temperature:0` + `stream` 按设置开关 | 流式取 SSE delta；非流式取 `choices.0.message.content` |
| `openai-responses` | `POST …/v1/responses`：`model` + `instructions`（系统提示词）+ `input` + `temperature:0` | 响应 `output` 数组按类型扫描 `message` → `output_text`（推理模型把 `reasoning` 排在 `message` 前，不能固定取 `output.0`） |
| `anthropic-messages` | `POST …/v1/messages`，`max_tokens` 必填（8192） | `content` 数组中 text 块的拼接 |

- Compatible 与 OpenAI-Responses 均不携带 `frequency_penalty`。
- API URL 填基础地址（例如 `https://api.openai.com/v1`），请求时追加当前协议端点。
- 协议与流式开关都参与配置版本计算：切换协议或流式模式后旧缓存不命中，按新配置重新请求。

## 调度与重试（core/Scheduler + TranslationException）

- 线程池异步执行，`AdjustableSemaphore` 限制并发（`max_concurrency`，可运行时调整）。
- 去重：`Scheduler.inFlight`（按 messageId + contentHash + generation）+ `TranslationCache`。
- 错误分类：`CONFIG / NETWORK / TIMEOUT / HTTP / RATE_LIMIT / PARSE / EMPTY / CANCELLED`。
- 自动重试：无；一次失败后由用户手动点击图标重新发起请求。
- 过期请求丢弃：提交、获得并发许可后及回写前校验任务是否仍有效。

## 缓存

`TranslationCache`，LRU 上限 512；键 = `SHA-256(正文 + 配置版本)`。
配置版本纳入 `apiUrl / model / targetLang / protocol / streaming / systemPrompt /
凭据 SHA-256 指纹`：任何影响结果的配置变化都会使旧条目不再命中。

## 配置项（core/Config；缺省键使用默认值，显式非法值使快照无效）

| 键 | 默认值 | 说明 |
|---|---|---|
| `auto_translate_enabled` | true | 总开关 |
| `api_url` | `https://api.openai.com/v1` | API 基础 URL |
| `api_key` | 空 | 仅存本机 remote preferences，不回显 |
| `api_model` | `gpt-4o-mini` | 模型名 |
| `target_lang` | `简体中文` | 目标语言 |
| `extra_headers` | 空 | 额外请求头（每行一个 `Key: Value`） |
| `provider_protocol` | `openai-compatible` | 见上表 |
| `streaming_enabled` | true | Compatible 与 Anthropic Messages 使用流式请求；Responses 固定非流式 |
| `system_prompt` | 英文规则式提示词 | 只翻译、不接话、不拒译侮辱性内容、无对照示例 |
| `timeout_ms` / `max_concurrency` | 正整数 | 超时与并发上限，≤0 视为未配置 |

- `apiKey` 与 `extraHeaders` 默认值为空串；空密钥与空请求头是有效配置。
- 缺省配置键使用默认值；已保存的类型或必填值无效会使配置整体不可用。
- 保存用 `commit()` 后台执行，按返回值报告成功/失败；提示语不声称目标进程
  已即时生效（目标进程侧未注册 remote preferences 变更监听，运行中的 Discord
  可能需重启才生效）。

## 隐私与日志

- 消息正文与译文**始终不写入日志**；详细日志开关只影响诊断信息详细程度。
- `XLog.redact`：密钥只输出 `<configured>` / `<empty>`；`safeUrl` 只记录
  scheme + host（含端口）；非法请求头会以配置错误拒绝，并只报告行号。
- 配置版本中凭据只以 SHA-256 指纹参与，不含原始值。