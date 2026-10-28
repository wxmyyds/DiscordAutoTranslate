# 列表消息译文本地回填修复

## 现象

用户上报：含项目符号列表的英文消息，图标显示“已翻译”，但正文仍是英文。
原文结构（脱敏）：

```
Codex Relay — AI API for Coding
（空行）
If you're looking for an API provider for AI coding tools, ...
（空行）
What it offers:
（空行）
• Support for Codex CLI, Claude Code, and other AI coding tools
• Access to multiple AI models
• Pay-as-you-go pricing
• Custom API keys and spending limits
• R$5 in free credits after Telegram verification
（空行）
You can try it out with the free credits before spending anything.
（空行）
Sign up here: https://codex-relay.codes/register?aff=QlIz
```

## 已确认的代码机制（事实，非推测）

1. `TextExtractor` 为每个列表项前置 `"\n" + "• "`（`TextExtractor.java` 的
   `BulletListContentNode` 分支），并且列表节点本身是
   `List<List<ContentNode>>`。
2. `ContentNodes.addSegment` 只在分隔符含 ≥2 个换行时推进段落组。列表项之间
   只有单换行，因此 5 个列表项与相邻段落共用段落组：整条消息旧口径只有
   6 个段落组（标题 / 引导句 / “What it offers:” / 整张列表 / 结尾段 /
   “Sign up here:”），而可翻译片段是 10 个。
3. `alignSpanToParts` 旧实现只有三级：空行块 1:1 → 整行 1:1 → 段落级。
   模型保留“每项一行”时，空行块数是 6、整行数（含空行）也不等于片段数 10，
   两级都要求数量相等，对不上。若空行块数恰好等于段落组数 6 且 URL 锚点
   完好，段落级会把整块列表译文塞进首项、其余 4 项置空（列表塌成一行）；
   块数不等于 6，或模型丢了/改坏了 URL 锚点，则整条拒绝回填，`replaceText`
   返回 `null`，调用方保留原文，图标状态仍是已翻译。上报时正文全部保持
   英文，说明当时不满足“6 块 + 锚点完好”这一组合；上报未保留原始译文，
   因此不宣称具体是哪一种。

## 最小修复（只改 `ContentNodes`）

- `PlanWalk` 新增 `listLevel`、`forceNewGroup`。`BulletListContentNode` 遍历
  时每个列表项强制新开段落组，并标记该片段是否位于列表项内
  （`AlignmentPlan.listFlags`）；列表结束后的正文也设边界，避免被并进最后
  一项。列表本身没有可翻译片段时不设边界。
- 新增“非空行 1:1”层级，放在段落级之前，且仅在包含列表片段时启用：要求
  非空行数恰好等于片段数。这样“模型保留每项一行、但空行数与片段数不等”
  的译文可以逐项落位。不含列表的消息不启用该层级，原有严格判定不变。
- 列表片段回填时剥离模型回显的项目符号（`- `、`• `、`1. ` 等），显示符号
  由原节点渲染，否则会显示成双符号。只对 `listFlags` 标记为列表项的片段
  生效，普通段落里以 `- ` 开头的正文不受影响。样式标记路径中落在列表项内
  的片段同样处理。
- 结构仍然对不上（例如模型把 5 项合并成一行）时返回 `null` 保留原文：
  无法可靠拆分，不引入错位或内容丢失。

## 残余风险

- “非空行 1:1”按行位置对应：单侧拆分（一项变两行）或合并（两项变一行）
  都会使非空行数不等于片段数，从而被拒绝、保留原文；但若模型同时把一个
  列表项拆成两行、又把相邻两项合并成一行（行数恰好不变），就会出现行序
  错位。这种双向结构错误无法仅凭文本可靠识别。
- 列表项内部有多个内联片段（如加粗 + 普通文本）时，模型按“每项一行”
  返回会使行数少于片段数，仍被拒绝并保留原文：不会错位，也不会像旧实现
  那样把整张列表塞进首项。
- 列表项译文本就以 `- ` / `• ` / `1. ` 开头（例如原文本身是个短横线
  引导的说明）时会被当作回显符号剔除。列表项不会渲染自己的符号文本，
  这种情况只能靠人工判断。
- 本次未在真机验证渲染结果，需按下文复核。

## 未改动

请求协议、Hook 时序、缓存键、渲染路径均不变；不新增依赖。先前的
“段落块 1:1 → 整行 1:1 → 段落级”与 unsafe 判定保留，新层级只在其后补充。

## 验证

- 新增 `BulletListAlignmentTest`（8 个用例）：
  - 上报原文 10 个片段、列表符号进入请求正文；
  - 模型保留空行 + `- `、保留空行 + `• `、去掉列表前空行、列表项之间也
    留空行，四种形态都逐项回填；
  - 有序列表回显 `1. ` 被剥离；
  - 纯列表消息逐项落位；
  - 列表被合并成一段时 `replaceText` 返回 `null`，原节点未被清空；
  - URL 链接节点原样保留，`Sign up here: ` 的尾随空白按原结构保留；
  - 不含列表的段落消息仍拒绝结构不符的译文，行首 `- ` 不会被误剥离。
- 同步更新 `LongTextRegressionTest` 与 `ContentNodes` 中描述对齐层级的
  注释。
- 构建与单元测试：**该操作需要通过 GitHub Actions 执行。** 本地未运行
  Gradle、javac 或测试。

### 设备复核（待用户执行）

1. 安装本分支 GitHub Actions 产出的 APK，让 Discord 重新加载模块。
2. 用上报的 Codex Relay 消息复测：5 个列表项应逐项显示中文，且不出现
   双项目符号；结尾段落与链接保持原位。
3. 复测不含列表的多段消息、含加粗/emoji 的消息，确认行为未变。
4. 若仍有列表消息保持英文，提供脱敏的请求正文与模型返回译文，以及
   Discord 与模块版本。
