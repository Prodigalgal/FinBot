# FinBot Kubernetes 部署基线

## 架构边界

- 当前存储是 SQLite，因此 Web 与 Worker 放在同一个 Pod、共享同一个 `ReadWriteOnce` PVC。
- `replicas` 必须保持 `1`，更新策略必须保持 `Recreate`。在迁移 PostgreSQL 前，不允许横向扩 Pod。
- `/app` 是只读镜像内容；`/var/lib/finbot` 保存 SQLite、报告、运行时配置和 AI Workflow 配置。
- `paper_execution.submit_orders` 首次启动固定为 `false`；真实盘 host 与 Mainnet 私有 API 仍由代码硬禁止。
- Firecrawl keyless 从 Secret 读取 `FIRECRAWL_VLESS_SUBSCRIPTION_URL`，由应用内 sing-box bridge 将订阅节点转换为本地 HTTP 代理池；候选失败后进入指数冷却，禁止 direct fallback。订阅 URL、token、节点 UUID 和临时 sing-box 配置都不进入 GitOps。
- Gate/Bybit 固定通过 `finbot-egress-proxy` 的 IPv4 出口。该代理调度到带有 `infra.mnnu/location=sg` 标签的新加坡节点，只接受同 namespace 的 FinBot Pod 访问，并且只开放 HTTPS `CONNECT:443`。
- Firecrawl 与交易所使用独立 ProxyPool、路由策略和 bridge 生命周期；任一代理池不可用时只影响对应 provider，不允许跨池借道。

## 准备镜像

正式发布由 GitHub Actions 构建并推送 `docker.io/speedproxy/finbot:sha-<commit>`，
随后更新私有 GitOps 仓库 `Prodigalgal/ircs-prod-config` 的 `finbot/` 目录。
ArgoCD Application `finbot` 只从该仓库同步生产资源，CI 不直接修改集群。

```bash
docker build -t docker.io/speedproxy/finbot:VERSION .
docker push docker.io/speedproxy/finbot:VERSION
cd deploy/k8s/base
kustomize edit set image ghcr.io/example/finbot=docker.io/speedproxy/finbot:VERSION
```

## 注入 Secret

不要提交实际 Secret。先基于 `finbot-secrets.env.example` 创建私有文件，再由集群生成 Secret：

`sub2api` 当前配置为公网 HTTP 开发网关。正式集群启用前必须把 `base_url` 替换为 HTTPS 地址或集群内私网 Service，避免 `SUB2API_API_KEY` 明文跨公网传输。

```bash
kubectl create namespace finbot --dry-run=client -o yaml | kubectl apply -f -
kubectl -n finbot create secret generic finbot-secrets \
  --from-env-file=finbot-secrets.env \
  --dry-run=client -o yaml | kubectl apply -f -
```

DockerHub 拉取凭据使用只读 PAT 创建为 `finbot-dockerhub`；不要复用 CI 的 push PAT，
也不要把生成的 `.dockerconfigjson` 写入 GitOps 仓库。

认证保持单管理员模型。`FINBOT_ADMIN_USERNAME`、`FINBOT_ADMIN_PASSWORD`（或
`FINBOT_ADMIN_PASSWORD_HASH`）和 `FINBOT_SESSION_SECRET` 由 `finbot-secrets` 注入；
数学验证码 challenge 默认 120 秒过期，PoW 默认 16 bits，可分别通过
`FINBOT_AUTH_CHALLENGE_TTL_SECONDS`、`FINBOT_AUTH_POW_DIFFICULTY_BITS` 调整。

## 部署与验证

当前集群的新加坡节点是 `instance-20251229-0833`。首次部署前补齐语义化地域标签；代理 Deployment 故意不使用同时命中大阪和新加坡的 `infra.mnnu/egress=fixed-public`：

```bash
SG_NODE=instance-20251229-0833
kubectl label node "$SG_NODE" infra.mnnu/location=sg --overwrite
kubectl get nodes -l infra.mnnu/location=sg -o wide
```

```bash
kubectl apply -k deploy/k8s/oracle
kubectl -n finbot rollout status deployment/finbot --timeout=5m
kubectl -n finbot rollout status deployment/finbot-egress-proxy --timeout=3m
kubectl -n finbot get pod,svc,pvc
kubectl -n finbot get pod -l app.kubernetes.io/name=finbot-egress-proxy -o wide
kubectl -n finbot logs deployment/finbot -c web --tail=100
kubectl -n finbot logs deployment/finbot -c worker --tail=100
kubectl -n finbot logs deployment/finbot-egress-proxy --tail=100
kubectl -n finbot port-forward service/finbot-web 8780:8780
curl -fsS http://127.0.0.1:8780/health
curl -fsS http://127.0.0.1:8780/api/v1/autonomous/status
```

部署前确认交易所出口节点位于新加坡、Firecrawl 订阅已通过 Secret 注入，并分别执行订阅 bridge 与 Bybit smoke：

```bash
kubectl get nodes -l infra.mnnu/location=sg -o wide
kubectl -n finbot exec deployment/finbot -c worker -- \
  python -m finbot.cli.proxy_diagnostics --data-dir /var/lib/finbot/data --smoke
```

若固定出口节点不可用，代理 Pod 会保持 `Pending` 或失去 Ready，交易所请求会显式失败；不要临时绕到未验证地区的公网出口。`runtime_config.json` 首次生成后保存在 PVC 中，修改 bootstrap 不会覆盖已有配置，升级时需通过系统设置或受控迁移同步 `exchange.proxy_pool`。

## 备份与恢复

备份使用 SQLite 原生 backup API，并同时保存运行配置和 SHA256 清单：

```bash
kubectl -n finbot exec deployment/finbot -c web -- \
  python -m finbot.cli.runtime_backup backup --runtime-root /var/lib/finbot
```

将归档复制到集群外后必须执行校验。恢复时先停止 FinBot Deployment，恢复到空 PVC；已有数据库默认拒绝覆盖：

```bash
python -m finbot.cli.runtime_backup verify --archive finbot-BACKUP.tar.gz
python -m finbot.cli.runtime_backup restore \
  --archive finbot-BACKUP.tar.gz \
  --runtime-root /var/lib/finbot
```

只有已保存旧 PVC/文件备份且明确执行灾难恢复时才允许增加 `--overwrite`。恢复后依次检查 SQLite integrity、readiness、Worker 心跳、账户只读和模拟执行开关。

Ingress、TLS、备份策略、StorageClass 和镜像仓库在目标集群 overlay 中配置，不写死在 base。首次上线前至少对 `/var/lib/finbot` 做 PVC 快照或文件级备份，并验证恢复到新 PVC。
