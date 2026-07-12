from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone

from finbot.config.source_catalog import SourceCatalog
from finbot.config.topic_watchlist import TopicWatchlists
from finbot.ingestion.scheduler import SourceScheduler


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Preview fetch jobs generated from the source catalog.")
    parser.add_argument("--catalog", default="config/source_catalog.example.yml")
    parser.add_argument("--topics", default="config/topic_watchlists.example.yml")
    parser.add_argument("--source", action="append")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    catalog = SourceCatalog.load(args.catalog)
    topics = TopicWatchlists.load(args.topics)
    scheduler = SourceScheduler(topics)
    selected = set(args.source or [])
    now = datetime.now(timezone.utc)
    jobs = []
    for source in catalog.sources:
        if selected and source.id not in selected:
            continue
        for job in scheduler.jobs_for_source(source, now):
            jobs.append(job.model_dump(mode="json"))
    print(json.dumps({"job_count": len(jobs), "jobs": jobs}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

