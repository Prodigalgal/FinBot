# FinBot Oracle K8S deployment

> 当前清单是 Java/quant/Web 稳态生产拓扑。旧 `Deployment/finbot` 与一次性 SQLite 导入 Hook 已在历史迁移核验完成后退役；`finbot-state` PVC 仅作为受控回滚证据保留。不要绕过 ArgoCD 长期维护生产资源。

## 准备运行 Secret

以 [`finbot-secrets.env.example`](./finbot-secrets.env.example) 为字段清单，在受控目录创建 `finbot-secrets.env`。真实 AI key、交易所 key、订阅 token、节点 URL、管理员密码和内部 token 不得进入源码或 GitOps 仓库。

应用层 fallback 按资源 ID 组织，不按厂商命名。`AI_PROVIDER_KEYS_JSON` 为 `{ "providerId": "key" }`；`EXCHANGE_ACCOUNT_CREDENTIALS_JSON` 为 `{ "accountId": { "API_KEY": "...", "API_SECRET": "..." } }`；信息源、出站路由和代理网关分别使用相同的 `sourceId`、`routeType`、`gatewayId` 映射。日常轮换应优先在后台使用加密热配置，这些 JSON 只承担首次启动与灾难恢复。

```powershell
$env:KUBECONFIG = 'D:\WorkSpace\Project\服务器管理\private\kubeconfigs\mnnu-admin.conf'
kubectl create namespace finbot --dry-run=client -o yaml | kubectl apply -f -
kubectl -n finbot create secret generic finbot-secrets `
  --from-env-file=finbot-secrets.env `
  --dry-run=client -o yaml | kubectl apply -f -
```

`FIRECRAWL_PROXY_NODES` 与 `EXCHANGE_PROXY_NODES` 使用分号分隔 VLESS/Hysteria2 URL；Firecrawl 只允许使用私有四节点池，禁止回退到大订阅池，且默认 profile/source 均关闭。Firecrawl proxy 以空载控制面启动，不会因为 Secret 中存在节点而自动探测；管理员显式开启 profile 后，Java 才会热加载四节点。`WEB_CRAWL_SUBSCRIPTION_URL` 由独立网页采集网关轮换。三套 proxy gateway 各自进行 sing-box 选择、独立健康探测并每 30 分钟刷新；Java 的 `WEB_CRAWL`、`FIRECRAWL` 和交易路由不会共享健康状态。Firecrawl 与网页采集均 fail-closed；Gate TestNet 与 Bybit Demo 当前经集群出口直连官方 TLS 端点，只有在代理节点完成真实出口验证后才通过 `network_proxy_route` 显式切回代理。

DockerHub 拉取凭据只使用 read-only PAT：

```powershell
kubectl -n finbot create secret docker-registry finbot-dockerhub `
  --docker-server=https://index.docker.io/v1/ `
  --docker-username='<dockerhub-user>' `
  --docker-password='<read-only-token>' `
  --dry-run=client -o yaml | kubectl apply -f -
