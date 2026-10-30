# 翻译引擎与配置

模块运行在 Discord 进程内，翻译引擎是纯逻辑层（不依赖 Android 框架），
单元测试在 JVM 上执行（`testDebugUnitTest`）。

## 网络层

- 用 JDK 自带 `HttpURLConnection`，**零第三方依赖**：Discord 已打包 okhttp3，
  模块再打包一份会类冲突/版本错配。
- JSON 用 `org.json`（Android 系统内置）；JVM 单测通过 `testImplementation`
  提供 `org.json:json`。
- 支持非流式（一次性 JSON）与流式（SSE，`Http.postJson(..., sse=true)`）两种
  响应：SSE 时按行读取并返回帧文本，由协议层累积 delta。

## 供应商协议

配置项 `provider_protocol`，三种取值（`Config.PROTOCOL_*`）：

| 协议 | 请求端点/体 | 译文提取 |
|---|---|---|
| `openai-compatible`（默认） | `POST …/v1/chat/completions`：`model` + `messages`(system+user) + `temperature:0` + `frequency_penalty:0.2` | `choices.0.message.content` |
| `openai-responses` | `POST …/v1/responses`：`model` + `instructions`（系统提示词）+ `input` + `temperature:0` | 响应 `output` 数组按类型扫描 `message` → `output_text`（推理模型把 `reasoning` 排在 `message` 前，不能固定取 `output.0`） |
| `anthropic-messages` | `POST …/v1/messages`，`max_tokens` 必填（8192） | `content` 数组中 text 块的拼接 |

- OpenAI-Responses **不携带 `frequency_penalty`**（官方接口不接受）。
- API URL 填完整端点；若 URL 以另一协议的端点结尾（`/chat/completions` 与
  `/responses` 互指），请求时自动改写为当前协议端点，其余自定义网关原样使用。
- 协议参与配置版本计算：切换协议后旧缓存不命中，按新协议重新请求。

## 调度与重试（core/Scheduler + TranslationException）

- 线程池异步执行，`AdjustableSemaphore` 限制并发（`max_concurrency`，可运行时调整）。
- 去重：`Scheduler.inFlight`（按 messageId + contentHash + generation）+ `TranslationCache`。
- 错误分类：`CONFIG / NETWORK / TIMEOUT / HTTP / RATE_LIMIT / PARSE / EMPTY / CANCELLED`。
- 重试：仅 `isRetryable()` 为 true 的错误，最多 3 次尝试：
  - `TIMEOUT`/`NETWORK`/5xx：临时性，重试；
  - `RATE_LIMIT`(429)：退避更长（2s/4s/8s），并采用服务端 `Retry-After` 建议值
    与指数退避两者较大值，最长等待 60 秒；
  - `HTTP` 4xx（含 401/403）/`CONFIG`/`PARSE`/`EMPTY`：不重试。
- 过期请求丢弃：提交前 + 重试前 + 回写前三重校验（见 architecture.md）。

## 缓存

`TranslationCache`，LRU 上限 512；键 = `SHA-256(正文 + 配置版本)`。
配置版本纳入 `apiUrl / model / targetLang / bodyTemplate(协议) / systemPrompt /
凭据 SHA-256 指纹`：任何影响结果的配置变化都会使旧条目不再命中。

## 配置项（core/Config，未配置一律回落默认值）

| 键 | 默认值 | 说明 |
|---|---|---|
| `auto_translate_enabled` | true | 总开关 |
| `api_url` | `https://api.openai.com/v1` | API 基础 URL |
| `api_key` | 空 | 仅存本机 remote preferences，不回显 |
| `api_model` | `gpt-4o-mini` | 模型名 |
| `target_lang` | `简体中文` | 目标语言 |
| `extra_headers` | 空 | 额外请求头（每行一个 `Key: Value`） |
| `provider_protocol` | `openai-compatible` | 见上表 |
| `system_prompt` | 英文规则式提示词 | 只翻译、不接话、不拒译侮辱性内容、无对照示例 |
| `timeout_ms` / `max_concurrency` | 正整数 | 超时与并发上限，≤0 视为未配置 |

- `apiKey` 与 `extraHeaders` 默认值为空串，“空白回落”对它们等价，
  不影响“无需认证 / 无额外请求头”用法。
- 保存用 `commit()` 后台执行，按返回值报告成功/失败；提示语不声称目标进程
  已即时生效（目标进程侧未注册 remote preferences 变更监听，运行中的 Discord
  可能需重启才生效）。

## 隐私与日志

- 消息正文与译文**始终不写入日志**；详细日志开关只影响诊断信息详细程度。
- `XLog.redact`：密钥只输出 `<configured>` / `<empty>`；`safeUrl` 只记录
  scheme + host（含端口）；非法请求头行只记录行号。
- 配置版本中凭据只以 SHA-256 指纹参与，不含原始值。