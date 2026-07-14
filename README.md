# FinBot

FinBot 是面向自动研究与模拟交易的 AI 决策平台：定时或即时采集信息，清理和压缩证据，结合交易所行情与 Python 量化结果，执行可配置的多 Agent 多轮辩论，再由独立风控和执行机器人操作 Gate TestNet / Bybit Demo。

> Breaking change：生产主系统已经切换到 Java 26 + PostgreSQL + `/api/v2`。根目录旧 Python Web/Worker 和 SQLite 仅作为冻结回滚基线，不再是构建或部署入口。

## 架构

| 组件 | 技术 | 责任 |
| --- | --- | --- |
| `backend/finbot-bootstrap` | Java 26、Spring Boot 4.1 | Auth、API、常驻 Worker、SSE、业务装配 |
| `backend/finbot-domain` | 纯 Java | 强类型领域状态、值对象和规则 |
| `backend/finbot-application` | Java | 用例、端口、任务编排和事务边界 |
| `backend/finbot-infrastructure` | Spring Data JDBC、`JdbcClient`、Liquibase | PostgreSQL、AI、交易所、Firecrawl 和 HTTP adapter |
| `quant-service/` | Python 3.13、FastAPI | 无状态量化研究与 HTTP/SSE 输出 |
| `proxy-gateway/` | Python 3.13、sing-box | VLESS/Hysteria2 订阅转 HTTP proxy、健康选择和定时刷新 |
| `web-ui/` | React、TypeScript、Vite | 单管理员运营台和自由 DAG 工作流编辑器 |
| `backend/finbot-migration` | Java 26 | 一次性只读历史导入，不进入在线运行时 |

## 验证代码

```powershell
$env:JAVA_HOME = 'D:\DevlopEnv\JDK\jdk-26.0.1'
Set-Location backend
.\gradlew.bat --no-daemon clean test :finbot-bootstrap:bootJar :finbot-migration:bootJar

Set-Location ..\quant-service
python -m pip install -e ".[dev]"
python -m ruff check src tests
python -m mypy src tests
python -m pytest -q

Set-Location ..\proxy-gateway
python -m pip install -e ".[dev]"
python -m ruff check src tests
python -m mypy src tests
python -m pytest -q

Set-Location ..\web-ui
npm ci
npm run build
```

Testcontainers 需要本机 Docker；没有 Docker 时 PostgreSQL 集成测试会明确标记为 skipped，并由 GitHub Actions 的 Linux runner 强制执行。

## 本地运行

先启动 PostgreSQL 18，并从 [`.env.example`](./.env.example) 注入必填变量。Java API 和常驻 Worker 是同一个可横向扩展的进程，Python 只启动 quant 服务。

```powershell
Set-Location quant-service
python -m finbot_quant.main

Set-Location ..\backend
$env:JAVA_HOME = 'D:\DevlopEnv\JDK\jdk-26.0.1'
.\gradlew.bat :finbot-bootstrap:bootRun

Set-Location ..\web-ui
npm run dev
```

默认端口为 Java `8080`、quant `8081`、Vite `5173`。登录使用环境注入的单管理员账户，并要求一次性数学验证码和 SHA-256 PoW。

## 生产发布

`main` 分支通过 GitHub Actions 完成 Java/Python/React 测试、真实 PostgreSQL 集成测试、浏览器 smoke、Secret scan、K8S 渲染、四镜像构建、Trivy 扫描和 Cosign 签名。镜像推送至 `docker.io/speedproxy`，随后只更新私有 GitOps 仓库 `Prodigalgal/ircs-prod-config/finbot`；ArgoCD Application `finbot` 负责同步 Oracle K8S。

生产 Secret、首次历史导入、代理池、运行验收和回滚步骤见 [`deploy/k8s/README.md`](./deploy/k8s/README.md)。架构契约见 [`docs/decisions/012-java26-spring-data-jdbc-liquibase-python-quant.md`](./docs/decisions/012-java26-spring-data-jdbc-liquibase-python-quant.md)，迁移门禁见 [`docs/migrations/010-java-breaking-exec-plan.md`](./docs/migrations/010-java-breaking-exec-plan.md)。
