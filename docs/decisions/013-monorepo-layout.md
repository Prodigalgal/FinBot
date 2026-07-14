# ADR 013：按 Apps、Services、Platform 组织主仓库

状态：Accepted

日期：2026-07-14

## 背景

Java breaking migration 完成后，主仓库顶层同时存在 `backend`、`web-ui`、`quant-service`、`proxy-gateway` 和 `deploy/k8s`。这些名称能运行，但没有显式表达用户界面、在线服务、平台资源和共享契约之间的所有权；根 `Dockerfile` 也让 Backend 构建边界不够直观。旧 Python 设计文档和当前架构文档混在 `docs/` 根目录，`tasks/current.md` 同时承担历史流水账和当前任务板，增加了检索成本。

## 决策

采用以下稳定目录边界：

| 路径 | 所有权 | 依赖规则 |
| --- | --- | --- |
| `apps/web` | 浏览器应用 | 只依赖公开 `/api/v2` 与 SSE 契约 |
| `services/backend` | Java 主系统 | 拥有领域、应用、基础设施、运行装配和迁移工具 |
| `services/quant` | Python 量化服务 | 只通过 `contracts/` 与 Backend 通信，不持有交易凭据 |
| `services/proxy-gateway` | 代理控制面 | 只处理代理节点与网络健康，不解析业务或凭据 |
| `platform/k8s` | K8S / Argo CD | 只引用发布镜像与 Secret 名，不内嵌 Secret |
| `contracts` | 跨服务契约 | 由通信双方共同验证，变更必须保持强类型 |
| `docs` | 需求、决策、迁移与证据 | 当前文档与历史归档分离 |
| `tasks` | 工作状态 | 当前板保持简短，历史流水账进入 `done/` |

Backend Dockerfile 归属 `services/backend/Dockerfile`，但 build context 保持仓库根目录，因为镜像还需要受版本控制的迁移脚本。根 `.dockerignore` 采用 allowlist，只向 Backend build 发送 Java 源码和该脚本。其他镜像使用组件目录作为独立 context。

Core 和 Proxy GitHub Actions 继续独立发布。路径过滤器、缓存 key、测试工作目录、镜像 context 和 GitOps `rsync` 源必须与新目录一致；K8S 资源名、镜像名、API、数据库 Schema 和 Secret key 不因目录迁移改变。

## 结果

- 顶层能直接区分产品界面、服务、平台、契约和文档。
- Proxy 改动不会触发 Core 镜像，Core 改动不会重建 Proxy。
- Docker build context 更小，降低无关文件进入构建上下文和供应链扫描噪声的风险。
- 旧路径是 breaking change；本次一次性更新仓库内全部构建、测试、部署和文档引用，不保留路径兼容软链接。
- 历史 Python 源码继续位于独立归档仓库；主仓库只保留历史设计文档，且明确标记为非当前运行说明。

## 验证

- Java `clean test` 与两个 `bootJar`。
- Quant / Proxy 的 Ruff、mypy、pytest；Quant OpenAPI 校验。
- React TypeScript clean build、生产 build 和系统 smoke。
- 两条 workflow YAML 解析、Kustomize 渲染与迁移 shell syntax。
- Secret scan、Gitleaks、ARM64 镜像构建、Trivy、Cosign、GitOps 与 Argo CD `Synced / Healthy`。
