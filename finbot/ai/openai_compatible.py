from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping

import httpx

from finbot.config.env_file import read_env_file
from finbot.config.paths import runtime_root
from finbot.council.models import SUPPORTED_REASONING_EFFORTS


DEFAULT_PROVIDER_KEYS_FILE = Path(os.getenv("AI_PROVIDER_KEYS_FILE", "config/ai-provider-keys.env"))
SUPPORTED_PROTOCOLS = {"chat", "responses"}


@dataclass(frozen=True)
class OpenAICompatibleProvider:
    name: str
    api_key: str | None
    base_url: str | None
    chat_model: str | None
    responses_model: str | None
    timeout_seconds: float = 60.0

    def model_for(self, protocol: str) -> str | None:
        if protocol == "chat":
            return self.chat_model
        if protocol == "responses":
            return self.responses_model
        return None

    def missing_for(self, protocol: str) -> list[str]:
        missing: list[str] = []
        if not self.api_key:
            missing.append("api_key")
        if not self.base_url:
            missing.append("base_url")
        if not self.model_for(protocol):
            missing.append(f"{protocol}_model")
        return missing

    def is_configured_for(self, protocol: str) -> bool:
        return protocol in SUPPORTED_PROTOCOLS and not self.missing_for(protocol)


@dataclass(frozen=True)
class ProviderStatus:
    name: str
    protocol: str
    configured: bool
    missing: list[str]
    base_url_configured: bool
    model: str | None


@dataclass(frozen=True)
class LLMCompletion:
    provider: str
    protocol: str
    model: str
    content: str
    usage: dict[str, Any]


class OpenAICompatibleError(RuntimeError):
    def __init__(self, message: str, status_code: int | None = None):
        self.status_code = status_code
        super().__init__(message)