```

## 理解同步顺序

ArgoCD 按以下 Sync Waves 执行，任一步失败都会阻止新服务接管流量：

| Wave | 资源 | 门禁 |
| --- | --- | --- |
| `-50` | PostgreSQL | StatefulSet 与保留 PVC Ready |
| `-40` | `finbot-database-bootstrap` | 目标 PostgreSQL database 已存在 |
| `-30` | `finbot-schema-migration` | Java Liquibase 全部 changeset 成功 |
| `-10` | 三个 proxy gateway | 控制面 Ready；Firecrawl 默认 `enabled=false` 且无 egress，网页采集/交易所按各自健康状态运行 |
| `0` | Java、quant、Web | 所有 Deployment 与探针 Ready |

历史导入已把每一张旧表逐行保存到 `legacy_archive_row`，并把产品、Gate/Bybit 合约、别名和 Kline 转换进新强类型表。导入 manifest、逐表计数和 SHA-256 继续保存在 PostgreSQL，日常发布不再挂载或扫描旧 SQLite 卷。

## 部署策略

当前生产环境按成本和集群容量选择单副本：Backend、Quant、Web、Firecrawl Proxy 和 Exchange Proxy 的 `replicas` 均为 `1`，PostgreSQL 为单 StatefulSet Pod。Firecrawl 默认空载，不会因启动环境中的节点列表主动访问上游。系统不声明高可用，发布期间允许短暂中断；所有单副本 Deployment 统一使用 `maxSurge: 0` 和 `maxUnavailable: 1`，避免 rollout 因集群容量不足永久等待新 Pod 调度。PostgreSQL 与 bootstrap Job 使用已在 ARM64 生产节点验证的固定 image digest。

database bootstrap 与 schema migration 是 Argo CD Hook，成功后通过 `HookSucceeded` 自动删除，`ttlSecondsAfterFinished` 是兜底。Hook 定义保留在 GitOps 中以便后续 Schema 变更继续执行；运行完成的 Job/Pod 不作为长期资源保留。

## 发布

源码 `main` push 后，GitHub Actions 构建并签名以下 ARM64 镜像：

- `speedproxy/finbot-backend:sha-<commit>`
- `speedproxy/finbot-quant:sha-<commit>`
- `speedproxy/finbot-web:sha-<commit>`
- `speedproxy/finbot-proxy:sha-<commit>`

Core Actions 同步本目录并更新 Backend、Quant、Web 三个 tag；Proxy Actions 只更新独立代理镜像 tag。两个流水线通过 GitOps concurrency 串行写入私有仓库 `Prodigalgal/ircs-prod-config/finbot`。ArgoCD Application `finbot` 从 `finbot/oracle` 自动同步；CI 不持有 kubeconfig，也不直接写集群。

## 验证运行态

```powershell
kubectl -n argocd get application finbot `
  -o custom-columns=NAME:.metadata.name,SYNC:.status.sync.status,HEALTH:.status.health.status,REV:.status.sync.revision
kubectl -n finbot get deploy,statefulset,pod,svc,pvc,job -o wide
kubectl -n finbot rollout status deployment/finbot-backend --timeout=10m
kubectl -n finbot rollout status deployment/finbot-quant --timeout=5m
kubectl -n finbot rollout status deployment/finbot-web --timeout=5m
kubectl -n finbot rollout status deployment/finbot-firecrawl-proxy --timeout=5m
kubectl -n finbot rollout status deployment/finbot-web-crawl-proxy --timeout=5m
kubectl -n finbot rollout status deployment/finbot-exchange-proxy --timeout=5m
```

同步进行中可检查迁移日志；Hook 成功后 Job 会自动删除：

```powershell
kubectl -n finbot logs job/finbot-schema-migration --tail=200
kubectl -n finbot port-forward service/finbot-firecrawl-proxy 18081:8081
curl.exe -fsS http://127.0.0.1:18081/health
```

发布完成后执行迁移资源清场，不删除 PVC、Longhorn 快照或 Hook 清单：

```powershell
kubectl -n finbot delete job `
  finbot-database-bootstrap finbot-schema-migration `
  --ignore-not-found=true
kubectl -n finbot delete pod `
  -l app.kubernetes.io/component=migration `
  --ignore-not-found=true
kubectl -n finbot get job,pod
```

登录 `https://finbot.mnnu.eu.org` 后，在“运行与调度 / 历史迁移核对”确认源行数等于归档行数。数据库层可进一步核对：

```sql
SELECT import_id, status, source_table_count, source_row_count,
       archived_row_count, transformed_row_count, completed_at
FROM legacy_import_manifest
ORDER BY started_at DESC;

SELECT source_table, disposition, source_row_count, archived_row_count,
       transformed_row_count, content_sha256, status
FROM legacy_import_table
ORDER BY source_table;
```

完成登录、即时研究 SSE、三轮辩论、量化、风险拒绝或测试网下单、账户同步后，至少观察三个调度周期，确认没有重复交易、Worker 僵尸租约或 Pod 重启。

## 回滚

当前稳态只保留 `finbot-state` PVC 和 PostgreSQL 内的历史归档证据，不再保留旧 `Deployment/finbot` 或自动导入 Hook。若新版本未通过门禁：

1. 在 `ircs-prod-config` 回退到已验证的上一版 Java revision，让 ArgoCD 恢复镜像与路由。
2. 只有必须恢复旧 Python runtime 时，才回退到包含 legacy freeze 的历史 revision；确认 Java Deployment 已停止并核验 `finbot-state` 后再恢复旧 replicas，禁止新旧 Worker 同时运行。
3. Java 新表保留事故快照，不自动反写旧 SQLite/旧表。
4. 核对旧 `/health/ready`、Worker 心跳和 TestNet/Demo 开关后再恢复流量。

`finbot-state` PVC 与 Longhorn 快照的删除仍需单独授权；从 GitOps 清单移除 PVC 会触发持久卷回收，不能与普通 Job/Pod 清场混为一谈。
