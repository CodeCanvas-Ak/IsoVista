# AGENTS.md

## 先读这里

- 若根目录存在本地 `NEXT_SESSION_HANDOFF.md`，先读它补充最近会话上下文；该文件通过 `.git/info/exclude` 忽略，不作为长期事实的单源。
- 开始任务前先读 [ROADMAP.md](ROADMAP.md) 的「代码地图」「配置与运行」「当前风险」「执行路线」。它是当前代码事实的唯一常驻入口，代码核对基线记录在该文件顶部。
- [docs/plans/](docs/plans/) 是目标态设计稿，不代表功能已经实现；实施顺序只看 [11-roadmap](docs/plans/11-roadmap.md)。
- 实现状态以源码和测试为准。外部 checker、数据库、原生库或 Docker 未实际验证时，明确写「未验证」。

## 按任务定位

| 任务 | 先读 | 主要改动位置 |
| --- | --- | --- |
| 主流程、配置、批量实验 | ROADMAP 的「配置与运行」；目标设计再看 `docs/plans/10-experiment-automation.md` | `src/main/java/Main.java`、`config/Config.java`、`util/ConfigParser.java` |
| 新增或修改 checker | `docs/how-to-implement-a-new-checker.md`；目标设计再看 `docs/plans/06-history-checkers.md` | `checker/**`、对应 `src/test/java/*Verifier*` |
| 新增数据库 | `docs/how-to-check-a-new-database.md` | `collector/**`、`pom.xml`、`scripts/standalone/` |
| 历史格式与样例 | ROADMAP 的「代码地图」；目标设计再看 `docs/plans/02-benchmark-suite.md` | `history/**`、`examples/histories/` |
| 后端、运行队列或 API | ROADMAP 的「FastAPI 与 Vue 契约」 | `backend/main.py`、`frontend/src/api/` |
| 前端展示 | `docs/plans/08-visualization.md` 及当前组件 | `frontend/src/components/` |
| 实验与论文图表 | `docs/plans/09-performance-analysis.md`、`docs/plans/10-experiment-automation.md` | 实验配置、结果数据与绘图脚本 |

## 全仓约束

1. 从仓库根目录运行程序。Java、后端和脚本均使用相对路径，包括 `config.properties`、`current/`、`result/`、`third_party/` 和根目录 shaded JAR。
2. 不手改 `third_party/` 内的 Viper、MonoSAT 或 vendored 文件；升级依赖单独评审，不与业务改动混合。
3. 修改配置键、运行结果字段或 API 时，同步核对 Java、FastAPI、Vue 三方消费者，并更新 ROADMAP。
4. 不提交 `target/`、`node_modules/`、`dist/`、`current/`、`result/`、缓存或本地环境覆盖文件。
5. 不删除或弱化失败测试来换取通过，不用未运行的检查声称验证完成。
6. 未经用户要求，不执行 commit、push、发布或历史改写。

## 常用命令

```bash
# Java：需要 JDK 11
mvn -B -DskipTests package
mvn -B -Dtest=HistoryLoaderSerializerTest test
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

JDK 25 与项目的 Lombok 1.18.26 不兼容，会表现为大量 Lombok 生成成员缺失。先用 JDK 11 建立基线，不要误报为代码回归。

## 开发约定（oil-dev）

- 快测：`mvn -B -Dtest={target} test`（JDK 11）；前端改完后在 `frontend/` 跑 `npm run build`。
- 重测：`mvn -B clean test`；涉及 Web 流程时补后端启动与 API 冒烟。
- 门禁：`mvn -B -DskipTests package`、`python -m compileall -q backend`、`cd frontend && npm ci && npm run build`；发布前再跑 `docker build --no-cache -t isovista .`。
- 高风险：`src/main/java/checker/**`、`src/main/java/Main.java`、`backend/main.py`、`Dockerfile`、`config.properties`。
- 禁改：`third_party/**`。
