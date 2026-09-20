# SentinelOps AI Implementation Plan Index

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver SentinelOps AI from the approved design as one continuous, test-first product line: a verified Demo milestone followed immediately by real integrations and a production-ready `v1.0.0`.

**Architecture:** A Spring Boot modular monolith owns incident, diagnosis, approval, execution-control, identity, audit, and evaluation state. A separate least-privilege Java executor consumes transactional-outbox notifications, atomically claims approved work, verifies short-lived asymmetric tickets, invokes allowlisted adapters, and reports results; a React console presents the incident-centered workflow. PostgreSQL is the sole business source of truth, while Redis-compatible Streams only wake executors.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring Modulith 2.1.1, Spring AI 2.0.1, PostgreSQL 17 + pgvector, Valkey/Redis Streams, React 19.3, TypeScript, Vite 8.1, Tailwind CSS 4.3, OpenTelemetry, Prometheus/Loki/Tempo/Grafana, Testcontainers 2.0.5, Vitest/MSW/Playwright, Docker Compose.

## Global Constraints

- Java 21 LTS is the production baseline; Java 25 may be an additional CI compatibility build but must not become the runtime requirement.
- Use Spring Boot 4.1.x, Spring AI 2.0.x, Spring Modulith, Spring Security OAuth2 Resource Server, Spring Data JPA, `JdbcClient`, Flyway, Maven Wrapper, and OpenAPI 3.1.
- Use React 19, TypeScript, Vite, TanStack Query/Table, React Router, Radix primitives, Tailwind CSS, ECharts, Vitest, MSW, and Playwright.
- Use PostgreSQL 17 + pgvector as the sole business truth; Redis-compatible Streams are notification transport only.
- The product is single-organization with OIDC, multi-user RBAC, service scopes, and separation of duties; do not add multi-tenancy or billing.
- The model may call only registered, bounded, read-only tools and may emit only a structured `DiagnosisProposal`; it must never receive Shell, SQL, arbitrary URL, cluster credentials, or direct state-transition authority.
- R1/R2 changes require valid human approval bound to proposal hash, Runbook version, target, parameters, and expiry; R3 actions are unsupported.
- Recovery must be proven by a Runbook-declared health/SLO query; model text can never move an incident to `RESOLVED`.
- Preserve append-only incident/audit history, immutable published Runbook versions, command idempotency, execution leases, and fencing tokens.
- All SQL identifiers are lowercase `snake_case`; use `timestamptz`, indexed foreign keys, CHECK/UNIQUE constraints, keyset pagination, partial indexes for active rows, short transactions, and `FOR UPDATE SKIP LOCKED` for Outbox claims.
- Database migrations only move forward and are tested against a real PostgreSQL 17 + pgvector container.
- Default logging and tracing must exclude full prompts, raw log bodies, credentials, and sensitive user data.
- `DEMO_MODE=true` may only enable fault injection in `demo-service`; production configuration must not register those endpoints or silently use fake AI/embeddings.
- The existing `E:\test\work\java-roadmap` repository and its worktrees must remain untouched.
- Work only in `E:\test\work\sentinelops-ai`; use small focused files, package-by-feature, TDD, and a commit after each independently reviewable task.
- Demo completion is a milestone, not a stopping point: after Stage 1 verification and `v0.1.0-demo`, continue directly into Stage 2 unless new authority or external deployment credentials are required.

---

## 1. Plan decomposition

The approved design spans three separately reviewable delivery units. Execute them in this exact order:

1. [Stage 1 — Demo vertical slice](./2026-09-20-sentinelops-ai-stage-1-demo.md)
   Produces a working alert-to-recovery path, deterministic AI, approval, signed execution, React incident cockpit, Docker Demo profile, and `v0.1.0-demo` verification evidence.
2. [Stage 2A — Real integrations and AI quality](./2026-09-20-sentinelops-ai-stage-2a-integrations.md)
   Replaces fixed evidence/model behavior through ports with real Prometheus/Loki, pgvector hybrid retrieval, Spring AI bounded tools, real model profiles, versioned Runbooks, Eval datasets, and governance UI.
3. [Stage 2B — Production hardening and v1 release](./2026-09-20-sentinelops-ai-stage-2b-production.md)
   Adds hostile-input protection, hardened lease/fencing behavior, real HTTP/Kubernetes adapters, observability, failure/load drills, least-privilege deployment, production Compose, and the final release gate.

Each plan leaves the repository in a working state. Never start a later plan while an earlier plan's final verification command is red.

## 2. Locked repository map

