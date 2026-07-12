from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml


class TopicWatchlists:
    def __init__(self, topics: dict[str, Any], query_expansion_rules: dict[str, Any] | None = None):
        self.topics = topics
        self.query_expansion_rules = query_expansion_rules or {}

    @classmethod
    def load(cls, path: str | Path) -> "TopicWatchlists":
        p = Path(path)
        data = yaml.safe_load(p.read_text(encoding="utf-8")) or {}
        return cls(
            topics=data.get("topics") or {},
            query_expansion_rules=data.get("query_expansion_rules") or {},
        )

    def enabled_queries(self, limit: int | None = None) -> list[str]:
        queries: list[str] = []
        for topic in self.topics.values():
            if not topic.get("enabled", True):
                continue
            for query in topic.get("base_queries") or []:
                queries.append(query)
                if limit and len(queries) >= limit:
                    return queries
        return queries

