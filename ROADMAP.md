# IsoVista 代码地图与毕设路线图

> 盘点日期：2026-11-20
>
> 代码基线：`0f5719c0eb781a04e53932b1e2da243403e1eb92`（`main`）
>
> 阅读范围：第一方 Java、Python、Vue 源码，配置、测试、CI、启动脚本、现有规划文档；第三方子模块内部实现不作为第一方代码展开。
>
> 状态约定：未特别标注的内容为上述基线的代码事实；「规划」「风险」「待验证」不得当作已实现能力。

## 1. 给后续 Agent 的 60 秒导览

IsoVista 当前是一个**数据库事务历史生成、采集、隔离级别验证与结果可视化工具**。它有三条实际组成链路：

1. Java CLI 是核心：生成或加载历史，调用 collector 在数据库中执行，再选择一个或多个 checker 验证。
2. FastAPI 是文件与子进程编排层：维护运行队列，写入 `config.properties`，通过 `java -jar` 启动核心，读取 `current/` 与 `result/`。
3. Vue 是展示层：配置实验、查看运行与 bug、解析 DOT 图、展示 profile 和运行时指标。

**毕设推荐主线**：优先做成「多隔离级别检查器的统一、可复现实验与可视化平台」。先把基线、回归样例、实验矩阵、结果 manifest 和论文图表做扎实；历史合成、复杂 reducer 和新负载生成作为后续扩展，不要同时铺开全部规划。

继续工作前只需按任务阅读本文件对应小节及 `docs/plans/` 的一篇专题文档。修改目录、入口、配置键、API 或测试命令后，必须同步更新本文件。

## 2. 整理后的仓库结构

```text
IsoVista/
├── src/
│   ├── main/java/
│   │   ├── Main.java                 # CLI 与实验主流程
│   │   ├── checker/                 # C4、PolySI、Viper、Elle、VeriStrong
│   │   ├── collector/               # 数据库执行与结果采集
│   │   ├── config/Config.java       # 配置键与默认路径
│   │   ├── generator/               # general、TPCC 历史生成
│   │   ├── history/                 # 历史模型、loader、serializer
│   │   └── util/                    # 配置映射、性能与运行时记录
│   ├── main/resources/              # 日志配置、原生动态库
│   └── test/java/                   # JUnit 5 测试
├── backend/
│   ├── main.py                      # FastAPI、运行队列、结果读取
│   └── requirements.txt
├── frontend/
│   ├── src/api/                     # 与 FastAPI 对应的请求封装
│   ├── src/components/              # 配置、运行、bug、图与侧栏
│   ├── package.json
│   └── package-lock.json            # 锁定前端依赖，支持 npm ci
├── docs/
│   ├── plans/                       # 01～11 目标态设计稿
│   ├── images/                      # README 与使用文档配图
│   ├── how-to-check-a-new-database.md
│   ├── how-to-implement-a-new-checker.md
│   └── use-cases.md
├── examples/histories/              # 已收集的 bug 历史样例
├── third_party/                     # Viper、MonoSAT、Elle CLI；勿手改
├── scripts/standalone/              # MySQL、PostgreSQL Docker Compose
├── .github/workflows/               # Maven 测试、Docker 镜像发布
├── config.properties                # 本地默认配置，含待替换示例路径
├── Dockerfile
├── run.sh                           # 容器内前端与后端启动脚本
├── pom.xml                          # Java 11、依赖与 shaded JAR
├── AGENTS.md                        # 极简 Agent 路由与硬约束
└── ROADMAP.md                       # 当前代码事实、验证记录与执行路线
```

本次目录归位关系：`ai/plan → docs/plans`、`images → docs/images`、`history/bugs → examples/histories`、`resource → third_party`。原 `ai/meta/project-overview.md` 已合并并改写为本文件，避免两份代码导览互相漂移。

### 文档路由

| 要做什么 | 阅读顺序 |
| --- | --- |
| 了解总体目标与模块边界 | 本文件 → `docs/plans/01-vision-and-architecture.md` |
| 建回归历史与基准套件 | `docs/plans/02-benchmark-suite.md` |
| 改负载、执行或采集 | `docs/plans/03-workload-generator.md`、`04-executor-and-logger.md` |
| 改 checker | `docs/plans/06-history-checkers.md` + 对应实现和测试 |
| 做 bug 缩减 | `docs/plans/07-bug-reducer.md` |
| 做图和展示 | `docs/plans/08-visualization.md` |
| 做性能对比与自动实验 | `docs/plans/09-performance-analysis.md`、`10-experiment-automation.md` |
| 看原始阶段规划 | `docs/plans/11-roadmap.md`；执行任务以本文件第 8 节为准 |

