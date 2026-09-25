# SentinelOps AI Stage 2B Production Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the formal SentinelOps AI system against hostile/replayed inputs and infrastructure failures, complete lease/fencing and real execution adapters, make every workflow observable without leaking content, prove safety with fault/load drills, and package a least-privilege production Compose release tagged `v1.0.0`.

**Architecture:** The production phase preserves the same control/data paths and adds defense in depth at trust boundaries. Webhooks are authenticated before JSON binding; authorization is repeated at application services; execution remains separate and fenced; observability carries identifiers rather than payloads; database migration and runtime privileges are split; release automation creates auditable test, Eval, scan, SBOM, backup/restore, and smoke-test evidence.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring Security, Resilience4j core 2.4.0, Fabric8 Kubernetes Client 7.8.0, PostgreSQL 17 + pgvector, Valkey, OpenTelemetry Collector 0.161.0, Prometheus 3.13.3, Loki 3.6.7, Tempo, Grafana 13.2.x, React 19.3, Playwright, k6, Docker Compose, GitHub Actions, Trivy, CycloneDX SBOM.

## Global Constraints

- Begin only with green Stage 1 and Stage 2A verification reports and a clean worktree.
- Keep PostgreSQL as sole truth; outages of Redis, model, telemetry, or executor must never cause an unrecorded state transition or direct API-side action.
- Authenticate, size-limit, replay-check, schema-validate, and rate-limit webhooks before incident ingestion.
- Production uses explicit OIDC issuer/audience/CORS/redirect allowlists and external secrets; no Demo user, private signing key, default password, fake AI, or fault-injection route may be active.
- Executor has no primary database credentials; target permissions are adapter/service scoped and must not include arbitrary command execution.
- Fencing tokens are mandatory on heartbeat/result and propagated to supporting targets; unknown outcomes for non-idempotent steps escalate instead of retrying.
- Do not use update/delete privileges for append-only audit/event/attempt history in production runtime roles.
- Telemetry may contain stable IDs, status, duration, counts, model/tool names and hashes; it may not contain full prompts, evidence bodies, credentials, raw tool results, approval comments with secrets, or ticket JWTs.
- All production images use explicit versions, non-root users, health checks, read-only filesystems where possible, dropped capabilities, resource limits and private networks.
- Security/Eval hard thresholds cannot be waived silently. Any accepted exception must be documented with owner, reason, expiry and compensating control.
- Tests precede implementation and each task ends with a focused commit.

---

## Task 1: Authenticate, bound, rate-limit, and replay-protect inbound webhooks

**Files:**
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/incident/adapter/in/webhook/WebhookSecurityProperties.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/incident/adapter/in/webhook/BoundedWebhookRequest.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/incident/adapter/in/webhook/WebhookSignatureVerifier.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/incident/adapter/in/webhook/WebhookReplayGuard.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/incident/adapter/in/webhook/WebhookRateLimiter.java`
- Create: `apps/ops-api/src/main/resources/db/migration/V17__webhook_replay_nonce.sql`
- Modify: `apps/ops-api/src/main/java/io/sentinelops/api/incident/adapter/in/web/AlertmanagerWebhookController.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/incident/webhook/WebhookSecurityIT.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/incident/webhook/WebhookFuzzTest.java`
- Create: `apps/demo-service/src/main/java/io/sentinelops/demo/alert/DemoAlertSigningRelay.java`
- Create: `apps/demo-service/src/main/java/io/sentinelops/demo/alert/DemoAlertRelayController.java`
- Create: `apps/demo-service/src/test/java/io/sentinelops/demo/alert/DemoAlertSigningRelayTest.java`
- Modify: `deploy/observability/alertmanager.yml`
- Modify: `deploy/compose/compose.demo.yml`

**Interfaces:**
- Produces: headers `X-Sentinel-Source`, `X-Sentinel-Timestamp`, `X-Sentinel-Nonce`, and `X-Sentinel-Signature: v1=<hex-hmac>`.
- Produces: HMAC input `timestamp + "\n" + nonce + "\n" + rawBody` with constant-time verification.
- Produces: durable nonce replay window and per-source token-bucket rate limit.
- Produces: a Demo-only private-network signing relay because Alertmanager does not natively calculate per-request timestamped HMAC headers; this relay is absent from production.
- Consumes: source-specific secret reference resolved by runtime secret provider; secret value never enters configuration endpoint or audit.

- [x] **Step 1: Write failing security tests before changing the controller**

```java
@ParameterizedTest
@MethodSource("invalidRequests")
void rejectsInvalidRequestBeforeIncidentPersistence(WebhookRequest request, int expectedStatus) {
    send(request).andExpect(status().is(expectedStatus));
    assertThat(incidentCount()).isZero();
}

static Stream<Arguments> invalidRequests() {
    return Stream.of(
        args(unsignedRequest(), 401),
        args(badSignature(), 401),
        args(timestampOlderThanFiveMinutes(), 401),
        args(replayedNonce(), 409),
        args(bodyLargerThanOneMiB(), 413),
        args(payloadWithMoreThanTwoHundredAlerts(), 413),
        args(payloadWithLabelValueOverFourKiB(), 400)
    );
}
```

Fuzz test randomly varies nesting, missing fields, Unicode, nulls and label counts for 2,000 generated payloads; every case must produce a bounded 2xx/4xx response without 500, OutOfMemoryError, stack trace body or persisted partial incident.

- [x] **Step 2: Run tests and observe failure**

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api,apps/demo-service -am -Dtest=WebhookSecurityIT,WebhookFuzzTest,DemoAlertSigningRelayTest test
```

