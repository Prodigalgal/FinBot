from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

from finbot.config.ai_sites import AISitesConfigStore
from finbot.config.runtime_config import RuntimeConfigStore
from finbot.web.auth import AuthSettings, hash_password, verify_password
from finbot.web.service import FinBotWebApp, create_fastapi_app


ADMIN_PASSWORD = "correct-horse-battery-staple"
SESSION_SECRET = "session-secret-with-at-least-32-characters"


class WebAuthenticationTests(unittest.TestCase):
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
                rejected = client.post(
                    "/api/v1/auth/login",
                    json={"username": "admin", "password": "incorrect-password-value"},
                )
                accepted = client.post(
                    "/api/v1/auth/login",
                    json={"username": "admin", "password": ADMIN_PASSWORD},
                )
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
                responses = [
                    client.post(
                        "/api/v1/auth/login",
                        json={"username": "admin", "password": "incorrect-password-value"},
                    )
                    for _ in range(3)
                ]

        self.assertEqual([response.status_code for response in responses], [401, 401, 429])

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
    )


def _state(root: Path) -> FinBotWebApp:
    return FinBotWebApp(
        data_dir=str(root / "data"),
        config_store=RuntimeConfigStore(root),
        ai_config_store=AISitesConfigStore(root),
    )


if __name__ == "__main__":
    unittest.main()

