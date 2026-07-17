from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class VlessNode:
    address: str
    port: int
    uuid: str
    security: str
    transport: str
    server_name: str
    host: str | None
    path: str
    service_name: str | None
    fingerprint: str
    reality_public_key: str | None
    reality_short_id: str | None
    insecure: bool
    name: str | None


@dataclass(frozen=True, slots=True)
class Hysteria2Node:
    address: str
    port: int
    password: str
    server_ports: tuple[str, ...]
    hop_interval: str
    server_name: str
    insecure: bool
    obfs_type: str | None
    obfs_password: str | None
    name: str | None


ProxyNode = VlessNode | Hysteria2Node


@dataclass(frozen=True, slots=True)
class Subscription:
    nodes: tuple[ProxyNode, ...]
    invalid_node_count: int


@dataclass(frozen=True, slots=True)
class NodeSelection:
    nodes: tuple[ProxyNode, ...]
    insecure_node_count: int
    rejected_insecure_node_count: int
    eligible_node_count: int
    selection_offset: int

    @property
    def enabled_insecure_node_count(self) -> int:
        return sum(1 for node in self.nodes if node.insecure)