Expected: FAIL because the Stage 1 Demo webhook allows unsigned requests.

- [x] **Step 3: Add replay nonce storage and bounded request parsing**

```sql
create table webhook_replay_nonce (
  source_key text not null,
  nonce_hash text not null,
  observed_at timestamptz not null,
  expires_at timestamptz not null,
  primary key (source_key, nonce_hash)
);
create index webhook_replay_nonce_expiry_idx on webhook_replay_nonce(expires_at);
```

Read at most 1 MiB from the servlet input stream, verify signature over exact raw bytes, then bind JSON with a maximum of 200 alerts, 100 labels per alert, 4 KiB per label value and 64 KiB total annotations. Persist only SHA-256 nonce hashes. A scheduled cleanup deletes expired nonce rows in batches of 1,000 using a short transaction.

- [x] **Step 4: Implement signature, time and rate checks in fixed order**

Order: body cap → known source → timestamp parse/skew ≤ 5 minutes → nonce syntax → HMAC constant-time compare → nonce insert → token bucket → JSON bind/validation → ingestion. Use Resilience4j `RateLimiterRegistry` keyed by source with a configured 60 requests/minute and burst 20 for the initial production policy. Return `Retry-After` on 429.

`DemoAlertRelayController` is registered only when Demo mode is true and is reachable only on the private Alertmanager/Demo network. It reads at most 1 MiB, generates epoch-second timestamp plus a cryptographically random 128-bit nonce, signs the unmodified raw body as source `demo-alertmanager`, forwards once to ops-api, and returns a non-2xx response so Alertmanager owns retry. Alertmanager targets this relay; Compose injects the same Demo-only secret into relay and ops-api. Production contains neither the relay route nor its secret and fails startup if any configured production source lacks its secret reference.

- [x] **Step 5: Verify no partial persistence and bounded behavior**

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api,apps/demo-service -am -Dtest=WebhookSecurityIT,WebhookFuzzTest,DemoAlertSigningRelayTest test
```

Expected: PASS; a valid duplicate Alertmanager event is idempotent, while replaying the same nonce is rejected before incident mutation.

- [x] **Step 6: Commit webhook hardening**

```powershell
git add apps/ops-api apps/demo-service deploy/observability/alertmanager.yml deploy/compose/compose.demo.yml
git commit -m "feat: harden inbound alert webhooks"
```

## Task 2: Harden browser/API security, secret configuration, and approval separation

**Files:**
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/identity/adapter/in/security/AudienceValidator.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/identity/adapter/in/security/AllowedOriginConfiguration.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/shared/config/SecretReference.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/shared/config/ProductionStartupValidator.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/identity/ProductionSecurityIT.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/approval/ApprovalMutationSecurityIT.java`
- Create: `web/ops-console/nginx.conf`
- Create: `web/ops-console/src/auth/authConfig.ts`
- Create: `web/ops-console/e2e/security-boundaries.spec.ts`
- Create: `docs/threat-model/stride.md`
- Create: `docs/adr/0003-security-and-trust-boundaries.md`

**Interfaces:**
- Produces: strict issuer/audience/time JWT validation; explicit production origins; CSP and browser security headers.
- Produces: startup validation rejecting deterministic provider, Demo mode/users, wildcard origins, inline private keys, default passwords, HTTP OIDC issuer, and absent ticket-signing material under `production`.
- Consumes: OIDC/service-scope model and approval rules from earlier plans.

- [x] **Step 1: Write failing startup and cross-role mutation tests**

```java
@ParameterizedTest
@ValueSource(strings = {
    "sentinelops.demo-mode=true",
    "sentinelops.ai.provider=deterministic",
    "sentinelops.security.allowed-origins=*",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://idp.local/realms/prod"
})
void productionRejectsUnsafeSetting(String property) {}

@Test void approverCannotAlterParametersWhileApproving() {}
@Test void platformAdminWithoutApproverRoleCannotApprove() {}
@Test void revokedRoleIsEnforcedOnNextRequest() {}
```

- [x] **Step 2: Run security tests and observe failure**

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api -Dtest=ProductionSecurityIT,ApprovalMutationSecurityIT test
npm --prefix .\web\ops-console run e2e -- security-boundaries.spec.ts
```

Expected: FAIL because production validation/CSP/security scenarios are incomplete.

Backend startup, approval, JWT and stale-grant tests produced the expected red results before implementation. Browser E2E could not run in the red phase because Docker was unavailable; the later complete browser suite passed against the rebuilt Compose stack.

- [x] **Step 3: Enforce issuer, audience, origin, and claim rules**

Require `aud` contains `sentinelops-api`, issuer exactly matches configuration, token is not before/expired, and subject is nonblank. Configure exact CORS origins and methods; credentials remain disabled for bearer API requests. Do not accept roles from headers/query/body. Internal Executor audience and routes use a separate security chain.

- [x] **Step 4: Add production startup validation and safe browser headers**

Nginx headers include a nonce-free static CSP compatible with the Vite build (`default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self' <configured-oidc-origin>; frame-ancestors 'none'; base-uri 'self'; form-action 'self' <configured-oidc-origin>`), `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Permissions-Policy`, and HSTS only on TLS production entry.

