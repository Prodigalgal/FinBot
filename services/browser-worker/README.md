# FinBot Browser Worker (C2)

Playwright-based internal service that loads a target URL in a real Chromium session,
waits for JS challenges to settle, and returns cookies / final URL for the Java crawler.

The service is a C2 capability, not a default crawler fallback. Java calls it only when
the source's `CrawlerHeaderProfile` explicitly enables `BROWSER_WORKER` after C1 has
classified a supported challenge.

## Endpoints

- `GET /internal/v1/health` — liveness/readiness
- `POST /internal/v1/challenge/solve` — solve JS / WAF interstitials

Auth: `Authorization: Bearer <FINBOT_BROWSER_WORKER_TOKEN>`

## Runtime boundaries

- One Chromium process is shared; each solve request creates and closes an isolated browser context.
- Every browser context receives the configured `FINBOT_BROWSER_WORKER_PROXY_URL`; there is no
  direct-connect fallback. The Kubernetes NetworkPolicy only permits DNS and the
  `finbot-web-crawl-proxy:8080` bridge.
- TLS verification remains enabled. Unresolved challenge pages are returned with
  `detail=playwright-chromium;challenge-unresolved` and must remain fail-closed in Java.
- The Kubernetes service is cluster-internal and only Backend ingress is allowed.
- Capacity is bounded by `FINBOT_BROWSER_WORKER_MAX_CONCURRENT_SOLVES` and a bounded acquire
  wait (`FINBOT_BROWSER_WORKER_ACQUIRE_TIMEOUT_MS`). Capacity exhaustion returns HTTP 429 with
  `Retry-After: 1`; health reports active, waiting, rejected, completed and failed counts.
- The current production profile still keeps C2 disabled. Enabling a source requires a controlled
  proxy egress check and an explicit solve/replay/rollback smoke; deployment alone is not proof of
  challenge resolution.
- Cookies, internal bearer tokens and request headers must not be written to logs.

## Local run

```bash
python -m pip install .
playwright install chromium
export FINBOT_BROWSER_WORKER_TOKEN=dev-token
export FINBOT_BROWSER_WORKER_PROXY_URL=http://127.0.0.1:18080
export FINBOT_BROWSER_WORKER_MAX_CONCURRENT_SOLVES=2
export FINBOT_BROWSER_WORKER_ACQUIRE_TIMEOUT_MS=5000
finbot-browser-worker
```

## Verification

```bash
python -m pip install -e ".[dev]"
python -m pytest -q
```

The tests cover service authentication, health readiness, mandatory proxy injection, bounded
capacity, challenge classification and Java adapter replay validation. Production C2 enablement
and rollback remain a separate controlled smoke gate.
