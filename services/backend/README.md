# FinBot Java Backend

Breaking-rewrite backend for FinBot v2.

## Runtime

- Java 26
- Spring Boot 4.1
- Spring MVC with virtual threads
- Spring Data JDBC and Spring `JdbcClient`
- Liquibase and PostgreSQL

## Modules

- `finbot-domain`: pure Java domain types and invariants.
- `finbot-application`: use cases and inbound/outbound ports.
- `finbot-infrastructure`: PostgreSQL, provider, exchange and Python quant HTTP/SSE adapters.
- `finbot-bootstrap`: Spring Boot composition, REST and SSE adapters.

The archived Python implementation is a migration oracle only. The sibling `../quant` service is an
isolated, typed research boundary reached through `../../contracts/quant-research.openapi.yaml`; no
Java module may depend on Python source code or the legacy database contract.

`FINBOT_QUANT_SERVICE_TOKEN` is mandatory at runtime and must be injected from a K8S Secret. It has no
source-controlled default.

## Build

```powershell
$env:JAVA_HOME = 'D:\DevlopEnv\JDK\jdk-26.0.1'
.\gradlew.bat clean test
```
