from __future__ import annotations

import os
from functools import lru_cache

from finbot.config.settings import Settings
from finbot.storage.postgres_store import PostgresStore
from finbot.storage.sqlite_store import SQLiteStore


@lru_cache(maxsize=4)
def _postgres_store(database_url: str) -> PostgresStore:
    store = PostgresStore(database_url)
    store.init_schema()
    return store


def create_runtime_store(settings: Settings) -> SQLiteStore:
    if settings.database_url:
        return _postgres_store(settings.database_url)
    deployment_mode = os.getenv("FINBOT_DEPLOYMENT_MODE", "development").strip().lower()
    if deployment_mode == "production":
        raise RuntimeError("生产模式必须配置 FINBOT_DATABASE_URL；SQLite 运行时已移除")
    store = SQLiteStore(settings.sqlite_path)
    store.init_schema()
    return store
