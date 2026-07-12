# syntax=docker/dockerfile:1.7

FROM node:22-bookworm-slim AS web-builder
WORKDIR /build/web-ui
COPY web-ui/package.json web-ui/package-lock.json ./
RUN npm ci
COPY web-ui/ ./
RUN npm run build

FROM python:3.12-slim-bookworm AS runtime
ARG TARGETARCH
ARG SING_BOX_VERSION=1.13.14
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    FINBOT_RUNTIME_ROOT=/var/lib/finbot
WORKDIR /app

RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates curl tinyproxy \
    && case "$TARGETARCH" in \
         amd64) SING_BOX_SHA256="f48703461a15476951ac4967cdad339d986f4b8096b4eb3ff0829a500502d697" ;; \
         arm64) SING_BOX_SHA256="4742df6a4314e8ecc41736849fca6d73b8f9e91b6e8b06ee794ff17ba180579e" ;; \
         *) echo "Unsupported sing-box architecture: $TARGETARCH" >&2; exit 1 ;; \
       esac \
    && curl --fail --location --retry 3 --output /tmp/sing-box.tar.gz \
       "https://github.com/SagerNet/sing-box/releases/download/v${SING_BOX_VERSION}/sing-box-${SING_BOX_VERSION}-linux-${TARGETARCH}.tar.gz" \
    && echo "${SING_BOX_SHA256}  /tmp/sing-box.tar.gz" | sha256sum --check --strict \
    && tar --extract --gzip --file /tmp/sing-box.tar.gz --directory /tmp \
    && install --mode 0755 "/tmp/sing-box-${SING_BOX_VERSION}-linux-${TARGETARCH}/sing-box" /usr/local/bin/sing-box \
    && /usr/local/bin/sing-box version \
    && rm -rf /tmp/sing-box.tar.gz "/tmp/sing-box-${SING_BOX_VERSION}-linux-${TARGETARCH}" \
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
