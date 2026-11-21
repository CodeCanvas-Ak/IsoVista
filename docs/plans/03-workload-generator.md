# 03 · Workload Generator

## 1. 目标与现状

生成器负责产生**事务计划**：会话、事务、操作及其参数。真实交错由执行器形成，生成器不应伪装成数据库调度器。

当前 `GeneralGenerator` 已支持读写寄存器、uniform/zipf/hotspot 分布和基本规模参数。近期工作是把参数、随机种子和输出保存好，而不是同时实现多套复杂生成算法。

## 2. 统一参数

| 标准参数 | 当前 `workload.*` 键 | 含义 |
| --- | --- | --- |
| `histories` | `history` | 生成的历史数量 |
| `sessions` | `session` | 会话数 |
| `txns_per_session` | `transaction` | 每会话事务数 |
| `ops_per_txn` | `operation` | 每事务操作数 |
| `read_ratio` | `readproportion` | 读操作比例 |
| `key_space` | `key` | 键空间 |
| `distribution` | `distribution` | 键分布 |
| `seed` | 当前无独立键，目标新增 | 随机序列根种子 |

`workload.variable` 是批量扫描入口，归 10 的实验展开层处理，不属于生成算法。

目标 `WorkloadSpec` 示例：

```yaml
schema_version: 1
benchmark: rw-register
generator: general
seed: 42
params:
  histories: 1
  sessions: 4
  txns_per_session: 10
  ops_per_txn: 4
  read_ratio: 0.5
  key_space: 100
  distribution: zipf
  duplicate_writes: true
```

旧 properties 键通过映射继续可用；新字段只有在实现后再加入根目录配置说明。

## 3. 可复现约定

- 解析后的 `WorkloadSpec` 与事务计划一并保存，实例身份按 02 的规则计算。
- 所有随机选择使用显式、可保存状态的 PRNG；禁止混用全局随机源和时间种子。
- 相同 spec 在同一实现版本下应生成逐字节一致的计划；跨版本比较时同时记录生成器版本。
- 数据分布用统计容差验证，不要求有限样本逐项等于理论比例。
- 执行后的读值、成功事务集合和并发顺序不属于计划，因此不要求两次数据库执行完全相同。

## 4. 分层实现

### A. 通用生成器：近期完成

- 保留现有规模和分布能力，补 seed、解析后参数和计划序列化。
- 明确写值唯一性或重复写值策略，并把该假设传给 checker。
- 为边界参数、非法参数和空结果提供明确错误。
- 只有实验需要时才加入 abort；aborted 历史与成功事务历史必须可区分。

### B. 模板生成器：按对照实验需要加入

用小型操作 DSL 描述 smallbank、读改写等固定形状，键和值仍由统一参数采样。模板应来源于 02 的 benchmark，不在 Java 中重复硬编码另一份定义。

list-append 也作为独立模型处理，不能只改文件格式而保留读写寄存器语义。

### C. Mini-Transaction：可选优化

针对短小 RMW 事务建立形状库，用于降低验证成本或覆盖特定异常。其写值假设、操作上限和适用 checker 必须显式记录，并通过对照实验确认收益，不预设性能倍数。

### D. DBStorm / Adaptive：后续研究项

数据状态镜像、冲突图探索和反馈驱动生成属于独立增强。启动前必须已有：

1. 可复现基线生成器；
2. 覆盖率或异常模式的明确度量；
3. 固定预算下的随机基线；
4. 10 的批量运行和结果收集能力。

## 5. 与执行器的边界

- 生成器输出计划，不直接决定真实数据库的提交顺序。
- 定向异常若需要 barrier 或延迟注入，应在计划中声明协同点，由 04 的能力检查决定是否可执行。
- 不支持协同的执行器只能按普通并发运行，并把定向场景标记为“未保证触发”，不能静默声称已复现。
- 重试和 aborted attempt 是执行观测，不回写为原计划的随机选择。

## 6. 最小测试集

- 同 spec 两次生成结果一致，不同 seed 能产生不同计划。
- 会话、事务和操作数量符合约束，键与值满足声明的取值规则。
- uniform/zipf/hotspot 在固定样本上满足统计 sanity check。
- 重复写值、唯一写值和 list-append 的假设不会混淆。
- 非法比例、空键空间和过度规模在消耗大量资源前被拒绝。

## 7. 验收

近期只需满足：现有配置可迁移、计划可保存、seed 可复现、结果可被 04 和已有 loader/checker 消费。高级生成器必须分别提供“相对随机基线增加了什么”的实验证据，否则不进入默认流程。
