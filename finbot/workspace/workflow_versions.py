from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from typing import Any

from finbot.config.ai_sites import AISitesConfigStore
from finbot.council.models import CouncilTemplate
from finbot.storage.sqlite_store import SQLiteStore


class WorkflowVersionService:
    def __init__(self, store: SQLiteStore, ai_store: AISitesConfigStore):
        self.store = store
        self.ai_store = ai_store
        self.store.init_schema()

    def list_versions(self, template_id: str | None = None) -> dict[str, Any]:
        self._bootstrap_current_templates()
        versions = [_version_payload(row) for row in self.store.list_workflow_versions(template_id)]
        return {
            "status": "ok",
            "count": len(versions),
            "versions": versions,
            "node_tests": (
                [_node_test_payload(row) for row in self.store.list_workflow_node_tests(template_id)]
                if template_id else []
            ),
        }

    def save_draft(
        self,
        template_payload: dict[str, Any],
        *,
        workflow_version_id: str | None = None,
        parent_version_id: str | None = None,
        expected_checksum: str | None = None,
        change_note: str = "",
    ) -> dict[str, Any]:
        template = CouncilTemplate.from_payload(template_payload).to_dict()
        template_id = str(template["template_id"])
        self._bootstrap_current_templates()
        current = self.store.get_workflow_version(workflow_version_id) if workflow_version_id else None
        if current is not None and current["template_id"] != template_id:
            raise ValueError("工作流草稿与 template_id 不匹配")
        if current is None:
            version_number = self.store.next_workflow_version_number(template_id)
            workflow_version_id = _stable_id("workflow-version", template_id, version_number, _now())
        else:
            version_number = int(current["version_number"])
        now = _now()
        saved = self.store.save_workflow_draft(
            {
                "workflow_version_id": workflow_version_id,
                "template_id": template_id,
                "version_number": version_number,
                "content": template,
                "checksum": _checksum(template),
                "parent_version_id": parent_version_id or (current["parent_version_id"] if current else None),
                "change_note": " ".join(change_note.split()).strip()[:500],
                "created_by": "local",
                "created_at": current["created_at"] if current else now,
            },
            expected_checksum=expected_checksum,
        )
        return {"status": "saved", "version": _version_payload(saved), "estimate": self.estimate(template)}

    def publish(self, workflow_version_id: str) -> dict[str, Any]:
        row = self.store.get_workflow_version(workflow_version_id)
        if row is None:
            raise LookupError(f"未找到工作流版本：{workflow_version_id}")
        if row["status"] != "draft":
            raise ValueError("只有 draft 版本可以发布")
        template = CouncilTemplate.from_payload(_loads(row["content_json"], {})).to_dict()
        previous_config = self.ai_store.payload()
        next_config = dict(previous_config)
        templates = [item for item in previous_config.get("council_templates", []) if isinstance(item, dict)]
        matched = False
        next_templates = []
        for item in templates:
            if str(item.get("template_id") or "") == template["template_id"]:
                next_templates.append(template)
                matched = True
            else:
                next_templates.append(item)
        if not matched:
            next_templates.append(template)
        next_config["council_templates"] = next_templates
        self.ai_store.update(next_config)
        try:
            published = self.store.publish_workflow_version(workflow_version_id, _now())
        except Exception:
            self.ai_store.update(previous_config)
            raise
        return {
            "status": "published",
            "version": _version_payload(published),
            "ai_config": self.ai_store.public_payload(),
        }

    def rollback(self, workflow_version_id: str, *, publish: bool = True) -> dict[str, Any]:
        target = self.store.get_workflow_version(workflow_version_id)
        if target is None:
            raise LookupError(f"未找到工作流版本：{workflow_version_id}")
        template = _loads(target["content_json"], {})
        draft = self.save_draft(
            template,
            parent_version_id=workflow_version_id,
            change_note=f"回滚自 v{target['version_number']}",
        )
        if not publish:
            return {"status": "draft", "version": draft["version"], "estimate": draft["estimate"]}
        return self.publish(str(draft["version"]["workflow_version_id"]))

    def estimate(self, template_payload: dict[str, Any], rounds: int | None = None) -> dict[str, Any]:
        template = CouncilTemplate.from_payload(template_payload)
        requested_rounds = template.round_policy.default_rounds if rounds is None else int(rounds)
        safe_rounds = max(
            template.round_policy.min_rounds,
            min(requested_rounds, template.round_policy.max_rounds),
        )
        enabled_roles = [role for role in template.roles if role.enabled]
        role_invocations = {role.role_id: 0 for role in enabled_roles}
        for round_index in range(1, safe_rounds + 1):
            phase = template.phase_for_round(round_index)
            for role in template.roles_for_phase(phase):
                role_invocations[role.role_id] += 1
        invocation_specs = [
            (role.role_id, role.site_id, role.model, role.reasoning_effort, role_invocations[role.role_id])
            for role in enabled_roles
            if role_invocations[role.role_id] > 0
        ]
        invocation_specs.append(
            (
                template.chair.role_id,
                template.chair.site_id,
                template.chair.model,
                template.chair.reasoning_effort,
                1,
            )
        )
        node_estimates = []
        known_cost = 0.0
        unknown_count = 0
        total_tokens = 0
        for role_id, site_id, model, reasoning_effort, invocation_count in invocation_specs:
            input_tokens = 3000 * invocation_count
            output_tokens = 1200 * invocation_count
            total_tokens += input_tokens + output_tokens
            pricing = self.ai_store.site_pricing(str(site_id or ""), model)
            input_rate = pricing.get("input_cost_per_million_tokens")
            output_rate = pricing.get("output_cost_per_million_tokens")
            if input_rate is None or output_rate is None:
                cost = None
                unknown_count += 1
            else:
                cost = input_tokens / 1_000_000 * float(input_rate) + output_tokens / 1_000_000 * float(output_rate)
                known_cost += cost
            node_estimates.append(
                {
                    "role_id": role_id,
                    "site_id": site_id,
                    "model": model,
                    "reasoning_effort": reasoning_effort,
                    "invocation_count": invocation_count,
                    "input_tokens": input_tokens,
                    "output_tokens": output_tokens,
                    "estimated_cost_usd": cost,
                    "cost_status": "known" if cost is not None else "unknown",
                }
            )
        cost_status = "known" if unknown_count == 0 else "unknown" if unknown_count == len(node_estimates) else "partial"
        return {
            "status": "ok",
            "template_id": template.template_id,
            "rounds": safe_rounds,
            "role_count": len(enabled_roles),
            "invocation_count": sum(item[4] for item in invocation_specs),
            "estimated_total_tokens": total_tokens,
            "estimated_cost_usd": known_cost if cost_status != "unknown" else None,
            "cost_status": cost_status,
            "unknown_pricing_count": unknown_count,
            "nodes": node_estimates,
            "workflow_node_count": len(template.workflow.nodes),
            "non_llm_node_count": sum(
                node.node_type not in {"agent", "chair"} and not (node.node_type == "aggregator" and node.role_id)
                for node in template.workflow.nodes
            ),
            "cost_tier": template.cost_tier,
            "assumptions": {"input_tokens_per_call": 3000, "output_tokens_per_call": 1200},
        }

    def test_node(
        self,
        template_payload: dict[str, Any],
        *,
        node_id: str,
        workflow_version_id: str | None = None,
        sample_input: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        template = CouncilTemplate.from_payload(template_payload)
        node = next((item for item in template.workflow.nodes if item.node_id == node_id), None)
        if node is None:
            raise ValueError(f"未找到工作流节点：{node_id}")
        created_at = _now()
        safe_input = _safe_sample(sample_input or {})
        output: dict[str, Any] = {
            "mode": "validation",
            "node_type": node.node_type,
            "schema_valid": True,
            "external_call_sent": False,
        }
        estimated_tokens = 0
        estimated_cost = None
        cost_status = "not_applicable"
        llm_node = node.node_type in {"agent", "chair"} or (node.node_type == "aggregator" and node.role_id)
        if llm_node:
            role = template.chair if node.node_type == "chair" else next(
                item for item in template.roles if item.role_id == node.role_id
            )
            if not role.site_id:
                raise ValueError("节点未绑定 AI 站点")
            site = next((item for item in self.ai_store.public_payload()["sites"] if item["site_id"] == role.site_id), None)
            if site is None or not site["enabled"]:
                raise ValueError("节点绑定的 AI 站点不存在或未启用")
            if not role.model:
                raise ValueError("节点未配置模型")
            estimated_tokens = 4200
            pricing = self.ai_store.site_pricing(role.site_id, role.model)
            input_rate = pricing.get("input_cost_per_million_tokens")
            output_rate = pricing.get("output_cost_per_million_tokens")
            if input_rate is not None and output_rate is not None:
                estimated_cost = 3000 / 1_000_000 * float(input_rate) + 1200 / 1_000_000 * float(output_rate)
                cost_status = "known"
            else:
                cost_status = "unknown"
            output.update(
                {
                    "role_id": role.role_id,
                    "site_id": role.site_id,
                    "model": role.model,
                    "protocol": role.protocol or "chat",
                    "reasoning_effort": role.reasoning_effort,
                    "prompt_configured": bool(role.system_prompt or role.user_prompt_template),
                    "sample_input_keys": sorted(safe_input.keys())[:20],
                }
            )
        elif node.node_type != "input":
            output.update(
                {
                    "operation": node.operation,
                    "config": node.config,
                    "sample_input_keys": sorted(safe_input.keys())[:20],
                    "safety": {
                        "arbitrary_code_allowed": False,
                        "arbitrary_http_allowed": False,
                        "trading_execution_allowed": False,
                    },
                }
            )
        finished_at = _now()
        test_run = {
            "node_test_id": _stable_id("workflow-node-test", template.template_id, node_id, created_at),
            "template_id": template.template_id,
            "workflow_version_id": workflow_version_id,
            "node_id": node_id,
            "status": "passed",
            "input": safe_input,
            "output": output,
            "estimated_tokens": estimated_tokens,
            "estimated_cost_usd": estimated_cost,
            "cost_status": cost_status,
            "error": None,
            "created_at": created_at,
            "finished_at": finished_at,
        }
        self.store.insert_workflow_node_test(test_run)
        return {"status": "passed", "test": test_run}

    def _bootstrap_current_templates(self) -> None:
        for current_template in self.ai_store.council_templates():
            template = current_template.to_dict()
            template_id = str(template["template_id"])
            if self.store.list_workflow_versions(template_id, limit=1):
                continue
            now = _now()
            self.store.insert_workflow_version_if_absent(
                {
                    "workflow_version_id": _stable_id("workflow-bootstrap", template_id, 1),
                    "template_id": template_id,
                    "version_number": 1,
                    "status": "published",
                    "content": template,
                    "checksum": _checksum(template),
                    "parent_version_id": None,
                    "change_note": "现有运行配置基线",
                    "created_by": "system",
                    "created_at": now,
                    "published_at": now,
                }
            )


def _version_payload(row: Any) -> dict[str, Any]:
    payload = dict(row)
    payload["content"] = _loads(payload.pop("content_json", "{}"), {})
    return payload


def _node_test_payload(row: Any) -> dict[str, Any]:
    payload = dict(row)
    payload["input"] = _loads(payload.pop("input_json", "{}"), {})
    payload["output"] = _loads(payload.pop("output_json", "{}"), {})
    return payload


def _checksum(payload: dict[str, Any]) -> str:
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def _safe_sample(value: Any, key: str = "") -> Any:
    if any(marker in key.lower() for marker in ("key", "secret", "token", "password", "authorization")):
        return "***"
    if isinstance(value, dict):
        return {
            str(item_key)[:100]: _safe_sample(item_value, str(item_key))
            for item_key, item_value in list(value.items())[:50]
        }
    if isinstance(value, (list, tuple)):
        return [_safe_sample(item, key) for item in list(value)[:50]]
    if isinstance(value, str):
        return value[:1000]
    if isinstance(value, (int, float, bool)) or value is None:
        return value
    return str(value)[:1000]


def _stable_id(*parts: Any) -> str:
    return hashlib.sha256(":".join(str(part) for part in parts).encode("utf-8")).hexdigest()


def _loads(raw: Any, fallback: Any) -> Any:
    try:
        return json.loads(raw) if isinstance(raw, str) and raw else fallback
    except (TypeError, ValueError):
        return fallback


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
