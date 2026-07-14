# syntax=docker/dockerfile:1.7

FROM --platform=$BUILDPLATFORM golang:1.25.12-bookworm AS sing-box-builder
ARG SING_BOX_VERSION=1.13.14
ARG SING_BOX_COMMIT=25a600db24f7680ad9806ce5427bd0ab8afe1114
ARG TARGETOS
ARG TARGETARCH

WORKDIR /source/sing-box
RUN git init . \
    && git remote add origin https://github.com/SagerNet/sing-box.git \
    && git fetch --depth 1 origin "${SING_BOX_COMMIT}" \
    && git checkout --detach FETCH_HEAD \
    && test "$(git rev-parse HEAD)" = "${SING_BOX_COMMIT}" \
    && go get golang.org/x/crypto@v0.53.0 golang.org/x/net@v0.55.0 \
    && mkdir -p /out \
    && BUILD_TAGS="$(cat release/DEFAULT_BUILD_TAGS_OTHERS)" \
    && CGO_ENABLED=0 GOOS="${TARGETOS}" GOARCH="${TARGETARCH}" \
       go build -trimpath -tags "${BUILD_TAGS}" \
       -ldflags "-X github.com/sagernet/sing-box/constant.Version=${SING_BOX_VERSION} -X internal/godebug.defaultGODEBUG=multipathtcp=0 -checklinkname=0 -s -w -buildid=" \
       -o /out/sing-box ./cmd/sing-box

FROM python:3.13-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    SING_BOX_PATH=/usr/local/bin/sing-box
WORKDIR /app

COPY --from=sing-box-builder /out/sing-box /usr/local/bin/sing-box
RUN groupadd --gid 10001 finbot \
    && useradd --uid 10001 --gid finbot --shell /usr/sbin/nologin --no-create-home finbot

COPY pyproject.toml ./
COPY src/ ./src/
RUN python -m pip install .

USER 10001:10001
EXPOSE 8080 8081
ENTRYPOINT ["finbot-proxy-gateway"]
