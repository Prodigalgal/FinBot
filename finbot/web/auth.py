from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import secrets
import threading
import time
from collections import defaultdict, deque
from dataclasses import dataclass
from typing import Any

from fastapi import Request
from pydantic import BaseModel, Field
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.responses import JSONResponse, Response

from finbot.web.auth_challenge import (
    DEFAULT_CHALLENGE_TTL_SECONDS,
    DEFAULT_POW_DIFFICULTY_BITS,
    LoginChallengeError,
    LoginChallengeStore,
    verify_proof_of_work,
)


DEFAULT_COOKIE_NAME = "finbot_session"
DEFAULT_SESSION_TTL_SECONDS = 8 * 60 * 60
DEFAULT_PASSWORD_ITERATIONS = 310_000
PUBLIC_PATHS = frozenset(
    {
        "/health",
        "/health/live",
        "/health/ready",
        "/api/v1/auth/status",
        "/api/v1/auth/challenge",
        "/api/v1/auth/login",
        "/api/v1/auth/logout",
    }
)
PROTECTED_NON_API_PATHS = frozenset({"/docs", "/openapi.json", "/redoc"})


class LoginRequest(BaseModel):
    username: str = Field(min_length=1, max_length=100)
    password: str = Field(min_length=1, max_length=500)
    challenge_id: str = Field(min_length=20, max_length=200)
    math_answer: int
    pow_nonce: int = Field(ge=0, le=2**53 - 1)


@dataclass(frozen=True)
class AuthSettings:
    enabled: bool = False
    username: str = "admin"
    password_hash: str | None = None
    password: str | None = None
    session_secret: str | None = None
    cookie_name: str = DEFAULT_COOKIE_NAME
    cookie_secure: bool = False
    session_ttl_seconds: int = DEFAULT_SESSION_TTL_SECONDS
    trusted_origins: tuple[str, ...] = ()
    login_attempt_limit: int = 5
    login_window_seconds: int = 300
    challenge_ttl_seconds: int = DEFAULT_CHALLENGE_TTL_SECONDS
    pow_difficulty_bits: int = DEFAULT_POW_DIFFICULTY_BITS

    @classmethod
    def from_env(cls) -> "AuthSettings":
        return cls(
            enabled=_env_bool("FINBOT_AUTH_ENABLED", False),
            username=os.getenv("FINBOT_ADMIN_USERNAME", "admin").strip(),
            password_hash=os.getenv("FINBOT_ADMIN_PASSWORD_HASH") or None,
            password=os.getenv("FINBOT_ADMIN_PASSWORD") or None,
            session_secret=os.getenv("FINBOT_SESSION_SECRET") or None,
            cookie_name=os.getenv("FINBOT_AUTH_COOKIE_NAME", DEFAULT_COOKIE_NAME).strip(),
            cookie_secure=_env_bool("FINBOT_AUTH_COOKIE_SECURE", False),
            session_ttl_seconds=_env_int(
                "FINBOT_AUTH_SESSION_TTL_SECONDS",
                DEFAULT_SESSION_TTL_SECONDS,
            ),
            trusted_origins=tuple(
                value.strip().rstrip("/")
                for value in os.getenv("FINBOT_AUTH_TRUSTED_ORIGINS", "").split(",")
                if value.strip()
            ),
            login_attempt_limit=_env_int("FINBOT_AUTH_LOGIN_ATTEMPT_LIMIT", 5),
            login_window_seconds=_env_int("FINBOT_AUTH_LOGIN_WINDOW_SECONDS", 300),
            challenge_ttl_seconds=_env_int(
                "FINBOT_AUTH_CHALLENGE_TTL_SECONDS",
                DEFAULT_CHALLENGE_TTL_SECONDS,
            ),
            pow_difficulty_bits=_env_int(
                "FINBOT_AUTH_POW_DIFFICULTY_BITS",
                DEFAULT_POW_DIFFICULTY_BITS,
            ),
        ).validated()

    def validated(self) -> "AuthSettings":
        if not self.enabled:
            return self
        errors: list[str] = []
        if not self.username:
            errors.append("FINBOT_ADMIN_USERNAME 不能为空")
        if not self.password_hash and not self.password:
            errors.append("必须配置 FINBOT_ADMIN_PASSWORD_HASH")
        if self.password and len(self.password) < 8:
            errors.append("FINBOT_ADMIN_PASSWORD 至少需要 8 个字符")
        if not self.session_secret or len(self.session_secret) < 32:
            errors.append("FINBOT_SESSION_SECRET 至少需要 32 个字符")
        if not self.cookie_name:
            errors.append("FINBOT_AUTH_COOKIE_NAME 不能为空")
        if not 300 <= self.session_ttl_seconds <= 7 * 24 * 60 * 60:
            errors.append("FINBOT_AUTH_SESSION_TTL_SECONDS 必须在 300 到 604800 之间")
        if not 1 <= self.login_attempt_limit <= 100:
            errors.append("FINBOT_AUTH_LOGIN_ATTEMPT_LIMIT 必须在 1 到 100 之间")
        if not 10 <= self.login_window_seconds <= 3600:
            errors.append("FINBOT_AUTH_LOGIN_WINDOW_SECONDS 必须在 10 到 3600 之间")
        if not 30 <= self.challenge_ttl_seconds <= 600:
            errors.append("FINBOT_AUTH_CHALLENGE_TTL_SECONDS 必须在 30 到 600 之间")
        if not 8 <= self.pow_difficulty_bits <= 24:
            errors.append("FINBOT_AUTH_POW_DIFFICULTY_BITS 必须在 8 到 24 之间")
        if errors:
            raise RuntimeError("FinBot 认证配置无效：" + "；".join(errors))
        return self


