from __future__ import annotations

from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit


TRACKING_PREFIXES = ("utm_",)
TRACKING_KEYS = {"fbclid", "gclid", "yclid", "mc_cid", "mc_eid", "ref", "spm"}


def canonicalize_url(url: str | None) -> str | None:
    if not url:
        return None
    parts = urlsplit(url.strip())
    query_items = []
    for key, value in parse_qsl(parts.query, keep_blank_values=True):
        lower = key.lower()
        if lower in TRACKING_KEYS or any(lower.startswith(prefix) for prefix in TRACKING_PREFIXES):
            continue
        query_items.append((key, value))
    host = parts.netloc.lower()
    path = parts.path.rstrip("/") or parts.path
    return urlunsplit((parts.scheme.lower(), host, path, urlencode(query_items), ""))