```text
sentinelops-ai/
├─ .editorconfig
├─ .gitattributes
├─ .gitignore
├─ .nvmrc
├─ .mvn/wrapper/maven-wrapper.properties
├─ mvnw
├─ mvnw.cmd
├─ pom.xml
├─ README.md
├─ apps/
│  ├─ ops-api/
│  │  ├─ pom.xml
│  │  └─ src/{main,test}/...
│  ├─ ops-executor/
│  │  ├─ pom.xml
│  │  └─ src/{main,test}/...
│  └─ demo-service/
│     ├─ pom.xml
│     └─ src/{main,test}/...
├─ web/ops-console/
│  ├─ package.json
│  ├─ package-lock.json
│  ├─ vite.config.ts
│  ├─ playwright.config.ts
│  └─ src/...
├─ contracts/
│  ├─ openapi/sentinelops-api.yaml
│  └─ webhooks/alertmanager.schema.json
├─ deploy/
│  ├─ compose/{compose.core.yml,compose.demo.yml,compose.production.yml}
│  ├─ caddy/Caddyfile
│  ├─ env/production.env.example
│  ├─ keycloak/sentinelops-realm.json
│  ├─ kubernetes/executor-rbac.yaml
│  ├─ postgres/{init,grants}/...
│  └─ observability/{otel-collector.yml,prometheus.yml,alertmanager.yml,loki.yml,tempo.yml,grafana/...}
├─ evals/
│  ├─ datasets/incidents-v1.jsonl
│  └─ baselines/deterministic-v1.json
├─ docs/
│  ├─ adr/
│  ├─ runbooks/
│  ├─ threat-model/
│  └─ superpowers/{specs,plans}/
├─ scripts/
│  ├─ verify.ps1
│  ├─ verify-stage2a.ps1
│  ├─ demo.ps1
│  ├─ smoke.ps1
│  ├─ security-scan.ps1
│  ├─ fault-drill.ps1
│  ├─ backup.ps1
│  └─ restore-check.ps1
├─ tests/{faults,load,security}/...
└─ .github/workflows/{ci.yml,security.yml,release.yml}
```

### Backend package map

`ops-api` uses `io.sentinelops.api` as its base package. Direct child packages are Spring Modulith modules:

- `incident`: alert ingestion, fingerprinting, state machine, evidence, incident queries;
- `knowledge`: service catalog, Runbook identity/version, knowledge chunks and search;
- `diagnosis`: model port, tools, prompt versions, diagnosis runs/proposals and policy gate;
- `approval`: risk policy, quorum, decisions and invalidation;
- `execution`: execution aggregate, Outbox, claim/heartbeat/result, ticket signing and verification orchestration;
- `identity`: OIDC principal mapping, roles and service scopes;
- `audit`: append-only audit and Eval orchestration;
- `shared`: ID/clock abstractions, Problem Details and narrow common infrastructure only.

Every feature may contain `domain`, `application`, `adapter.in.web`, `adapter.in.internal`, and `adapter.out.*` subpackages. Domain packages must not import Spring MVC, Redis, model SDK, HTTP clients, or JPA repositories from another feature.

`ops-executor` uses `io.sentinelops.executor` with `stream`, `controlplane`, `ticket`, `runbook`, and `adapter` packages. It must not depend on the `ops-api` Maven artifact.

## 3. Cross-plan interfaces

These signatures are stable boundaries. If implementation discovers a necessary change, update all affected plan documents and tests in the same commit before changing code.

```java
public record IncidentId(UUID value) {}
public record DiagnosisRunId(UUID value) {}
public record ProposalId(UUID value) {}
public record RunbookVersionId(UUID value) {}
public record ApprovalRequestId(UUID value) {}
public record ExecutionId(UUID value) {}

public interface EvidenceSource {
    String sourceType();
    CapturedEvidence capture(EvidenceQuery query, EvidenceBudget budget);
}

public interface KnowledgeSearch {
    List<KnowledgeHit> search(KnowledgeQuery query, int limit);
}

public interface ModelGateway {
    ModelDiagnosisResult diagnose(ModelDiagnosisRequest request);
}

public interface RunbookAdapter {
    String adapterId();
    ExecutionStepResult execute(AuthorizedRunbookStep step, IdempotencyContext context);
}
```

The public API error shape is fixed:

```json
{
  "type": "https://sentinelops.local/problems/resource-version-conflict",
  "title": "Resource version conflict",
  "status": 412,
  "detail": "Incident changed after it was displayed.",
  "errorCode": "INCIDENT_VERSION_CONFLICT",
  "traceId": "4f3a...",
  "resourceVersion": 9
}
```

## 4. Version pins and compatibility rationale

Use these researched release pins in initial build files and commit lockfiles. Task 1 dependency resolution and full reactor tests are the compatibility gate; do not describe a combination as tested until that gate passes:

