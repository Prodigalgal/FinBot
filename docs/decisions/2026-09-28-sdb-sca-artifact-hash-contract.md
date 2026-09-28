# SDB-SCA artifact hash contract

Status: Accepted
Date: 2026-09-28

## Task spec

- Goal: allow completed SDB-SCA AI outputs to pass persistence integrity validation.
- Scope: share one SHA-256 rule between the artifact producer and JDBC persistence validator.
- Non-goals: changing debate phase identity, retry policy, JSON serialization, or any database schema.
- Affected files: `DebateArtifact`, `SdbScaPhaseExecutor`, `JdbcDebateProtocolStore`, and their tests.
- Acceptance: artifact hashes equal SHA-256 over the exact UTF-8 content bytes; replay and PostgreSQL persistence tests pass.
- Verification: domain hash vector, SDB-SCA application test, PostgreSQL integration tests, and backend build.

## Decision

`DebateArtifact.contentHashFor` defines the stored content hash as SHA-256 over the exact UTF-8 bytes of the artifact content. The application creates hashes through this domain method, and JDBC validates through the same method. `WorkflowExecutionIds.sha256` remains unchanged for multi-part identifiers and task seeds.

The previous application path used the multi-part identifier digest, which appends a field separator. JDBC correctly calculated SHA-256 over the raw content bytes, so every generated artifact failed validation and remained unpersisted. Existing persisted hashes and the database schema need no migration. Reverting the code would reintroduce failed artifact writes; rolling back the release can restore the prior build while the fix is redeployed.