## 3. Java 核心调用链

### 3.1 主入口与运行流程

核心入口是 `src/main/java/Main.java`：

- `Main.call()`：从 CLI 第一个参数读取 properties 文件。
- `Main.run()`（`Main.java:48`）：扫描 `collector` 与 `checker` 包，通过每个实现的静态 `NAME` 注册（`Main.java:52-68`）。
- `Main.runOneShot`（`Main.java:111`）：执行一个批次中的全部历史。
- 生成模式（`Main.java:114-130`）：`GeneralGenerator` 生成历史，再由 collector 在数据库中执行并回填结果。
- 加载模式（`Main.java:131-134`）：按 `history.type` 选择 loader，从 `history.path` 加载。
- 验证模式（`Main.java:137-177`）：一个历史可依次交给多个 checker；失败时写入 `current/bug_N/` 下的 `bug_hist.txt` 与 `conflict.dot`。
- 参数扫描（`Main.java:200-218`）：`workload.variable` 指定变量名，对应列表值逐批覆盖配置。
- 收尾（`Main.java:240`）：`RuntimeDataSerializer` 将配置、日志、profile、bug 等输出到 `output.path`，默认 `./result`。

CLI 的 `main()` 最后会清理 `./current`；运行中的 Web 后端则依赖该目录提供实时日志和进度，因此不要用另一个 CLI 进程同时操作同一工作目录。

### 3.2 配置与隔离级别映射

`config/Config.java` 定义实际配置键；根目录 `config.properties` 使用同一套点号键：

| 配置键 | 作用 |
| --- | --- |
| `db.url` / `db.username` / `db.password` | JDBC 连接 |
| `db.type` | collector 注册名 |
| `db.isolation` | JDBC 事务隔离级别 |
| `workload.skipgeneration` | `false` 生成并采集；`true` 加载已有历史 |
| `workload.history/session/transaction/operation` | 历史、会话、事务、操作规模 |
| `workload.readproportion/key/distribution` | 读写比例、键空间、键分布 |
| `workload.variable` | 可选的批量扫描变量 |
| `history.path` / `history.type` | 历史文件与格式，当前为 `text` 或 `elle` |
| `checker.isolation` | 单个隔离配置或列表，如 `['SNAPSHOT_ISOLATION']` |
| `profiler.enable` / `output.path` | 性能记录与结果目录 |

`util/ConfigParser.java` 是隔离配置到 checker 的唯一映射入口：

| `checker.isolation` 值 | 实际实现 |
| --- | --- |
| `READ_COMMITTED` | `checker.C4.C4` |
| `REPEATABLE_READ` | `checker.C4.C4` |
| `READ_ATOMICITY` | `checker.C4.C4` |
| `TRANSACTIONAL_CAUSAL_CONSISTENCY` | `checker.C4.C4`，内部可使用 `C4List` |
| `SNAPSHOT_ISOLATION` | `checker.PolySI.PolySI` |
| `SERIALIZABLE` | `checker.VeriStrong_SER.SMTCheckerSER` |
| `VIPER_SNAPSHOT_ISOLATION` | `checker.Viper.Viper` |
| `POLYSI+_SNAPSHOT_ISOLATION` | `checker.VeriStrong_SI.SMTCheckerSI` |
| `ELLE_TRANSACTIONAL_CAUSAL_CONSISTENCY` | `checker.elle.Elle` |

新增 checker 时不能只写类：还要提供无歧义的 `NAME`、`Properties` 构造函数、隔离映射、输出与 profile 行为，以及正反向测试。

### 3.3 模块责任与代码锚点

