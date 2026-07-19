# syntax=docker/dockerfile:1.7

# Keep base image and Python package versions locked together.
FROM mcr.microsoft.com/playwright/python:v1.52.0-noble

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PORT=8082 \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    HOME=/home/finbot \
    PATH=/app/.venv/bin:$PATH \
    VIRTUAL_ENV=/app/.venv

WORKDIR /app

USER root
# Patch known HIGH OS CVEs present in the Playwright base image so CI Trivy gates pass.
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ca-certificates \
        python3-venv \
    && DEBIAN_FRONTEND=noninteractive apt-get upgrade -y \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --gid 10001 finbot \
    && useradd --uid 10001 --gid finbot --shell /usr/sbin/nologin --create-home finbot \
    && mkdir -p /tmp /home/finbot \
    && chown -R finbot:finbot /app /tmp /home/finbot /ms-playwright

COPY pyproject.toml README.md ./
COPY src/ ./src/
RUN python3 -m venv /app/.venv \
    && /app/.venv/bin/pip install --upgrade pip \
    && /app/.venv/bin/pip install . \
    && chown -R finbot:finbot /app /ms-playwright

USER 10001:10001
EXPOSE 8082
ENTRYPOINT ["finbot-browser-worker"]
