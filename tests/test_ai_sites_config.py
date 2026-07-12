from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import httpx

from finbot.ai.openai_compatible import OpenAICompatibleClient, OpenAICompatibleProvider, load_provider_configs
from finbot.config.ai_sites import (
    AI_TASK_ID_COMPRESSION,
    AI_TASK_ID_TRADE_SYNTHESIS,
    AISitesConfigStore,
    render_prompt_template,
)


class FakeModelsHttpClient:
    def __init__(self, *args, **kwargs):
        pass

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return None

    def get(self, url: str, headers: dict[str, str]) -> httpx.Response:
        request = httpx.Request("GET", url)
        return httpx.Response(
            200,
            json={
                "data": [
                    {"id": "model-b"},
                    {"id": "model-a"},
                    {"id": "model-a"},
                ]
            },
            request=request,
        )


class FakeCompletionHttpClient:
    last_payload: dict | None = None

    def __init__(self, *args, **kwargs):
        pass

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return None

    def post(self, url: str, json: dict, headers: dict[str, str]) -> httpx.Response:
        type(self).last_payload = json
        request = httpx.Request("POST", url)
        response_body = (
            {"output_text": '{"status":"ok"}'}
            if url.endswith("/responses")
            else {"choices": [{"message": {"content": '{"status":"ok"}'}}]}
        )
        return httpx.Response(
            200,
            json=response_body,
            request=request,
        )