| 模块 | 已实现责任 | 后续修改先读 |
| --- | --- | --- |
| 历史模型 | `History → Session → Transaction → Operation`；Lombok 生成主要访问器 | `history/History.java`、`Session.java`、`Transaction.java`、`Operation.java` |
| 历史加载 | `text` 解析五元组；`elle` 解析 list-append 语义 | `history/loader/`、`util/HistoryLoaderFactory.java` |
| 历史输出 | 文本输出；Elle、Elle 文本和 Viper 格式由 checker 路径使用 | `history/serializer/`；通用工厂目前只开放 `text` |
| 负载生成 | uniform、zipf、hotspot 键分布与 Bernoulli 读写比例 | `generator/general/GeneralGenerator.java` |
| 数据采集 | JDBC 建表、并发执行、事务重试、实际读值回填 | `collector/Collector.java`、`DBClient.java` 及数据库子包 |
| C4 | 已知图、实时顺序与 TAP 判定；含 Elle list 变体 | `checker/C4/C4.java`、`C4List.java`、`graph/` |
| PolySI | `KnownGraph`、剪枝、约束构造、MonoSAT 求解 | `checker/PolySI/verifier/`、`graph/` |
| VeriStrong | 通过 JNI 调用图编码与 MonoSAT | `checker/VeriStrong_SI/`、`VeriStrong_SER/` |
| 外部 checker | Viper 调 Python，Elle 调独立 JAR，路径在 `third_party/` | `checker/Viper/Viper.java`、`checker/elle/Elle.java` |
| 运行时指标 | 时间、内存、PID、阶段 CSV、结果目录打包 | `util/Profiler.java`、`Runtime*Recorder.java`、`RuntimeDataSerializer.java` |

collector 注册名以源码为准：`MYSQL`、`POSTGRES`、`POSTGRES-SAVE`、`H2`、`MARIA`。其中 `POSTGRES` 对应 `collector.postgresql_reproduce`，普通 `collector.postgresql` 使用 `POSTGRES-SAVE`，不要按目录名猜配置值。

## 4. FastAPI 与 Vue 的真实契约

### 4.1 后端不是 Java 库封装

`backend/main.py` 的关键事实：

- `dbtest_path = DBTest-1.0-SNAPSHOT-shaded.jar`，所以本地启动前必须把 shaded JAR 放到后端进程工作目录；按现有文档约定从仓库根目录启动。
- `/run` 只把配置放入 `run_queue`；单个后台 `run_worker` 线程顺序消费。
- worker 写入固定的 `config.properties`，用 `subprocess.Popen(["java", "-jar", ...])` 启动 Java，并等待进程结束。
- `BugStore` 与 `RunStore` 启动时扫描 `result/`；运行中的实时数据来自 `current/`。
- `/stop` 使用全局停止事件，并尝试清理 `current/`。它不是多租户、多并发任务系统。
- 历史上传入口为 `/upload/`；前端通过 multipart 表单上传，不是 JSON。

主要 API 分组：

| 分组 | 端点 |
| --- | --- |
| 运行 | `POST /run`、`PUT /stop`、`GET /run_list`、`GET /current_run_id` |
| bug | `GET /bug_list`、`GET /view/{bug_id}`、`POST /bug/tag`、`GET /download/{bug_id}` |
| 运行结果 | `GET /run_profile/{run_id}`、`GET /runtime_info/{run_id}`、`GET /runtime_stage/{run_id}`、`GET /download_run/{run_id}` |
| 实时状态 | `GET /current_log`、`GET /current_profile`、`GET /current_runtime_info`、`GET /current_runtime_stage` |
| 其他 | `GET /history_count`、`GET /bug_count`、`POST /upload/` |

### 4.2 前端定位

- `Configuration.vue`：构造数据库、负载、checker、profiler 配置并启动运行。
- `RunList.vue` / `RunView.vue`：运行列表、日志、进度、ECharts 性能图、运行包下载。
- `BugList.vue`：bug 列表与标签。
- `BugView.vue`：请求 DOT 文本，在前端解析节点和边，再用 AntV G6 绘图。
- `api/api.js`：除文件上传外，HTTP 路径统一在这里维护。
- `utils/request.js`：Axios 实例，后端地址来自 `VITE_BACKEND_URL`，默认 `http://127.0.0.1:8000/`。

修改 API 时同时检查以上消费者；修改 DOT 格式时还要检查 `/view/{bug_id}` 和 `BugView.vue` 的文本解析，不要只验证 Java 输出文件已经生成。

## 5. 测试、构建与运行

### 5.1 环境前提

