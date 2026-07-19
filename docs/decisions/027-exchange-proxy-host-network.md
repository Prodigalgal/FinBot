# 027：交换所 HY2 代理使用节点网络

## 状态

Proposed · blocked by PodSecurity · 2026-07-19

## 背景

交换所代理需要通过 Hysteria2 建立 QUIC/UDP 会话。真实线上测试显示，四条 HY2 节点在 Windows 和 `game-proxy` 的 `hostNetwork` Pod 内均可握手并访问 Bybit Demo API；同一套配置在 FinBot 的 Cilium Pod 内全部在握手阶段超时或被重置。服务端监听、密码、SNI、Salamander 混淆和 Bybit HTTP 响应均已排除为根因，NetworkPolicy 也明确允许 UDP 出站。

## 方案结论

直接在现有 `finbot` 命名空间启用 `hostNetwork` 不可接受：命名空间使用 `restricted` PodSecurity，真实滚动时 API Server 拒绝了该 Pod。当前版本保持普通 CNI Pod，不降低命名空间安全级别。可行的后续方案是建立单独、最小权限的网络代理命名空间，或启用并验证 Cilium egress gateway，再将交换所代理迁移过去。

如果采用专用命名空间，交换所代理仍应保持独立单副本，并启用：

- `hostNetwork: true`，使 HY2 使用已验证的节点 UDP 路径；
- `dnsPolicy: ClusterFirstWithHostNet`，继续使用集群 DNS；
- 保留非 root、RuntimeDefault seccomp、现有 8080/8081 健康探针和内部 ClusterIP Service。

其他 Web/Backend/Quant/采集代理继续使用普通 Pod 网络，不扩大变更范围。

## 代价与边界

Host network Pod 不依赖 CNI 的 UDP 转发，能规避当前 OCI/Cilium 路径问题，但其 8080/8081 监听位于节点网络命名空间。迁移前必须为专用命名空间设置 PodSecurity、Secret 投递、NetworkPolicy 和节点防火墙边界；未完成这些边界前不得启用。

## 回滚

当前普通 CNI Pod 清单是回滚点。回滚后 Bybit HY2 代理仍可能在 OCI/Cilium UDP 路径上失败，不能将直连回退误认为代理恢复。