OIDC uses Authorization Code + PKCE and exact redirect URIs. Store tokens in memory/session scope, not localStorage; logout clears Query cache and OIDC session state.

- [x] **Step 5: Complete the STRIDE threat model with implemented controls**

Document assets, trust boundaries, spoofing/tampering/repudiation/information disclosure/DoS/elevation threats, control owner, test name and residual risk. Include webhook replay, prompt injection, SSRF, self-approval, ticket theft, executor compromise, stale fencing token and Demo-mode exposure.

- [x] **Step 6: Verify backend and browser security**

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api -Dtest=ProductionSecurityIT,ApprovalMutationSecurityIT test
npm --prefix .\web\ops-console run e2e -- security-boundaries.spec.ts
```

Expected: PASS; CSP has no wildcard/unsafe-eval, forged roles are ignored, and startup fails fast on every unsafe production fixture.

- [x] **Step 7: Commit security boundaries**

```powershell
git add apps/ops-api web/ops-console docs/threat-model docs/adr
git commit -m "feat: enforce production trust boundaries"
```

## Task 3: Complete execution lease, heartbeat, fencing, and unknown-outcome semantics

**Files:**
- Create: `apps/ops-api/src/main/resources/db/migration/V18__execution_attempt_events.sql`
- Modify: `apps/ops-api/src/main/java/io/sentinelops/api/execution/application/ExecutionApplicationService.java`
- Modify: `apps/ops-api/src/main/java/io/sentinelops/api/execution/adapter/out/persistence/ExecutionStore.java`
- Use: `apps/ops-api/src/main/java/io/sentinelops/api/execution/adapter/out/persistence/ExecutionStore.java` for database-enforced lease policy
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/execution/application/ExecutionResultPolicy.java`
- Modify: `apps/ops-executor/src/main/java/io/sentinelops/executor/stream/ExecutionMessageListener.java`
- Modify: existing `apps/ops-executor/src/main/java/io/sentinelops/executor/stream/ExecutionLeaseHeartbeat.java` and `ScheduledExecutionLeaseHeartbeat.java`
- Create: `apps/ops-executor/src/main/java/io/sentinelops/executor/runbook/StepIdempotency.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/execution/ExecutionLeaseConcurrencyIT.java`
- Create: `apps/ops-executor/src/test/java/io/sentinelops/executor/ExecutorCrashRecoveryIT.java`
- Create: `apps/ops-executor/src/test/java/io/sentinelops/executor/UnknownOutcomePolicyTest.java`

**Interfaces:**
- Produces: 30-second lease, heartbeat every 10 seconds, monotonically increasing fencing token and typed terminal/unknown outcomes.
- Produces: `ExecutionResultPolicy.decide(stepDefinition, adapterResult): ResultDisposition`.
- Consumes: existing claim/heartbeat/result APIs and Runbook idempotency metadata.

- [x] **Step 1: Write failing crash/race tests**

```java
@Test void onlyOneOfEightConcurrentClaimsReceivesTheCurrentFencingToken() {}
@Test void oldExecutorCompletionIsRejectedAfterLeaseReclaim() {}
@Test void idempotentStepRetriesAfterConfirmedPreDispatchFailure() {}
@Test void nonIdempotentTimeoutAfterDispatchEscalatesWithoutRetry() {}
@Test void heartbeatNeverExtendsARevokedOrTerminalExecution() {}
```

- [x] **Step 2: Run tests and observe failure**

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api,apps/ops-executor -am -Dtest=ExecutionLeaseConcurrencyIT,ExecutorCrashRecoveryIT,UnknownOutcomePolicyTest test
```

Expected: FAIL because Stage 1 has only minimal lease behavior.

- [x] **Step 3: Implement state-conditioned heartbeat and completion SQL**

Heartbeat:

```sql
update execution
set lease_until = clock_timestamp() + interval '30 seconds',
    updated_at = clock_timestamp()
where id = :id
  and status = 'running'
  and claimed_by = :executor_id
  and fencing_token = :fencing_token
  and lease_until >= clock_timestamp() - interval '2 seconds'
returning lease_until;
```

Completion/failure uses the same identity/token predicates and updates only once. Zero rows returns typed stale/revoked conflict. Keep token checks in the database statement, not a prior SELECT.

- [x] **Step 4: Implement explicit attempt phases and outcome policy**

Create an append-only phase journal instead of mutating the immutable terminal `execution_attempt` row:

```sql
create table execution_attempt_event (
  id uuid primary key,
  execution_id uuid not null references execution(id),
  step_id text not null,
  attempt_no integer not null,
  fencing_token bigint not null,
  phase text not null,
  metadata jsonb not null default '{}'::jsonb,
  occurred_at timestamptz not null,
  constraint execution_attempt_event_attempt_positive check (attempt_no > 0),
  constraint execution_attempt_event_fencing_positive check (fencing_token > 0),
  constraint execution_attempt_event_phase_allowed check
    (phase in ('prepared','dispatched','acknowledged','failed_before_dispatch','unknown_after_dispatch')),
  constraint execution_attempt_event_metadata_object check (jsonb_typeof(metadata) = 'object'),
  unique (execution_id, step_id, attempt_no, phase)
);
create index execution_attempt_event_execution_time_idx
  on execution_attempt_event(execution_id, occurred_at, id);
