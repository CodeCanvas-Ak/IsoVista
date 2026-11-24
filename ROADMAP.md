# IsoVista 代码地图

> 代码核对基线：`aa60dcd`（2026-11-24）。
>
> 本文只维护当前代码事实、配置与运行、已知风险和执行入口。目标态设计在 [docs/plans/](docs/plans/README.md)，完整单人路线在 [11-roadmap](docs/plans/11-roadmap.md)；规划内容不得当作已实现能力。

## 1. 代码地图

### 1.1 三条主链路

- **Java CLI 核心**：生成或加载历史，调用 collector 在数据库执行，再由一个或多个 checker 验证。
- **FastAPI 编排层**：运行队列交给 worker 池，每次运行使用独立工作目录与结果目录，写入配置并启动 shaded JAR，读取 `current/` 与 `result/`。
- **Vue 展示层**：配置实验、查看运行与 bug，使用 G6 展示依赖图、ECharts 展示指标。

```text
properties / uploaded history
             │
             ▼
Generator / Loader ──▶ Collector ──▶ History ──▶ Checkers
                           │                       │
                           └──── observed values   ├─▶ current/bug_N
                                                   └─▶ profiles + result
FastAPI：queue → worker 池 → workspaces/run_<uid>/config.properties → java -jar → result/run_<uid>/
Vue：Configuration / RunList / RunView / BugList / BugView
```

### 1.2 目录与代码锚点

| 位置 | 当前职责 | 修改时先看 |
| --- | --- | --- |
| `src/main/java/Main.java` | CLI、注册、生成/加载、验证、参数扫描、结果收尾 | `run()`、`runOneShot` |
| `config/Config.java` | 实际配置键与默认路径 | `util/ConfigParser.java`、根目录配置 |
| `history/` | History/Session/Transaction/Operation、loader、serializer | `HistoryLoaderFactory`、对应测试 |
| `generator/general/` | RW-register 负载与键分布 | `GeneralGenerator.java` |
| `collector/` | JDBC 执行、读值回填、重试与清理 | `Collector.java`、`DBClient.java`、数据库子包 |
| `checker/C4/` | RC/RR/RA/TCC 图检查及 list 变体 | `C4.java`、`C4List.java` |
| `checker/PolySI/` | SI 图与约束检查 | `verifier/`、`graph/` |
| `checker/VeriStrong_{SI,SER}/` | 通过 JNI/MonoSAT 检查 SI/SER | 对应 `SMTChecker*` |
| `checker/{Viper,elle}/` | 外部进程 checker 适配 | 进程参数、PID 注册、输出解析 |
| `checker/result/` | 统一结果：`CheckerStatus`、`CheckerResult`、`CheckerRunner`（超时、OOM、错误分类与反例归档） | `CheckerRunner.run` |
| `regression/` | 回归套件与分进程 `CheckExecutor` | `RegressionSuite.run`、`benchmarks/regression/` |
| `util/` | 配置映射、profile、运行时记录、结果打包、输入指纹与版本信息 | `Profiler`、`Runtime*Recorder`、`RuntimeDataSerializer`、`RunRecord`、`HistoryFingerprint` |
| `backend/main.py` | 队列与 worker 池、进程、运行隔离、存储、API 与 DOT 解析 | 端点及其前端消费者 |
| `frontend/src/` | 配置、运行、bug、图与请求封装 | `api/api.js`、`components/` |
| `src/test/java/` | SI/SER 算法、主流程、Elle 适配、统一结果、回归套件、结果归档与历史 round-trip | `TestSIVerifier`、`TestSERVerifier`、`MainTest`、`ElleTest`、`CheckerRunnerTest`、`RegressionSuiteTest`、`RuntimeDataSerializerTest`、`HistoryFingerprintTest` |
| `backend/test_main.py` | 后端存储、失败记录、运行隔离、活动运行列表、DOT 与下载的隔离回归 | 使用临时目录，禁止真实 worker 启动 |
| `benchmarks/regression/` | 8 个回归用例（`case.yaml` + 历史），逐条声明 checker、隔离级别、预期 verdict 与依赖 | `regression.RegressionSuite` |
| `examples/histories/` | SI、SER、TCC 原始历史样例 | 预期结论已在回归用例中实际验证 |
| `scripts/standalone/` | MySQL、PostgreSQL 本地 Compose | 对应 compose 文件 |
| `third_party/` | Viper、MonoSAT、Elle CLI 等资产 | 不手改 |

### 1.3 Java 主流程

