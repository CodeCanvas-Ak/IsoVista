# IsoVista 代码地图

> 代码核对基线：`85ad47f35e7f61039823f010dce937a70adb3c11`（2026-11-21）。
>
> 本文只维护当前代码事实、配置与运行、已知风险和执行入口。目标态设计在 [docs/plans/](docs/plans/README.md)，完整单人路线在 [11-roadmap](docs/plans/11-roadmap.md)；规划内容不得当作已实现能力。

## 1. 代码地图

### 1.1 三条主链路

- **Java CLI 核心**：生成或加载历史，调用 collector 在数据库执行，再由一个或多个 checker 验证。
- **FastAPI 编排层**：串行消费运行队列，写入配置并启动 shaded JAR，读取 `current/` 与 `result/`。
- **Vue 展示层**：配置实验、查看运行与 bug，使用 G6 展示依赖图、ECharts 展示指标。

```text
properties / uploaded history
             │
             ▼
Generator / Loader ──▶ Collector ──▶ History ──▶ Checkers
                           │                       │
                           └──── observed values   ├─▶ current/bug_N
                                                   └─▶ profiles + result
FastAPI：queue → config.properties → java -jar → current/ + result/
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
| `util/` | 配置映射、profile、运行时记录、结果打包 | `Profiler`、`Runtime*Recorder`、`RuntimeDataSerializer` |
| `backend/main.py` | 队列、进程、存储、API 与 DOT 解析 | 端点及其前端消费者 |
| `frontend/src/` | 配置、运行、bug、图与请求封装 | `api/api.js`、`components/` |
| `src/test/java/` | SI/SER 算法、主流程、Elle 适配、结果归档与历史 round-trip | `TestSIVerifier`、`TestSERVerifier`、`MainTest`、`ElleTest`、`RuntimeDataSerializerTest`、`HistoryLoaderSerializerTest` |
| `backend/test_main.py` | 后端存储、失败记录、DOT 与下载的隔离回归 | 使用临时目录，禁止真实 worker 启动 |
| `examples/histories/` | SI、SER、TCC 原始历史样例 | 对应预期结论需另行核验 |
| `scripts/standalone/` | MySQL、PostgreSQL 本地 Compose | 对应 compose 文件 |
| `third_party/` | Viper、MonoSAT、Elle CLI 等资产 | 不手改 |

### 1.3 Java 主流程

1. `Main.run()` 先扫描 `collector` 并校验 `db.type`，未知类型会在 checker 扫描和原生库初始化前失败；实现类以静态 `NAME` 注册，并需要 `Properties` 构造函数。
2. `workload.skipgeneration=false` 时由 `GeneralGenerator` 生成历史，再由 collector 执行并回填实际读值；为 `true` 时按 `history.type` 加载 `history.path`。类型按值且忽略大小写解析，路径保留原始大小写。
3. 一个历史可依次运行多个 checker。返回 `false` 时写入 `current/bug_N/`；text 历史保存为 `bug_hist.txt`，Elle 历史保存为 `bug_hist.edn`。checker 未实现 DOT 输出时保留历史并继续收尾，不伪造 `conflict.dot`。
4. `workload.variable` 支持单维批量扫描；每次覆盖一个 `workload.*` 值。
5. `RuntimeDataSerializer` 将配置、日志、profile 和 bug 等写入 `output.path`，默认 `./result`；归档配置使用副本并移除 `db.password`，文件移动失败会向上传播，不再生成看似完整的成功结果。

CLI `main()` 仅在退出码为 0 时清理 `./current`，失败时保留现场供后端归档和排障。Web 运行期间依赖该目录展示实时状态，不要在同一工作目录并行启动另一个 CLI。

### 1.4 FastAPI 与 Vue 契约

- 后端从工作目录读取 `DBTest-1.0-SNAPSHOT-shaded.jar`，因此按仓库约定从根目录启动。
- `/run` 只入队，单个 worker 顺序执行；worker 写固定 `config.properties`，所有运行共享 `current/`。
- Java 启动异常和非零退出会写入 `result/run_<uuid>/metadata.json`，状态为 `Failed` 并保留 `error`；用户停止记为 `Stopped`。`/stop` 在无活动进程时返回 409，进程清理由 worker 收尾。
- `RunStore`、`BugStore` 每次扫描都重建计数与索引，损坏或字段不全的 metadata 会记录并跳过；实时信息来自 `current/`。共享目录意味着 `/stop` 仍不是多租户任务隔离机制。
- `/view/{bug_id}` 使用 pydot 解析 DOT，返回 nodes/edges JSON；缺失图、缺失 `relate_to` 等可选属性或解析失败会降级为空图和说明。`BugView.vue` 消费 JSON 并由 G6 绘图，修改 DOT 属性时仍须同时检查这两处。
- bug/run 下载按请求创建独立临时 ZIP；前端分别使用 `bug-<id>.zip`、`run-<id>.zip` 文件名。配置写入 localStorage 以及成功/失败结果归档前都会移除 `db.password`，实际运行仍使用当次输入的口令；运行列表区分 `Failed` 与 `Stopped`。
- HTTP 请求主要集中在 `frontend/src/api/api.js`；文件上传使用 multipart。后端地址来自 `VITE_BACKEND_URL`，当前 `frontend/.env` 指向 `http://127.0.0.1:8000/`。

## 2. 配置与运行

### 2.1 配置键

| 分组 | 当前键 |
| --- | --- |
| 数据库 | `db.url`、`db.username`、`db.password`、`db.type`、`db.isolation` |
| 负载 | `workload.type`、`workload.skipgeneration`、`workload.history/session/transaction/operation`、`workload.readproportion/key/distribution`、`workload.variable` |
| 已有历史 | `history.path`、`history.type`（`text` 或 `elle`） |
| checker | `checker.isolation`，支持单个值或列表字符串 |
| 输出与性能 | `profiler.enable`、`output.path` |