create trigger execution_attempt_event_immutable
before update or delete on execution_attempt_event
for each row execute function reject_row_mutation();
```

Write `prepared` before the network call, `dispatched` immediately before handing bytes to the adapter transport, and one terminal phase afterward; the terminal `execution_attempt` remains a sanitized summary inserted once. An idempotent step may be retried after lease recovery; a non-idempotent `unknown_after_dispatch` transitions execution/incident to `escalated` with operator instructions. Never infer success from a timeout.

- [x] **Step 5: Implement bounded executor heartbeat lifecycle**

Start heartbeat only after verified ticket and before dispatch. Cancel it in `finally`. If two consecutive heartbeats fail, stop before the next Runbook step and report unknown/failure according to current phase. Ticket expiry forbids starting a new step but does not erase an already recorded outcome.

- [x] **Step 6: Verify concurrency and crash recovery**

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api,apps/ops-executor -am -Dtest=ExecutionLeaseConcurrencyIT,ExecutorCrashRecoveryIT,UnknownOutcomePolicyTest test
```

Expected: PASS; no duplicate effect, no stale completion, no retry of unknown non-idempotent side effect.

- [x] **Step 7: Commit execution reliability**

```powershell
git add apps/ops-api apps/ops-executor
git commit -m "feat: harden execution leases and fencing"
```

## Task 4: Add production HTTP and Kubernetes Runbook adapters with strict allowlists

**Files:**
- Modify: `apps/ops-executor/pom.xml`
- Create: `apps/ops-executor/src/main/java/io/sentinelops/executor/adapter/ProductionAdapterConfiguration.java`
- Create: `apps/ops-executor/src/main/java/io/sentinelops/executor/adapter/http/HttpActionCatalog.java`
- Create: `apps/ops-executor/src/main/java/io/sentinelops/executor/adapter/http/ProductionHttpRunbookAdapter.java`
- Create: `apps/ops-executor/src/main/java/io/sentinelops/executor/adapter/kubernetes/KubernetesTargetCatalog.java`
- Create: `apps/ops-executor/src/main/java/io/sentinelops/executor/adapter/kubernetes/KubernetesRunbookAdapter.java`
- Create: `apps/ops-executor/src/main/java/io/sentinelops/executor/adapter/kubernetes/KubernetesActionPolicy.java`
- Create: `apps/ops-executor/src/test/java/io/sentinelops/executor/adapter/http/ProductionHttpRunbookAdapterTest.java`
- Create: `apps/ops-executor/src/test/java/io/sentinelops/executor/adapter/kubernetes/KubernetesRunbookAdapterTest.java`
- Create: `apps/ops-executor/src/test/java/io/sentinelops/executor/adapter/AdapterContractTest.java`
- Create: `apps/ops-executor/src/test/java/io/sentinelops/executor/adapter/ProductionAdapterConfigurationTest.java`
- Create: `deploy/kubernetes/executor-rbac.yaml`
- Create: `docs/runbooks/adapter-security.md`

**Interfaces:**
- Produces: HTTP operations selected only by configured action key.
- Produces: Kubernetes operations `restart_deployment` and `scale_deployment` within target/min/max constraints; no generic patch/delete/exec API.
- Consumes: verified ticket, immutable Runbook step, configured target alias and service-account identity.

- [x] **Step 1: Write failing adapter contract and attack tests**

```java
interface AdapterContractTest {
    RunbookAdapter adapter();
    @Test default void rejectsUnknownOperation() {}
    @Test default void rejectsUnknownTargetAlias() {}
    @Test default void rejectsExtraParameters() {}
    @Test default void propagatesStableIdempotencyKeyAndFencingToken() {}
}

@Test void kubernetesAdapterCannotDeleteResourceOrExecInPod() {}
@Test void scaleRejectsReplicaCountOutsideCatalogBounds() {}
@Test void targetNamespaceAndNameCannotComeFromModelParameter() {}
```

- [x] **Step 2: Run adapter tests and observe failure**

Run:

```powershell
.\mvnw.cmd -pl apps/ops-executor -Dtest=AdapterContractTest,ProductionHttpRunbookAdapterTest,KubernetesRunbookAdapterTest test
```

Expected: FAIL because production adapters/catalogs are absent.

- [x] **Step 3: Add Fabric8 7.8.0 and immutable target catalogs**

Add `io.fabric8:kubernetes-client:7.8.0`, its mock-server test artifact, and Boot-managed Apache HttpClient5 for checked-address DNS pinning. Catalog entries are loaded from mounted read-only configuration and map aliases to scheme/host/path or cluster/namespace/kind/name plus allowed operations/bounds. Validate catalogs at startup; reject IP literals/private-network DNS answers for external HTTP targets unless explicitly registered. Disable redirects, proxies and automatic retries; treat a 3xx after dispatch as unknown outcome.

- [x] **Step 4: Implement exact operation-specific adapters**

HTTP adapter uses configured method/path/body template and permits only typed substitutions defined by the action. Kubernetes restart patches only `spec.template.metadata.annotations['sentinelops.io/restarted-at']`; scale patches only `spec.replicas` within catalog bounds. Use resourceVersion preconditions, field manager `sentinelops-executor`, request timeout, and service account. Do not expose Fabric8 client objects to Runbook definitions.

