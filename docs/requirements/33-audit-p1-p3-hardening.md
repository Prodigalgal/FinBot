# 审计 P1-P3 工程加固

## 目标

修复 2026-07-16 审计确认的运行时背压、行情完整性、关键交易恢复测试、控制面契约、代理 TLS 策略和 Web 回归测试缺口，使现有功能在不改变业务流程的前提下具备可验证的生产边界。

## 范围

- Worker 增加全局和按任务类型的并发上限；容量耗尽时不认领新任务，并暴露运行、容量、拒绝和 lease 丢失状态。
- Worker 丢失任务 lease 后中断本地执行，禁止继续提交完成或失败状态。
- Bybit K 线响应出现畸形行、重复时间戳或时间序列缺口时整体失败，不再静默丢弃或去重。
- 为模拟订单提交、未知结果恢复、对账状态推进、Worker 背压和 lease 恢复增加直接测试。
- 将 `contracts/finbot-control-plane.openapi.yaml` 补全为 Web 控制面固定路由契约，生成 TypeScript 路径类型，并在 CI 阻止前端、Controller 和 OpenAPI 路由漂移。
- 代理网关默认拒绝声明 `insecure` / `allowInsecure` 的订阅节点；仅显式配置时允许，并在健康状态中暴露数量。
- 为 Web API、认证状态、交易配置和工作流关键状态增加 Vitest 回归测试。

## 非目标

- 不改变研究、辩论、交易决策或风险策略。
- 不启用真实盘交易，不修改交易所凭据和代理订阅内容。
- 不改造现有 UI 视觉风格，不引入新的业务页面。
- 不将虚拟线程替换为平台线程；虚拟线程继续承担阻塞 I/O，容量由显式背压控制。

## 影响模块

- `services/backend/finbot-bootstrap`：Worker 运行时、配置、指标和测试。
- `services/backend/finbot-infrastructure`：Bybit 行情解析及数据库恢复测试。
- `services/backend/finbot-application`：模拟执行和对账测试。
- `contracts/`、`apps/web/`、`.github/workflows/`：控制面契约、生成类型和 CI 校验。
- `services/proxy-gateway`：TLS 节点策略、健康状态和测试。

## 验收标准

1. Worker 达到全局或类型容量时不再调用 `claimNext`；状态和 Micrometer 指标可见。
2. heartbeat 返回 lease 丢失后，运行任务收到中断，且不会调用 `complete` 或 `fail`。
3. Bybit 任一畸形行、重复时间戳或非连续时间间隔均产生明确 provider/data-integrity 错误。
4. OpenAPI 覆盖所有 Web 固定 API 路径和公开 Controller 路径；生成 TS 类型无未提交漂移。
5. `PROXY_ALLOW_INSECURE_TLS` 默认 `false`，不安全节点被拒绝且健康端点可观察。
6. 后端、代理、Web 单元测试和构建全部通过；CI、GitOps 和线上 smoke 无退化。

## 测试方式

- `services/backend/gradlew.bat clean test`
- `python -m pytest -q`（`services/proxy-gateway`）
- `npm test -- --run` 与 `npm run build`（`apps/web`）
- OpenAPI 路由覆盖与生成文件一致性脚本
- GitHub Actions、ArgoCD 健康状态及生产 HTTP/API/browser smoke