- Java：**JDK 11**。`pom.xml` 的 source/target 都是 11，Lombok 为 1.18.26。
- Node：Docker 使用 20.10.0；前端锁文件已纳入版本控制。
- Python：安装 `backend/requirements.txt`；Viper 另有自身依赖。
- 完整算法测试与发布：优先 Linux。MonoSAT、VeriStrong、Viper、Elle 和数据库具有额外原生或外部依赖。
- 所有相对路径都以仓库根目录为基准；不要从 `backend/` 内随意启动后仍假设路径不变。

### 5.2 命令分层

```bash
# Java 编译与打包，不运行测试
mvn -B -DskipTests package

# 秒级、无数据库的局部回归
mvn -B -Dtest=HistoryLoaderSerializerTest test

# 单个算法用例示例
mvn -B -Dtest=TestSIVerifier#readCommitted test

# 全量 Java 测试；需要匹配的原生环境
mvn -B clean test

# 后端语法检查
python -m compileall -q backend

# 前端，先进入 frontend/
npm ci
npm run build

# 正式集成镜像
docker build --no-cache -t isovista .
```

Java 当前有 33 个 `@Test`：SI 16、SER 16、文本历史 round-trip 1。`TestLoader.java` 只有加载辅助方法，没有被自动执行的测试注解。后端和前端目前没有自动化测试目录。

本地 Web 运行的前置条件与顺序：

1. 在 JDK 11 下执行 `mvn -DskipTests package`。
2. 将 `target/DBTest-1.0-SNAPSHOT-shaded.jar` 复制到仓库根目录。
3. 从根目录执行 `python backend/main.py`。
4. 在 `frontend/` 执行 `npm ci && npm run dev`。

### 5.3 本次实际验证记录

| 检查 | 结果 | 说明 |
| --- | --- | --- |
| `python -m compileall -q backend` | 通过 | 仅证明 Python 语法可编译，不代表 API 冒烟通过 |
| 前端安装锁文件并 `npm run build` | 通过 | Vite 5.1.1；3214 个模块完成构建 |
| 前端构建告警 | 待处理 | 两处 CSS 使用 `//` 注释；主 JS 约 3.50 MB，存在大包告警 |
| `mvn -B -DskipTests package` | 当前环境失败 | 本机只有 JDK 25；Lombok 1.18.26 未生成 `log`、getter、constructor 等成员，导致编译失败 |
| Java 单元测试 | 未执行成功 | 受同一 JDK/Lombok 基线问题阻塞；需在 JDK 11 重跑 |
| Docker 构建 | 未执行 | 当前环境没有 Docker CLI |
| 数据库、Viper、Elle、真实 Web 端到端 | 未执行 | 本次是结构与文档整理，不伪报外部依赖验证通过 |

## 6. 当前风险与先做事项

### P0：先恢复可重复基线

1. 用 JDK 11 跑通编译、局部测试和全量测试，记录真实失败清单。当前 JDK 25 失败是工具链不兼容，不应通过大规模改业务代码解决。
2. 在干净 Linux 环境验证 `mvn clean test` 与 Docker build；CI 配置存在不等于最近通过。
3. 用一个已知文本历史完成 CLI 冒烟，再完成一次 FastAPI 队列运行，确认 JAR、配置、`current/`、`result/` 的相对路径完全一致。

### P1：会直接影响实验可信度的问题

- `Main.java:131` 对 `history.path` 整体调用 `toLowerCase()`，会破坏大小写敏感路径；只应对格式或枚举做大小写归一化。
- `Main.java:168` 用 `==` 比较历史类型字符串，可能导致失败历史没有按预期序列化；应使用值比较并补回归测试。
- 根目录 `config.properties` 仍有外部机器绝对路径和本地数据库口令示例。后续应提供无个人路径的示例配置，同时保持实际本地覆盖文件不进版本库。
- 后端所有运行共享固定的配置与 `current/` 路径；队列、停止、重启恢复和重复运行需要明确状态契约后再扩展并发。
- SI/SER 测试依赖 MonoSAT；后端与前端没有自动化测试，不能直接以「构建成功」替代「实验闭环成功」。

### P2：可维护性与体验