每个新适配器必须覆写三参数 `execute(step, context, beforeTransport)`：先校验签名步骤、目标、参数和本地凭据，再紧贴出站传输前调用 `beforeTransport`。接口默认实现不能保证生产适配器的这一顺序。只有目标的稳定幂等键与 fencing 契约经过测试，才能同时加入控制平面和 Executor 的重放白名单；其他步骤分发后结果未知时升级人工核对。

- [x] **Step 5: Create minimum Kubernetes RBAC and verify forbidden calls**

`executor-rbac.yaml` grants `get`, `patch`, and `update` on named `deployments` in the configured namespace; it does not grant pods/exec, secrets, delete, wildcard resources or cluster-wide scope. Use `resourceNames` where Kubernetes authorization supports it and document remaining namespace scoping.

- [x] **Step 6: Run adapter tests against MockWebServer/Fabric8 mock server**

Run:

```powershell
.\mvnw.cmd -pl apps/ops-executor -Dtest=AdapterContractTest,ProductionHttpRunbookAdapterTest,KubernetesRunbookAdapterTest test
```

Expected: PASS; captured Kubernetes requests contain only allowed patch fields and HTTP tests prove redirect/host/parameter attacks are blocked.

- [x] **Step 7: Commit production adapters**

```powershell
git add apps/ops-executor deploy/kubernetes docs/runbooks
git commit -m "feat: add allowlisted production adapters"
```

## Task 5: Add privacy-safe OpenTelemetry and operations dashboards

**Files:**
- Modify: `apps/ops-api/pom.xml`
- Modify: `apps/ops-executor/pom.xml`
- Modify: `apps/demo-service/pom.xml`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/shared/observability/CorrelationContext.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/shared/observability/BusinessMetrics.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/shared/observability/SensitiveTelemetryFilter.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/observability/TelemetryPrivacyIT.java`
- Create: `deploy/observability/otel-collector.yml`
- Create: `deploy/observability/tempo.yml`
- Create: `deploy/observability/grafana/provisioning/datasources/datasources.yml`
- Create: `deploy/observability/grafana/provisioning/dashboards/dashboards.yml`
- Create: `deploy/observability/grafana/dashboards/sentinelops-overview.json`
- Create: `deploy/observability/grafana/dashboards/sentinelops-ai-quality.json`
- Create: `deploy/observability/grafana/dashboards/sentinelops-execution.json`
- Create: `scripts/verify-observability-trace.ps1`

**Interfaces:**
- Produces: correlated spans/logs/metrics with `incident.id`, `diagnosis.run.id`, `approval.id`, `execution.id`, source/tool/model names, statuses and duration.
- Produces: business meters for MTTD/diagnosis/approval/MTTR, source/tool result, tokens/cost, citation validity, approval funnel, action outcome, Outbox backlog, Stream lag, lease and fencing conflicts.
- Consumes: existing domain events and Spring AI observations; full content remains disabled.

- [ ] **Step 1: Write failing telemetry privacy tests**

Inject canary values into prompt, evidence, bearer token, approval comment and execution ticket. Run one full workflow with in-memory OTLP exporter and captured logs, then assert none of the canaries appear in span names/attributes/events, metric tags or logs; assert correlation IDs do appear.

- [ ] **Step 2: Run privacy test and observe failure**

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api -Dtest=TelemetryPrivacyIT test
```

Expected: FAIL until telemetry conventions and filters are complete.

- [ ] **Step 3: Instrument domain milestones and external dependencies**

Use Micrometer Observation/OpenTelemetry integration. Add low-cardinality tags for result/source/tool/model/risk and high-cardinality trace attributes only for UUID correlation. Never use service names supplied by untrusted payload until resolved through service catalog. Leave Spring AI tool arguments/results export disabled.

- [ ] **Step 4: Provision the local observability stack**

Use OpenTelemetry Collector 0.161.0 with memory limiter, batch processor and OTLP exporters to Prometheus/Loki/Tempo. Grafana 13.2.x data sources are provisioned read-only; dashboards show incident funnel, diagnosis quality/cost, approval latency, Outbox/Stream, execution outcomes and trace links. No anonymous admin in production profile.

