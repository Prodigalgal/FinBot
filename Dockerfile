# syntax=docker/dockerfile:1.7

FROM node:22-bookworm-slim AS web-builder
WORKDIR /build/web-ui
COPY web-ui/package.json web-ui/package-lock.json ./
RUN npm ci
COPY web-ui/ ./
RUN npm run build

FROM python:3.12-slim-bookworm AS runtime
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    FINBOT_RUNTIME_ROOT=/var/lib/finbot
WORKDIR /app

RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates tinyproxy \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 finbot \
    && useradd --uid 10001 --gid finbot --shell /usr/sbin/nologin --create-home finbot

COPY pyproject.toml ./
COPY finbot/ ./finbot/
COPY config/source_catalog.example.yml config/topic_watchlists.example.yml config/runtime_config.example.json ./config/
COPY --from=web-builder /build/web-ui/dist ./web-ui/dist/

RUN pip install --no-cache-dir . \
    && install -d -o finbot -g finbot /var/lib/finbot/data /var/lib/finbot/config

USER 10001:10001
EXPOSE 8780
CMD ["python", "-m", "finbot.cli.serve_web", "--data-dir", "/var/lib/finbot/data", "--host", "0.0.0.0", "--port", "8780", "--catalog", "/app/config/source_catalog.example.yml", "--topics", "/app/config/topic_watchlists.example.yml", "--frontend-dist", "/app/web-ui/dist"]