@dataclass(frozen=True)
class SessionInfo:
    username: str
    expires_at: int

    def to_dict(self) -> dict[str, Any]:
        return {
            "username": self.username,
            "expires_at": self.expires_at,
        }


class LoginRateLimiter:
    def __init__(self, limit: int, window_seconds: int):
        self.limit = limit
        self.window_seconds = window_seconds
        self._attempts: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def allow(self, identity: str, now: float | None = None) -> bool:
        timestamp = now if now is not None else time.monotonic()
        with self._lock:
            attempts = self._attempts[identity]
            cutoff = timestamp - self.window_seconds
            while attempts and attempts[0] <= cutoff:
                attempts.popleft()
            if len(attempts) >= self.limit:
                return False
            attempts.append(timestamp)
            return True

    def reset(self, identity: str) -> None:
        with self._lock:
            self._attempts.pop(identity, None)


class AuthManager:
    def __init__(self, settings: AuthSettings):
        self.settings = settings.validated()
        self.rate_limiter = LoginRateLimiter(
            settings.login_attempt_limit,
            settings.login_window_seconds,
        )
        self.challenge_store = LoginChallengeStore(
            settings.challenge_ttl_seconds,
            settings.pow_difficulty_bits,
        )

    def issue_login_challenge(self, identity: str) -> dict[str, Any]:
        if not self.settings.enabled:
            return {"enabled": False, "challenge": None}
        return {
            "enabled": True,
            "challenge": self.challenge_store.issue(identity).public_payload(),
        }

    def authenticate(
        self,
        username: str,
        password: str,
        identity: str,
        *,
        challenge_id: str,
        math_answer: int,
        pow_nonce: int,
    ) -> SessionInfo | None:
        if not self.settings.enabled:
            return SessionInfo(username=self.settings.username, expires_at=0)
        if not self.rate_limiter.allow(identity):
            raise PermissionError("登录尝试过于频繁，请稍后重试")
        self.challenge_store.consume_and_verify(
            challenge_id=challenge_id,
            identity=identity,
            math_answer=math_answer,
            pow_nonce=pow_nonce,
        )
        username_matches = hmac.compare_digest(username, self.settings.username)
        password_matches = self._verify_password(password)
        if not username_matches or not password_matches:
            return None
        self.rate_limiter.reset(identity)
        return SessionInfo(
            username=self.settings.username,
            expires_at=int(time.time()) + self.settings.session_ttl_seconds,
        )

    def issue_cookie(self, response: Response, session: SessionInfo) -> None:
        response.set_cookie(
            key=self.settings.cookie_name,
            value=self._encode_session(session),
            max_age=self.settings.session_ttl_seconds,
            httponly=True,
            secure=self.settings.cookie_secure,
            samesite="strict",
            path="/",
        )

    def clear_cookie(self, response: Response) -> None:
        response.delete_cookie(
            key=self.settings.cookie_name,
            path="/",
            secure=self.settings.cookie_secure,
            httponly=True,
            samesite="strict",
        )

    def session_for_request(self, request: Request) -> SessionInfo | None:
        if not self.settings.enabled:
            return SessionInfo(username=self.settings.username, expires_at=0)
        token = request.cookies.get(self.settings.cookie_name)
        return self._decode_session(token) if token else None

    def origin_allowed(self, request: Request) -> bool:
        origin = request.headers.get("origin")
        if not origin:
            return True
        normalized = origin.rstrip("/")
        return normalized in self.settings.trusted_origins

    def _verify_password(self, password: str) -> bool:
        if self.settings.password_hash:
            return verify_password(password, self.settings.password_hash)
        return bool(
            self.settings.password
            and hmac.compare_digest(password, self.settings.password)
        )

    def _encode_session(self, session: SessionInfo) -> str:
        payload = {
            "username": session.username,
            "expires_at": session.expires_at,
            "nonce": secrets.token_urlsafe(12),
        }
        encoded = _b64encode(
            json.dumps(payload, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
        )
        signature = hmac.new(
            self.settings.session_secret.encode("utf-8"),
            encoded.encode("ascii"),
            hashlib.sha256,
        ).digest()
        return f"{encoded}.{_b64encode(signature)}"

    def _decode_session(self, token: str) -> SessionInfo | None:
        try:
            encoded, raw_signature = token.split(".", 1)
            expected = hmac.new(
                self.settings.session_secret.encode("utf-8"),
                encoded.encode("ascii"),
                hashlib.sha256,
            ).digest()
            if not hmac.compare_digest(expected, _b64decode(raw_signature)):
                return None
            payload = json.loads(_b64decode(encoded).decode("utf-8"))
            username = str(payload["username"])
            expires_at = int(payload["expires_at"])
        except (KeyError, TypeError, ValueError, json.JSONDecodeError):
            return None
        if not hmac.compare_digest(username, self.settings.username):
            return None
        if expires_at <= int(time.time()):
            return None
        return SessionInfo(username=username, expires_at=expires_at)


class AuthenticationMiddleware(BaseHTTPMiddleware):
    def __init__(self, app: Any, manager: AuthManager):
        super().__init__(app)
        self.manager = manager

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        settings = self.manager.settings
        if not settings.enabled or request.method == "OPTIONS" or _is_public_path(request.url.path):
            return await call_next(request)
        if not _is_protected_path(request.url.path):
            return await call_next(request)
        session = self.manager.session_for_request(request)
        if session is None:
            return JSONResponse(
                status_code=401,
                content={"detail": "需要登录", "code": "authentication_required"},
            )
        if request.method not in {"GET", "HEAD", "OPTIONS"} and not self.manager.origin_allowed(request):
            return JSONResponse(
                status_code=403,
                content={"detail": "请求来源不受信任", "code": "origin_not_allowed"},
            )
        request.state.finbot_session = session
        return await call_next(request)


def hash_password(
    password: str,
    *,
    iterations: int = DEFAULT_PASSWORD_ITERATIONS,
    salt: bytes | None = None,
) -> str:
    if len(password) < 16:
        raise ValueError("管理员密码至少需要 16 个字符")
    actual_salt = salt or secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256",
        password.encode("utf-8"),
        actual_salt,
        iterations,
    )
    return f"pbkdf2_sha256${iterations}${_b64encode(actual_salt)}${_b64encode(digest)}"