- [ ] **Step 5: Verify privacy, metric names, dashboards and trace continuity**

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api,apps/ops-executor,apps/demo-service -am -Dtest=TelemetryPrivacyIT test
docker compose -p sentinelops-observe -f .\deploy\compose\compose.core.yml -f .\deploy\compose\compose.demo.yml config --quiet
.\scripts\verify-observability-trace.ps1 # after a fresh Demo E2E incident closes
```

Expected: PASS; dashboard JSON parses and all data sources resolve. Webhook, diagnosis, and each human approval command retain UUID correlation; the durable Outbox carries only validated W3C trace context, and the Stream consumer starts a trace with a Span Link to the execution request. Real PostgreSQL/Valkey tests and a Compose workflow prove the link through Outbox → Stream → Executor. Claim, adapter, and completion are connected through synchronous HTTP propagation; scheduled verification is correlated by `execution.id`. No trace, link, metric, or log contains sensitive content. Human approval may create a separate trace because its wait time is unbounded.

- [ ] **Step 6: Commit observability**

```powershell
git add apps deploy/observability
git commit -m "feat: add privacy-safe operations telemetry"
```

## Task 6: Split database/runtime privileges and automate dependency, image, and SBOM checks

**Files:**
- Create: `deploy/postgres/init/001_roles.sql`
- Create: `deploy/postgres/grants/runtime-grants.sql`
- Create: `deploy/postgres/provision-roles.sh`, `deploy/postgres/README.md`, and Core/Demo-only placeholder secret files
- Create: `apps/ops-api/src/main/resources/db/callback/afterMigrate.sql`
- Create: `apps/ops-api/src/main/resources/db/migration/V19__restrict_webhook_nonce_delete.sql`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/schema/RuntimeDatabasePrivilegesIT.java`
- Modify: `apps/ops-api/src/test/java/io/sentinelops/api/schema/SchemaMigrationIT.java`
- Create: `.github/workflows/ci.yml`
- Create: `.github/workflows/security.yml`
- Create: `scripts/security-scan.ps1`
- Create: `tests/security-scan/security-scan-behavior.ps1`
- Modify: `pom.xml`
- Modify: `web/ops-console/package.json`
- Modify: `web/ops-console/Dockerfile` and the three Java service Dockerfiles when image scans identify fixable base-image vulnerabilities (separate upgrade commits)
- Create: `deploy/images/install-temurin21-jre.sh` to install the checksum-pinned Java security baseline in the Alpine runtime images
- Create: `.trivyignore.yaml`
- Create: `docs/security/exception-policy.md`
- Create: `docs/security/dependency-check-exceptions.json`
- Modify: `deploy/compose/compose.core.yml` and `apps/ops-api/src/main/resources/application.yml`

**Interfaces:**
- Produces: roles `sentinelops_migrator` and `sentinelops_app`; executor has no database role.
- Produces: CI artifacts for tests, dependency review, Java/npm audit, Trivy filesystem/images, secrets scan and CycloneDX SBOM.
- Consumes: all migrations and production images.

- [x] **Step 1: Write failing runtime privilege tests**

Connect as `sentinelops_app` and assert required SELECT/INSERT/UPDATE works, including the reviewed `webhook_replay_nonce` expiry cleanup and `role_grant` revocation DELETE operations. Assert schema DDL, role creation, extension creation, `audit_record` UPDATE/DELETE, `incident_event` UPDATE/DELETE, `execution_attempt` UPDATE/DELETE, and `execution_attempt_event` UPDATE/DELETE fail. Assert existing and future application routines do not grant runtime EXECUTE while pgvector operations and existing triggers still work. Assert executor configuration contains no JDBC URL/user/password.

- [x] **Step 2: Run privilege test and observe failure**

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api -Dtest=RuntimeDatabasePrivilegesIT test
```

Expected: FAIL because Demo owner credentials still grant excessive rights.

- [x] **Step 3: Create least-privilege roles and forward grants**

Revoke PUBLIC schema/table/sequence defaults. The database administrator installs the pgvector extension before Flyway runs; the migrator owns schema/migrations. App receives schema usage and explicit table DML; it receives no DDL/role/extension privileges. Grant DELETE only for the reviewed `webhook_replay_nonce` expiry cleanup and `role_grant` revocation operations, plus any separately reviewed retention worker. Use `alter default privileges for role sentinelops_migrator` so future Flyway tables receive reviewed runtime grants. Append-only tables receive SELECT/INSERT only; state changes occur on aggregate tables. Revoke PUBLIC/runtime EXECUTE on application-owned routines and globally revoke future migrator function defaults without changing pgvector extension functions. A packaged `afterMigrate` callback grants reviewed UPDATE/DELETE on newly created current tables and revokes access to `flyway_schema_history` before the API serves requests; verify these rights before a second administrator grant pass.

Do not store passwords in SQL files. Compose passes psql variables from Docker secrets and gives Flyway a distinct migrator login; managed production databases execute reviewed role/grant scripts through the platform administrator. Demo-only placeholder files may keep the existing local volume password compatible, but production must require externally supplied secrets.

- [x] **Step 4: Add pinned security workflows and SBOM generation**

Maven adds CycloneDX and OWASP dependency-check reporting without skipping tests. npm runs `npm ci`, `npm audit --audit-level=high`, lint/test/build. Trivy scans repository and built images with severity HIGH,CRITICAL and fails on unfixed critical issues unless a time-bounded exception exists.

`.trivyignore.yaml` entries require CVE, affected artifact/image, reason, compensating control, owner and expiry. `security-scan.ps1` rejects expired or incomplete entries before invoking scanners.

- [x] **Step 5: Verify privileges and local security pipeline**

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api -Dtest=RuntimeDatabasePrivilegesIT test
.\scripts\security-scan.ps1
```

Expected: PASS; scan artifacts include Java/npm dependency reports, image report and Java/Node SBOM; runtime app cannot mutate append-only records.

Also render the Core/Demo Compose configuration and start a task-specific stack to verify `ops-api` reaches readiness while PostgreSQL reports `sentinelops_app` for runtime sessions and `sentinelops_migrator` for Flyway. Stop only the task-specific stack and its volumes after collecting evidence.

- [x] **Step 6: Commit least privilege and security automation**

```powershell
git add deploy/postgres apps/ops-api .github scripts pom.xml web/ops-console docs/security .trivyignore.yaml
git commit -m "chore: enforce least privilege and security gates"
```

