# LocalPerks 回归与压测

`frontend-regression.cjs` 为前端回归；Java 功能回归位于 `src/test/java`。

本目录其余工具用于本机隔离压测，默认使用 MySQL 13306、Redis 16379、RocketMQ 19876/20911，
不能指向开发库或生产库。`benchmark-start.ps1` 创建独立数据目录，
`benchmark-fixtures.py init` 在新库导入基础表及 V2/V3/V4，不覆盖已有测试库。
脚本中的登录令牌和 Redis 密码仅为本地测试夹具。

## 工具分组

- `benchmark-*`、`http-read-benchmark.py`：隔离依赖、测试数据、HTTP 负载和统计。
- `keepalive-probe.py`、`nginx-upstream-failure.ps1`：连接复用和上游故障响应验证。
- `optimization-*`、`BenchmarkCloseLoad.java`：2026-10-28 优化前后对照及后台关单负载。
- `cache-ab-*`：同构环境下开启/关闭 L1 缓存的配对测试。
- `prelaunch-*`：2026-10-27 的上线前检查及原始样本核验。

这些工具保留了当时的目录名、阶段顺序及 Windows 本机运行时路径，执行前需逐一检查
JDK、Maven、Python、Node、MySQL、Nginx、RocketMQ 路径与端口。
`cache-ab-app.ps1` 校验历史 JAR 哈希；`optimization-*` 和 `prelaunch-*` 含历史阶段及产物依赖，
不能把它们当成任意工作区上的一键启动脚本。缺少历史产物时应准备新的隔离实验并使用新的运行目录，
不能覆盖旧证据或跳过版本校验。

## 结果与产物

- [订单批量落库与连接优化](../output/benchmarks/localperks-optimization-2026-10-28/REPORT.md)
- [L1 缓存配对测试](../output/benchmarks/localperks-cache-ab-2026-10-28/REPORT.md)
- [库存回补发件箱回归](../output/validation/stock-restore-2026-10-29/REPORT.md)

2026-10-30 按用户要求清理了隔离压测数据库、Redis 持久化、消息存储、旧 JAR、原始请求
和完整日志；`output/benchmarks/` 及上述 validation 目录仅保留过程与结果 Markdown 文档。
操作过程见 `output/benchmarks/压测操作过程.md`，汇总见 `output/benchmarks/压测结果汇总.md`。
报告中的历史原始数据路径已不可用，不能直接重跑依赖旧 JAR/环境的历史脚本。
`benchmark-start.ps1` 的 RocketMQ 参数现已修正为 `mappedFileSizeCommitLog=67108864`；
历史误写的 `mapedFileSizeCommitLog` 未生效，实际按默认 1 GiB 预分配，不能把历史结果当作 64 MiB 配置。
性能结果对应报告记录的历史版本和环境，不能当作后续所有代码版本的性能保证。
