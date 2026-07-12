# P4 量化验证与 Oracle K8S 生产化 Goal

## 目标

整合最近两轮功能/流程/UI/UX/架构审计与 AI 量化同类产品对标结果，完成 P0-P2，
并将生产候选部署到 Oracle Cloud 双区 K8S，取得可重复的线上验收证据。

## 基线事实

- [x] 已有产品库、Watchlist、Hybrid Universe、即时/定时研究和真正多轮 Council。
- [x] 已有工作流草稿/发布/回滚、节点测试、成本预估、Director 与受控 Learning。
- [x] 已有人工复核、Shadow Portfolio、建议结果评估、组合风险和 AI Governance。
- [x] 已有 Gate TestNet / Bybit Demo 并发模拟提交与账户/PnL 只读面板。
- [x] Oracle Cloud K8S 三节点当前均 Ready，平台主线 Argo CD Application 当前 Synced/Healthy。
- [x] FinBot 已建立 Private GitHub 仓库 `Prodigalgal/FinBot`，提交、CI 与镜像 tag 可追溯。
- [x] 构建由 GitHub Actions ARM64 Buildx 完成；集群验证通过 Oracle 控制面受控执行，不依赖本机 PATH。

## P0：云上安全与可靠性

- [x] 将认证、会话、限速和 API 保护从 `web/service.py` 抽到独立模块。
- [x] 增加 `/health/live`、`/health/ready`，保留 `/health` 兼容。
- [x] 增加生产配置校验：认证、Secure Cookie、不可变安全边界、代理和 Secret readiness。
- [x] 强化 K8S securityContext、NetworkPolicy、readiness/liveness 和 core-public 调度。
- [x] 建立 Oracle overlay、HTTPRoute、ServiceMonitor/告警和 Secret 名称契约。
- [x] 为 SQLite/PVC 增加备份与恢复脚本/文档，完成首次备份证据。
- [x] 修正前端认证动线、401 恢复、加载/错误/空态和敏感配置展示，并增加数学验证码/PoW。

## P1：量化验证与执行风控

- [x] 新增 execution backtest 领域模型、市场回放器、成交/费率/滑点/资金费模型。
- [x] 新增交易所合约规格与风险档位快照，支持维护保证金和强平计算。
- [x] 新增风险预算与杠杆反推；极限杠杆默认硬阻断并只允许 Paper/TestNet。
- [x] 新增 OMS 状态机、订单事件历史、部分成交、撤改、reduce-only OCO 退出计划和对账。
- [x] 增加单笔/日亏损、连续止损、回撤、相关暴露、强平距离 Kill Switch。
- [x] 增加回测、风险、OMS 成功/失败/边界/恢复测试。

## P2：实验、组合、可观测性与维护性

- [x] 新增样本外、walk-forward、Monte Carlo、参数敏感性和前视偏差检查。
- [x] 新增费用后绩效、风险调整收益、Benchmark Alpha 和策略/产品/交易所归因。
- [x] 新增策略实验注册表、control/challenger 和 Agent 消融分析。
- [x] 增加 Prometheus 指标、结构化日志、数据新鲜度和任务积压告警。
- [x] 拆分认证、量化、OMS、指标等职责并增加架构依赖/文件规模 guard，避免继续反向增长。
- [x] 增加 CI 浏览器组件 smoke、关键用户动线回归和研究/建议/风险/订单/持仓统一时间线。

## Oracle K8S 发布

- [x] 确认 `finbot.mnnu.eu.org` DNS、Gateway listener、证书和 Cloudflare 代理策略。
- [x] 在 ARM64 构建不可变镜像并推送 DockerHub。
- [x] 在 GitOps 配置仓库新增 FinBot Application 与 manifests，禁止提交 Secret。
- [x] 创建运行时 Secret，确保 AI/交易所密钥、管理员认证和代理信息不出现在日志/API。
- [x] Argo CD 同步并验证 Deployment、egress proxy、PVC、Service、HTTPRoute 和监控对象。
- [x] 完成线上健康、数学验证码/PoW 登录、受保护 API、账户只读和代理出口 smoke。
- [x] 记录镜像 tag、Git revision、Pod/PVC、HTTP 证据、遗留风险和回滚命令。

## 验证命令

```powershell
python -m compileall finbot
python -m unittest discover -s tests -v
Set-Location web-ui
npm run build
```

云端验收以 `docs/requirements/26-p4-quant-validation-oracle-k8s.md` 为准。