## Task 7: Prove outage, duplicate-delivery, hostile-input, and alert-storm behavior

**Files:**
- Create: `tests/faults/redis-outage.ps1`
- Create: `tests/faults/executor-crash.ps1`
- Create: `tests/faults/model-timeout.ps1`
- Create: `tests/load/alert-storm.js`
- Create: `tests/security/prompt-injection-corpus.jsonl`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/reliability/DependencyFailureIT.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/reliability/PromptInjectionCorpusIT.java`
- Create: `scripts/fault-drill.ps1`
- Create: `docs/runbooks/failure-recovery.md`

**Interfaces:**
- Produces: machine-readable reports for Redis outage, executor crash/reclaim, model timeout, prompt injection corpus and 60-second alert storm.
- Produces: acceptance thresholds: no lost persisted command, no duplicate side effect, no unauthorized tool/action, no false `RESOLVED`, and bounded API latency/error behavior.
- Consumes: complete Demo/observability stack.

- [ ] **Step 1: Write failing dependency-failure and corpus tests**

`DependencyFailureIT` covers PostgreSQL down (503/no false acknowledgment), Redis down (Outbox backlog/no direct action), model timeout (manual/degraded path), Loki down (missing evidence/no unsafe proposal), and telemetry down (workflow continues safely). `PromptInjectionCorpusIT` loads at least 25 multilingual malicious strings and asserts no action tool/unknown Runbook and no secret echo.

- [ ] **Step 2: Create the k6 alert-storm test before tuning**

```javascript
import http from 'k6/http';
import { check } from 'k6';
import crypto from 'k6/crypto';