1. `Main.run()` 先扫描 `collector` 并校验 `db.type`，缺失或未知类型会在 checker 扫描和原生库初始化前失败；实现类以静态 `NAME` 注册，并需要 `Properties` 构造函数。
2. `workload.skipgeneration=false` 时由 `GeneralGenerator` 生成历史，再由 collector 执行并回填实际读值；为 `true` 时按 `history.type` 加载 `history.path`。类型按值且忽略大小写解析，路径保留原始大小写。
3. 一个历史可依次运行多个 checker，每个 checker 由 `CheckerRunner` 包成一条 `CheckerResult`：`ACCEPT`/`REJECT` 是结论，`TIMEOUT`/`OOM`/`ERROR`/`SKIPPED` 是非结论状态，不会被写成 accept 或 reject。返回 `false` 时写入 `current/bug_N/`：text 历史保存为 `bug_hist.txt`，Elle 历史保存为 `bug_hist.edn`，`bug.json` 记录 checker、隔离级别、状态、反例引用与历史哈希。checker 未实现反例图时只保留历史并写明 `counterexample_format=none`，不伪造 `conflict.dot`。
4. `workload.variable` 支持单维批量扫描；每次覆盖一个 `workload.*` 值。
5. `RuntimeDataSerializer` 将配置、日志、profile 和 bug 写入 `<output.path>/<output.run_id>`，未指定 `output.run_id` 时使用 `run_<timestamp>`；归档配置使用副本并移除 `db.password`，同时将 `db.url` 的 userinfo 密码和敏感参数替换为 `<redacted>`，运行时原始配置不变。文件移动失败会向上传播，不再生成看似完整的成功结果。
6. 每次运行额外写 `results.yaml`（逐 checker 结果）与 `run.json`（`run_id`、状态、起止时间、代码版本、配置与历史 sha256、环境、结果与产物清单）。`CheckerResult` 与 `RunRecord` 均以 snake_case 序列化，`CheckerStatus` 写小写值。

CLI `main()` 仅在退出码为 0 时清理 `./current`，失败时保留现场供后端归档和排障。CLI 直接运行时仍使用进程工作目录，不要在同一工作目录并行启动多个 CLI。

### 1.4 FastAPI 与 Vue 契约

- 后端从工作目录读取 `DBTest-1.0-SNAPSHOT-shaded.jar`，因此按仓库约定从根目录启动。
- `/run` 只入队。每次运行获得 `workspaces/run_<uid>/` 工作目录和 `result/run_<uid>/` 结果目录，后端重写 `output.path`（相对工作目录的结果根）并追加 `output.run_id`，因此并行运行不会互相覆盖配置、`current/` 或产物；工作目录在运行结束后删除。
- worker 数由 `ISOVISTA_MAX_PARALLEL_RUNS` 控制，默认 1（串行）。并行是显式开启的：多个运行会竞争同一 CPU、内存和数据库。
- Java 启动异常和非零退出会写入 `result/run_<uid>/metadata.json`，状态为 `Failed` 并保留 `error`；用户停止记为 `Stopped`。`/stop?run=<uid>` 在无对应活动进程时返回 409，进程清理由 worker 收尾。
- 所有 `current_*` 端点接受可选 `run` 参数，缺省时跟随最近启动的活动运行；`/run_list` 由已完成运行与活动运行合并而成。`active_run` 同时接受 run uid 和活动记录。
- `RunStore`、`BugStore` 每次扫描都重建计数与索引，损坏或字段不全的 metadata 会记录并跳过；实时信息来自该运行自己的 `current/`。
- `/view/{bug_id}` 使用 pydot 解析 DOT，返回 nodes/edges JSON；缺失图、缺失 `relate_to` 等可选属性或解析失败会降级为空图和说明。`BugStore` 读取 `bug.json` 的 `isolation`，`counterexample_path()` 按 `counterexample_format` 解析 DOT 或 SVG。`BugView.vue` 消费 JSON 并由 G6 绘图，修改 DOT 属性时仍须同时检查这两处。
- bug/run 下载按请求创建独立临时 ZIP；前端分别使用 `bug-<id>.zip`、`run-<id>.zip` 文件名。配置写入 localStorage 前移除 `db.password`；Java 与后端的成功/失败归档还会清洗 `db.url` 内嵌凭据，实际运行仍使用当次输入的原始配置。运行列表区分 `Failed` 与 `Stopped`。
- HTTP 请求主要集中在 `frontend/src/api/api.js`；文件上传使用 multipart。后端地址来自 `VITE_BACKEND_URL`，当前 `frontend/.env` 指向 `http://127.0.0.1:8000/`。

## 2. 配置与运行

### 2.1 配置键

