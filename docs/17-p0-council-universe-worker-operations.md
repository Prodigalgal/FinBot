# Council、Universe 与常驻服务运维

## 进程边界

- Web：`python -m finbot.cli.serve_web --host 127.0.0.1 --port 8780`
- Worker：`python -m finbot.cli.serve_worker --data-dir data`
- Web 默认不启动内嵌 scheduler；`POST /api/v1/autonomous/run-now` 只写入 SQLite 队列。
- Worker 持有 `autonomous-scheduler` 租约，生成定时请求并串行执行手动和定时请求。

## K8S 生产常驻

正式运行目标是 Kubernetes。部署基线位于 `deploy/k8s/`：Web 与 Worker 作为同一 Pod 的两个容器，共享 `/var/lib/finbot` PVC；镜像根文件系统只读并使用非 root 用户。

- SQLite 阶段固定 `replicas: 1` 和 `strategy: Recreate`，禁止两个 Pod 同时访问数据库文件。
- `FINBOT_RUNTIME_ROOT=/var/lib/finbot` 持久化运行时配置、AI Workflow、SQLite 和报告；`/app` 只保存镜像资产。
- API key、代理池和模拟盘凭据通过 `finbot-secrets` 注入，不写入镜像、ConfigMap 或 Git。
- K8S 不依赖 Windows Scheduled Task，也不在容器内自动拉起本地代理桥；代理使用集群内 sidecar/service 或外部 HTTP/SOCKS 地址。

构建、部署和 smoke 命令见 `deploy/k8s/README.md`。

## Windows 开发机常驻

当前工作站的两个 Scheduled Task 只用于开发与回归，不是生产部署方案：

```powershell
PowerShell -ExecutionPolicy Bypass -File .\scripts\install_finbot_worker_task.ps1
PowerShell -ExecutionPolicy Bypass -File .\scripts\install_finbot_web_task.ps1
```

安装脚本配置任务失败后每分钟重启、忽略重复实例、允许电池供电、无限执行时长，并立即启动。supervisor 使用 Windows Job Object 保证停止任务时一并回收 Python 子进程。查看状态：

```powershell
Get-ScheduledTask -TaskName FinBot-Worker,FinBot-Web
Invoke-RestMethod http://127.0.0.1:8780/api/v1/autonomous/status
```

回滚：

```powershell
PowerShell -ExecutionPolicy Bypass -File .\scripts\remove_finbot_worker_task.ps1
PowerShell -ExecutionPolicy Bypass -File .\scripts\remove_finbot_web_task.ps1
```

## API 状态

- `GET /api/v1/autonomous/status`：scheduler 模式、Worker 心跳、租约、队列、最近请求、Universe、Council 和建议。
- `POST /api/v1/autonomous/run-now`：入队手动闭环请求。
- `GET /api/v1/instruments`：交易所产品目录。
- `GET /api/v1/universe/latest`：最近一次 Hybrid Universe。
- `GET/PUT /api/v1/ai/config`：AI site、环节绑定、提示词和 Council templates。
- `GET /api/v1/evaluations/recommendations/latest`：历史建议 outcome、收益/回撤和置信度校准。
- `GET /api/v1/portfolio-risk/latest`：相关性、集中度和压力测试。
- `GET /api/v1/ai/governance/latest`：Prompt/model/variant、token/成本和 claim 证据覆盖。
- `GET /api/v1/setup`：默认配置档案、默认数据量和启用就绪度。
- `POST /api/v1/setup/apply`：显式应用 `recommended`、`economy` 或 `deep_research`；默认 `preserve_existing=true`。

## P2 默认配置与费率

- 推荐档案只物化非敏感、非代理策略、非环境映射字段，不覆盖 `.env`、AI key、代理池或订阅地址。
- 首次应用前应备份 `config/runtime_config.json`；本工作站的 P2 初始备份位于 `data/backups/runtime-config-pre-p2-20260711-105104.json`。
- DeepSeek 与 MiMo 默认费率使用 USD / 1M tokens、cache miss 输入价；UI 显示计费模型、官方来源和核对日期。
- 调用模型与计费模型不一致时成本为 `unknown`，不得手工套用近似价格。
- 历史 invocation 的重算只写入新治理报告，原始调用记录保持不变。

应用推荐档案：

```powershell
$body = @{ profile_id = 'recommended'; preserve_existing = $true } | ConvertTo-Json
Invoke-RestMethod -Uri http://127.0.0.1:8780/api/v1/setup/apply -Method Post -ContentType application/json -Body $body
```

恢复 P2 应用前运行时配置：

```powershell
Copy-Item .\data\backups\runtime-config-pre-p2-20260711-105104.json .\config\runtime_config.json -Force
```

## AI 调度工作流

- AI 配置 v5 在 `council_templates[].workflow` 中保存版本化 DAG；节点引用既有 `roles/chair`，不会复制 key 或形成第二套 LLM 配置。
- 保存边界会校验唯一 input/chair、role 映射、端点、自环、重复边、DAG、input 可达性和 chair 可达性。
- phase 内按拓扑层执行；同轮消息沿上游依赖传递，历史轮次广播给全部角色，Chair 的直接引用取自最终活跃节点。
- 本工作站首次物化前没有 `config/ai_sites.json`，保存后的 v5 快照位于 `data/backups/ai-sites-workflow-v5-20260711-120245.json`，其中不包含非空 API key。

恢复已验证的 v5 工作流配置：

```powershell
Copy-Item .\data\backups\ai-sites-workflow-v5-20260711-120245.json .\config\ai_sites.json -Force
Restart-ScheduledTask -TaskName FinBot-Web
Restart-ScheduledTask -TaskName FinBot-Worker
```

验证工作流契约：

```powershell
$ai = Invoke-RestMethod http://127.0.0.1:8780/api/v1/ai/config
$ai.council_templates[0].workflow | ConvertTo-Json -Depth 8
```

## 故障恢复

- Worker 崩溃后，request lease 到期会被后续 Worker 自动放回队列。
- scheduler lease 到期后，另一 Worker 可接管；同一时刻只有 lease owner 消费队列。
- Web 重启不影响 Worker；Worker 重启不丢失已入队请求。
- `worker.embedded_scheduler=true` 仅用于本地兼容，不应与独立 Worker 同时开启。
