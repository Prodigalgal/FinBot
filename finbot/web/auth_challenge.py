from __future__ import annotations

import hashlib
import hmac
import secrets
import threading
import time
from dataclasses import dataclass
from typing import Any


DEFAULT_CHALLENGE_TTL_SECONDS = 120
DEFAULT_POW_DIFFICULTY_BITS = 16
MAX_OUTSTANDING_CHALLENGES = 10_000


@dataclass(frozen=True)
class LoginChallenge:
    challenge_id: str
    identity: str
    math_question: str
    math_answer: int
    pow_prefix: str
    difficulty_bits: int
    expires_at: int

    def public_payload(self) -> dict[str, Any]:
        return {
            "challenge_id": self.challenge_id,
            "math_question": self.math_question,
            "pow_prefix": self.pow_prefix,
            "difficulty_bits": self.difficulty_bits,
            "expires_at": self.expires_at,
        }


class LoginChallengeError(ValueError):
    pass


class LoginChallengeStore:
    def __init__(self, ttl_seconds: int, difficulty_bits: int):
        self.ttl_seconds = ttl_seconds
        self.difficulty_bits = difficulty_bits
        self._challenges: dict[str, LoginChallenge] = {}
        self._lock = threading.Lock()

    def issue(self, identity: str, now: int | None = None) -> LoginChallenge:
        timestamp = int(time.time()) if now is None else now
        left, operation, right, answer = _math_problem()
        challenge = LoginChallenge(
            challenge_id=secrets.token_urlsafe(24),
            identity=identity,
            math_question=f"{left} {operation} {right} = ?",
            math_answer=answer,
            pow_prefix=secrets.token_urlsafe(18),
            difficulty_bits=self.difficulty_bits,
            expires_at=timestamp + self.ttl_seconds,
        )
        with self._lock:
            self._prune(timestamp)
            if len(self._challenges) >= MAX_OUTSTANDING_CHALLENGES:
                oldest_id = min(self._challenges, key=lambda key: self._challenges[key].expires_at)
                self._challenges.pop(oldest_id, None)
            self._challenges[challenge.challenge_id] = challenge
        return challenge

    def consume_and_verify(
        self,
        *,
        challenge_id: str,
        identity: str,
        math_answer: int,
        pow_nonce: int,
        now: int | None = None,
    ) -> None:
        timestamp = int(time.time()) if now is None else now
        with self._lock:
            self._prune(timestamp)
            challenge = self._challenges.pop(challenge_id, None)
        if challenge is None or challenge.expires_at <= timestamp:
            raise LoginChallengeError("安全校验已过期，请刷新后重试")
        if not hmac.compare_digest(challenge.identity, identity):
            raise LoginChallengeError("安全校验与当前请求不匹配，请刷新后重试")
        if not hmac.compare_digest(str(challenge.math_answer), str(math_answer)):
            raise LoginChallengeError("数学验证码错误，请刷新后重试")
        if not verify_proof_of_work(challenge.pow_prefix, pow_nonce, challenge.difficulty_bits):
            raise LoginChallengeError("PoW 安全校验失败，请刷新后重试")

    def _prune(self, now: int) -> None:
        expired_ids = [
            challenge_id
            for challenge_id, challenge in self._challenges.items()
            if challenge.expires_at <= now
        ]
        for challenge_id in expired_ids:
            self._challenges.pop(challenge_id, None)


def verify_proof_of_work(prefix: str, nonce: int, difficulty_bits: int) -> bool:
    if nonce < 0 or not 0 <= difficulty_bits <= 256:
        return False
    digest = hashlib.sha256(f"{prefix}:{nonce}".encode("utf-8")).digest()
    full_bytes, remaining_bits = divmod(difficulty_bits, 8)
    if any(digest[index] != 0 for index in range(full_bytes)):
        return False
    if remaining_bits == 0:
        return True
    return digest[full_bytes] >> (8 - remaining_bits) == 0


def _math_problem() -> tuple[int, str, int, int]:
    operation = secrets.choice(("+", "-", "x"))
    if operation == "x":
        left = secrets.randbelow(11) + 2
        right = secrets.randbelow(11) + 2
        return left, operation, right, left * right
    left = secrets.randbelow(81) + 10
    right = secrets.randbelow(41) + 2
    if operation == "+":
        return left, operation, right, left + right
    if right > left:
        left, right = right, left
    return left, operation, right, left - right