| Component | Initial pin | Rule |
| --- | --- | --- |
| Java | 21 | Compile with `--release 21`; CI may additionally run 25 |
| Spring Boot | 4.1.1 | Parent/BOM owns Spring ecosystem versions |
| Spring AI | 2.0.1 | Explicit BOM import; use 2.0 tool-calling APIs |
| Spring Modulith | 2.1.1 | Compatible stable line for Boot 4.1 |
| springdoc-openapi | 3.0.2 | Spring Boot 4 requires springdoc major 3 |
| Testcontainers | 2.0.5 | Use 2.x module/package names |
| Resilience4j core | 2.4.0 | Use core decorators/config, not a Boot starter until Boot 4 starter compatibility is verified |
| PostgreSQL | 17 | Approved design baseline |
| pgvector | 0.8.6 | Pin image/extension line; no unversioned image tags |
| Keycloak | 26.7.4 | Local Demo identity provider only |
| Node.js | 22.17.1 or newer supported LTS | Matches local environment and Vite 8 engine floor |
| React | 19.3.x | Lock exact resolved version in `package-lock.json` |
| Vite | 8.1.x | Lock exact resolved version |
| Tailwind CSS | 4.3.x | Lock exact resolved version |

Before accepting automated dependency updates, run all release gates; never mix a major upgrade into a feature task.

## 5. Branch, commit, and milestone policy

- Start execution in an isolated worktree created by `using-git-worktrees`; the current repository only contains approved docs.
- Use one task branch/worktree for the sequential plan unless the user explicitly authorizes parallel agents.
- Commit messages use conventional prefixes: `build:`, `feat:`, `fix:`, `test:`, `docs:`, `chore:`.
- Every task ends with a clean worktree and the exact focused test command shown in its plan.
- After each phase, run `scripts/verify.ps1` plus the phase-specific Compose/E2E command.
- Create `v0.1.0-demo` only after Stage 1 release gates pass.
- Continue immediately with Stage 2A; do not wait for a new product decision.
- Create `v1.0.0` only after Stage 2B gates, clean-clone smoke test, security report, and documented known limitations.

## 6. Spec-to-plan coverage

| Approved design area | Owning plan/tasks |
| --- | --- |
| Architecture, repo, module boundaries | Stage 1 Tasks 1–2 |
| PostgreSQL schema and constraints | Stage 1 Task 3; Stage 2A Task 3; Stage 2B Task 6 |
| Incident state machine and alert dedupe | Stage 1 Task 4 |
| Deterministic diagnosis and structured proposal | Stage 1 Task 5 |
| Risk policy, RBAC and approval | Stage 1 Task 6; Stage 2B Task 2 |
| Public API, incident cockpit read model and OpenAPI | Stage 1 Tasks 4–7, 9–10 |
| Outbox, execution, ticket, Executor | Stage 1 Tasks 7–8; Stage 2B Task 3 |
| Demo service and alert-to-recovery path | Stage 1 Tasks 9–11 |
| Real Prometheus/Loki evidence and redaction | Stage 2A Tasks 1–2 |
| Versioned Runbooks and hybrid RAG | Stage 2A Task 3 |
| Spring AI 2.0 bounded tool loop and providers | Stage 2A Task 4 |
| AI Eval and quality thresholds | Stage 2A Task 5 |
| Governance/Runbook/Eval UI | Stage 2A Task 6 |
| Full OIDC service scopes and audit | Stage 2A Task 7 |
| Webhook hostility and API hardening | Stage 2B Task 1 |
| Reliability, lease/fencing and adapters | Stage 2B Tasks 3–4 |
| OpenTelemetry and local observability stack | Stage 2B Task 5 |
| Least-privilege database/deployment and security | Stage 2B Task 6 |
| Fault/load drills, production Compose and docs | Stage 2B Tasks 7–8 |
| Continuous Demo-to-formal transition | Stage 1 Task 11 and immediate Stage 2A start |

## 7. Master verification commands

Run from `E:\test\work\sentinelops-ai` in PowerShell:

```powershell
.\mvnw.cmd -T 1C verify
npm --prefix .\web\ops-console ci
npm --prefix .\web\ops-console run lint
npm --prefix .\web\ops-console test -- --run
npm --prefix .\web\ops-console run build
npm --prefix .\web\ops-console run e2e
.\scripts\verify.ps1
.\scripts\verify-stage2a.ps1
.\scripts\verify.ps1 -Release
.\scripts\security-scan.ps1
.\scripts\fault-drill.ps1
.\scripts\smoke.ps1 -Profile production
docker compose -p sentinelops -f .\deploy\compose\compose.core.yml config --quiet
docker compose -p sentinelops -f .\deploy\compose\compose.core.yml -f .\deploy\compose\compose.demo.yml config --quiet
docker compose -p sentinelops-prod -f .\deploy\compose\compose.production.yml config --quiet
```

When `SENTINELOPS_RESTORE_TEST_URL` is configured, also run `scripts/restore-check.ps1 -TestDatabaseUrl $env:SENTINELOPS_RESTORE_TEST_URL`. Expected final result: every applicable command exits `0`; Maven reports no failed tests; Vitest and Playwright report no failures; Compose configuration contains no unresolved required variables; security/Eval thresholds pass; restore validation passes when configured; `git status --short` is empty.