class AISitesConfigTests(unittest.TestCase):
    def test_trade_synthesis_prompt_treats_valid_market_confirmation_as_research_confirmation(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            payload = AISitesConfigStore(Path(temp_dir)).public_payload()

        prompt = payload["prompts"][AI_TASK_ID_TRADE_SYNTHESIS]["system_prompt"]
        self.assertIn("视为已经满足 require_research_confirmation", prompt)
        self.assertIn("不得仅因该字段把方向建议降级", prompt)

    def test_ai_sites_redacts_key_and_loads_provider(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            store = AISitesConfigStore(root)
            store.update(
                {
                    "sites": [
                        {
                            "site_id": "custom",
                            "display_name": "自定义站点",
                            "enabled": True,
                            "base_url": "https://ai.example.test/v1",
                            "api_key": "secret-key",
                            "chat_models": ["custom-chat"],
                            "responses_models": ["custom-responses"],
                            "default_chat_model": "custom-chat",
                            "default_responses_model": "custom-responses",
                            "timeout_seconds": 12,
                        }
                    ],
                    "task_bindings": {
                        AI_TASK_ID_COMPRESSION: {
                            "enabled": True,
                            "site_id": "custom",
                            "protocol": "chat",
                            "model": "custom-chat",
                            "reasoning_effort": "high",
                            "fallback_site_ids": [],
                        }
                    },
                    "prompts": {
                        AI_TASK_ID_COMPRESSION: {
                            "system_prompt": "只返回 JSON。",
                            "user_prompt_template": "目标 {target_type}/{target_id}: {payload_json}",
                        }
                    },
                }
            )

            public = store.public_payload()
            providers = load_provider_configs(project_root=root)
            prompt = store.prompt(AI_TASK_ID_COMPRESSION)
            binding = store.task_binding(AI_TASK_ID_COMPRESSION)

        self.assertTrue(public["sites"][0]["api_key_configured"])
        self.assertNotIn("api_key", public["sites"][0])
        self.assertEqual(providers["custom"].base_url, "https://ai.example.test/v1")
        self.assertEqual(providers["custom"].api_key, "secret-key")
        self.assertEqual(providers["custom"].chat_model, "custom-chat")
        self.assertEqual(binding.reasoning_effort, "high")
        self.assertEqual(prompt.system_prompt, "只返回 JSON。")
        self.assertEqual(
            render_prompt_template(prompt.user_prompt_template, payload_json="{}", target_type="event", target_id="e1"),
            "目标 event/e1: {}",
        )

    def test_blank_key_update_preserves_existing_secret(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            store = AISitesConfigStore(root)
            first_payload = store.default_payload()
            first_payload["sites"][0]["enabled"] = True
            first_payload["sites"][0]["api_key"] = "original-key"
            store.update(first_payload)
            second_payload = store.public_payload()
            second_payload["sites"][0]["api_key"] = ""
            store.update(second_payload)
            providers = load_provider_configs(project_root=root)

        self.assertEqual(providers["deepseek"].api_key, "original-key")

    def test_model_refresh_updates_protocol_specific_model_list(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            store = AISitesConfigStore(root)
            payload = store.default_payload()
            payload["sites"][0]["enabled"] = True
            payload["sites"][0]["api_key"] = "key"
            store.update(payload)

            store.update_models("deepseek", "chat", ["deepseek-chat-new", "deepseek-chat-old"])
            public = store.public_payload()
            providers = load_provider_configs(project_root=root)

        deepseek = next(site for site in public["sites"] if site["site_id"] == "deepseek")
        self.assertEqual(deepseek["chat_models"], ["deepseek-chat-new", "deepseek-chat-old"])
        self.assertEqual(deepseek["default_chat_model"], "deepseek-chat-new")
        self.assertEqual(providers["deepseek"].chat_model, "deepseek-chat-new")

    def test_openai_compatible_client_lists_models(self) -> None:
        provider = OpenAICompatibleProvider(
            name="custom",
            api_key="key",
            base_url="https://ai.example.test/v1",
            chat_model="model-a",
            responses_model=None,
        )

        with patch("finbot.ai.openai_compatible.httpx.Client", FakeModelsHttpClient):
            models = OpenAICompatibleClient().list_models(provider)

        self.assertEqual(models, ["model-a", "model-b"])

    def test_mimo_chat_uses_mimo2api_reasoning_effort_field(self) -> None:
        provider = OpenAICompatibleProvider(
            name="mimo",
            api_key="key",
            base_url="https://mimo.example.test/v1",
            chat_model="mimo-v2.5-pro",
            responses_model=None,
        )

        with patch("finbot.ai.openai_compatible.httpx.Client", FakeCompletionHttpClient):
            completion = OpenAICompatibleClient().complete(
                provider,
                "chat",
                "只返回 JSON。",
                "{}",
                reasoning_effort="high",
            )

        self.assertEqual(completion.model, "mimo-v2.5-pro")
        self.assertEqual(FakeCompletionHttpClient.last_payload["reasoning_effort"], "high")
        self.assertNotIn("thinking", FakeCompletionHttpClient.last_payload)

    def test_generic_chat_preserves_reasoning_intensity(self) -> None:
        provider = OpenAICompatibleProvider(
            name="custom",
            api_key="key",
            base_url="https://custom.example.test/v1",
            chat_model="reasoning-model",
            responses_model=None,
        )

        with patch("finbot.ai.openai_compatible.httpx.Client", FakeCompletionHttpClient):
            OpenAICompatibleClient().complete(provider, "chat", "system", "user", reasoning_effort="xhigh")

        self.assertEqual(FakeCompletionHttpClient.last_payload["reasoning_effort"], "xhigh")

    def test_responses_protocol_maps_reasoning_intensity(self) -> None:
        provider = OpenAICompatibleProvider(
            name="custom",
            api_key="key",
            base_url="https://custom.example.test/v1",
            chat_model=None,
            responses_model="reasoning-model",
        )

        with patch("finbot.ai.openai_compatible.httpx.Client", FakeCompletionHttpClient):
            OpenAICompatibleClient().complete(provider, "responses", "system", "user", reasoning_effort="medium")

        self.assertEqual(FakeCompletionHttpClient.last_payload["reasoning"], {"effort": "medium"})

    def test_role_presets_use_existing_sites_without_exposing_keys(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = AISitesConfigStore(Path(temp_dir))
            public = store.public_payload(env={"DEEPSEEK_API_KEY": "secret", "MIMO_API_KEY": "secret-2"})

        self.assertGreaterEqual(len(public["role_presets"]), 7)
        self.assertEqual(public["role_presets"][0]["site_id"], "mimo")
        self.assertTrue(public["role_presets"][0]["model"])
        self.assertNotIn("api_key", public["role_presets"][0])

    def test_default_pricing_is_model_bound_and_auditable(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = AISitesConfigStore(Path(temp_dir))
            public = store.public_payload()
            matching = store.site_pricing("deepseek", model="deepseek-v4-flash")
            mismatching = store.site_pricing("deepseek", model="another-model")

        deepseek = next(site for site in public["sites"] if site["site_id"] == "deepseek")
        mimo = next(site for site in public["sites"] if site["site_id"] == "mimo")
        sub2api = next(site for site in public["sites"] if site["site_id"] == "sub2api")
        self.assertEqual(deepseek["input_cost_per_million_tokens"], 0.14)
        self.assertEqual(deepseek["output_cost_per_million_tokens"], 0.28)
        self.assertEqual(mimo["input_cost_per_million_tokens"], 0.435)
        self.assertEqual(mimo["output_cost_per_million_tokens"], 0.87)
        self.assertEqual(sub2api["default_responses_model"], "gpt-5.6-luna")
        self.assertEqual(sub2api["pricing_basis"], "internal-conservative-estimate")
        self.assertEqual(sub2api["input_cost_per_million_tokens"], 5.0)
        self.assertEqual(sub2api["output_cost_per_million_tokens"], 20.0)
        self.assertEqual(matching["pricing_basis"], "cache_miss")
        self.assertTrue(matching["model_matches"])
        self.assertFalse(mismatching["model_matches"])
        self.assertIsNone(mismatching["input_cost_per_million_tokens"])
        self.assertIsNone(mismatching["output_cost_per_million_tokens"])

    def test_default_sub2api_site_loads_responses_key_from_environment(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = AISitesConfigStore(Path(temp_dir))
            public = store.public_payload(env={"SUB2API_API_KEY": "secret"})
            providers = load_provider_configs(project_root=Path(temp_dir), env={"SUB2API_API_KEY": "secret"})

        sub2api = next(site for site in public["sites"] if site["site_id"] == "sub2api")
        self.assertTrue(sub2api["api_key_configured"])
        self.assertEqual(sub2api["responses_models"], ["gpt-5.6-luna"])
        self.assertEqual(providers["sub2api"].responses_model, "gpt-5.6-luna")
        self.assertIsNone(providers["sub2api"].chat_model)

    def test_experiment_assignment_is_stable(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = AISitesConfigStore(Path(temp_dir))
            payload = store.default_payload()
            payload["experiments"] = [
                {
                    "experiment_id": "debate-model-ab",
                    "display_name": "Debate model A/B",
                    "task_id": "ai_debate",
                    "enabled": True,
                    "variants": [
                        {"variant_id": "control", "weight": 1, "site_id": "deepseek", "protocol": "chat", "model": "deepseek-v4-flash", "reasoning_effort": "low"},
                        {"variant_id": "challenger", "weight": 1, "site_id": "mimo", "protocol": "chat", "model": "mimo-v2.5-pro", "reasoning_effort": "high"},
                    ],
                }
            ]
            store.update(payload)

            first = store.experiment_variant("ai_debate", "loop-a:risk_controller")
            second = store.experiment_variant("ai_debate", "loop-a:risk_controller")

        self.assertEqual(first, second)
        self.assertIn(first["variant_id"], {"control", "challenger"})
        self.assertIn(first["reasoning_effort"], {"low", "high"})


if __name__ == "__main__":
    unittest.main()
