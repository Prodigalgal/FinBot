# syntax=docker/dockerfile:1.7

FROM python:3.13-slim-bookworm
ARG TARGETARCH
ARG SING_BOX_VERSION=1.13.14
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    SING_BOX_PATH=/usr/local/bin/sing-box
WORKDIR /app

RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates curl \
    && case "$TARGETARCH" in \
         amd64) SING_BOX_SHA256="f48703461a15476951ac4967cdad339d986f4b8096b4eb3ff0829a500502d697" ;; \
         arm64) SING_BOX_SHA256="4742df6a4314e8ecc41736849fca6d73b8f9e91b6e8b06ee794ff17ba180579e" ;; \
         *) echo "Unsupported architecture: $TARGETARCH" >&2; exit 1 ;; \
       esac \
    && curl --fail --location --retry 3 --output /tmp/sing-box.tar.gz \
       "https://github.com/SagerNet/sing-box/releases/download/v${SING_BOX_VERSION}/sing-box-${SING_BOX_VERSION}-linux-${TARGETARCH}.tar.gz" \
    && echo "${SING_BOX_SHA256}  /tmp/sing-box.tar.gz" | sha256sum --check --strict \
    && tar --extract --gzip --file /tmp/sing-box.tar.gz --directory /tmp \
    && install --mode 0755 "/tmp/sing-box-${SING_BOX_VERSION}-linux-${TARGETARCH}/sing-box" /usr/local/bin/sing-box \
    && rm -rf /tmp/sing-box.tar.gz "/tmp/sing-box-${SING_BOX_VERSION}-linux-${TARGETARCH}" /var/lib/apt/lists/* \
    && groupadd --gid 10001 finbot \
    && useradd --uid 10001 --gid finbot --shell /usr/sbin/nologin --no-create-home finbot

COPY pyproject.toml ./
COPY src/ ./src/
RUN python -m pip install .

USER 10001:10001
EXPOSE 8080 8081
ENTRYPOINT ["finbot-proxy-gateway"]