| 分组 | 当前键 |
| --- | --- |
| 数据库 | `db.url`、`db.username`、`db.password`、`db.type`、`db.isolation` |
| 负载 | `workload.type`、`workload.skipgeneration`、`workload.history/session/transaction/operation`、`workload.readproportion/key/distribution`、`workload.variable` |
| 已有历史 | `history.path`、`history.type`（`text` 或 `elle`） |
| checker | `checker.isolation`，支持单个值或列表字符串；`checker.timeout_ms`（0 表示不超时）；`checker.elle_plot`（可选，归档 Elle 的 SVG 反例图，需要 graphviz） |
| 输出与性能 | `profiler.enable`、`output.path`、`output.run_id`（仅允许 `[A-Za-z0-9._-]{1,128}`） |

Collector 注册名为 `MYSQL`、`POSTGRES`、`POSTGRES-SAVE`、`H2`、`MARIA`。其中 `POSTGRES` 指向 `collector.postgresql_reproduce`，普通 `collector.postgresql` 使用 `POSTGRES-SAVE`，不要按目录名猜配置。

`checker.isolation` 当前映射：

| 配置值 | 实现 |
| --- | --- |
| `READ_COMMITTED` / `REPEATABLE_READ` / `READ_ATOMICITY` / `TRANSACTIONAL_CAUSAL_CONSISTENCY` | `C4` |
| `SNAPSHOT_ISOLATION` | `PolySI` |
| `SERIALIZABLE` | `SMT_SER` |
| `VIPER_SNAPSHOT_ISOLATION` | `Viper` |
| `POLYSI+_SNAPSHOT_ISOLATION` | `SMT_SI` |
| `ELLE_TRANSACTIONAL_CAUSAL_CONSISTENCY` | `ELLE_TCC`；内部枚举为 `CAUSAL_CONSISTENCY`，Elle 命令使用 `causal-cerone` |

### 2.2 环境与常用命令

- Java 使用 **JDK 11**；前端以 Docker 中的 Node 20.10.0 为基准；Python 使用 **3.10** 并安装 `backend/requirements.txt`，与 CI 基线一致。
- 所有相对路径以仓库根目录为基准。MonoSAT、Viper、Elle、VeriStrong 和数据库有额外依赖，完整验证优先 Linux/容器。

```bash
# Java：JDK 11
mvn -B -DskipTests package
mvn -B -Dtest=MainTest,HistoryLoaderSerializerTest,ElleTest,ConfigParserTest,RuntimeDataSerializerTest test
mvn -B clean test

# 回归套件：逐用例打印 checker、预期与实际 verdict；--probe 只报告不判定
java -cp target/DBTest-1.0-SNAPSHOT-shaded.jar regression.RegressionSuite benchmarks/regression
java -cp target/DBTest-1.0-SNAPSHOT-shaded.jar regression.RegressionSuite benchmarks/regression --probe [--only si-]

# 后端语法与隔离回归
python -m compileall -q backend
python -m unittest backend.test_main

# 前端：在 frontend/ 下执行
npm ci
npm run build

# 正式集成镜像
docker build --no-cache -t isovista .

# 本机完整 Java 测试环境：先构建当前应用镜像，再复用其中的 JDK 11/native 资产
# 不要用 Maven 3.6.3 代替；旧 Surefire 可能报告 BUILD SUCCESS 但实际发现 0 个 JUnit 5 用例
docker build -t isovista:local .
docker build -f scripts/standalone/test-env.Dockerfile -t isovista-test-env:local .
docker run --rm -v "${PWD}:/workspace" -v isovista-m2:/root/.m2 -w /workspace isovista-test-env:local mvn -B clean test
# 本机只有 JDK 25 时上述命令必须走容器；离线加 -o，否则 Maven 会卡在依赖重解析
```

本地 Web 顺序：

1. 在 JDK 11 下打包，并将 `target/DBTest-1.0-SNAPSHOT-shaded.jar` 复制到仓库根目录。
2. 从根目录运行 `python backend/main.py`。
3. 在 `frontend/` 运行 `npm ci` 和 `npm run dev`。
4. 使用专用测试数据库；现有 collector 会创建并清理 `dbtest`，不要指向有数据的业务库。

### 2.3 已记录验证状态

