# IsoVista 目标态设计文档

本目录保存后续设计，不代表功能已经实现。代码现状、已验证环境与当前风险以 [ROADMAP.md](../../ROADMAP.md) 为准；实际开发顺序以 [11 · 单人实施路线图](11-roadmap.md) 为准。

## 阅读约定

- **毕设主线优先**：先完成回归历史、统一实验记录、性能对比和可视化闭环，不以数据库或 checker 数量代替实验深度。
- **扩展按需启动**：SQL 谓词、历史合成、bug 归约、自适应生成和在线检查均为后续扩展，不与主线并行铺开。
- **设计逐步落地**：文档中的新目录、字段和接口是目标契约。实现时应先写最小版本和回归测试，再回写实际结果，不把计划字段冒充现有能力。
- **只保留必要规则**：文献用于解释设计来源，具体结论以原论文为准；不在各文档重复堆砌文献清单、数量指标或未经实测的性能目标。

## 文档索引

| 文档 | 唯一职责 | 当前优先级 |
| --- | --- | --- |
| [01 · 愿景与总体架构](01-vision-and-architecture.md) | 目标边界、组件关系与演进原则 | 主线指导 |
| [02 · Benchmark 与回归历史](02-benchmark-suite.md) | 历史样例、预期结论、来源与负载模板规范 | 主线 |
| [03 · Workload Generator](03-workload-generator.md) | 可复现负载参数、事务模板与生成器扩展 | 基础先行，高级生成可选 |
| [04 · Executor 与 Logger](04-executor-and-logger.md) | 执行观测、trace、数据库能力与 SQL 扩展 | 按需补齐 |
| [05 · History Synthesizer](05-history-synthesizer.md) | 历史归一化、变换与定向合成 | 可选 |
| [06 · History Checkers](06-history-checkers.md) | checker 适配、结果语义与交叉验证 | 主线 |
| [07 · Bug Reducer](07-bug-reducer.md) | 历史归约、复现与去重的边界 | 可选 |
| [08 · Visualization](08-visualization.md) | 图数据契约、异常解释与运行展示 | 主线 |
| [09 · Performance Analysis](09-performance-analysis.md) | 指标定义与公平对比协议 | 主线 |
| [10 · Experiment Automation](10-experiment-automation.md) | 实验配置、运行产物、断点续跑与图表生成 | 主线 |
| [11 · 单人实施路线图](11-roadmap.md) | 阶段依赖、任务顺序与阶段验收 | 执行入口 |

## 内容归属

| 信息 | 唯一维护位置 |
| --- | --- |
| 当前目录、配置键、API、已知问题 | 根目录 `ROADMAP.md` |
| 当前扩展操作步骤 | `docs/how-to-*.md` |
| 长期目标与组件边界 | `01` |
| checker 结果状态与兼容性 | `06` |
| 性能指标与比较方法 | `09` |
| 实验配置、manifest 与 artifact 布局 | `10` |
| 阶段顺序与完成标准 | `11` |

修改某一契约时，只更新其归属文档和直接消费者；其他文档使用相对链接引用，避免多份拷贝逐渐失真。
