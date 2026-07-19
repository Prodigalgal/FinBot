# 027：交换所 HY2 代理使用节点网络

## 状态

Accepted · 2026-07-19

## 背景

交换所代理需要通过 Hysteria2 建立 QUIC/UDP 会话。真实线上测试显示，四条 HY2 节点在 Windows 和 `game-proxy` 的 `hostNetwork` Pod 内均可握手并访问 Bybit Demo API；同一套配置在 FinBot 的 Cilium Pod 内全部在握手阶段超时或被重置。服务端监听、密码、SNI、Salamander 混淆和 Bybit HTTP 响应均已排除为根因，NetworkPolicy 也明确允许 UDP 出站。

## 决策

`finbot-exchange-proxy` 保持独立单副本，但启用：

- `hostNetwork: true`，使 HY2 使用已验证的节点 UDP 路径；
- `dnsPolicy: ClusterFirstWithHostNet`，继续使用集群 DNS；
- 保留非 root、RuntimeDefault seccomp、现有 8080/8081 健康探针和内部 ClusterIP Service。

其他 Web/Backend/Quant/采集代理继续使用普通 Pod 网络，不扩大变更范围。

## 代价与边界

Host network Pod 不依赖 CNI 的 UDP 转发，能规避当前 OCI/Cilium 路径问题，但其 8080/8081 监听位于节点网络命名空间。OCI 安全组和节点防火墙必须继续限制这些端口只对集群/内网可达；发布后必须检查 EndpointSlice 已切换到节点地址、Service 健康探针和 Bybit 代理探测均为成功。

## 回滚

删除 Deployment Pod 模板中的 `hostNetwork` 与 `dnsPolicy` 两项即可恢复普通 CNI Pod；回滚后 Bybit HY2 代理预期重新回到已知的握手失败状态，不能将直连回退误认为代理恢复。
