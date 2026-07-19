from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path

from finbot_proxy.models import ProxyNode
from finbot_proxy.singbox import build_configuration as build_sing_box_configuration
from finbot_proxy.xray import build_configuration as build_xray_configuration


class ProxyEngine(StrEnum):
    SING_BOX = "SING_BOX"
    XRAY = "XRAY"

    @classmethod
    def parse(cls, value: object) -> ProxyEngine:
        if not isinstance(value, str):
            raise ValueError("engine must be SING_BOX or XRAY")
        normalized = value.strip().replace("-", "_").upper()
        try:
            return cls(normalized)
        except ValueError as error:
            raise ValueError("engine must be SING_BOX or XRAY") from error

    def build_configuration(
        self,
        nodes: tuple[ProxyNode, ...],
        *,
        listen_port_start: int,
    ) -> str:
        if self is ProxyEngine.SING_BOX:
            return build_sing_box_configuration(
                nodes,
                listen_port_start=listen_port_start,
            )
        return build_xray_configuration(nodes, listen_port_start=listen_port_start)

    def process_spec(
        self,
        *,
        sing_box_path: Path,
        xray_path: Path,
        runtime_directory: Path,
    ) -> EngineProcessSpec:
        if self is ProxyEngine.SING_BOX:
            candidate = runtime_directory / "sing-box.candidate.json"
            active = runtime_directory / "sing-box.json"
            return EngineProcessSpec(
                name="sing-box",
                executable=sing_box_path,
                candidate=candidate,
                active=active,
                check_command=(str(sing_box_path), "check", "-c", str(candidate)),
                run_command=(str(sing_box_path), "run", "-c", str(active)),
            )
        candidate = runtime_directory / "xray.candidate.json"
        active = runtime_directory / "xray.json"
        return EngineProcessSpec(
            name="Xray",
            executable=xray_path,
            candidate=candidate,
            active=active,
            check_command=(str(xray_path), "run", "-test", "-config", str(candidate)),
            run_command=(str(xray_path), "run", "-config", str(active)),
        )


@dataclass(frozen=True, slots=True)
class EngineProcessSpec:
    name: str
    executable: Path
    candidate: Path
    active: Path
    check_command: tuple[str, ...]
    run_command: tuple[str, ...]