class OpenAICompatibleClient:
    def list_models(self, provider: OpenAICompatibleProvider) -> list[str]:
        missing = []
        if not provider.api_key:
            missing.append("api_key")
        if not provider.base_url:
            missing.append("base_url")
        if missing:
            raise OpenAICompatibleError(f"Provider {provider.name} is missing: {', '.join(missing)}")
        response = self._get(provider, "models")
        raw_models = response.get("data") or response.get("models") or []
        models: list[str] = []
        if isinstance(raw_models, list):
            for item in raw_models:
                if isinstance(item, dict):
                    model_id = item.get("id") or item.get("name")
                else:
                    model_id = item
                if isinstance(model_id, str) and model_id.strip():
                    models.append(model_id.strip())
        return sorted(dict.fromkeys(models))

    def complete(
        self,
        provider: OpenAICompatibleProvider,
        protocol: str,
        system_prompt: str,
        user_prompt: str,
        require_json: bool = True,
        max_output_tokens: int | None = None,
        reasoning_effort: str = "provider_default",
    ) -> LLMCompletion:
        if protocol not in SUPPORTED_PROTOCOLS:
            raise OpenAICompatibleError(f"Unsupported protocol: {protocol}")
        missing = provider.missing_for(protocol)
        if missing:
            raise OpenAICompatibleError(f"Provider {provider.name} is missing: {', '.join(missing)}")
        if reasoning_effort not in SUPPORTED_REASONING_EFFORTS:
            raise OpenAICompatibleError(f"Unsupported reasoning effort: {reasoning_effort}")
        if protocol == "chat":
            return self._chat_completion(
                provider,
                system_prompt,
                user_prompt,
                require_json=require_json,
                max_output_tokens=max_output_tokens,
                reasoning_effort=reasoning_effort,
            )
        return self._responses_completion(
            provider,
            system_prompt,
            user_prompt,
            require_json=require_json,
            max_output_tokens=max_output_tokens,
            reasoning_effort=reasoning_effort,
        )

    def _chat_completion(
        self,
        provider: OpenAICompatibleProvider,
        system_prompt: str,
        user_prompt: str,
        require_json: bool,
        max_output_tokens: int | None,
        reasoning_effort: str,
    ) -> LLMCompletion:
        model = provider.chat_model
        assert model is not None
        payload: dict[str, Any] = {
            "model": model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "temperature": 0.1,
        }
        if provider.name == "mimo":
            if reasoning_effort not in {"provider_default", "none", "minimal"}:
                payload["reasoning_effort"] = reasoning_effort
        elif provider.name == "deepseek":
            payload["thinking"] = {
                "type": "disabled" if reasoning_effort in {"provider_default", "none", "minimal"} else "enabled"
            }
        elif reasoning_effort != "provider_default":
            payload["reasoning_effort"] = reasoning_effort
        if require_json:
            payload["response_format"] = {"type": "json_object"}
        if max_output_tokens is not None:
            payload["max_tokens"] = max(1, int(max_output_tokens))
        response = self._post(provider, "chat/completions", payload)
        choices = response.get("choices") or []
        if not choices:
            raise OpenAICompatibleError(f"Provider {provider.name} returned no choices")
        content = ((choices[0].get("message") or {}).get("content") or "").strip()
        if not content:
            raise OpenAICompatibleError(f"Provider {provider.name} returned empty content")
        return LLMCompletion(provider=provider.name, protocol="chat", model=model, content=content, usage=response.get("usage") or {})

    def _responses_completion(
        self,
        provider: OpenAICompatibleProvider,
        system_prompt: str,
        user_prompt: str,
        require_json: bool,
        max_output_tokens: int | None,
        reasoning_effort: str,
    ) -> LLMCompletion:
        model = provider.responses_model
        assert model is not None
        payload: dict[str, Any] = {
            "model": model,
            "instructions": system_prompt,
            "input": user_prompt,
            "stream": False,
        }
        selected_effort = "none" if provider.name == "mimo" and reasoning_effort == "provider_default" else reasoning_effort
        if selected_effort != "provider_default":
            payload["reasoning"] = {"effort": selected_effort}
        if max_output_tokens is not None:
            payload["max_output_tokens"] = max(1, int(max_output_tokens))
        response = self._post(provider, "responses", payload)
        content = _extract_responses_text(response).strip()
        if not content:
            raise OpenAICompatibleError(f"Provider {provider.name} returned empty content")
        return LLMCompletion(provider=provider.name, protocol="responses", model=model, content=content, usage=response.get("usage") or {})

    def _post(self, provider: OpenAICompatibleProvider, endpoint: str, payload: dict[str, Any]) -> dict[str, Any]:
        return self._request(provider, "POST", endpoint, payload=payload)

    def _get(self, provider: OpenAICompatibleProvider, endpoint: str) -> dict[str, Any]:
        return self._request(provider, "GET", endpoint, payload=None)

    def _request(self, provider: OpenAICompatibleProvider, method: str, endpoint: str, payload: dict[str, Any] | None) -> dict[str, Any]:
        base_url = provider.base_url.rstrip("/")
        url = f"{base_url}/{endpoint.lstrip('/')}"
        headers = {
            "Authorization": f"Bearer {provider.api_key}",
            "Content-Type": "application/json",
        }
        try:
            with httpx.Client(timeout=provider.timeout_seconds) as client:
                if method == "GET":
                    response = client.get(url, headers=headers)
                else:
                    response = client.post(url, json=payload, headers=headers)
        except httpx.HTTPError as exc:
            raise OpenAICompatibleError(f"Provider {provider.name} request failed: {type(exc).__name__}") from exc
        if response.status_code >= 400:
            detail = _safe_error_detail(response)
            raise OpenAICompatibleError(
                f"Provider {provider.name} returned HTTP {response.status_code}: {detail}",
                status_code=response.status_code,
            )
        try:
            data = response.json()
        except ValueError as exc:
            raise OpenAICompatibleError(f"Provider {provider.name} returned non-JSON response") from exc
        if not isinstance(data, dict):
            raise OpenAICompatibleError(f"Provider {provider.name} returned invalid JSON shape")
        return data


