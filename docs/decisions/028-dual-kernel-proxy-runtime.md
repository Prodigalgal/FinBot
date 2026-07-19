# ADR 028: 代理池显式选择 sing-box 或 Xray 内核

状态：Accepted

## 背景

现有 proxy gateway 将节点解析、配置生成和子进程命令绑定在 sing-box 上。HY2 需要 sing-box，而新建的 VLESS REALITY 节点也需要用 Xray 做交叉验证和可选运行内核。把内核选择隐藏在部署环境中，会使后台热配置与真实进程不一致。

## 决策

1. `proxy_gateway_profile.engine` 是内核选择的唯一持久化真相，取值为 `SING_BOX` 或 `XRAY`。
2. Python runtime 使用 engine adapter 生成配置、校验命令、启动命令和运行文件名；RoundRobin HTTP proxy、节点轮换和目标探测保持内核无关。
3. sing-box adapter 接受 VLESS/Hysteria2；Xray adapter 只接受 VLESS。配置生成失败时拒绝激活新内核并保留最后一个健康进程；新进程启动失败时清空路由并 fail closed。
4. 镜像固定内置 sing-box `1.13.14` 与 Xray `26.3.27`，禁止运行时下载二进制。
5. 内核通过 Java 控制面随代理池配置热下发，健康状态回显实际 engine；业务服务仍只依赖 HTTP proxy URL。

## 结果

- VLESS 池可在不改 Deployment 的情况下切换内核并重新探测。
- HY2 池继续使用 sing-box，避免伪装成 Xray 可支持的协议。
- 镜像略有增大，但依赖版本、配置校验和失败边界更明确。
- 新增内核必须实现同一 adapter 契约并补配置生成、进程命令和协议兼容测试。
