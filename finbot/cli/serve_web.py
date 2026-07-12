from __future__ import annotations

import argparse

import uvicorn

from finbot.observability.logging import configure_logging
from finbot.web import FinBotWebApp, create_fastapi_app


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="启动统一 FinBot 网页服务。")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8780)
    parser.add_argument("--catalog", default="config/source_catalog.example.yml")
    parser.add_argument("--topics", default="config/topic_watchlists.example.yml")
    parser.add_argument("--frontend-dist", default="web-ui/dist")
    parser.add_argument("--reload", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    logger = configure_logging("web")
    state = FinBotWebApp(data_dir=args.data_dir, catalog_path=args.catalog, topics_path=args.topics)
    app = create_fastapi_app(state, frontend_dist=args.frontend_dist)
    logger.info(
        "web_listening",
        extra={"event": "web_listening", "component": "web", "status": f"{args.host}:{args.port}"},
    )
    uvicorn.run(app, host=args.host, port=args.port, reload=args.reload, log_config=None)


if __name__ == "__main__":
    main()