def verify_password(password: str, encoded_hash: str) -> bool:
    try:
        algorithm, iterations_text, salt_text, digest_text = encoded_hash.split("$", 3)
        if algorithm != "pbkdf2_sha256":
            return False
        iterations = int(iterations_text)
        if not 100_000 <= iterations <= 2_000_000:
            return False
        salt = _b64decode(salt_text)
        expected = _b64decode(digest_text)
    except (TypeError, ValueError):
        return False
    actual = hashlib.pbkdf2_hmac(
        "sha256",
        password.encode("utf-8"),
        salt,
        iterations,
    )
    return hmac.compare_digest(actual, expected)


def _is_public_path(path: str) -> bool:
    return path in PUBLIC_PATHS or path.startswith("/assets/")


def _is_protected_path(path: str) -> bool:
    return path.startswith("/api/") or path in PROTECTED_NON_API_PATHS


def _b64encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _b64decode(value: str) -> bytes:
    padding = "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode(value + padding)


def _env_bool(key: str, default: bool) -> bool:
    raw = os.getenv(key)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _env_int(key: str, default: int) -> int:
    raw = os.getenv(key)
    if raw is None or not raw.strip():
        return default
    try:
        return int(raw)
    except ValueError as exc:
        raise RuntimeError(f"{key} 必须是整数") from exc