- `backend/main.py` 566 行，存储、进程、文件解析和 API 混在一处；有测试后再按边界拆分，不做无保护的大规模重写。
- DOT 在前端靠文本解析，后续适合由后端输出经过 schema 验证的图数据，同时保留原始 DOT 下载。
- 修复 CSS 注释，并在功能稳定后做路由级懒加载和依赖拆包。
- `docs/plans/` 中的目录、协议和性能预算是目标设计，实施时要回写实际字段与实际测量结果。

## 7. 推荐毕设边界

### 必做主线

- 多 checker 统一配置与运行。
- 可维护的回归历史集，每个样例有预期结论、语义标签和来源。
- 可重复实验矩阵：checker × isolation × workload × database。
- 每次运行保存配置、代码版本、依赖版本、原始日志、结构化结果与环境信息。
- 统一性能与正确性对比，图表能从原始结果重新生成。
- 一个可演示的 Web 闭环和一份干净环境复现说明。

### 有主线后再选做

- history synthesizer、feedback workload generator：适合增强「发现更多 bug」的论点。
- bug reducer：适合增强「定位与解释」的论点，但不同 checker 的反馈语义必须先统一。
- 新数据库或新 checker：只有在能形成明确对照实验时增加，不用数量代替深度。

## 8. 分阶段执行路线

### 阶段 0：可重复工程基线

- 固定 JDK 11、Node 20 与 Python 环境说明。
- 完成第 6 节 P0，建立首次可信的构建与测试记录。
- 将当前 `config.properties` 的示例路径、口令和输出目录整理为可复现约定。
- 为第 6 节两个路径／字符串问题各补一个失败后再通过的测试。

**验收**：干净检出后能按 README 完成 Java 构建、局部测试、前端构建和一次文本历史 CLI 检查；结果目录可定位且不含个人机器路径。

### 阶段 1：回归历史与统一实验记录

- 按 `docs/plans/02-benchmark-suite.md` 建 `regression/` 与 manifest；现有 `examples/histories/` 作为原始样例来源，不直接冒充完整回归集。
- 给每个历史记录预期 accept/reject、异常语义、checker 适用性和最小复现说明。
- 定义 run manifest：run id、seed、配置、Git revision、环境、起止时间、checker 结论与产物路径。
- 补后端 API 冒烟和至少一个端到端样例。

**验收**：同一命令重复运行同一回归集，结论一致，产物可追踪到代码版本和配置；失败不会被记成 accept 或 reject。

### 阶段 2：实验矩阵与论文图表

- 按 `09`、`10` 文档实现 checker × workload × isolation 矩阵，原始结果采用稳定 schema。
- 分离正确性、异常分类、延迟、吞吐、内存与 checker 开销；不重复执行同一条件的不同指标。
- 图表脚本只读取原始结果，可一键重建论文表格和图。
- 在相同硬件、采样条件和负载下做对比，同时报告绝对值与相对差异。

**验收**：论文中的每个核心数字都能回链到原始文件和生成命令；换一台机器能得到同趋势且过程可审计。

### 阶段 3：展示与解释增强

- 统一 bug、run、profile 与图数据契约，减少前端文本猜测。
- 完善冲突图、关键事务定位和运行状态；补 reducer 或 synthesizer 时严格限制在选定主线内。

**验收**：演示者能从配置运行走到异常解释，原始历史、图、配置和日志均可下载；新增结论有回归测试。

### 阶段 4：论文与答辩交付

- 论文实验章节引用阶段 2 的可重建结果。
- 准备系统架构图、核心算法流程、对照实验、失效案例和局限说明。
- 保留一份干净环境复现手册、演示数据和预构建产物。

**验收**：另一位同学只按仓库文档即可复现核心实验和演示，不需要原作者口头补充路径或环境细节。

## 9. 下一张具体任务单

**题目：在 JDK 11 下建立可信基线并修复历史路径处理。**

1. 执行 `mvn -B clean test`，保存通过数、失败数和首个根因。
2. 执行 `mvn -B -Dtest=HistoryLoaderSerializerTest test`，建立秒级回归入口。
3. 为 `history.path.toLowerCase()` 和历史类型 `==` 比较各写一个最小复现。
4. 只修复这两处及对应测试，不顺带升级 Java、Lombok、算法或 Web 架构。
5. 再跑局部测试、全量测试、前端构建和一次 CLI 冒烟；把结果回填到本文件第 5.3 节。

完成这张任务单后，再进入阶段 1，不要从全部 11 份规划同时开工。
