# FinBot Proxy Gateway

Python 3.13 + sing-box/Xray 的独立代理控制面。服务解析 VLESS/Hysteria2 订阅或受控节点列表，执行健康选择、失败冷却和周期刷新，并向业务服务暴露 HTTP proxy 与脱敏健康接口。每个代理池显式选择内核：sing-box 支持 VLESS/Hysteria2，Xray 只支持 VLESS；不兼容节点会拒绝加载。

```powershell
python -m pip install -e ".[dev]"
python -m ruff check src tests
python -m mypy src tests
python -m pytest -q
```

真实订阅 token、节点 UUID、密码和 URL 只允许通过环境变量或 K8S Secret 注入。代理服务不持有 AI 或交易所凭据，也不解析业务请求。

`PROXY_ENABLED=false` 允许网关在没有 `PROXY_SUBSCRIPTION_URL`/`PROXY_NODES` 时以空载控制面启动：`serviceReady=true`、`ready=false`、节点数为 `0`，且不会发起订阅或目标探测。Java 控制面通过 `/control/config` 下发 `enabled=true`、`engine` 和节点来源后才启动 egress；再次下发 `enabled=false` 会停止当前内核、清空轮询目标并回到空载状态。

启动默认值为 `PROXY_ENGINE=SING_BOX`，也可设置为 `XRAY`。生产运行中以内核热配置为准，健康状态的 `engine` 表示实际激活的内核；二进制路径分别由 `SING_BOX_PATH` 与 `XRAY_PATH` 指定，镜像内已固定携带 sing-box `1.13.14` 和 Xray `26.3.27`。

`PROXY_ALLOW_INSECURE_TLS` 默认且生产固定为 `false`。订阅中的 `insecure` / `allowInsecure` 只描述节点，不会自行授权降低 TLS 校验；不安全节点会被拒绝。只有受控故障排查才可临时显式设为 `true`，并应通过 `/health` 的 `insecureNodeCount`、`rejectedInsecureNodeCount`、`enabledInsecureNodeCount` 和 `allowInsecureTls` 核对实际状态。