Collector 注册名为 `MYSQL`、`POSTGRES`、`POSTGRES-SAVE`、`H2`、`MARIA`。其中 `POSTGRES` 指向 `collector.postgresql_reproduce`，普通 `collector.postgresql` 使用 `POSTGRES-SAVE`，不要按目录名猜配置。

`checker.isolation` 当前映射：

| 配置值 | 实现 |
| --- | --- |
| `READ_COMMITTED` / `REPEATABLE_READ` / `READ_ATOMICITY` / `TRANSACTIONAL_CAUSAL_CONSISTENCY` | `C4` |
| `SNAPSHOT_ISOLATION` | `PolySI` |
| `SERIALIZABLE` | `SMT_SER` |
| `VIPER_SNAPSHOT_ISOLATION` | `Viper` |
| `POLYSI+_SNAPSHOT_ISOLATION` | `SMT_SI` |
| `ELLE_TRANSACTIONAL_CAUSAL_CONSISTENCY` | `ELLE_TCC`；内部枚举为 `SNAPSHOT_ISOLATION`，但 Elle 命令实际使用 `causal-cerone`，见当前风险 |

### 2.2 环境与常用命令

- Java 使用 **JDK 11**；前端以 Docker 中的 Node 20.10.0 为基准；Python 使用 **3.10** 并安装 `backend/requirements.txt`，与 CI 基线一致。
- 所有相对路径以仓库根目录为基准。MonoSAT、Viper、Elle、VeriStrong 和数据库有额外依赖，完整验证优先 Linux/容器。

```bash
# Java：JDK 11
mvn -B -DskipTests package
mvn -B -Dtest=MainTest,HistoryLoaderSerializerTest,ElleTest,RuntimeDataSerializerTest test
mvn -B clean test

# 后端语法与隔离回归
python -m compileall -q backend
python -m unittest backend.test_main

# 前端：在 frontend/ 下执行
npm ci
npm run build

# 正式集成镜像
docker build --no-cache -t isovista .
```

本地 Web 顺序：

1. 在 JDK 11 下打包，并将 `target/DBTest-1.0-SNAPSHOT-shaded.jar` 复制到仓库根目录。
2. 从根目录运行 `python backend/main.py`。
3. 在 `frontend/` 运行 `npm ci` 和 `npm run dev`。
4. 使用专用测试数据库；现有 collector 会创建并清理 `dbtest`，不要指向有数据的业务库。

### 2.3 已记录验证状态

| 项目 | 当前记录 |
| --- | --- |
| 后端语法与回归 | 2026-11-21：`compileall` 通过；`backend.test_main` 12 项通过 |
| 前端生产构建 | 2026-11-21：`npm ci` 与 Vite build 通过；已清理 CSS 注释警告，仍有既有大 chunk 警告 |
| Java 定向回归、打包与 CLI | 2026-11-21：Linux JDK 11.0.33 下 10 项定向测试通过，shaded JAR 打包通过；未知数据库按预期非零退出，不再先触发 native 错误，失败时保留 `current/` |
| Java 全量测试 | 已实际执行 `mvn -B clean test`，但验证环境缺少 `libmonosat.so`，23 个 SI/SER solver 用例以 `UnsatisfiedLinkError` 阻断；不得记为全量通过 |
| Docker、数据库、真实外部 checker、真实 Web 端到端 | 当前记录为未验证；Elle 单元测试不代替真实 elle-cli 验证 |

## 3. 当前风险

### P0：先补齐可信基线

- JDK 11 定向回归和打包已建立记录；仍需在具备 MonoSAT 原生库的环境重跑全量 SI/SER 测试，不要把 JDK 25 的 Lombok 生成成员缺失或 native 环境缺失误报为业务断言失败。
- `ConfigParser` 将 Elle TCC 配置映射到 `SNAPSHOT_ISOLATION` 枚举，与 TCC 名称不一致；Elle 命令实际使用 `causal-cerone`。需用 TCC 样例确认配置、日志与结果口径后修正，不能只改显示名称。

### P1：影响实验与维护

- 后端运行共享固定配置和 `current/`，并行运行会互相污染；并发扩展前必须先隔离工作目录。
- 后端已有 12 项隔离单元回归，但仍缺真实 Web 端到端和前端组件自动化；构建成功不等于实验闭环成功。
- DOT 缺文件、缺可选属性和解析失败已有空图降级，复杂图的正确性仍需端到端核验；`Elle.outputDotFile()` 当前未实现，只能保证历史归档，不能宣称具备可视化闭环。
- 当前 artifact 尚未统一记录代码版本、输入哈希、环境和全部非结论状态，论文对比前必须补齐。

## 4. 执行路线

完整任务、依赖和阶段验收只维护在 [11 · 单人实施路线图](docs/plans/11-roadmap.md)。

| 顺序 | 目标 | 专题文档 |
| --- | --- | --- |
| 阶段 0 | JDK 11 基线、已知问题回归、CLI/Web 冒烟 | 本文当前风险 |
| 阶段 1 | 回归历史、统一 checker 结果与 run manifest | 02、06、10 |
| 阶段 2 | 参数矩阵、公平性能对比、可重建图表 | 03、09、10 |
| 阶段 3 | 图数据契约、异常解释与演示闭环 | 08 |
| 阶段 4 | 论文、干净环境复现与答辩交付 | 11 |

**当前只从阶段 0 开始。** SQL IR、完整插件框架、reducer、历史合成和大规模数据库扩展均不阻塞主线。

修改目录、入口、配置键、API、结果字段或测试命令后，同步更新本文及直接消费者；阶段性流水账和长期设计细节不要写入本文件。
