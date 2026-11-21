# 01 · 愿景与总体架构

## 1. 目标与边界

IsoVista 的长期目标是成为数据库隔离级别黑盒测试平台，让负载、执行历史、checker、实验结果和异常解释能够复用。

当前毕设不追求一次性完成完整生态，而是先形成一条可信主线：

> **同一历史可由多个 checker 验证，实验可重复，结果可追踪，异常可解释，论文图表可重新生成。**

### 主线范围

- 复用现有 Java CLI、collector、checker、FastAPI 队列和 Vue 展示。
- 建立带来源和预期结论的回归历史集。
- 统一 checker 结果、运行 manifest、性能指标和实验产物。
- 支持参数扫描、批量运行、图表重建和 Web 演示。

### 后续扩展

- SQL 谓词与范围读、新数据库、新 checker。
- 定向负载、历史合成、bug 归约和更丰富的解释视图。
- 在线检查、白盒加速和公开数据集。

### 非目标

- 不做通用 SQL 正确性测试或数据库性能跑分平台。
- 不承诺覆盖 SQL 全语义，也不在缺少观测数据时推断谓词依赖。
- 不以接入数量、代码规模或未经实测的指标作为完成标准。

## 2. 设计原则

1. **黑盒优先**：默认只使用客户端可观测历史；白盒信息必须显式标注，不能与黑盒结果混用。
2. **契约先于重构**：先固定历史、结果和 artifact schema，再逐步替换内部实现，避免推倒重写。
3. **同输入、同口径**：所有比较共享历史、预算、环境和指标定义；不兼容的语义不强行排名。
4. **失败也是结果**：`timeout`、`oom`、`error` 与 `accept/reject` 分开记录，不能转成通过或失败结论。
5. **单机可复现**：先保证串行运行和干净环境复现，再考虑并行调度与分布式执行。

## 3. 目标数据流

```text
Experiment YAML
      │
      ▼
Benchmark / Workload ──▶ Executor + Logger ──▶ History / Trace
      │                                            │
      │                                            ▼
      │                                      Checker adapters
      │                                            │
      │                                            ▼
      │                                Verdict + Counterexample + Profile
      │                                            │
      ▼                                            ▼
Run manifest + Artifact store ───────────▶ Analysis + Visualization

可选支路：History transforms / Reducer / Synthesizer
```

主线中，历史可以直接来自已有文件，不必每次经过数据库执行。synthesizer 和 reducer 是独立支路，不能成为基本检查流程的前置依赖。

## 4. 组件职责与现有落点

| 组件 | 目标职责 | 现有落点 | 详细设计 |
| --- | --- | --- | --- |
| Experiment runner | 展开参数矩阵、调用现有 CLI、记录状态与产物 | `Main.java`、后端运行队列 | 10 |
| Benchmark / regression | 描述历史来源、模型、参数和预期结论 | `examples/histories/`、生成参数 | 02 |
| Workload generator | 从参数与 seed 生成可复现事务计划 | `generator/general/` | 03 |
| Executor / logger | 执行计划并忠实记录观测，不解释隔离语义 | `collector/`、运行时记录器 | 04 |
| History / transforms | 加载、校验、导出及可选结构变换 | `history/` | 05 |
| Checker adapters | 统一能力、结果状态、反例与阶段数据 | `checker/`、`ConfigParser` | 06 |
| Reducer | 在保持判定语义的前提下缩小历史 | 当前无实现 | 07 |
| Visualization | 展示图、异常上下文和运行指标 | `BugView.vue`、`RunView.vue` | 08 |
| Analysis | 从原始产物重建表格与图 | 现有 CSV、ECharts | 09、10 |

## 5. 契约边界

为避免每篇文档重复定义同一字段，按以下边界维护：

- **历史与负载输入**：由 02、03 定义；执行器只消费，不反向修改 benchmark 定义。
- **执行观测**：由 04 定义；trace 保存事实，checker 输入所需的历史语义由加载或转换步骤确认。
- **checker 结果**：由 06 定义；性能阶段名称由 09 约束。
- **运行产物**：由 10 定义；manifest 引用历史、结果、日志和图表，不复制其内容。
- **图数据**：由 08 定义；原始 DOT 继续作为可下载产物保留。

新增字段必须带 schema 版本，并检查 Java、FastAPI、Vue 和分析脚本的实际消费者。

## 6. 演进方式

1. 先用适配器包住现有实现，保持旧配置和已有历史可用。
2. 先迁移一个 checker、一个文本历史和一个小型实验矩阵，验证契约后再推广。
3. 外部工具统一通过进程适配器接入，记录退出状态、子进程资源消耗和原始输出。
4. SQL、在线检查和白盒能力只有在输入语义与观测来源完整时才加入，不为架构完整提前留空壳。

## 7. 总体验收

- 现有历史在新旧链路中的 checker 判定一致；差异有明确原因和回归记录。
- 任一实验可从 manifest 找回配置、代码版本、历史哈希、原始日志和结果。
- 论文核心表格与图可以只依据 artifact 重新生成。
- 演示可从上传历史或配置运行走到异常图，无需手工修改中间文件。
- 可选模块未完成时，不影响上述主线独立交付。
