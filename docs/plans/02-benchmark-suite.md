# 02 · Benchmark 与回归历史

## 1. 目标

把“可复用的测试输入”与“一次随机生成的负载”分开管理：

- **回归历史**：固定历史、来源、适用 checker 和预期结论，用于防止正确性回归。
- **Benchmark 模板**：描述事务结构、初始数据和参数空间，用于生成可复现实验输入。
- **负载实例**：由 benchmark、参数和 seed 唯一确定，供不同 checker 共享。

当前优先建设回归历史，不先铺大型 TPC 套件。现有 `examples/histories/` 是原始样例来源，但缺少统一元数据，不能直接视为完整回归集。

## 2. 目录与最小文件

目标布局如下，按需创建，不为未来模块预建空目录：

```text
benchmarks/
├── regression/
│   └── <case-id>/
│       ├── case.yaml          # 来源、语义、预期结论
│       ├── history.txt        # 或 history.edn
│       └── counterexample.dot # 可选，已知反例
├── kv/
│   └── <benchmark-id>/
│       ├── benchmark.yaml     # 模板、参数和兼容声明
│       └── transactions.yaml  # 需要模板时提供
└── sql/                       # SQL 链路实现后再引入
```

每个回归用例只保留必要文件。来源或许可信息写入 `case.yaml`，不额外维护重复 README。

## 3. 回归用例契约

```yaml
schema_version: 1
id: dgraph-si
kind: regression
model: kv-rw                 # kv-rw | kv-list；sql 留待后续
history: history.txt
source:
  type: collected            # collected | handcrafted | reduced | synthesized
  reference: examples/histories/dgraph_si.txt
  description: Dgraph SI counterexample
anomalies: [snapshot-isolation-violation]
checks:
  - checker: PolySI
    isolation: SNAPSHOT_ISOLATION
    verdict: reject          # accept | reject
notes: 保留必要的语义或假设说明
```

规则：

- 每个 `checks` 项都必须能在当前实现中执行；尚未支持的 checker 不写成预期结果。
- `accept/reject` 只表示指定规格下的判定，不附带“数据库一定有 bug”的额外结论。
- 来源不明、无法加载或结论未确认的样例进入待核验清单，不进入默认回归套件。
- 初始化事务、重复写值、aborted 操作和 list-append 等假设必须显式说明。
- 内容哈希由运行器计算并写入实验 manifest，不在样例文档里手工维护。

## 4. Benchmark 模板契约

当需要固定负载形状时，再引入 `benchmark.yaml`：

```yaml
schema_version: 1
id: rw-register
kind: template
model: kv-rw
generator: general
params:
  sessions: {type: int, default: 4, min: 1}
  txns_per_session: {type: int, default: 10, min: 1}
  ops_per_txn: {type: int, default: 4, min: 1}
  read_ratio: {type: float, default: 0.5, min: 0, max: 1}
  key_space: {type: int, default: 100, min: 1}
  distribution: {type: enum, default: uniform, values: [uniform, zipf, hotspot]}
  seed: {type: int, default: 0}
compatible_models: [kv-rw]
```

模板只定义生成所需信息，不保存数据库密码、机器路径或某次运行结果。参数名称与 03 对齐，批量扫描由 10 处理。

## 5. 负载实例与复现

负载实例身份由以下内容计算：

```text
instance_id = hash(benchmark id + schema version + resolved params + seed)
```

- 同一实例必须生成相同的事务计划，并保存解析后的参数和哈希。
- 真实数据库的调度、重试和耗时允许不同，因此执行 trace 不参与负载实例 ID。
- 对比 checker 时直接共享已保存的历史文件，避免“参数相同但输入不同”。

## 6. 扩展顺序

1. **KV 回归集**：迁移并核验 `examples/histories/` 中的 SI、SER、TCC 样例，补充最小 accept/reject 对例。
2. **KV 模板**：先规范现有 rw-register，再按实验问题加入 list-append、smallbank 或 YCSB 风格模板。
3. **定向异常集**：为 write skew、lost update 等场景建立小而明确的用例，不追求事务规模。
4. **SQL 模板**：只有在 04 能记录谓词语义、06 有兼容 checker 后，才加入 TPC-C 或 phantom 场景。

引入权威 benchmark 时，应保留其事务语义和出处；只借用名称而改写负载，不构成有效对照。

## 7. 与上下游的接口

- 03 根据模板和参数生成事务计划。
- 04 执行计划并产生观测；回归历史可绕过执行器直接加载。
- 06 根据模型、隔离级别和假设声明兼容性，不兼容项记为 `skipped`。
- 10 把用例 ID、历史哈希和实际判定写入运行产物。

## 8. 验收

- 默认回归集中的每个文件均可加载，manifest 字段可校验，来源与预期结论完整。
- 每个核心 checker 至少有正例、反例和边界用例；不适用项不会被误算为失败。
- 同一模板、参数和 seed 可重建相同事务计划。
- 修改 loader、serializer 或 checker 后，可一键运行默认回归集并输出分歧清单。
