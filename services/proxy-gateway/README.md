# FinBot Proxy Gateway

Python 3.13 + sing-box 的独立代理控制面。服务解析 VLESS/Hysteria2 订阅或受控节点列表，执行健康选择、失败冷却和周期刷新，并向业务服务暴露 HTTP proxy 与脱敏健康接口。

```powershell
python -m pip install -e ".[dev]"
python -m ruff check src tests
python -m mypy src tests
python -m pytest -q
```

真实订阅 token、节点 UUID、密码和 URL 只允许通过环境变量或 K8S Secret 注入。代理服务不持有 AI 或交易所凭据，也不解析业务请求。