def load_provider_configs(
    keys_file: Path | None = DEFAULT_PROVIDER_KEYS_FILE,
    env: Mapping[str, str] | None = None,
    project_root: Path | None = None,
    ai_sites_file: Path | None = None,
) -> dict[str, OpenAICompatibleProvider]:
    root = project_root or Path.cwd()
    from finbot.config.ai_sites import AISitesConfigStore

    ai_store = AISitesConfigStore(runtime_root(root), path=ai_sites_file)
    if ai_store.exists():
        return {
            site.site_id: OpenAICompatibleProvider(
                name=site.site_id,
                api_key=site.api_key,
                base_url=site.base_url,
                chat_model=site.default_chat_model,
                responses_model=site.default_responses_model,
                timeout_seconds=site.timeout_seconds,
            )
            for site in ai_store.sites(keys_file=keys_file, env=env).values()
        }

    env_values = dict(env or os.environ)
    file_values = read_env_file(keys_file)

    def value(name: str, default: str | None = None) -> str | None:
        raw = env_values.get(name) or file_values.get(name) or default
        if raw is None:
            return None
        raw = raw.strip()
        return raw or None

    timeout = _float_value(value("AI_COMPRESSION_TIMEOUT_SECONDS", "60"), default=60.0)
    return {
        "deepseek": OpenAICompatibleProvider(
            name="deepseek",
            api_key=value("DEEPSEEK_API_KEY"),
            base_url=value("DEEPSEEK_BASE_URL", "https://api.deepseek.com"),
            chat_model=value("DEEPSEEK_CHAT_MODEL", "deepseek-v4-flash"),
            responses_model=value("DEEPSEEK_RESPONSES_MODEL"),
            timeout_seconds=timeout,
        ),
        "mimo": OpenAICompatibleProvider(
            name="mimo",
            api_key=value("MIMO_API_KEY"),
            base_url=value("MIMO_BASE_URL", "https://mimo2api.mnnu.eu.org/v1"),
            chat_model=value("MIMO_CHAT_MODEL", "mimo-v2.5-pro"),
            responses_model=value("MIMO_RESPONSES_MODEL", "mimo-v2.5-pro"),
            timeout_seconds=timeout,
        ),
        "sub2api": OpenAICompatibleProvider(
            name="sub2api",
            api_key=value("SUB2API_API_KEY"),
            base_url=value("SUB2API_BASE_URL", "http://168.138.40.52:8181/v1"),
            chat_model=None,
            responses_model=value("SUB2API_RESPONSES_MODEL", "gpt-5.6-terra"),
            timeout_seconds=max(timeout, 90.0),
        ),
    }


def provider_status(provider: OpenAICompatibleProvider, protocol: str) -> ProviderStatus:
    missing = provider.missing_for(protocol)
    return ProviderStatus(
        name=provider.name,
        protocol=protocol,
        configured=not missing,
        missing=missing,
        base_url_configured=bool(provider.base_url),
        model=provider.model_for(protocol),
    )


def _extract_responses_text(response: dict[str, Any]) -> str:
    output_text = response.get("output_text")
    if isinstance(output_text, str):
        return output_text
    parts: list[str] = []
    for item in response.get("output") or []:
        if not isinstance(item, dict):
            continue
        for content in item.get("content") or []:
            if not isinstance(content, dict):
                continue
            text = content.get("text")
            if isinstance(text, str):
                parts.append(text)
    return "\n".join(parts)


def _safe_error_detail(response: httpx.Response) -> str:
    try:
        data = response.json()
    except ValueError:
        data = response.text
    if isinstance(data, dict):
        message = data.get("error") or data.get("message") or data
    else:
        message = data
    text = str(message).replace("\n", " ").strip()
    return text[:500] if text else "no error detail"


def _float_value(value: str | None, default: float) -> float:
    if value is None:
        return default
    try:
        return float(value)
    except ValueError:
        return default