export const options = {
  scenarios: { storm: { executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: '60s', preAllocatedVUs: 40 } },
  thresholds: { http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<500'] },
};

function signedAlertBodyForFingerprint(incidentFingerprint) {
  const timestamp = `${Math.floor(Date.now() / 1000)}`;
  const nonce = `${__VU}-${__ITER}-${Date.now()}`;
  const sourceEventId = `${incidentFingerprint}-${nonce}`;
  const payload = JSON.stringify({
    version: '4',
    groupKey: incidentFingerprint,
    status: 'firing',
    receiver: 'sentinelops-load',
    groupLabels: { alertname: 'CheckoutConnectionPoolExhausted', service_key: 'checkout-api' },
    commonLabels: { alertname: 'CheckoutConnectionPoolExhausted', service_key: 'checkout-api', severity: 'sev1', incident_fingerprint: incidentFingerprint },
    commonAnnotations: { summary: 'Synthetic connection-pool alert storm' },
    externalURL: 'http://alertmanager:9093',
    alerts: [{
      status: 'firing',
      labels: { alertname: 'CheckoutConnectionPoolExhausted', service_key: 'checkout-api', severity: 'sev1', incident_fingerprint: incidentFingerprint },
      annotations: { summary: 'Synthetic connection-pool alert storm' },
      startsAt: new Date().toISOString(),
      endsAt: '0001-01-01T00:00:00Z',
      generatorURL: 'http://prometheus:9090/graph',
      fingerprint: sourceEventId,
    }],
  });
  const signature = crypto.hmac('sha256', __ENV.WEBHOOK_SECRET, `${timestamp}\n${nonce}\n${payload}`, 'hex');
  return {
    payload,
    headers: {
      'Content-Type': 'application/json',
      'X-Sentinel-Source': 'load-test',
      'X-Sentinel-Timestamp': timestamp,
      'X-Sentinel-Nonce': nonce,
      'X-Sentinel-Signature': `v1=${signature}`,
    },
  };
}

export default function () {
  const body = signedAlertBodyForFingerprint('checkout-pool');
  const response = http.post(`${__ENV.API_URL}/api/v1/integrations/alertmanager/webhook`, body.payload, { headers: body.headers });
  check(response, { acceptedOrDuplicate: r => r.status === 202 || r.status === 200 });
}
```

The load profile configures source `load-test` for 10,000 requests/minute with burst 200; production defaults remain 60/minute with burst 20. The helper uses a unique nonce/source-event fingerprint per request but one normalized `incident_fingerprint`, so the expected result is one active incident with 6,000 occurrence events. A separate source using the production default drives the rate-limit scenario and treats the documented 429 plus `Retry-After` response as success.

- [ ] **Step 3: Run fault/load suite and observe failures**

Run:

```powershell
.\scripts\fault-drill.ps1
```

Expected: at least one scenario fails before recovery automation/limits are fully wired; preserve its diagnostic report.

- [ ] **Step 4: Fix only demonstrated reliability gaps**

Use `systematic-debugging` for each failing drill. Keep transactions short, add missing bounded retries only for idempotent operations, ensure Outbox resumes, executor reclaim honors fencing, and model/data-source outages generate typed incident events. Do not relax the load/fault assertions to make tests pass.

- [ ] **Step 5: Run the full drill twice**

Run:

```powershell
.\scripts\fault-drill.ps1
.\scripts\fault-drill.ps1
```

Expected: both PASS with one active incident for the storm fingerprint, zero duplicate side effects, no unauthorized action, no false resolution, and sanitized report files under `build/verification/faults/`.

- [ ] **Step 6: Commit fault/load evidence and recovery docs**

```powershell
git add tests apps/ops-api scripts docs/runbooks
git commit -m "test: prove production failure semantics"
```

## Task 8: Package production Compose, backup/upgrade runbooks, and release `v1.0.0`

**Files:**
- Create: `deploy/compose/compose.production.yml`
- Create: `deploy/caddy/Caddyfile`
- Create: `deploy/env/production.env.example`
- Create: `scripts/smoke.ps1`
- Create: `scripts/backup.ps1`
- Create: `scripts/restore-check.ps1`
- Create: `.github/workflows/release.yml`
- Create: `docs/deployment/production-compose.md`
- Create: `docs/deployment/backup-restore.md`
- Create: `docs/deployment/upgrade.md`
- Create: `docs/deployment/operations.md`
- Create: `docs/demo/eight-minute-script.md`
- Create: `docs/known-limitations.md`
- Modify: `README.md`
- Modify: `scripts/verify.ps1`
- Create: `web/ops-console/e2e/production-smoke.spec.ts`

**Interfaces:**
- Produces: production Compose using external OIDC/model/data-source secrets and either external managed PostgreSQL/Valkey or explicitly selected local services.
- Produces: TLS reverse-proxy example, backup/restore validation, rolling upgrade/migration procedure, clean-environment smoke and immutable release artifacts.
- Consumes: every prior feature and verification gate.

- [ ] **Step 1: Write failing production smoke and configuration policy tests**

Smoke checks unauthenticated health only, OIDC redirect, authenticated incident list, signed webhook, one read-only diagnosis/manual degraded result, approval denial for wrong role, and absence/404 of every Demo fault path. Compose-policy test asserts no default password, wildcard origin, host Docker socket, privileged container, embedded API key or executor DB credential.

- [ ] **Step 2: Run production config/smoke checks and observe failure**

Run:

```powershell
docker compose -p sentinelops-prod -f .\deploy\compose\compose.production.yml config --quiet
.\scripts\smoke.ps1 -Profile production
```

Expected: FAIL because production deployment/runbooks and smoke script are not complete.

- [ ] **Step 3: Implement hardened production Compose and TLS example**

Application containers run as non-root with `read_only: true`, `tmpfs` for writable temp, `cap_drop: [ALL]`, `no-new-privileges`, health checks, restart policy and CPU/memory limits. Executor has only target-specific secret mounts/network. Caddy terminates HTTPS for a configured domain; HSTS is enabled only when TLS is active. Required variables use `${NAME:?message}` so missing secrets fail Compose rendering.

Production profile never includes demo-service, fault endpoints, Demo realm import, ephemeral ticket keys, deterministic model or public database/Valkey ports.

- [ ] **Step 4: Implement safe backup and restore verification**

`backup.ps1` uses `pg_dump --format=custom --no-owner --no-acl` to a timestamped file plus SHA-256 manifest and captures current Flyway version. `restore-check.ps1` creates a uniquely named temporary database inside an explicitly provided test PostgreSQL instance, restores, runs Flyway validation and read-only integrity queries, then removes only that validated temporary database in `finally`. It refuses production hostnames unless `-AllowProductionRestoreTarget` is explicitly supplied and still never overwrites an existing database.

- [ ] **Step 5: Complete release documentation and CI workflow**

Document prerequisites, secret generation, OIDC client/audience, database roles/migration job, Prometheus/Loki/model configuration, DNS/TLS, resource sizing, backup schedule, restore drill, upgrade/rollback, key rotation, incident response for SentinelOps itself, and known limits. The eight-minute script follows the approved 8-step story and clearly labels simulated versus real integrations.

Release workflow verifies tag matches `pom.xml`/package version, runs all tests/Eval/scans, builds multi-stage images, generates checksums and SBOMs, and publishes artifacts only after all gates pass. Do not publish images or create a GitHub release unless the user later authorizes external publication.

- [ ] **Step 6: Run the final local release gate**

Run:

```powershell
.\scripts\verify.ps1 -Release
.\scripts\security-scan.ps1
.\scripts\fault-drill.ps1
.\scripts\restore-check.ps1 -TestDatabaseUrl $env:SENTINELOPS_RESTORE_TEST_URL
.\scripts\smoke.ps1 -Profile production
git status --short
```

Expected:

- all Java/frontend/module/database/E2E tests pass;
- all five AI Eval thresholds pass;
- Webhook fuzz, prompt injection corpus, fault drills and alert storm pass;
- scans have no unexpired/unreviewed critical finding;
- backup restores into a temporary database and validates migrations/data;
- production smoke proves Demo endpoints absent;
- `git status --short` is empty.

- [ ] **Step 7: Commit release artifacts and tag locally**

```powershell
git add deploy scripts .github docs README.md web/ops-console apps pom.xml
git commit -m "feat: release deployable SentinelOps AI v1"
git tag -a v1.0.0 -m "SentinelOps AI v1.0.0"
git show --stat --oneline v1.0.0
```

Expected: local annotated tag exists and final report accurately separates implemented real adapters from configured/mock-only paths.

- [ ] **Step 8: Prepare external deployment handoff without assuming credentials**

If the user supplies a host/cloud project, domain, OIDC client and secret delivery mechanism, execute `docs/deployment/production-compose.md` against that target and run production smoke. If those external resources are absent, stop after the verified local `v1.0.0`, report exactly what is deployment-ready, and request only the missing resources; do not fabricate a public deployment.
