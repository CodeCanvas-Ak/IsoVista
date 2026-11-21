# 06 · History Checkers

## 1. 目标

统一的是 checker 的**输入、结果和运行方式**，不是强行统一其内部算法。图算法、SMT 和外部工具均可保留原实现，通过适配器接入。

当前扩展仍使用 `Checker` 接口，具体步骤见 [How to Implement a New Checker](../how-to-implement-a-new-checker.md)。当前注册名与 `checker.isolation` 映射以 [ROADMAP.md](../../ROADMAP.md) 为准，本文不复制一份易过期的表。

## 2. 能力声明

每个 checker 接入时明确：

- 支持的隔离规格，而不只是配置显示名称。
- 支持的数据模型与操作类型。
- 对初始化事务、唯一写值、完整 WR 关系和 aborted 操作的假设。
- 输出反例的形式及阶段 profile 能力。
- 外部进程、JNI、原生库、求解器和其他运行时依赖。

不兼容输入应返回 `skipped` 或在启动前拒绝，不能运行后把异常当作 `reject`。

## 3. 统一结果

```yaml
schema_version: 1
checker: PolySI
isolation: SNAPSHOT_ISOLATION
history_sha256: <hash>
status: accept                 # accept | reject | timeout | oom | error | skipped
counterexample_ref: conflict.dot
stages_ms:
  Construction: 10
  Pruning: 20
  Solving: 30
assumptions: [complete-wr]
error: null
```

约束：

- `accept/reject` 只用于完成验证后的语义结论。
- `timeout/oom/error/skipped` 不参与正确率分子，也不能写成耗时 0。
- `counterexample_ref` 指向 artifact 中的文件，原始输出和解析失败信息一并保留。
- 阶段名称与 09 对齐；未知耗时写 `null`，不能伪造为零。
- `history_sha256` 用于确认对比对象确实消费了同一输入。

## 4. 适配器分层

### Java checker

先保留 `verify/outputDotFile/getProfileInfo`，由外层运行器补充超时、错误状态、历史哈希和统一结果。只有现有接口确实阻碍实验时，才引入新的插件接口。

### 外部 checker

统一进程适配器负责：

1. 导出输入并记录格式与哈希；
2. 使用受控参数启动进程，注册子进程 PID 以采集资源；
3. 捕获退出码、标准输出、标准错误和超时；
4. 解析 verdict、反例和 profile，解析失败归为 `error`；
5. 保留原始输出，避免只留下平台解释后的结论。

## 5. 交叉验证协议

只对**模型、隔离规格和假设兼容**的 checker 比较同一历史：

| 结果组合 | 解释与动作 |
| --- | --- |
| 全部 `accept` | 在这些规格和假设下未发现违例，不代表已证明数据库正确 |
| 全部 `reject` | 比较反例语义和覆盖事务，可进入人工分析或 reducer |
| 部分 `accept`、部分 `reject` | 记录为分歧，先检查规格、输入转换和假设，不自动多数投票 |
| 出现 `timeout/oom/error` | 单独报告资源或运行问题，不参与 verdict 多数判断 |

反例图不同不等于结论不同；同一违例可能存在多个等价环。需要比较时先规范化事务、边和异常语义。

## 6. 后续扩展顺序

1. 把现有 checker 的结果统一，并建立 02 的正反回归集。
2. 扩展现有 Elle 集成或加入一个 SER checker，以补足明确的对照实验。
3. SQL checker 与 04 的谓词观测一起垂直实现，不提前登记“已支持 SQL”。
4. 在线、白盒或更多算法只有形成新的研究问题后再接入。

## 7. 性能与公平性

统一预算由 10 下发，指标和重复协议由 09 定义。checker 不得自行修改全局核数、内存或超时来制造优势。外部进程的子进程消耗必须包含在同一口径中。

## 8. 验收

- 现有 checker 接入统一结果后，在默认回归集上的 verdict 与迁移前一致。
- 每类状态均有测试，尤其是 timeout、外部进程非零退出和输出解析失败。
- 同一历史的对比报告能验证哈希一致，并列出全部假设差异。
- 新 checker 按当前扩展指南即可完成注册、反例输出、profile 和正反回归，不要求先重构全部旧 checker。
