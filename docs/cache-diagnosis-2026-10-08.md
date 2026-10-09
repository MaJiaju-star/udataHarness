# Solon Harness 缓存与消耗诊断

日期：2026-10-08。仅读取本地运行记录；未发送真实模型请求、未查询供应商账单、未重启运行服务。

## 已观测事实

最新会话：`web-526fabe6-9994-4b42-9d37-4cd030f0b421`，记录时间为北京时间 16:29:09 至 17:13:24。

| 项目 | 本地记录 |
| --- | --- |
| 模型 | deepseek-v4-flash |
| 主推理记录 | 28 次：27 次标为 incomplete，1 次 failed |
| usage 可用记录 | 0 次，真实缓存命中率未知 |
| 主会话摘要、预算压缩、阶段检查点 | 均无记录 |
| 推理前消息数 | 1 → 66 |
| 原压力估算 | 9692 → 78426 Token，含重复计数，不能作为账单 Token |
| 模型窗口 / 压缩阈值 | 1000000 / 750000 Token |
| 最后相邻历史匹配 | 上一请求 63 条历史均为下一请求的前缀；不含实际 system/tools，也不是供应商命中量 |

28 次压力估算之和为 1417322，仅说明这套旧估算下反复携带上下文的规模。它既含重复文本和准备余量，也没有区分缓存读写，**不能换算成供应商输入量或费用**。

最后模型视图中，31 条工具结果正文共 62036 字符，TextBlock 又存了一份相同正文。8 条用户消息正文共 1404 字符，也有相同 TextBlock 副本。持久化格式中的双份表示不会导致供应商按双份计费，但原压力估算错误地把两份都算入。

## 确认并修复的问题

### 流式观测位置错误

`ChatRequestDescDefault.internalStream()` 内的 ChatInterceptor 链接收 `STEP_END`，它携带本次物理调用的完整响应和 usage。`RESPONSE_END` 由外层 `stream().tail()` 生成，不经过这条链。

原 `ModelCallObserver` 只等 `RESPONSE_END`，使正常物理调用被标成 incomplete，供应商 usage 和缓存字段丢失。主推理压力锚点及 prepared 请求头也没有建立；因此之前的本地流式观测不足以验证第四批改造的真实效果。此前模拟测试直接发送 RESPONSE_END，未覆盖真实链路，这是测试缺口。

修复：接收 STEP_END，兼容替代传输的 RESPONSE_END；订阅正常完成后再校准压力，每个物理调用只记录一次。错误或取消优先于完成标记，不把部分完成当成最终成功。

### 压力估算重复计算内部格式

持久化 ChatMessage JSON 中 content 与 TextBlock、assistant text/raw 等存在重复表示。现在正文只计一次，单独估算 Thinking、工具参数和媒体。比较前缀时排除内部 metadata / createdAt，归一化懒加载的空 Thinking、工具参数及对象键顺序，避免内部表示变化被误认为消息变更。

### 成本阈值仍与最大容量绑定

当前配置的 1M 窗口乘以 0.75，得到 750k 的压缩阈值。该会话距离此阈值很远，也未显式请求检查点，因此历史持续累积。这是容量策略的现状，不等于“已经按成本有效收敛”。本次未擅自修改阈值。

## 缓存结论

目前不能给出实际命中百分比，也不能将缺失记录解释为 0% 命中。`commonMessagePrefix` 只能说明历史稳定，`cacheHitEstimate=false` 表示本地未做缓存命中推算，不能读成供应商缓存未命中。

DeepSeek 官方 API 默认启用上下文缓存，按已持久化的前缀单元匹配，属于 best effort；稳定前缀并不保证命中。[官方缓存说明](https://api-docs.deepseek.com/zh-cn/guides/kv_cache/)

对同一模型、同一统计范围且 usage 完整的调用，输入 Token 命中比例可按 `sum(cacheReadInputTokens) / sum(promptTokens)` 计算。DeepSeek 原始字段为 `prompt_cache_hit_tokens` 和 `prompt_cache_miss_tokens`，两者之和为 prompt_tokens。[官方 API 字段](https://api-docs.deepseek.com/api/create-chat-completion/)

需要分别统计 reason、summary、失败重试、输出与 Thinking；缓存输入仍计入输入量，输入量增长也不直接代表费用同比增长。子 Agent 与供应商内部重试仍存在观测覆盖限制。

## 验证与后续

全量 **112 项**测试通过。本地 HTTP SSE 回放发送 DeepSeek 格式的 `10000 prompt / 8000 cache hit / 2000 miss` 响应，经过实际 Solon 请求与解析链，验证记录只写一次、usage 不丢、压力校准与 prepared 请求头捕获有效。该 80% 是测试数据，**不是线上命中率**。

代码修复需运行服务加载新版后才生效。旧的未知 usage 无法从本地历史反推出真实缓存命中量，需要供应商日志或账单。

优先建议增加独立活跃上下文软预算（例如通过真实任务评测选择 32k / 64k / 128k），使最大窗口容量与日常成本阈值分开；在阶段完成时结合摘要成本和后续复用判断收敛。先采集修复后的真实 usage，再选择预算，避免一味缩小上下文导致频繁摘要和缓存重建。
