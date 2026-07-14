# ADR-009：量化执行领域边界与 Oracle K8S 部署策略

## 状态

Accepted，2026-07-12。

## 决策

1. FinBot 保持“AI 研究决策层”和“确定性量化执行层”分离。
2. AI 工作流只能产生结构化建议；名义价值、杠杆、保证金、强平和订单生命周期由非 AI 领域服务决定。
3. 新增量化能力按 `backtest`、`risk`、`execution` 三个领域拆分，不继续堆入 autonomous runner、Web service 或 exchange adapter。
4. 现有历史建议评估继续命名为 point-in-time recommendation evaluation；只有包含成交/费用/滑点模型的结果才称为 execution backtest。
5. 第一阶段云部署保持单 Pod 双容器、SQLite、Longhorn RWO、`replicas=1`、`Recreate`；PostgreSQL 迁移另立 ADR。
6. Oracle Cloud K8S 采用 GitOps 作为运行态真相来源；直接 `kubectl apply` 只允许 bootstrap 或故障恢复，并必须回写 Git。
7. 首次公网部署采用单管理员会话认证；多用户、OIDC 和细粒度 RBAC 后续增量实现。
8. 外部 TLS 由 Envoy Gateway/Cloudflare 终止，应用内部保持 HTTP；生产 Cookie 必须为 Secure。
9. Gate/Bybit 请求通过固定新加坡 egress proxy；代理失败即失败，不允许无感直连。
10. 极限杠杆能力只进入 Paper/TestNet 实验，任何产品都以实时合约规格和风险档位为准，不接受固定“500X”假设。

## 取舍

- 继续使用 SQLite 能最快上线现有闭环，但牺牲横向扩展；通过单副本、Recreate 和共享 PVC 控制一致性风险。
- 内置认证减少对外部身份服务的首发依赖，但只适合单管理员；接口和会话边界必须便于后续替换为 OIDC。
- 独立 backtest/risk/execution 模块增加短期代码量，但可避免 AI、HTTP、交易所协议和资金规则相互污染。
- GitOps 增加一次配置仓库改动，但提供可审计、可回滚和 Argo CD 自愈能力，符合当前集群治理方式。

## 回滚

- 关闭 HTTPRoute 可立即撤销公网入口，不影响 PVC 数据。
- 将 `paper_execution.submit_orders=false` 可停止所有模拟写入。
- 认证异常时只允许通过集群内 port-forward 诊断，不允许临时关闭公网认证。
- 应用回滚使用上一不可变镜像 tag 和 GitOps revision；PVC 不随 Deployment 回滚删除。

