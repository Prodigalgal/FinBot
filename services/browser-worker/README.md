# FinBot Browser Worker (C2)

Playwright-based internal service that loads a target URL in a real Chromium session,
waits for JS challenges to settle, and returns cookies / final URL for the Java crawler.

## Endpoints

- `GET /internal/v1/health` — liveness/readiness
- `POST /internal/v1/challenge/solve` — solve JS / WAF interstitials

Auth: `Authorization: Bearer <FINBOT_BROWSER_WORKER_TOKEN>`

## Local run

```bash
python -m pip install .
playwright install chromium
export FINBOT_BROWSER_WORKER_TOKEN=dev-token
finbot-browser-worker
```
