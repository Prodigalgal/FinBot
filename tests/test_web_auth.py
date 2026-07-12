from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from typing import Any
from unittest.mock import patch

from fastapi.testclient import TestClient

from finbot.config.ai_sites import AISitesConfigStore
from finbot.config.runtime_config import RuntimeConfigStore
from finbot.web.auth import AuthSettings, hash_password, verify_password, verify_proof_of_work
from finbot.web.service import FinBotWebApp, create_fastapi_app


ADMIN_PASSWORD = "correct-horse-battery-staple"
SESSION_SECRET = "session-secret-with-at-least-32-characters"


class WebAuthenticationTests(unittest.TestCase):
    def test_unknown_api_path_is_json_404_instead_of_spa_html(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            frontend = root / "dist"
            frontend.mkdir()
            (frontend / "index.html").write_text("<html>FinBot SPA</html>", encoding="utf-8")
            app = create_fastapi_app(
                _state(root),
                frontend_dist=str(frontend),
                auth_settings=AuthSettings(enabled=False),
            )
            with TestClient(app) as client:
                response = client.get("/api/v1/route-does-not-exist")

        self.assertEqual(response.status_code, 404)
        self.assertEqual(response.json()["code"], "api_not_found")
        self.assertNotIn("FinBot SPA", response.text)

    def test_single_admin_plain_password_can_be_injected_from_environment(self) -> None:
        with patch.dict(
            "os.environ",
            {
                "FINBOT_AUTH_ENABLED": "true",
                "FINBOT_ADMIN_USERNAME": "admin",
                "FINBOT_ADMIN_PASSWORD": "test-passphrase",
                "FINBOT_SESSION_SECRET": SESSION_SECRET,
                "FINBOT_AUTH_POW_DIFFICULTY_BITS": "12",
            },
            clear=True,
        ):
            settings = AuthSettings.from_env()

        self.assertTrue(settings.enabled)
        self.assertEqual(settings.username, "admin")
        self.assertEqual(settings.password, "test-passphrase")
        self.assertEqual(settings.pow_difficulty_bits, 12)

    def test_password_hash_round_trip_and_rejects_invalid_values(self) -> None:
        encoded = hash_password(ADMIN_PASSWORD, salt=b"0123456789abcdef")

        self.assertTrue(verify_password(ADMIN_PASSWORD, encoded))
        self.assertFalse(verify_password("incorrect-password-value", encoded))
        self.assertFalse(verify_password(ADMIN_PASSWORD, "unknown$1$bad$bad"))

    def test_enabled_auth_protects_api_and_manages_session_cookie(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            state = _state(Path(temp_dir))
            settings = _auth_settings()
            app = create_fastapi_app(state, frontend_dist=None, auth_settings=settings)

            with TestClient(app) as client:
                live = client.get("/health/live")
                anonymous = client.get("/api/v1/status")
                rejected = _login(client, password="incorrect-password-value")
                accepted = _login(client, password=ADMIN_PASSWORD)
                authenticated = client.get("/api/v1/status")
                untrusted_origin = client.put(
                    "/api/v1/config",
                    headers={"Origin": "https://attacker.example"},
                    json={"values": {}, "clear_keys": []},
                )
                logout = client.post("/api/v1/auth/logout", json={})
                after_logout = client.get("/api/v1/status")

        self.assertEqual(live.status_code, 200)
        self.assertEqual(anonymous.status_code, 401)
        self.assertEqual(anonymous.json()["code"], "authentication_required")
        self.assertEqual(rejected.status_code, 401)
        self.assertEqual(accepted.status_code, 200)
        cookie = accepted.headers["set-cookie"].lower()
        self.assertIn("httponly", cookie)
        self.assertIn("samesite=strict", cookie)
        self.assertEqual(authenticated.status_code, 200)
        self.assertEqual(untrusted_origin.status_code, 403)
        self.assertEqual(logout.status_code, 200)
        self.assertEqual(after_logout.status_code, 401)
        status_payload = accepted.json()
        self.assertTrue(status_payload["authenticated"])

    def test_login_rate_limit_blocks_repeated_failures(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            state = _state(Path(temp_dir))
            settings = AuthSettings(
                enabled=True,
                username="admin",
                password_hash=hash_password(ADMIN_PASSWORD, salt=b"0123456789abcdef"),
                session_secret=SESSION_SECRET,
                login_attempt_limit=2,
                login_window_seconds=60,
            )
            app = create_fastapi_app(state, frontend_dist=None, auth_settings=settings)

            with TestClient(app) as client:
                responses = [_login(client, password="incorrect-password-value") for _ in range(3)]

        self.assertEqual([response.status_code for response in responses], [401, 401, 429])

    def test_math_pow_and_single_use_challenge_are_enforced(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            app = create_fastapi_app(
                _state(Path(temp_dir)),
                frontend_dist=None,
                auth_settings=_auth_settings(),
            )
            with TestClient(app) as client:
                challenge = _challenge(client)
                wrong_math = client.post(
                    "/api/v1/auth/login",
                    json=_login_payload(challenge, math_answer=_math_answer(challenge) + 1),
                )
                replay = client.post(
                    "/api/v1/auth/login",
                    json=_login_payload(challenge),
                )
                bad_pow_challenge = _challenge(client)
                bad_pow = client.post(
                    "/api/v1/auth/login",
                    json=_login_payload(bad_pow_challenge, pow_nonce=0, force_bad_pow=True),
                )

        self.assertEqual(wrong_math.status_code, 400)
        self.assertIn("数学验证码错误", wrong_math.json()["detail"])
        self.assertEqual(replay.status_code, 400)
        self.assertIn("已过期", replay.json()["detail"])
        self.assertEqual(bad_pow.status_code, 400)
        self.assertIn("PoW", bad_pow.json()["detail"])

    def test_production_readiness_requires_auth_proxy_and_safe_execution(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            config_store = RuntimeConfigStore(root)
            config_store.update(
                {
                    "exchange.proxy_pool": "http://finbot-egress-proxy:8888",
                    "paper_execution.submit_orders": False,
                    "paper_execution.require_human_review": True,
                }
            )
            state = FinBotWebApp(
                data_dir=str(root / "data"),
                config_store=config_store,
                ai_config_store=AISitesConfigStore(root),
            )
            settings = _auth_settings(cookie_secure=True)
            app = create_fastapi_app(state, frontend_dist=None, auth_settings=settings)

            with patch.dict("os.environ", {"FINBOT_DEPLOYMENT_MODE": "production"}):
                with TestClient(app) as client:
                    ready = client.get("/health/ready")

            config_store.update({"paper_execution.submit_orders": True})
            with patch.dict("os.environ", {"FINBOT_DEPLOYMENT_MODE": "production"}):
                with TestClient(app) as client:
                    blocked = client.get("/health/ready")

        self.assertEqual(ready.status_code, 200)
        self.assertEqual(ready.json()["status"], "ready")
        self.assertEqual(blocked.status_code, 503)
        production_check = next(
            item for item in blocked.json()["checks"] if item["name"] == "production_safety"
        )
        self.assertIn("不允许开启模拟订单提交", production_check["detail"])


def _auth_settings(cookie_secure: bool = False) -> AuthSettings:
    return AuthSettings(
        enabled=True,
        username="admin",
        password_hash=hash_password(ADMIN_PASSWORD, salt=b"0123456789abcdef"),
        session_secret=SESSION_SECRET,
        cookie_secure=cookie_secure,
        trusted_origins=("https://finbot.mnnu.eu.org",),
        pow_difficulty_bits=8,
    )


def _state(root: Path) -> FinBotWebApp:
    return FinBotWebApp(
        data_dir=str(root / "data"),
        config_store=RuntimeConfigStore(root),
        ai_config_store=AISitesConfigStore(root),
    )


def _login(client: TestClient, *, password: str) -> Any:
    challenge = _challenge(client)
    return client.post(
        "/api/v1/auth/login",
        json=_login_payload(challenge, password=password),
    )


def _challenge(client: TestClient) -> dict[str, Any]:
    response = client.get("/api/v1/auth/challenge")
    if response.status_code != 200:
        raise AssertionError(response.text)
    return response.json()["challenge"]


def _login_payload(
    challenge: dict[str, Any],
    *,
    password: str = ADMIN_PASSWORD,
    math_answer: int | None = None,
    pow_nonce: int | None = None,
    force_bad_pow: bool = False,
) -> dict[str, Any]:
    answer = _math_answer(challenge) if math_answer is None else math_answer
    nonce = _solve_pow(challenge) if pow_nonce is None else pow_nonce
    if force_bad_pow:
        while verify_proof_of_work(challenge["pow_prefix"], nonce, challenge["difficulty_bits"]):
            nonce += 1
    return {
        "username": "admin",
        "password": password,
        "challenge_id": challenge["challenge_id"],
        "math_answer": answer,
        "pow_nonce": nonce,
    }


def _math_answer(challenge: dict[str, Any]) -> int:
    left_text, operation, right_text, _equals, _question = challenge["math_question"].split()
    left = int(left_text)
    right = int(right_text)
    if operation == "+":
        return left + right
    if operation == "-":
        return left - right
    return left * right


def _solve_pow(challenge: dict[str, Any]) -> int:
    nonce = 0
    while not verify_proof_of_work(challenge["pow_prefix"], nonce, challenge["difficulty_bits"]):
        nonce += 1
    return nonce


if __name__ == "__main__":
    unittest.main()
