from __future__ import annotations

import hashlib
import re


def normalize_title(title: str | None) -> str:
    value = (title or "").lower()
    value = re.sub(r"\s+", " ", value)
    value = re.sub(r"[^a-z0-9\u4e00-\u9fff ]+", "", value)
    return value.strip()


def content_hash(text: str | bytes | None) -> str:
    if text is None:
        text = ""
    if isinstance(text, str):
        payload = text.encode("utf-8", errors="ignore")
    else:
        payload = text
    return hashlib.sha256(payload).hexdigest()

