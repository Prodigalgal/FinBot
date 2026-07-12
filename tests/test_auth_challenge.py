from __future__ import annotations

import unittest

from finbot.web.auth_challenge import (
    LoginChallengeError,
    LoginChallengeStore,
    verify_proof_of_work,
)


class LoginChallengeTests(unittest.TestCase):
    def test_public_payload_omits_answer_and_identity(self) -> None:
        challenge = LoginChallengeStore(ttl_seconds=30, difficulty_bits=8).issue("client-a", now=100)

        payload = challenge.public_payload()

        self.assertNotIn("math_answer", payload)
        self.assertNotIn("identity", payload)
        self.assertEqual(payload["difficulty_bits"], 8)

    def test_expired_and_wrong_identity_challenges_are_consumed(self) -> None:
        store = LoginChallengeStore(ttl_seconds=30, difficulty_bits=8)
        expired = store.issue("client-a", now=100)
        with self.assertRaisesRegex(LoginChallengeError, "已过期"):
            store.consume_and_verify(
                challenge_id=expired.challenge_id,
                identity="client-a",
                math_answer=expired.math_answer,
                pow_nonce=_solve(expired.pow_prefix, expired.difficulty_bits),
                now=131,
            )

        mismatched = store.issue("client-a", now=200)
        nonce = _solve(mismatched.pow_prefix, mismatched.difficulty_bits)
        with self.assertRaisesRegex(LoginChallengeError, "不匹配"):
            store.consume_and_verify(
                challenge_id=mismatched.challenge_id,
                identity="client-b",
                math_answer=mismatched.math_answer,
                pow_nonce=nonce,
                now=201,
            )
        with self.assertRaisesRegex(LoginChallengeError, "已过期"):
            store.consume_and_verify(
                challenge_id=mismatched.challenge_id,
                identity="client-a",
                math_answer=mismatched.math_answer,
                pow_nonce=nonce,
                now=202,
            )


def _solve(prefix: str, difficulty_bits: int) -> int:
    nonce = 0
    while not verify_proof_of_work(prefix, nonce, difficulty_bits):
        nonce += 1
    return nonce


if __name__ == "__main__":
    unittest.main()
