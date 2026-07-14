# FinBot Oracle K8S deployment

> Breaking change：本清单会先把旧 `Deployment/finbot` 缩容到 `0`，再迁移 Schema 与历史，最后启动 Java/quant/Web。首次同步前必须确认 `finbot-state` PVC、PostgreSQL 和 `finbot-secrets` 均已备份；不要绕过 ArgoCD 直接长期维护生产资源。

## 准备运行 Secret

以 [`finbot-secrets.env.example`](./finbot-secrets.env.example) 为字段清单，在受控目录创建 `finbot-secrets.env`。真实 AI key、交易所 key、订阅 token、节点 URL、管理员密码和内部 token 不得进入源码或 GitOps 仓库。

```powershell
$env:KUBECONFIG = 'D:\WorkSpace\Project\服务器管理\private\kubeconfigs\mnnu-admin.conf'
kubectl create namespace finbot --dry-run=client -o yaml | kubectl apply -f -
kubectl -n finbot create secret generic finbot-secrets `
  --from-env-file=finbot-secrets.env `
  --dry-run=client -o yaml | kubectl apply -f -
```

`EXCHANGE_PROXY_NODES` 使用分号分隔 VLESS/Hysteria2 URL。Firecrawl 和交易所各自运行独立 proxy gateway，自动进行 sing-box `urltest` 选择并每 30 分钟刷新。Firecrawl 路由强制使用 IPv4 代理并 fail-closed；Gate TestNet 与 Bybit Demo 当前经集群出口直连官方 TLS 端点，只有在代理节点完成真实出口验证后才通过 `network_proxy_route` 显式切回代理。

DockerHub 拉取凭据只使用 read-only PAT：

```powershell
kubectl -n finbot create secret docker-registry finbot-dockerhub `
  --docker-server=https://index.docker.io/v1/ `
  --docker-username='<dockerhub-user>' `
  --docker-password='<read-only-token>' `
  --dry-run=client -o yaml | kubectl apply -f -
```

## 理解首次切流

ArgoCD 按以下 Sync Waves 执行，任一步失败都会阻止新服务接管流量：

| Wave | 资源 | 门禁 |
| --- | --- | --- |
| `-50` | PostgreSQL | StatefulSet 与保留 PVC Ready |
| `-40` | `finbot` legacy freeze | 旧 Python Web/Worker 缩到 0，SQLite 停止写入 |
| `-30` | `finbot-schema-migration` | Java Liquibase 12 个 changeset 成功 |
| `-20` | `finbot-legacy-import` | 旧 SQLite 只读导入、逐表计数与 SHA-256 核对 |
| `-10` | 两个 proxy gateway | Firecrawl 与交易所 HTTP proxy Ready |
| `0` | Java、quant、Web | 所有 Deployment 与探针 Ready |

历史导入把每一张旧表逐行保存到 `legacy_archive_row`，并把产品、Gate/Bybit 合约、别名和 Kline 转换进新强类型表。相同 SQLite SHA-256 再次执行会直接返回已完成结果，不重复写入。

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
kubectl -n finbot rollout status deployment/finbot-exchange-proxy --timeout=5m
```

检查迁移与代理，不输出 Secret：

```powershell
kubectl -n finbot logs job/finbot-schema-migration --tail=200
kubectl -n finbot logs job/finbot-legacy-import --tail=200
kubectl -n finbot port-forward service/finbot-firecrawl-proxy 18081:8081
curl.exe -fsS http://127.0.0.1:18081/health
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

首次迁移保留 `finbot-state` PVC、旧 `Deployment/finbot` 定义和旧 PostgreSQL 表。若新版本未通过门禁：

1. 在 `ircs-prod-config` 回退到迁移前 revision，让 ArgoCD 恢复旧镜像与路由。
2. 确认 Java Deployment 已停止后，再恢复旧 `finbot` replicas；禁止新旧 Worker 同时运行。
3. Java 新表保留事故快照，不自动反写旧 SQLite/旧表。
4. 核对旧 `/health/ready`、Worker 心跳和 TestNet/Demo 开关后再恢复流量。

通过三个完整调度周期和一次回滚演练后，才可在后续 GitOps revision 移除 `legacy-freeze.yaml`、历史导入 Hook 和 `finbot-state` PVC；PVC 删除必须单独审批并先完成快照。