| 项目 | 当前记录 |
| --- | --- |
| 后端语法与回归 | 2026-11-24：`compileall` 通过；`backend.test_main` **18 项通过**，覆盖 URL 内嵌凭据清洗、Stopped、ZIP 异常清理、运行目录隔离、活动运行列表与指定 run 的实时端点 |
| 前端生产构建 | 2026-11-24：Vite build 通过且无 chunk 警告。`chart` 590.13 kB（gzip 197 kB）只由 RunView 懒加载，`graph` 1500.81 kB（gzip 445 kB）只由 BugView 懒加载；两者是 ECharts/G6 运行时本身，`chunkSizeWarningLimit` 提到 1600 kB 并注明原因 |
| Java 全量测试 | 2026-11-24：容器内 Linux JDK 11 `mvn -B -o clean test` 实际执行 **67 项，0 failure、0 error、0 skipped**，其中 `RegressionSuiteTest` 逐条 fork 子进程跑完 12 条检查 |
| 回归套件 | 2026-11-24：8 个用例 12 条检查全部 ok——SI 的 PolySI/SMT_SI/Viper 正反例、SER 的 SMT_SER 正反例、C4 正反例、Elle 正反例。过程中修正两处环境事实：SMT 求解器要求事务号从 0 开始，Elle 缺 graphviz 时会整次崩溃 |
| 后端并行与端到端 | 2026-11-24：容器内 `ISOVISTA_MAX_PARALLEL_RUNS=2` 同时提交两个运行，均 `Finished`，结果目录互不相同、`workspaces/` 运行后清空。修复配置文件相对路径导致 Java 找不到配置、输出目录双层嵌套、活动运行列表 500 三个问题 |
| 真实 MySQL 与 Viper | 2026-11-21：MySQL 9.3 专用 `dbtest` 正向生成/采集成功，PolySI `NO BUG`、Viper `sat: True`；同一已知 SI 异常经 PolySI 与 Viper 均检出（`sat: False`、`bug_count=2`）。修复了 text loader 未提交事务导致 Viper 忽略写操作的问题，普通提交与 aborted write 均有回归测试 |
| 真实 Elle/TCC 核验 | 2026-11-24：真实 elle-cli `causal-cerone` 对合法 list-append 正例返回 `true`，对 internal 违例负例返回 `false`（退出码 1），Java 均按 verdict 接收；C4 对 `yugabyte_tcc.txt` 判定为 `false`。Elle 的 SVG 反例图需要 graphviz，缺失时自动降级为不绘图且不影响 verdict |
| Docker、数据库、真实 Web 端到端 | 2026-11-21：完整 Ubuntu 22.04 镜像已实际构建并启动；Ego Browser 验证真实 MySQL 运行、历史上传、PolySI+Viper 双检出、Runs/Bugs 列表、Run 详情图表、Bug 详情图形组件、Run/Bug ZIP 下载与 Stop。2026-11-24 只重跑了后端容器冒烟，未重跑浏览器端到端 |

## 3. 当前风险

### P1：影响实验与维护

- 已有真实浏览器端到端记录，但没有持久化的前端组件/E2E 自动化；修改可视化拆包、路由或图形组件后必须重跑浏览器冒烟，不能只依赖 Vite build。本轮只重跑了后端容器冒烟。
- `yugabyte_tcc.txt` 是 text RW-register 历史，Elle 使用 list-append EDN；两者没有通用等价转换，当前分别用于 C4 与真实 EDN 核验，不得混用 verdict 口径。
- 反例图能力按 checker 不同：PolySI 与 SMT 输出 DOT，Elle 只能输出 SVG 且需要 graphviz（缺失时自动不绘图），Viper 当前没有反例图。`counterexample_format` 如实记录，Bug 页面可打开不等于该 checker 有冲突图。
- `chart` 与 `graph` 为避免运行时循环初始化错误而保留完整懒加载单元，体积即 ECharts/G6 运行时本身；后续优化必须同时验证 Run/Bug 路由。
- SMT 求解器（`SMT_SI`/`SMT_SER`）的 native 库在同一 JVM 内第二次加载会 SIGSEGV，回归套件因此逐条 fork 子进程；CLI 单次运行内不要对同一历史重复实例化同一 SMT checker。求解器还要求 text 历史的事务号从 0 开始，1 起始的历史会以 `vector::_M_range_check` 崩溃。
- 并行运行只是目录隔离，多个运行仍竞争同一数据库与 CPU；`ISOVISTA_MAX_PARALLEL_RUNS` 保持默认 1，除非明确要做吞吐实验。

## 4. 执行路线

完整任务、依赖和阶段验收只维护在 [11 · 单人实施路线图](docs/plans/11-roadmap.md)。

| 顺序 | 目标 | 专题文档 |
| --- | --- | --- |
| 阶段 0 | JDK 11 基线、已知问题回归、CLI/Web 冒烟 | 本文当前风险 |
| 阶段 1 | 回归历史、统一 checker 结果与 run manifest | 02、06、10 |
| 阶段 2 | 参数矩阵、公平性能对比、可重建图表 | 03、09、10 |
| 阶段 3 | 图数据契约、异常解释与演示闭环 | 08 |
| 阶段 4 | 论文、干净环境复现与答辩交付 | 11 |

**阶段 0 与阶段 1 已完成：回归套件、统一 checker 结果与 run manifest 已落地并验证，当前从阶段 2 开始。** SQL IR、完整插件框架、reducer、历史合成和大规模数据库扩展均不阻塞主线。

修改目录、入口、配置键、API、结果字段或测试命令后，同步更新本文及直接消费者；阶段性流水账和长期设计细节不要写入本文件。
