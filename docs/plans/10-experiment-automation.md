# 10 · Experiment Automation

## 1. 目标

用一个声明式文件驱动小型实验矩阵，保存足够信息以重跑和重建图表。近期实现优先做**独立实验脚本调用现有 Java CLI**，不先重写 FastAPI 队列或建设通用 SDK。

## 2. 实验配置

```yaml
schema_version: 1
name: si-checker-comparison
base_config: config/local.properties
benchmark:
  id: rw-register
  seed: 42
  params:
    txns_per_session: 20
    ops_per_txn: 4
    read_ratio: 0.5
    key_space: 100
    distribution: uniform
checkers: [SNAPSHOT_ISOLATION, VIPER_SNAPSHOT_ISOLATION, POLYSI+_SNAPSHOT_ISOLATION]
sweep:
  workload.session: [4, 8, 16]
repeats: 3
budget:
  timeout_ms: 600000
output: result/si-checker-comparison
```

- `base_config` 提供当前单次运行所需默认值；展开器生成每个 run 的 properties。
- `benchmark.params` 使用 03 的标准名称并映射到现有 `workload.*` 键；`checkers` 编译为现有 `checker.isolation` 列表，`sweep` 支持一个或多个 `workload.*` 键。
- 密码使用环境变量引用或本地覆盖文件，artifact 只保留脱敏配置和变量名。
- 配置必须先校验再运行；未知键、空矩阵和冲突参数直接报错。

## 3. 运行模型

```text
Experiment YAML
      │ 校验、脱敏、展开
      ▼
Run matrix ──▶ 现有 Java CLI / history loader ──▶ Raw artifacts
      │                                             │
      └────────── run manifest + checker results ◀──┘
                            │
                            ▼
                   Aggregate tables + figures
```

近期采用串行运行。并行只在资源隔离和输出目录独立后加入，不能共享同一个 `config.properties` 或 `current/`。

- 基础设施错误可按明确次数重试；checker 的 `timeout/oom/error` 是实验结果，不盲目重跑。
- 每个 run 使用独立目录，完成前写临时状态，完成后原子标记 `complete`。
- 断点续跑只跳过配置、代码、输入哈希一致且已完整的 run；其他情况重新执行。
- 对比 checker 时复用已保存历史，避免每个 checker 重新生成或重新采集。

## 4. Artifact 布局

```text
result/<experiment-id>/
├── experiment.yaml           # 解析后的脱敏配置
├── manifest.json             # 实验级索引
├── runs/
│   └── <run-id>/
│       ├── run.json          # 状态、环境、哈希、时间与产物引用
│       ├── config.properties # 脱敏；秘密由重跑者重新提供
│       ├── history.txt       # 或哈希 + 明确存储位置
│       ├── results.yaml      # 06 定义的 checker 结果
│       ├── logs/
│       └── profiles/
└── analysis/
    ├── summary.csv
    ├── tables/
    └── figures/
```

`run.json` 至少记录 run ID、代码 revision、配置哈希、历史哈希、起止时间、状态和环境。checker 结果字段不在本文重复定义，性能指标口径遵守 09。

大历史可存储在独立内容寻址位置，manifest 保存哈希和相对路径；不能只留下某台机器的绝对路径。

## 5. 分析与图表

建议的最小脚本落点：

```text
scripts/experiments/run.py       # 校验、展开、执行、续跑
scripts/experiments/analyze.py   # 只读取 artifact，生成表格和图
```

第一批图表只保留论文最需要的四类：

| 输出 | 内容 |
| --- | --- |
| verdict 表 | 各历史与 checker 的结论、分歧和非结论状态 |
| time curve | 随单一规模维度变化的总耗时，含重复统计 |
| memory curve | 同一实验点上的内存指标，注明测量口径 |
| stage chart | 共同阶段与 `Untracked` 的时间分解 |

分析脚本不得重新执行 checker 来“补数据”，也不得从手工复制的表格绘图。每个图对应一份可检查的聚合 CSV/JSON。

## 6. 与现有 Web 的关系

CLI 实验脚本先独立验证。稳定后，FastAPI 只需复用同一配置解析和 manifest 读取逻辑，把实验及 run 暴露给前端；不要在 Python 后端和脚本中各维护一套结果 schema。

现有 `workload.variable` 保留为单维兼容入口，多维 sweep 由实验展开器拆成多次单配置运行。

## 7. 验收

- 一个 YAML 可完成“小型参数矩阵 → 多 checker → 原始结果 → 汇总表和图”，中途无需手改文件。
- 中断后续跑不会重复已完成 run，也不会把不完整产物误判为完成。
- 同一 artifact 重复分析得到一致的聚合数据，图中数字可回链到具体 run。
- artifact 不包含密码、个人绝对路径或无关本地文件。
- 旧单配置 CLI 和现有 Web 单次运行仍可使用。
