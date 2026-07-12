# P4 量化验证与 Oracle K8S 生产化验收

验收日期：2026-07-12

## 结论

P0-P2 已完成并通过本地、GitHub Actions、K8S、HTTP/API 和浏览器多层验证。
FinBot 已由 ArgoCD 托管在 Oracle Cloud 双区 K8S，生产资源唯一真相源为
Private GitOps 仓库 `Prodigalgal/ircs-prod-config/finbot`。真实盘写入继续硬禁止。

## 发布标识

| 项目 | 值 |
| --- | --- |
| 源码仓库 | `Prodigalgal/FinBot`（Private） |
| 源码 revision | `cebebc70b3baeedf9d0940f72cecea93dcc543c1` |
| GitHub Actions run | `29187301974`，`completed/success` |
| 浏览器 artifact | `finbot-browser-smoke`，artifact `8258417437`，保留至 2026-07-26 |
| 镜像 | `docker.io/speedproxy/finbot:sha-cebebc70b3baeedf9d0940f72cecea93dcc543c1` |
| 镜像 digest | `sha256:d1ac97bd354f0eae22e09ea89fe74b11ff08b82a682aeac3fb85b86db5342afa` |
| GitOps revision | `6e26e0355daacd527cdb4471ac02441cbb5e1267` |
| ArgoCD Application | `finbot`，`Synced/Healthy` |
| 入口 | `https://finbot.mnnu.eu.org` |

## Changelog

### P0 安全与可靠性

- 单管理员认证从 Web 大文件拆分为 `finbot.web.auth` 与 `finbot.web.auth_challenge`。
- 管理员账户只允许通过 ENV/K8S Secret 注入；未引入用户表或第二套账户体系。
- 登录强制一次性数学验证码与 SHA-256 PoW；challenge 绑定请求来源、120 秒过期、使用后消费，默认难度 16 bits。
- 会话使用 HMAC、HttpOnly、Secure、SameSite=Strict Cookie；保留登录限速与 Origin 校验。
- 未知 `/api/*` 明确返回 JSON `404/api_not_found`，不再被 SPA fallback 掩盖。
- 增加独立 liveness/readiness、生产安全校验、NetworkPolicy、seccomp、只读根文件系统、禁用 ServiceAccount token。
- 增加 SQLite 原生 backup/verify/restore 和拒绝覆盖保护；首次集群外备份已完成恢复校验。

### P1 量化与执行风控

- 增加 execution backtest、手续费、滑点、资金费、强平和保守 candle 碰撞处理。
- 增加合约规格、维护保证金、风险预算、仓位与安全杠杆反推；500X 默认硬阻断。
- 增加独立执行风险门禁：单笔/日亏损、回撤、连续亏损、总暴露、强平距离和环境限制，AI 不可覆盖。
- OMS 覆盖 planned/submitted/partial/filled/cancelled/rejected/expired/reconciled、乐观版本、幂等撤改单和交易所对账。
- OMS 增加已成交入场单的 reduce-only 止损/止盈 OCO 退出计划；任一退出单成交会幂等取消兄弟单。

### P2 实验、可观测性与体验

- 增加 point-in-time、样本外、walk-forward、seeded Monte Carlo、参数敏感性、前视偏差和 Agent 消融。
- 增加费用后绩效、Sharpe/Sortino/Calmar、Benchmark Alpha、control/challenger 实验注册和 PnL 归因。
- 增加结构化 JSON 日志、Prometheus collector、ServiceMonitor、PrometheusRule、数据新鲜度、Worker 心跳和队列指标。
- 研究历史新增统一时间线，按时间整合采集、研究、建议、复核、风险、评估、模拟执行、OMS 订单与影子持仓。
- GitHub Actions 增加 Chromium 组件 smoke，真实覆盖数学题、PoW、登录、500X 阻断和桌面/移动布局。
- 增加架构依赖与大文件增长 guard；认证、量化、OMS、实验、指标边界已拆分。

## 验证证据

| 验证项 | 结果 |
| --- | --- |
| Python 全量测试 | 184 tests，全部通过 |
| Python compile/wheel | `compileall` 通过；wheel 构建通过 |
| 前端 | clean TypeScript + Vite build 通过；npm audit 0 vulnerabilities |
| Secret scan | 328 tracked files，通过 |
| CI 浏览器 | 数学题/PoW/登录/量化组件 smoke 成功；无 console warning/error；无横向溢出 |
| GitOps dry-run | 16 个资源 server-side dry-run 通过 |
| K8S | FinBot 3/3、egress 1/1、均 0 restart；PVC `20Gi/RWO/longhorn` Bound |
| HTTP | `/`、`/health/live`、`/health/ready` 均 200；匿名敏感 API 401 |
| 认证 | challenge 不返回答案；16-bit PoW 登录 200；注销后 401 |
| API 错误契约 | 未知 API 返回 `404 application/json` 与 `api_not_found` |
| 交易所账户 | Gate TestNet、Bybit Demo 均 `ready`；只读；Mainnet 私有 API 禁止 |
| 交易所出口 | Gate TestNet 与 Bybit Demo 公共探测均 HTTP 200 |
| Prometheus | `finbot_up=1`、`finbot_metrics_collector_errors=0`，Prometheus 查询命中 `finbot-metrics` |
| 备份 | `finbot-20260712T151000+0800.tar.gz`，SHA256 `4E5B5A139A6056F6CB97B63EB1A58A437E01534AE126ACC163FAE10A8F3F3286`，SQLite integrity `ok` |

浏览器截图位于本地 `output/playwright/`；CI 对应 artifact 为 `finbot-browser-smoke`。

## 兼容性与运行边界

- 现有 API 保持兼容，研究历史只增加 additive 字段；登录 API 因新增 challenge 字段发生有意的安全契约升级。
- SQLite 架构继续要求单副本、`Recreate`、Web/Worker/Metrics 同 Pod；迁移 PostgreSQL 前不得横向扩展。
- PoW challenge 存储在单 Pod 内存中，与当前单副本契约一致；未来多副本前必须迁移到共享短期存储。
- 真实盘、资金划转、提现和 Mainnet 私有写 API 仍不在范围内，并由代码硬阻断。

## 遗留风险

- Firecrawl keyless 上游偶发 `429 Too Many Requests`；Worker 会继续处理其他来源，当前数据新鲜度和心跳正常。若持续影响覆盖率，应配置正式额度或降低对应来源频率。
- MUI vendor chunk 约 506 kB，构建只有 chunk-size warning，不影响正确性；后续可按页面继续 lazy split。
- SQLite 单副本是当前明确约束，不具备多 Pod 高可用；数据库迁移应作为后续独立专案。

## 回滚点

- 应用回滚：将 `ircs-prod-config/finbot/oracle/kustomization.yaml` 镜像回退为
  `speedproxy/finbot:sha-3a2c082e37a4f92ecd748d3c48bed0f580c83056`，提交后由 ArgoCD 同步。
- GitOps 回滚：上一稳定 revision 为 `0030c69569e1685dc7a36a57d45d74674a245b39`。
- 数据恢复：停止 FinBot Deployment，使用 `finbot.cli.runtime_backup verify/restore` 恢复到空运行目录；默认禁止覆盖现有数据库。
- Secret 不进入 Git 回滚；如需轮换，修改 `服务器管理/private/finbot` 的私有来源并重新生成 K8S Secret。
