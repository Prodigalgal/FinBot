# FinBot

FinBot 是一个面向研究与模拟交易的 AI 决策平台，主链路覆盖信息采集、清理与压缩、
产品映射、多 Agent 多轮辩论、独立风险门禁、Gate TestNet / Bybit Demo 模拟执行和结果评估。

当前版本禁止 Mainnet 私有写入。LLM 只能提供研究判断，不能绕过独立风险规则、控制凭据或决定最终下单数量。

## 本地验证

```powershell
python -m pip install .
python -m compileall finbot
python -m unittest discover -s tests -v

Set-Location web-ui
npm ci
npx tsc -b --clean
npm run build
```

## 运行

开发配置从 `config/*.example.*` 和 `.env.example` 创建。实际 API key、管理员认证、
交易所凭据、代理节点和运行时数据不得提交到 Git。

```powershell
python -m finbot.cli.serve_web --data-dir data --frontend-dist web-ui/dist
python -m finbot.cli.serve_worker --data-dir data
```

## CI/CD 与生产部署

- 源码仓库：`Prodigalgal/FinBot`，Private。
- 镜像仓库：`docker.io/speedproxy/finbot`，使用不可变 `sha-<commit>` tag。
- GitOps 仓库：`Prodigalgal/ircs-prod-config`，生产资源位于 `finbot/`。
- 部署控制器：ArgoCD Application `finbot`。

`main` 分支 push 会先执行 Secret scan、后端全量测试、前端干净构建和依赖审计；
通过后构建 ARM64 镜像并推送 DockerHub，最后只更新 `ircs-prod-config` 中的资源和镜像 tag。
ArgoCD 负责从 GitOps 仓库同步集群，CI 不直接执行生产 `kubectl apply`。

GitHub Actions 仅引用以下加密 Secret：

- `DOCKERHUB_USERNAME`
- `DOCKERHUB_TOKEN`
- `FINBOT_GITOPS_DEPLOY_KEY`

Kubernetes Secret 由集群运行时创建，清单只引用 `finbot-secrets`、
`finbot-bootstrap-files` 和 `finbot-dockerhub` 的名称。

部署、备份和回滚细节见 `deploy/k8s/README.md`，长期架构约束见
`docs/requirements/26-p4-quant-validation-oracle-k8s.md`。
