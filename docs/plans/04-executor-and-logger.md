# 04 · Executor 与 Logger

## 1. 目标

执行层负责把事务计划提交给数据库，并忠实记录客户端可观测事实。近期重点是补齐可复现所需的观测和错误信息，不先把所有 collector 重写成大型插件体系。

必须区分两类产物：

- **事务计划**：03 生成的输入，不包含实际读值和真实交错。
- **执行 trace**：实际请求、响应、时间区间、重试和错误；用于审计或转换历史。
- **checker 历史**：经过语义校验后供 checker 消费的输入，不能包含来源不明的推断。

## 2. 近期实现方式

1. 保留 `Collector` / `DBClient` 和现有 JDBC 方言，只在执行边界增加事件记录。
2. 每个会话继续使用独立连接，记录配置中的数据库隔离级别和实际重试。
3. 先支持现有 read-write register，再按实验需要扩展 list-append 或 SQL。
4. 新数据库按明确对照问题接入；接入步骤见 [How to Check a New Database](../how-to-check-a-new-database.md)。

数据库数量不是独立验收目标。只有在隔离语义、驱动行为或已知 bug 能形成有效对照时，才增加新的 executor。

## 3. Trace 最小契约

建议使用带版本的 JSON Lines，便于流式写入和失败恢复：

```json
{"record_type":"run","schema_version":1,"run_id":"run-001"}
{"record_type":"operation","op_id":1,"session_id":0,"txn_id":0,"attempt":0,"type":"write","key":1,"value":2,"start_ns":1000,"end_ns":2400,"status":"ok"}
{"record_type":"operation","op_id":2,"session_id":1,"txn_id":1,"attempt":0,"type":"read","key":1,"value":2,"start_ns":1800,"end_ns":3000,"status":"ok"}
```

通用字段：

| 字段 | 说明 |
| --- | --- |
| `op_id/session_id/txn_id` | 与事务计划稳定关联 |
| `attempt` | 重试次数；失败 attempt 不被静默丢弃 |
| `type` | `read/write/begin/commit/abort`，按实际支持增加 |
| `start_ns/end_ns` | 同一次运行内的单调时钟区间，用于判断重叠，不充当全局实时顺序 |
| `status` | `ok/error/aborted/timeout` 等执行状态 |
| `error` | 可选的驱动错误类别与脱敏信息 |

`key/value` 属于 KV 扩展。原始 SQL、行集、谓词和观测完整性属于 SQL 扩展，未实现时不写入空壳字段冒充支持。

## 4. 执行与记录规则

- 时间区间只说明客户端观测到的重叠，不直接等同于数据库内部提交顺序。
- 重试必须保留 attempt 与错误类别；最终成功或耗尽重试都要可追踪。
- Logger 不做 WR/WW/RW 推断，这些属于 checker 或明确的历史转换步骤。
- 记录失败不能覆盖原始执行异常；写盘失败应使运行进入 `error`，而不是产生不完整却被视为成功的历史。
- 数据库密码、完整连接凭据和个人绝对路径不得进入 trace 或 artifact。
- 建表、清表和删除数据库行为必须在使用文档中可见，只允许对专用测试库执行。

## 5. 数据库能力声明

新增数据库时记录实际能力，而不是先引入一套庞杂接口：

| 能力 | 需要确认的内容 |
| --- | --- |
| 事务与隔离 | 驱动支持的级别、数据库实际语义和映射 |
| 操作模型 | KV、list-append、SQL、范围读或其他 |
| 重试与 abort | 可识别错误、重试是否改变历史语义 |
| 协同执行 | 是否支持 barrier、延迟注入；不支持时如何标记 |
| 观测范围 | 仅返回值，还是可得到行集、版本或时间戳等额外信息 |

JDBC 兼容不代表隔离语义相同。复用基类时仍需保存真实数据库名称、版本和方言。

## 6. SQL 谓词扩展：可选

SQL 支持必须以一次最小 phantom 实验为起点，逐步增加：

1. 参数化 SQL 与结果集记录；
2. `predicate + rows_returned`；
3. insert/delete/update 的影响行与观测完整性；
4. 在确有可靠手段时增加 `rows_checked` 或其他插桩信息。

纯黑盒只能观测返回行时，必须标记为 partial observation。不能声称已经获得数据库实际检查但未返回的全部行集。

## 7. 开销与验证

采集开销随实现一起测量：在相同负载下分别记录关闭和开启 trace 的吞吐、延迟、文件大小与资源消耗，并注明采样误差。

验收至少包括：

- 每个成功历史都能回链到计划和 trace，操作 ID 不重复，事务归属完整。
- 重试、驱动异常和写盘失败均有明确状态，不会生成“看似正常”的缺失历史。
- 时间区间满足 `start_ns <= end_ns`，重叠关系可重建；不要求两次真实执行的 trace 相同。
- 新数据库通过现有扩展指南中的构建、CLI 和 Web 验证，并明确外部依赖是否实测。
