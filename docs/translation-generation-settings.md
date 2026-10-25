# 供应商协议与翻译生成参数

## 本次调整

设置页的 API 配置项调整：

- **移除**「JSON 请求模板」与「响应译文提取路径」两个配置项。
- **新增**「供应商协议」下拉菜单，两个选项：
  - `OpenAI-Responses`：请求 `POST …/v1/responses`，请求体为 `model` + `instructions`（系统提示词）+ `input`（原文）+ `temperature:0`；译文在响应 `output` 数组中按类型扫描 `message` → `output_text` 提取（推理模型会把 `reasoning` 项排在 `message` 之前，不能固定取 `output.0`）。
  - `OpenAI-Compatible`（默认）：请求 `POST …/v1/chat/completions`，请求体为 `model` + `messages`（system + user）+ `temperature:0` + `frequency_penalty:0.2`，与旧版默认模板逐字段等价；译文取 `choices.0.message.content`。
- OpenAI-Responses 协议**不携带 `frequency_penalty`**：OpenAI 官方 Responses 接口不接受该参数（部分第三方兼容实现自行扩展了它）。
- API URL 仍填完整端点。若 URL 以另一协议的端点结尾（`/chat/completions` 与 `/responses` 互指），请求时会自动改写为当前协议端点，切换协议无需手动改 URL；其余自定义网关路径原样使用。
- 默认系统提示词保持上一版英文规则式文本（只翻译、不接话、不拒译侮辱性内容、无对照示例）。

## 已安装用户如何生效

模块始终优先使用已保存的配置，升级 APK **不会覆盖**已保存的提示词等旧值。

- 旧版保存的「JSON 请求模板」「响应译文提取路径」两个键在升级后被忽略（不再读取），无需清理。
- 未保存过协议的用户默认 `OpenAI-Compatible`，与旧版默认模板逐字段等价，**无需任何操作**。
- 原先使用**自定义请求模板**的用户：自定义请求格式与自定义提取路径已随配置项移除而不再支持。若服务商本身是 OpenAI 兼容 Chat Completions 或 Responses 接口，选择对应协议并确认 API URL 端点正确即可；其他私有接口格式当前无法适配。
- 想让已保存的旧中文提示词回落到新版英文默认值：打开模块设置，仅清空「系统提示词」一项再保存，即可按回落规则存为新版默认值。
- 协议参与配置版本计算：切换协议后，旧配置的译文缓存不再命中，会按新协议重新请求。

## 验证与限制

单元测试只验证两协议的请求体字段、端点改写、译文提取结构、提示词/原文的 JSON 无损往返和配置回落规则；不能证明远端模型一定服从提示词。构建和自动化测试需要通过 GitHub Actions 执行，本地未运行。

模型效果需用用户实际接口验证（设置页「测试连接」会用固定文本走一次完整请求）：

| 原文 | 期望行为 |
| --- | --- |
| `hii everyone` | 仅给出“嗨，大家好”等忠实译文，不追加提供帮助的问句 |
| `Can you help me?` | 翻译成“你能帮我吗？”，不要实际回答问题 |
| `Hi! Is there anything I can help you with?` | 原文本身含提供帮助的问句，应正常翻译，不应因约束而删掉 |

尚未用用户的实际模型进行效果测试，不承诺仅修改参数就能彻底消除扩写。
