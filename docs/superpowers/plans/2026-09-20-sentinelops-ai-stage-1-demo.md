# SentinelOps AI Stage 1 Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a production-shaped `v0.1.0-demo` vertical slice that detects a controlled service fault, creates one deduplicated incident, generates an evidence-backed deterministic diagnosis, enforces independent approval, executes one signed/idempotent Runbook action through the separate executor, verifies recovery, and displays the complete timeline in React.

**Architecture:** Stage 1 builds the final module boundaries and persistence constraints immediately, while substituting deterministic evidence/model behavior where external providers would make CI unstable. `ops-api` owns all durable state; Outbox + Valkey Stream wakes `ops-executor`; the executor claims a signed ticket and calls the isolated `demo-service`. The React console consumes the committed OpenAPI contract and polls the incident read API for the first milestone.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring Modulith 2.1.1, PostgreSQL 17 + pgvector, Valkey Streams, Nimbus JOSE through Spring Security, React 19.3, Vite 8.1, TypeScript, TanStack Query, Tailwind CSS 4.3, Vitest/MSW, Playwright, Docker Compose, Testcontainers 2.0.5.

## Global Constraints

- Work only in the isolated SentinelOps AI worktree created for execution; never edit `E:\test\work\java-roadmap`.
- Compile and test Java with release 21; do not use Java 22+ language or library APIs.
- PostgreSQL is the sole source of truth; Valkey messages contain only event/execution identifiers and may be duplicated.
- Use lowercase `snake_case`, `timestamptz`, explicit CHECK/UNIQUE constraints, indexes for every foreign key, a partial unique index for active incident fingerprints, and short transactions.
- Keep domain transitions deterministic and model-free; AI/deterministic engines emit proposals but cannot update incident state directly.
- Expose no Shell, SQL, arbitrary URL, or free-form action endpoint.
- R1 approval requires a different authenticated principal from the requester; R3 is rejected before persistence of an executable request.
- Bind approval and execution to an immutable canonical proposal hash and immutable Runbook version.
- Recovery is based only on the Runbook verification probe; no model/deterministic diagnosis result may resolve an incident.
- All state-changing HTTP requests require `Idempotency-Key`; stale resource commands require `If-Match`.
- Tests precede implementation, every task ends with focused green tests and a commit, and Stage 1 completion flows immediately to Stage 2A.

---

## File structure for this plan

```text
apps/ops-api/src/main/java/io/sentinelops/api/
├─ SentinelOpsApiApplication.java
├─ shared/{id,time,problem}/...
├─ incident/{domain,application,adapter}/...
├─ knowledge/{domain,application,adapter}/...
├─ diagnosis/{domain,application,adapter}/...
├─ approval/{domain,application,adapter}/...
├─ execution/{domain,application,adapter}/...
├─ identity/{application,adapter}/...
└─ audit/{domain,application,adapter}/...

apps/ops-executor/src/main/java/io/sentinelops/executor/
├─ SentinelOpsExecutorApplication.java
├─ stream/ExecutionMessageListener.java
├─ controlplane/ControlPlaneClient.java
├─ ticket/ExecutionTicketVerifier.java
└─ runbook/{RunbookAdapter,DemoHttpRunbookAdapter,RunbookDispatcher}.java

apps/demo-service/src/main/java/io/sentinelops/demo/
├─ DemoServiceApplication.java
├─ checkout/CheckoutController.java
├─ fault/{FaultMode,FaultState,DemoFaultController}.java
└─ runbook/DemoRecoveryController.java

web/ops-console/src/
├─ app/{App,router,queryClient}.tsx
├─ auth/{AuthProvider,RequireRole}.tsx
├─ api/{client,generated}.ts
├─ features/incidents/...
├─ features/approvals/...
├─ components/...
└─ styles/index.css
```

## Task 1: Bootstrap the Java multi-module build and enforce module boundaries

**Files:**
- Create: `.editorconfig`
- Create: `.gitattributes`
- Create: `.gitignore`
- Create: `.mvn/wrapper/maven-wrapper.properties`
- Create: `mvnw`
- Create: `mvnw.cmd`
- Create: `pom.xml`
- Create: `apps/ops-api/pom.xml`
- Create: `apps/ops-executor/pom.xml`
- Create: `apps/demo-service/pom.xml`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/SentinelOpsApiApplication.java`
- Create: `apps/ops-executor/src/main/java/io/sentinelops/executor/SentinelOpsExecutorApplication.java`
- Create: `apps/demo-service/src/main/java/io/sentinelops/demo/DemoServiceApplication.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/ApplicationContextTest.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/ModuleBoundaryTest.java`

**Interfaces:**
- Produces: three independent Spring Boot applications built by one Maven reactor.
- Produces: `io.sentinelops.api.SentinelOpsApiApplication` as the root used by Spring Modulith verification.
- Consumes: no application code; this is the repository foundation.

- [ ] **Step 0: Make the Java 21 build toolchain explicit**

The current host has Java 17 and 25 but not Java 21. Request host-package approval, then install the pinned LTS toolchain and bind only this PowerShell session to it:

```powershell
winget install --exact --id Microsoft.OpenJDK.21 --accept-package-agreements --accept-source-agreements
$sentinelopsJavaHome = (Get-ChildItem -LiteralPath 'C:\Program Files\Microsoft' -Directory -Filter 'jdk-21*' | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName)
if (-not $sentinelopsJavaHome) { throw 'Microsoft OpenJDK 21 was not found after installation.' }
$env:JAVA_HOME = $sentinelopsJavaHome
$env:Path = "$sentinelopsJavaHome\bin;$env:Path"
java -version
```

Expected: `java -version` reports major version 21. Keep this shell for all Maven Wrapper commands; do not continue with Java 17 or silently change the production baseline to Java 25.

- [ ] **Step 1: Create deterministic repository/build configuration**

Use Maven Wrapper 3.9.11 and this root dependency policy:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
    <relativePath/>
  </parent>
  <groupId>io.sentinelops</groupId>
  <artifactId>sentinelops-parent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>
  <modules>
    <module>apps/ops-api</module>
    <module>apps/ops-executor</module>
    <module>apps/demo-service</module>
  </modules>
  <properties>
    <java.version>21</java.version>
    <maven.compiler.release>21</maven.compiler.release>
    <spring-ai.version>2.0.1</spring-ai.version>
    <spring-modulith.version>2.1.1</spring-modulith.version>
    <springdoc.version>3.0.2</springdoc.version>
    <testcontainers.version>2.0.5</testcontainers.version>
    <resilience4j.version>2.4.0</resilience4j.version>
  </properties>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.ai</groupId><artifactId>spring-ai-bom</artifactId>
        <version>${spring-ai.version}</version><type>pom</type><scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.springframework.modulith</groupId><artifactId>spring-modulith-bom</artifactId>
        <version>${spring-modulith.version}</version><type>pom</type><scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.testcontainers</groupId><artifactId>testcontainers-bom</artifactId>
        <version>${testcontainers.version}</version><type>pom</type><scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

Each application POM inherits the root. `ops-api` starts with Web MVC, validation, actuator, Modulith core, and test dependencies; Task 6 adds OAuth2 Resource Server together with its deny-by-default configuration. `ops-executor` starts with Web MVC, actuator, and test dependencies; Task 8 adds Redis and JOSE together with the listener/security code. `demo-service` starts with Web MVC, actuator, Prometheus registry, and test dependencies. Apply the Spring Boot Maven plugin only to executable modules. This sequencing prevents Spring Security auto-configuration from locking an otherwise unconfigured intermediate application.

Create the root POM first with `apply_patch`, then generate the checked-in Maven Wrapper from a pinned Java 21 Maven image so the host does not need a global Maven installation:

```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace maven:3.9.11-eclipse-temurin-21 mvn -N wrapper:wrapper -Dmaven=3.9.11
.\mvnw.cmd --version
```

Expected: the wrapper reports Maven 3.9.11 on Java 21. Add the three child POMs with `apply_patch` after the wrapper is generated.

- [ ] **Step 2: Write the failing application and module-boundary tests**

```java
@SpringBootTest
class ApplicationContextTest {
    @Test void starts() {}
}

class ModuleBoundaryTest {
    @Test
    void modulesAreAcyclic() {
        ApplicationModules.of(SentinelOpsApiApplication.class).verify();
    }
}
```

- [ ] **Step 3: Run the focused tests and observe failure**

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api -am -Dtest=ApplicationContextTest,ModuleBoundaryTest test
```

Expected: FAIL because the application classes and/or Modulith package arrangement are not yet present.

- [ ] **Step 4: Add the three minimal application entry points**

```java
package io.sentinelops.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulith;

@Modulith
@SpringBootApplication
public class SentinelOpsApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(SentinelOpsApiApplication.class, args);
    }
}
```

Create the executor entry point exactly as:

```java
package io.sentinelops.executor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SentinelOpsExecutorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SentinelOpsExecutorApplication.class, args);
    }
}
```

Create the Demo service entry point exactly as:

```java
package io.sentinelops.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoServiceApplication.class, args);
    }
}
```

- [ ] **Step 5: Run reactor tests and package all applications**

Run:

```powershell
.\mvnw.cmd -T 1C verify
```

Expected: PASS; the reactor builds three executable modules and Modulith verification reports no cycle.

- [ ] **Step 6: Commit the foundation**

```powershell
git add .editorconfig .gitattributes .gitignore .mvn mvnw mvnw.cmd pom.xml apps
git commit -m "build: bootstrap SentinelOps Java modules"
```

## Task 2: Bootstrap the React console and typed API boundary

**Files:**
- Create: `.nvmrc`
- Create: `web/ops-console/package.json`
- Create: `web/ops-console/package-lock.json`
- Create: `web/ops-console/index.html`
- Create: `web/ops-console/tsconfig.json`
- Create: `web/ops-console/vite.config.ts`
- Create: `web/ops-console/vitest.setup.ts`
- Create: `web/ops-console/src/main.tsx`
- Create: `web/ops-console/src/app/App.tsx`
- Create: `web/ops-console/src/app/queryClient.ts`
- Create: `web/ops-console/src/api/client.ts`
- Create: `web/ops-console/src/styles/index.css`
- Create: `web/ops-console/src/app/App.test.tsx`

**Interfaces:**
- Produces: browser entry point and `apiFetch<T>(path, init): Promise<T>`.
- Produces: one shared TanStack `QueryClient` with command retries disabled.
- Consumes: `/api/v1` JSON and `application/problem+json`; generated OpenAPI types are introduced after the backend contract exists.

Implementation note: before this task, load `frontend-design`, `ui-ux-pro-max`, and `vercel-react-best-practices`; retain the approved dark-steel/teal incident-console direction.

- [ ] **Step 1: Initialize exact frontend dependencies and lock them**

Create `.nvmrc` with `apply_patch`; its complete content is one line:

```text
22.17.1
```

Then run:

```powershell
npm create vite@8.1.0 .\web\ops-console -- --template react-ts
npm --prefix .\web\ops-console install --save-exact react@19.3.0 react-dom@19.3.0 @tanstack/react-query @tanstack/react-table react-router-dom oidc-client-ts echarts
npm --prefix .\web\ops-console install --save-exact @radix-ui/react-dialog @radix-ui/react-dropdown-menu @radix-ui/react-tabs
npm --prefix .\web\ops-console install --save-dev --save-exact tailwindcss@4.3.0 @tailwindcss/vite vitest @testing-library/react @testing-library/jest-dom @testing-library/user-event jsdom msw eslint prettier
```

Expected: `package-lock.json` is created and `npm audit --omit=dev` returns no unreviewed critical vulnerability.

- [ ] **Step 2: Write the failing application-shell test**

```tsx
import { render, screen } from '@testing-library/react';
import { App } from './App';

it('renders the incident-first navigation', () => {
  render(<App />);
  expect(screen.getByRole('heading', { name: '事故中心' })).toBeInTheDocument();
  expect(screen.getByRole('navigation', { name: '主导航' })).toBeInTheDocument();
});
```

- [ ] **Step 3: Run the frontend test and observe failure**

Run:

```powershell
npm --prefix .\web\ops-console test -- --run src/app/App.test.tsx
```

Expected: FAIL because `App` does not export the approved shell.

- [ ] **Step 4: Implement the minimal incident-console shell and safe fetch client**

```tsx
export function App() {
  return (
    <div className="app-shell">
      <aside>
        <a className="brand" href="/">SentinelOps AI</a>
        <nav aria-label="主导航">
          <a aria-current="page" href="/incidents">事故中心</a>
          <a href="/approvals">审批工作台</a>
          <a href="/runbooks">Runbooks</a>
          <a href="/evals">AI 评测</a>
        </nav>
      </aside>
      <main><h1>事故中心</h1><p>正在连接控制平面…</p></main>
    </div>
  );
}
```

```ts
export type ApiProblem = {
  status: number;
  errorCode: string;
  detail: string;
  traceId: string;
  resourceVersion?: number;
};

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    ...init,
    headers: { Accept: 'application/json', ...init.headers },
  });
  if (!response.ok) throw (await response.json()) as ApiProblem;
  return (await response.json()) as T;
}
```

Set Tailwind/Vite integration and CSS variables for `--ink`, `--steel`, `--surface`, `--teal`, `--amber`, and `--danger`; meet 4.5:1 contrast for body text and preserve visible focus rings.

- [ ] **Step 5: Verify test, lint, and production build**

Run:

```powershell
npm --prefix .\web\ops-console test -- --run
npm --prefix .\web\ops-console run lint
npm --prefix .\web\ops-console run build
```

Expected: all commands PASS and Vite emits `dist/` without TypeScript diagnostics.

- [ ] **Step 6: Commit the frontend foundation**

```powershell
git add .nvmrc web/ops-console
git commit -m "feat: add incident console foundation"
```

## Task 3: Create the PostgreSQL schema and migration test harness

**Files:**
- Modify: `apps/ops-api/pom.xml`
- Create: `apps/ops-api/src/main/resources/application.yml`
- Create: `apps/ops-api/src/main/resources/db/migration/V1__service_identity_incident.sql`
- Create: `apps/ops-api/src/main/resources/db/migration/V2__knowledge_diagnosis.sql`
- Create: `apps/ops-api/src/main/resources/db/migration/V3__approval_execution_outbox.sql`
- Create: `apps/ops-api/src/main/resources/db/migration/V4__audit.sql`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/support/PostgresIntegrationTest.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/schema/SchemaMigrationIT.java`

**Interfaces:**
- Produces: schema required by all Stage 1 modules and a reusable PostgreSQL integration-test base.
- Produces: `PostgresIntegrationTest` with a shared `pgvector/pgvector:0.8.6-pg17-trixie` container and Spring dynamic properties.
- Consumes: application-generated UUIDv7 values; migrations do not generate random UUID primary keys.

- [ ] **Step 1: Add persistence and Testcontainers dependencies**

Add `spring-boot-starter-data-jpa`, `spring-boot-starter-jdbc`, `spring-boot-starter-data-redis`, `flyway-core`, `flyway-database-postgresql`, PostgreSQL runtime, `spring-boot-testcontainers`, `testcontainers-junit-jupiter`, and `testcontainers-postgresql`. Configure Hikari `maximum-pool-size: 10`, `minimum-idle: 2`, `connection-timeout: 3000`, and `leak-detection-threshold: 10000`; configure JDBC `statement_timeout=5000` for application transactions.

- [ ] **Step 2: Write the failing migration integration test**

```java
class SchemaMigrationIT extends PostgresIntegrationTest {
    @Autowired JdbcClient jdbc;

    @Test
    void createsCoreTablesAndActiveFingerprintConstraint() {
        var tables = jdbc.sql("""
            select table_name from information_schema.tables
            where table_schema = 'public'
            """).query(String.class).list();
        assertThat(tables).contains("incident", "incident_event", "diagnosis_proposal",
                "incident_projection", "approval_request", "execution", "outbox_event", "audit_record");

        var indexes = jdbc.sql("""
            select indexname from pg_indexes where schemaname = 'public'
            """).query(String.class).list();
        assertThat(indexes).contains("incident_active_fingerprint_uk",
                "incident_event_incident_seq_uk", "outbox_pending_claim_idx");
    }
}
```

- [ ] **Step 3: Run the migration test and observe failure**

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api -Dtest=SchemaMigrationIT test
```

Expected: FAIL because Flyway migrations do not exist.

- [ ] **Step 4: Create the core migration with constraints and indexes**

`V1__service_identity_incident.sql` must define the following exact core shape:

```sql
create extension if not exists vector;

create table service_catalog (
  id uuid primary key,
  service_key text not null unique,
  display_name text not null,
  owner_team text not null,
  slo_config jsonb not null default '{}'::jsonb,
  data_source_refs jsonb not null default '{}'::jsonb,
  execution_target_aliases jsonb not null default '{}'::jsonb,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint service_key_nonblank check (btrim(service_key) <> ''),
  constraint service_slo_config_object check (jsonb_typeof(slo_config) = 'object'),
  constraint service_data_source_refs_object check (jsonb_typeof(data_source_refs) = 'object'),
  constraint service_execution_targets_object check (jsonb_typeof(execution_target_aliases) = 'object')
);

create table principal (
  id uuid primary key,
  issuer text not null,
  subject text not null,
  display_name text not null,
  created_at timestamptz not null,
  unique (issuer, subject)
);

create table role_grant (
  id uuid primary key,
  principal_id uuid not null references principal(id),
  role_name text not null,
  service_id uuid references service_catalog(id),
  granted_at timestamptz not null,
  constraint role_name_allowed check (role_name in
    ('observer','on_call_operator','sre_approver','runbook_admin','platform_admin')),
  unique nulls not distinct (principal_id, role_name, service_id)
);
create index role_grant_principal_idx on role_grant(principal_id);
create index role_grant_service_idx on role_grant(service_id) where service_id is not null;

create table incident (
  id uuid primary key,
  service_id uuid not null references service_catalog(id),
  fingerprint text not null,
  title text not null,
  severity text not null,
  status text not null,
  version bigint not null default 0,
  next_event_seq bigint not null default 1,
  occurrence_count bigint not null default 1,
  opened_at timestamptz not null,
  updated_at timestamptz not null,
  resolved_at timestamptz,
  constraint incident_severity_allowed check (severity in ('sev1','sev2','sev3','sev4')),
  constraint incident_status_allowed check (status in
    ('detected','triaging','diagnosed','awaiting_approval','executing','verifying','resolved','suppressed','escalated')),
  constraint incident_version_nonnegative check (version >= 0),
  constraint incident_event_seq_positive check (next_event_seq > 0)
);
create unique index incident_active_fingerprint_uk
  on incident(service_id, fingerprint)
  where status not in ('resolved','suppressed');
create index incident_status_opened_cursor_idx on incident(status, opened_at desc, id desc);
create index incident_service_opened_idx on incident(service_id, opened_at desc);

create table incident_projection (
  incident_id uuid primary key references incident(id),
  service_id uuid not null references service_catalog(id),
  title text not null,
  severity text not null,
  status text not null,
  resource_version bigint not null,
  occurrence_count bigint not null,
  opened_at timestamptz not null,
  updated_at timestamptz not null,
  resolved_at timestamptz
);
create index incident_projection_status_cursor_idx
  on incident_projection(status, opened_at desc, incident_id desc);
create index incident_projection_service_cursor_idx
  on incident_projection(service_id, opened_at desc, incident_id desc);

create function refresh_incident_projection() returns trigger language plpgsql as $$
begin
  insert into incident_projection(
    incident_id, service_id, title, severity, status, resource_version,
    occurrence_count, opened_at, updated_at, resolved_at
  ) values (
    NEW.id, NEW.service_id, NEW.title, NEW.severity, NEW.status, NEW.version,
    NEW.occurrence_count, NEW.opened_at, NEW.updated_at, NEW.resolved_at
  )
  on conflict (incident_id) do update set
    service_id = excluded.service_id,
    title = excluded.title,
    severity = excluded.severity,
    status = excluded.status,
    resource_version = excluded.resource_version,
    occurrence_count = excluded.occurrence_count,
    updated_at = excluded.updated_at,
    resolved_at = excluded.resolved_at;
  return NEW;
end;
$$;
create trigger incident_projection_refresh
after insert or update on incident
for each row execute function refresh_incident_projection();

create table incident_event (
  id uuid primary key,
  incident_id uuid not null references incident(id),
  seq_no bigint not null,
  event_type text not null,
  actor_type text not null,
  actor_id text not null,
  source text,
  source_event_id text,
  payload jsonb not null default '{}'::jsonb,
  occurred_at timestamptz not null,
  constraint incident_event_seq_positive check (seq_no > 0),
  constraint incident_event_incident_seq_uk unique (incident_id, seq_no)
);
create unique index incident_event_source_event_uk
  on incident_event(source, source_event_id)
  where source_event_id is not null;
create index incident_event_incident_time_idx on incident_event(incident_id, occurred_at, id);

create table evidence_snapshot (
  id uuid primary key,
  incident_id uuid not null references incident(id),
  diagnosis_run_id uuid,
  source_type text not null,
  source_ref text not null,
  query_spec jsonb not null,
  redacted_payload jsonb not null,
  content_hash text not null,
  captured_at timestamptz not null,
  truncated boolean not null default false,
  unique (incident_id, content_hash)
);
create index evidence_snapshot_incident_idx on evidence_snapshot(incident_id, captured_at);
```

`V2__knowledge_diagnosis.sql` must use typed searchable columns around bounded JSON payloads:

```sql
create table runbook (
  id uuid primary key,
  runbook_key text not null unique,
  service_id uuid not null references service_catalog(id),
  display_name text not null,
  owner_team text not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint runbook_key_nonblank check (btrim(runbook_key) <> '')
);
create index runbook_service_idx on runbook(service_id);

create table runbook_version (
  id uuid primary key,
  runbook_id uuid not null references runbook(id),
  version_number integer not null,
  lifecycle text not null,
  risk_level text not null,
  adapter_id text not null,
  definition jsonb not null,
  definition_checksum text not null,
  author_principal_id uuid references principal(id),
  reviewer_principal_id uuid references principal(id),
  created_at timestamptz not null,
  published_at timestamptz,
  constraint runbook_version_positive check (version_number > 0),
  constraint runbook_lifecycle_allowed check (lifecycle in ('draft','published','retired')),
  constraint runbook_risk_allowed check (risk_level in ('r0','r1','r2','r3')),
  constraint runbook_publish_time_consistent check
    ((lifecycle = 'draft' and published_at is null) or
     (lifecycle in ('published','retired') and published_at is not null)),
  constraint runbook_definition_object check (jsonb_typeof(definition) = 'object'),
  constraint runbook_checksum_nonblank check (btrim(definition_checksum) <> '')
);
create unique index runbook_version_number_uk on runbook_version(runbook_id, version_number);
create index runbook_version_author_idx on runbook_version(author_principal_id)
  where author_principal_id is not null;
create index runbook_version_reviewer_idx on runbook_version(reviewer_principal_id)
  where reviewer_principal_id is not null;

create table diagnosis_run (
  id uuid primary key,
  incident_id uuid not null references incident(id),
  requested_by_principal_id uuid not null references principal(id),
  incident_version bigint not null,
  engine_type text not null,
  status text not null,
  model_provider text,
  model_name text,
  prompt_version text not null,
  input_hash text not null,
  input_tokens bigint not null default 0,
  output_tokens bigint not null default 0,
  cost_micros bigint not null default 0,
  failure_code text,
  started_at timestamptz not null,
  completed_at timestamptz,
  constraint diagnosis_incident_version_nonnegative check (incident_version >= 0),
  constraint diagnosis_engine_allowed check (engine_type in ('deterministic','model')),
  constraint diagnosis_status_allowed check (status in ('running','succeeded','failed')),
  constraint diagnosis_usage_nonnegative check
    (input_tokens >= 0 and output_tokens >= 0 and cost_micros >= 0),
  constraint diagnosis_completion_consistent check
    ((status = 'running' and completed_at is null) or
     (status in ('succeeded','failed') and completed_at is not null))
);
create index diagnosis_run_incident_idx on diagnosis_run(incident_id, started_at desc, id desc);
create index diagnosis_run_requester_idx on diagnosis_run(requested_by_principal_id);

alter table evidence_snapshot
  add constraint evidence_snapshot_diagnosis_run_fk
  foreign key (diagnosis_run_id) references diagnosis_run(id);
create index evidence_snapshot_diagnosis_run_idx on evidence_snapshot(diagnosis_run_id)
  where diagnosis_run_id is not null;

create table diagnosis_proposal (
  id uuid primary key,
  diagnosis_run_id uuid not null unique references diagnosis_run(id),
  incident_id uuid not null references incident(id),
  runbook_version_id uuid references runbook_version(id),
  summary text not null,
  proposal_payload jsonb not null,
  proposal_hash text not null,
  risk_level text not null,
  created_at timestamptz not null,
  constraint diagnosis_proposal_risk_allowed check (risk_level in ('r0','r1','r2')),
  constraint diagnosis_proposal_payload_object check (jsonb_typeof(proposal_payload) = 'object'),
  constraint diagnosis_proposal_hash_nonblank check (btrim(proposal_hash) <> '')
);
create index diagnosis_proposal_incident_idx on diagnosis_proposal(incident_id, created_at desc, id desc);
create index diagnosis_proposal_runbook_version_idx on diagnosis_proposal(runbook_version_id)
  where runbook_version_id is not null;

create table diagnosis_proposal_evidence (
  proposal_id uuid not null references diagnosis_proposal(id),
  evidence_snapshot_id uuid not null references evidence_snapshot(id),
  primary key (proposal_id, evidence_snapshot_id)
);
create index diagnosis_proposal_evidence_snapshot_idx
  on diagnosis_proposal_evidence(evidence_snapshot_id);

create function reject_row_mutation() returns trigger language plpgsql as $$
begin
  raise exception using errcode = '55000', message = TG_TABLE_NAME || ' is append-only';
end;
$$;

create trigger evidence_snapshot_immutable
before update or delete on evidence_snapshot
for each row execute function reject_row_mutation();
create trigger diagnosis_proposal_immutable
before update or delete on diagnosis_proposal
for each row execute function reject_row_mutation();

create function reject_published_runbook_mutation() returns trigger language plpgsql as $$
begin
  if TG_OP = 'DELETE' and OLD.lifecycle in ('published','retired') then
    raise exception using errcode = '55000', message = 'published Runbook versions cannot be deleted';
  end if;
  if TG_OP = 'DELETE' then
    return OLD;
  end if;
  if OLD.lifecycle = 'retired' then
    raise exception using errcode = '55000', message = 'retired Runbook versions are immutable';
  end if;
  if OLD.lifecycle = 'published' then
    if NEW.lifecycle = 'retired'
       and (to_jsonb(NEW) - 'lifecycle') = (to_jsonb(OLD) - 'lifecycle') then
      return NEW;
    end if;
    raise exception using errcode = '55000', message = 'published Runbook definitions are immutable';
  end if;
  return NEW;
end;
$$;

create trigger runbook_version_published_immutable
before update or delete on runbook_version
for each row execute function reject_published_runbook_mutation();
```

`V3__approval_execution_outbox.sql` must define approval, execution, append-only attempts, and a reclaimable Outbox:

```sql
create table approval_request (
  id uuid primary key,
  incident_id uuid not null references incident(id),
  proposal_id uuid not null references diagnosis_proposal(id),
  proposal_hash text not null,
  requester_principal_id uuid not null references principal(id),
  policy_version text not null,
  required_approvals integer not null,
  independent_approver_required boolean not null,
  status text not null,
  resource_version bigint not null default 0,
  created_at timestamptz not null,
  expires_at timestamptz not null,
  decided_at timestamptz,
  constraint approval_quorum_allowed check (required_approvals in (1,2)),
  constraint approval_status_allowed check
    (status in ('pending','approved','rejected','expired','invalidated')),
  constraint approval_resource_version_nonnegative check (resource_version >= 0),
  constraint approval_expiry_order check (expires_at > created_at),
  constraint approval_decided_time_consistent check
    ((status = 'pending' and decided_at is null) or
     (status <> 'pending' and decided_at is not null))
);
create index approval_request_incident_idx on approval_request(incident_id, created_at desc, id desc);
create index approval_request_proposal_idx on approval_request(proposal_id);
create index approval_request_requester_idx on approval_request(requester_principal_id);
create unique index approval_request_active_proposal_uk on approval_request(proposal_id)
  where status in ('pending','approved');

create table approval_decision (
  id uuid primary key,
  approval_request_id uuid not null references approval_request(id),
  reviewer_principal_id uuid not null references principal(id),
  decision text not null,
  comment text not null default '',
  proposal_hash text not null,
  decided_at timestamptz not null,
  constraint approval_decision_allowed check (decision in ('approve','reject'))
);
create unique index approval_decision_reviewer_uk
  on approval_decision(approval_request_id, reviewer_principal_id);
create index approval_decision_reviewer_idx on approval_decision(reviewer_principal_id);

create table execution (
  id uuid primary key,
  incident_id uuid not null references incident(id),
  proposal_id uuid not null references diagnosis_proposal(id),
  approval_request_id uuid not null references approval_request(id),
  status text not null,
  idempotency_key text not null,
  ticket_jti text,
  claimed_by text,
  lease_until timestamptz,
  fencing_token bigint not null default 0,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  started_at timestamptz,
  completed_at timestamptz,
  constraint execution_status_allowed check
    (status in ('pending','running','verifying','succeeded','failed','unknown','escalated')),
  constraint execution_fencing_nonnegative check (fencing_token >= 0),
  constraint execution_lease_claim_consistent check
    ((claimed_by is null and lease_until is null) or
     (claimed_by is not null and lease_until is not null))
);
create index execution_incident_idx on execution(incident_id, created_at desc, id desc);
create index execution_proposal_idx on execution(proposal_id);
create index execution_approval_request_idx on execution(approval_request_id);
create unique index execution_idempotency_uk on execution(idempotency_key);
create unique index execution_ticket_jti_uk on execution(ticket_jti)
  where ticket_jti is not null;

create table execution_attempt (
  id uuid primary key,
  execution_id uuid not null references execution(id),
  step_id text not null,
  attempt_no integer not null,
  fencing_token bigint not null,
  adapter_id text not null,
  adapter_version text not null,
  request_hash text not null,
  outcome text not null,
  sanitized_result jsonb not null default '{}'::jsonb,
  started_at timestamptz not null,
  completed_at timestamptz not null,
  constraint execution_attempt_number_positive check (attempt_no > 0),
  constraint execution_attempt_fencing_positive check (fencing_token > 0),
  constraint execution_attempt_outcome_allowed check
    (outcome in ('succeeded','failed','unknown')),
  constraint execution_attempt_time_order check (completed_at >= started_at),
  unique (execution_id, step_id, attempt_no)
);

create table outbox_event (
  id uuid primary key,
  aggregate_type text not null,
  aggregate_id uuid not null,
  event_type text not null,
  payload jsonb not null,
  created_at timestamptz not null,
  claimed_by text,
  claim_until timestamptz,
  publish_attempts integer not null default 0,
  last_error text,
  published_at timestamptz,
  constraint outbox_payload_object check (jsonb_typeof(payload) = 'object'),
  constraint outbox_attempts_nonnegative check (publish_attempts >= 0),
  constraint outbox_claim_consistent check
    ((claimed_by is null and claim_until is null) or
     (claimed_by is not null and claim_until is not null))
);
create index outbox_pending_claim_idx on outbox_event(created_at, id)
  where published_at is null;
create index outbox_aggregate_idx on outbox_event(aggregate_type, aggregate_id, created_at);
```

`V4__audit.sql` creates the generic audit envelope and installs append-only guards on history tables:

```sql
create table audit_record (
  id uuid primary key,
  service_id uuid references service_catalog(id),
  actor_type text not null,
  actor_id text not null,
  action text not null,
  resource_type text not null,
  resource_id text not null,
  before_hash text,
  after_hash text,
  metadata jsonb not null default '{}'::jsonb,
  trace_id text,
  occurred_at timestamptz not null,
  constraint audit_actor_type_allowed check
    (actor_type in ('user','service','system','model')),
  constraint audit_metadata_object check (jsonb_typeof(metadata) = 'object')
);
create index audit_record_service_idx on audit_record(service_id, occurred_at, id)
  where service_id is not null;
create index audit_record_resource_cursor_idx
  on audit_record(resource_type, resource_id, occurred_at, id);

create trigger incident_event_immutable
before update or delete on incident_event
for each row execute function reject_row_mutation();
create trigger execution_attempt_immutable
before update or delete on execution_attempt
for each row execute function reject_row_mutation();
create trigger audit_record_immutable
before update or delete on audit_record
for each row execute function reject_row_mutation();
```

Every foreign key above has a leading-column B-tree index (or a unique index serving the same purpose). Store proposal, parameters, Runbook definition, sanitized attempt data, and audit metadata as JSONB, but keep status, risk, hashes, ownership, foreign keys, timestamps, lease, token, and versions in typed columns with CHECK constraints.

- [ ] **Step 5: Verify clean migration, constraint behavior, and restart**

Extend `SchemaMigrationIT` to attempt two active incidents with the same `(service_id, fingerprint)` and assert the second insert fails, then mark the first `resolved` and assert a new incident succeeds. Insert/update one incident and assert `incident_projection` mirrors status, resource version, occurrence count, and timestamps; rebuild it in the test from `incident` and assert byte-equivalent query rows. Attempt to update an evidence snapshot and assert SQLSTATE `55000`. Run:

```powershell
.\mvnw.cmd -pl apps/ops-api -Dtest=SchemaMigrationIT test
```

Expected: PASS; Flyway reports version 4 and the partial unique constraint behaves as specified.

- [ ] **Step 6: Commit the schema foundation**

```powershell
git add apps/ops-api/pom.xml apps/ops-api/src/main/resources apps/ops-api/src/test
git commit -m "feat: add incident platform schema"
```

## Task 4: Implement incident state transitions, webhook dedupe, and timeline queries

**Files:**
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/shared/id/UuidV7Generator.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/shared/time/TimeProvider.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/incident/domain/IncidentStatus.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/incident/domain/IncidentCommand.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/incident/domain/IncidentStateMachine.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/incident/application/AlertEnvelope.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/incident/application/IncidentSummary.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/incident/application/IncidentTimelineItem.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/incident/application/IncidentApplicationService.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/incident/adapter/out/persistence/IncidentStore.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/incident/adapter/in/web/AlertmanagerWebhookController.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/incident/adapter/in/web/IncidentQueryController.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/shared/problem/ApiExceptionHandler.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/incident/domain/IncidentStateMachineTest.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/incident/AlertIngestionIT.java`
- Create: `contracts/webhooks/alertmanager.schema.json`

**Interfaces:**
- Produces: `IncidentStateMachine.next(IncidentStatus, IncidentCommand): IncidentStatus`.
- Produces: `IncidentApplicationService.ingest(AlertEnvelope): IncidentSummary` and keyset incident/timeline queries.
- Produces: `POST /api/v1/integrations/alertmanager/webhook`, `GET /api/v1/incidents`, `GET /api/v1/incidents/{id}`, and `GET /api/v1/incidents/{id}/timeline`.
- Consumes: schema from Task 3, `UuidV7Generator`, and `TimeProvider`.

- [ ] **Step 1: Write the failing exhaustive state-machine tests**

```java
@ParameterizedTest
@CsvSource({
    "DETECTED, START_TRIAGE, TRIAGING",
    "TRIAGING, RECORD_DIAGNOSIS, DIAGNOSED",
    "TRIAGING, REQUEST_MANUAL_VERIFICATION, VERIFYING",
    "DIAGNOSED, REQUEST_APPROVAL, AWAITING_APPROVAL",
    "DIAGNOSED, REQUEST_MANUAL_VERIFICATION, VERIFYING",
    "AWAITING_APPROVAL, START_EXECUTION, EXECUTING",
    "EXECUTING, START_VERIFICATION, VERIFYING",
    "VERIFYING, CONFIRM_RECOVERY, RESOLVED",
    "VERIFYING, RETRY_TRIAGE, TRIAGING",
    "TRIAGING, ESCALATE, ESCALATED",
    "ESCALATED, REQUEST_MANUAL_VERIFICATION, VERIFYING"
})
void allowsDeclaredTransition(IncidentStatus from, IncidentCommand command, IncidentStatus expected) {
    assertThat(IncidentStateMachine.next(from, command)).isEqualTo(expected);
}

@Test
void modelCannotResolveIncident() {
    assertThatThrownBy(() -> IncidentStateMachine.next(DIAGNOSED, CONFIRM_RECOVERY))
        .isInstanceOf(IllegalIncidentTransition.class);
}
```

- [ ] **Step 2: Run the unit test and observe failure**

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api -Dtest=IncidentStateMachineTest test
```

Expected: FAIL because state types and transition table are absent.

- [ ] **Step 3: Implement the closed transition table**

```java
public final class IncidentStateMachine {
    private static final Map<Key, IncidentStatus> ALLOWED = Map.ofEntries(
        entry(key(DETECTED, START_TRIAGE), TRIAGING),
        entry(key(DETECTED, SUPPRESS), SUPPRESSED),
        entry(key(TRIAGING, RECORD_DIAGNOSIS), DIAGNOSED),
        entry(key(TRIAGING, REQUEST_MANUAL_VERIFICATION), VERIFYING),
        entry(key(TRIAGING, ESCALATE), ESCALATED),
        entry(key(DIAGNOSED, REQUEST_APPROVAL), AWAITING_APPROVAL),
        entry(key(DIAGNOSED, REQUEST_MANUAL_VERIFICATION), VERIFYING),
        entry(key(AWAITING_APPROVAL, START_EXECUTION), EXECUTING),
        entry(key(AWAITING_APPROVAL, ESCALATE), ESCALATED),
        entry(key(EXECUTING, START_VERIFICATION), VERIFYING),
        entry(key(EXECUTING, ESCALATE), ESCALATED),
        entry(key(VERIFYING, CONFIRM_RECOVERY), RESOLVED),
        entry(key(VERIFYING, RETRY_TRIAGE), TRIAGING),
        entry(key(VERIFYING, ESCALATE), ESCALATED),
        entry(key(ESCALATED, REQUEST_MANUAL_VERIFICATION), VERIFYING)
    );

    public static IncidentStatus next(IncidentStatus current, IncidentCommand command) {
        var next = ALLOWED.get(new Key(current, command));
        if (next == null) throw new IllegalIncidentTransition(current, command);
        return next;
    }

    private record Key(IncidentStatus status, IncidentCommand command) {}
}
```

- [ ] **Step 4: Write failing integration tests for duplicate and concurrent alerts**

Test these exact cases in `AlertIngestionIT`:

```java
@Test void duplicateSourceEventReturnsSameIncidentAndOneSourceEvent() {}
@Test void sameFingerprintInParallelCreatesOneActiveIncident() {}
@Test void recoveryDuringApprovalAppendsEventButDoesNotResolve() {}
@Test void incidentDetailReturnsStatusResourceVersionAndOccurrenceCount() {}
@Test void incidentListUsesOpenedAtAndIdCursorWithoutOffset() {}
```

The parallel test uses two threads released by one `CountDownLatch`; assert one active incident, `occurrence_count=2`, and two timeline events.

- [ ] **Step 5: Implement atomic ingestion and keyset queries**

Use one short transaction. Alertmanager events must carry a stable source event ID; if the payload omits one, derive it as SHA-256 over the source name and canonical alert payload. Serialize only identical deliveries first, before touching the incident row:

```sql
select pg_advisory_xact_lock(
  hashtextextended(:source || chr(31) || :source_event_id, 0)
);

select incident_id
from incident_event
where source = :source and source_event_id = :source_event_id;
```

If the second query returns a row, return that incident without changing `occurrence_count`, `version`, or the timeline. Otherwise perform the incident upsert and allocate its event sequence:

```sql
insert into incident(id, service_id, fingerprint, title, severity, status,
                     version, next_event_seq, occurrence_count, opened_at, updated_at)
values (:id, :service_id, :fingerprint, :title, :severity, 'detected', 0, 2, 1, :now, :now)
on conflict (service_id, fingerprint)
  where status not in ('resolved','suppressed')
do update set occurrence_count = incident.occurrence_count + 1,
              updated_at = excluded.updated_at,
              next_event_seq = incident.next_event_seq + 1,
              version = incident.version + 1
returning id, status, version, next_event_seq - 1 as allocated_seq;
```

Insert the event with `on conflict (source, source_event_id) where source_event_id is not null do nothing`. The transaction-scoped advisory lock makes the pre-check and incident upsert race-safe for concurrent copies of the same delivery; the unique index remains the final invariant. Acquire advisory keys in this single, documented order and perform no network call while the transaction is open.

Incident list query uses the rebuildable projection maintained by the same incident-row transaction:

```sql
select incident_id as id, service_id, title, severity, status,
       resource_version as version, occurrence_count, opened_at, updated_at
from incident_projection
where (:status is null or status = :status)
  and (:service_id is null or service_id = :service_id)
  and (:cursor_time is null or (opened_at, incident_id) < (:cursor_time, :cursor_id))
order by opened_at desc, incident_id desc
limit :page_size_plus_one;
```

Cap page size at 100. Return an opaque Base64URL cursor containing `openedAt` and `id`.

- [ ] **Step 6: Add Problem Details and verify the API**

Map validation to `400`, authentication to `401`, authorization to `403`, missing resources to `404`, idempotency conflict to `409`, stale `If-Match` to `412`, rate limit to `429`, and dependency unavailability to `503`. Every body carries `errorCode` and current trace ID.

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api -Dtest=IncidentStateMachineTest,AlertIngestionIT test
```

Expected: PASS, including the parallel duplicate case.

- [ ] **Step 7: Commit incident ingestion**

```powershell
git add apps/ops-api/src contracts/webhooks
git commit -m "feat: ingest and deduplicate incident alerts"
```

## Task 5: Add immutable Runbooks and deterministic evidence-backed diagnosis

**Files:**
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/knowledge/domain/RiskLevel.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/knowledge/domain/RunbookVersion.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/knowledge/application/RunbookCatalog.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/diagnosis/domain/DiagnosisProposalDraft.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/diagnosis/domain/DiagnosisProposal.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/diagnosis/application/DiagnosisEngine.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/diagnosis/application/DeterministicDiagnosisEngine.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/diagnosis/application/DiagnosisPolicy.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/diagnosis/application/ProposalHasher.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/diagnosis/application/DiagnosisApplicationService.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/diagnosis/adapter/in/web/DiagnosisController.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/incident/adapter/in/web/EvidenceQueryController.java`
- Create: `apps/ops-api/src/main/resources/db/migration/V5__seed_demo_service_runbook.sql`
- Create: `apps/ops-api/src/main/resources/db/migration/V6__command_idempotency.sql`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/shared/idempotency/IdempotencyService.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/diagnosis/DiagnosisPolicyTest.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/diagnosis/DiagnosisFlowIT.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/shared/idempotency/IdempotencyServiceIT.java`

**Interfaces:**
- Produces: `DiagnosisEngine.diagnose(DiagnosisContext): DiagnosisProposalDraft`.
- Produces: `DiagnosisPolicy.validate(draft, context, runbook): ValidatedDiagnosisProposal`.
- Produces: `ProposalHasher.hash(validated): String` using SHA-256 over canonical JSON.
- Produces: `POST /api/v1/incidents/{id}/diagnosis-runs` with `If-Match` and `Idempotency-Key`.
- Produces: `GET /api/v1/incidents/{id}/evidence` with redacted payload, source reference, content hash, capture time, and truncation flag; never expose an unredacted provider body.
- Produces: `IdempotencyService.execute(scope, key, requestHash, action)`; same key/same hash replays, while the same key with a different hash returns `409 IDEMPOTENCY_KEY_REUSED`.
- Consumes: incident state/timeline from Task 4 and immutable Runbook tables from Task 3.

- [ ] **Step 1: Write failing diagnosis policy tests**

```java
@Test
void rejectsUnknownEvidenceReference() {
    var draft = proposalWithEvidence("E-404", R1);
    assertThatThrownBy(() -> policy.validate(draft, contextWithEvidence("E-12"), publishedRunbook()))
        .isInstanceOf(InvalidProposalException.class)
        .hasMessageContaining("EVIDENCE_REFERENCE_UNKNOWN");
}

@Test
void rejectsR3EvenWhenRunbookIdExists() {
    var draft = proposalWithEvidence("E-12", R3);
    assertThatThrownBy(() -> policy.validate(draft, contextWithEvidence("E-12"), publishedRunbook()))
        .isInstanceOf(UnsupportedRiskException.class);
}

@Test
void canonicalHashIgnoresObjectPropertyOrderButNotParameters() {
    assertThat(hasher.hash(validProposalWithParameters(Map.of("replicas", 1))))
        .isEqualTo(hasher.hash(validProposalWithParameters(new TreeMap<>(Map.of("replicas", 1)))))
        .isNotEqualTo(hasher.hash(validProposalWithParameters(Map.of("replicas", 2))));
}
```

- [ ] **Step 2: Run policy tests and observe failure**

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api -Dtest=DiagnosisPolicyTest test
```

Expected: FAIL because proposal, policy, and hashing types do not exist.

- [ ] **Step 3: Implement the stable diagnosis contracts**

```java
public record DiagnosisProposalDraft(
    String summary,
    List<Hypothesis> hypotheses,
    List<String> missingEvidence,
    RunbookVersionId runbookVersionId,
    Map<String, Object> parameters,
    RiskLevel riskLevel,
    VerificationExpectation expectedVerification
) {}

public record Hypothesis(int rank, String statement, BigDecimal confidence,
                         List<UUID> evidenceRefs) {}

public interface DiagnosisEngine {
    DiagnosisProposalDraft diagnose(DiagnosisContext context);
}
```

`DeterministicDiagnosisEngine` must only recommend seeded Runbook `RB-DB-POOL-03` when evidence contains both `db_pool_pending > 0` and `acquire_timeout_count > 0`; otherwise it returns missing evidence and no action. It never writes a database row itself.

Use a Jackson `ObjectMapper` configured for sorted properties and ordered map entries; serialize only the validated business payload and hash UTF-8 bytes with SHA-256 Base64URL encoding.

- [ ] **Step 4: Add the published Demo Runbook through migration**

Seed one service and one immutable Runbook version with:

```json
{
  "runbookKey": "RB-DB-POOL-03",
  "risk": "R1",
  "adapterId": "demo-http",
  "parameters": {
    "type": "object",
    "properties": { "replicas": { "type": "integer", "minimum": 1, "maximum": 1 } },
    "required": ["replicas"],
    "additionalProperties": false
  },
  "steps": [{ "stepId": "recover-one", "operation": "recover_connection_pool" }],
  "verification": { "probe": "demo_checkout_health", "successThreshold": 1.0, "attempts": 6, "intervalSeconds": 5 },
  "rollback": null
}
```

The migration inserts deterministic UUID literals for the Demo service, two Demo principal rows (distinct author and reviewer), Runbook identity/version, a checksum of canonical definition JSON, `published` lifecycle, author/reviewer foreign keys, and timestamps. No runtime seeding code is allowed.

- [ ] **Step 5: Write the failing command-idempotency integration test**

`IdempotencyServiceIT` must prove that a first command executes once, the same principal/route/key/hash returns the stored response without executing again, a changed request hash is rejected, and an in-progress record cannot be stolen. Run:

```powershell
.\mvnw.cmd -pl apps/ops-api -Dtest=IdempotencyServiceIT test
```

Expected: FAIL because the migration and service do not exist.

- [ ] **Step 6: Add command idempotency storage and service**

```sql
create table idempotency_record (
  id uuid primary key,
  principal_key text not null,
  route_key text not null,
  idempotency_key text not null,
  request_hash text not null,
  response_status integer,
  response_body jsonb,
  state text not null,
  created_at timestamptz not null,
  expires_at timestamptz not null,
  constraint idempotency_state_allowed check (state in ('started','completed','failed')),
  constraint idempotency_expiry_order check (expires_at > created_at),
  unique (principal_key, route_key, idempotency_key)
);
create index idempotency_expiry_idx on idempotency_record(expires_at);
```

Insert `started` with `on conflict do nothing`; lock an existing record only long enough to compare its hash/state. Do not hold a database lock while executing an external call. Stage 1 command actions are database-only and store the completed response in the same application transaction. Run `IdempotencyServiceIT` again and expect PASS.

- [ ] **Step 7: Write and satisfy the diagnosis integration test**

`DiagnosisFlowIT` must ingest an alert, place two deterministic evidence snapshots, call the endpoint with version `0`, then assert:

```java
assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED);
assertThat(response.body().riskLevel()).isEqualTo(RiskLevel.R1);
assertThat(response.body().hypotheses().getFirst().evidenceRefs())
    .containsExactlyInAnyOrder(metricEvidenceId, logEvidenceId);
assertThat(response.body().proposalHash()).matches("^[A-Za-z0-9_-]{43}$");
assertThat(incident.status()).isEqualTo(IncidentStatus.DIAGNOSED);
```

The diagnosis command must first validate `If-Match`, then enter `TRIAGING`, freeze its evidence IDs, execute/validate the deterministic engine, persist the requester/run/proposal, and transition to `DIAGNOSED` in the same database-only idempotent command. `DiagnosisFlowIT` asserts an idempotent replay creates no second run, proposal, or timeline event. A failed validation keeps the incident in `TRIAGING` and appends a typed failure event. Run:

```powershell
.\mvnw.cmd -pl apps/ops-api -Dtest=DiagnosisPolicyTest,IdempotencyServiceIT,DiagnosisFlowIT test
```

Expected: PASS; an invalid reference leaves the incident in `TRIAGING` and records a failure event.

- [ ] **Step 8: Commit deterministic diagnosis and shared idempotency**

```powershell
git add apps/ops-api/src/main apps/ops-api/src/test
git commit -m "feat: add evidence-backed diagnosis proposals"
```

## Task 6: Enforce OIDC roles, idempotent commands, and independent approval

**Files:**
- Modify: `apps/ops-api/pom.xml`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/identity/application/PlatformRole.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/identity/application/CurrentPrincipal.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/identity/application/PrincipalLookup.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/identity/adapter/in/security/SecurityConfig.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/identity/adapter/in/security/SentinelJwtAuthenticationConverter.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/approval/domain/ApprovalStatus.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/approval/domain/ApprovalPolicy.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/approval/application/ApprovalApplicationService.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/approval/adapter/in/web/ApprovalController.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/approval/ApprovalPolicyTest.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/approval/ApprovalConcurrencyIT.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/identity/AuthorizationIT.java`

**Interfaces:**
- Produces: `ApprovalPolicy.requirements(RiskLevel): ApprovalRequirements`.
- Produces: `ApprovalApplicationService.request(ProposalId, RequestContext)` and `decide(ApprovalRequestId, DecisionCommand, RequestContext)`.
- Produces: `POST /api/v1/incidents/{incidentId}/approval-requests` and `POST /api/v1/approval-requests/{id}/decisions`, both with `If-Match` and `Idempotency-Key`.
- Produces: trusted `(issuer, subject)` principal lookup/upsert returning the local UUID needed by approval/diagnosis foreign keys; role/service authorization still comes only from validated JWT claims in Stage 1.
- Consumes: proposal hash, Runbook risk, and `IdempotencyService` from Task 5; JWT claims `realm_access.roles` and `service_ids`.

- [ ] **Step 1: Write failing policy and security tests**

```java
@Test
void r1NeedsOneIndependentApprover() {
    assertThat(policy.requirements(R1))
        .isEqualTo(new ApprovalRequirements(1, true, Duration.ofMinutes(8)));
}

@Test
void r3CannotCreateApprovalRequest() {
    assertThatThrownBy(() -> policy.requirements(R3))
        .isInstanceOf(UnsupportedRiskException.class);
}

@Test
void requesterCannotSelfApproveR1() {
    assertThatThrownBy(() -> service.decide(requestId,
        new DecisionCommand(APPROVE, "looks good", proposalHash), requesterContext))
        .isInstanceOf(SeparationOfDutiesException.class);
}
```

`AuthorizationIT` uses `spring-security-test` JWT post-processors and verifies Observer receives `403`, Operator can request diagnosis, and SRE Approver can decide only for a service in `service_ids`.

- [ ] **Step 2: Run approval/security tests and observe failure**

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api -Dtest=ApprovalPolicyTest,ApprovalConcurrencyIT,AuthorizationIT test
```

Expected: FAIL because security mapping and approval services are absent.

- [ ] **Step 3: Implement JWT role/service-scope mapping and deny-by-default routes**

```java
public enum PlatformRole {
    OBSERVER, ON_CALL_OPERATOR, SRE_APPROVER, RUNBOOK_ADMIN, PLATFORM_ADMIN
}

public record CurrentPrincipal(String issuer, String subject, Set<PlatformRole> roles,
                               Set<UUID> serviceIds) {
    public boolean canAccess(UUID serviceId) {
        return roles.contains(PlatformRole.PLATFORM_ADMIN) || serviceIds.contains(serviceId);
    }
}
```

`SecurityConfig` permits liveness/readiness and the Demo Alertmanager webhook path only under the `demo` profile. All `/api/**` and `/internal/**` routes are authenticated; internal routes require audience `sentinelops-executor` and service role. Disable CSRF only for bearer-token APIs and set stateless sessions.

`PrincipalLookup` performs `insert ... on conflict (issuer, subject) do update set display_name=excluded.display_name returning id` only after issuer/audience validation. It never accepts issuer, subject, roles, or service IDs from request JSON. Stage 2A Task 7 adds persistent grant reconciliation and domain-level authorization around this minimal identity bridge.

- [ ] **Step 4: Implement transactional approval decisions with deterministic lock order**

Within one transaction:

1. lock `approval_request` by ID;
2. load proposal and incident without external calls;
3. check `pending`, database time `< expires_at`, current proposal hash, role/service scope, and requester separation;
4. insert one `approval_decision` using its unique reviewer constraint;
5. count approvals and rejections;
6. update request to `approved`/`rejected` and append the incident event using the next sequence;
7. update incident from `DIAGNOSED` to `AWAITING_APPROVAL` when request is created; execution remains a separate command.

Use database time from `select clock_timestamp()` for expiry decisions so application clock skew cannot extend approval.

- [ ] **Step 5: Prove concurrent quorum and invalidation behavior**

`ApprovalConcurrencyIT` starts two approvers concurrently against an R2 fixture. Assert both decisions persist once, quorum changes once, and one `APPROVAL_GRANTED` event appears. Add tests for expired request and changed proposal hash returning `409 APPROVAL_INVALIDATED`.

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api -Dtest=ApprovalPolicyTest,ApprovalConcurrencyIT,AuthorizationIT test
```

Expected: PASS with no deadlock and no duplicate reviewer decision.

- [ ] **Step 6: Commit identity and approval**

```powershell
git add apps/ops-api
git commit -m "feat: enforce scoped incident approvals"
```

## Task 7: Create execution, transactional Outbox, claim leases, and signed tickets

**Files:**
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/execution/domain/ExecutionStatus.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/execution/domain/Execution.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/execution/application/ExecutionTicketClaims.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/execution/application/ExecutionTicketSigner.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/execution/application/ExecutionApplicationService.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/execution/adapter/out/persistence/ExecutionStore.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/execution/adapter/out/persistence/OutboxStore.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/execution/adapter/out/ticket/NimbusExecutionTicketSigner.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/execution/adapter/in/web/ExecutionController.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/execution/adapter/in/internal/ExecutorControlController.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/execution/adapter/in/internal/JwksController.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/execution/adapter/out/ticket/DemoEphemeralKeyConfiguration.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/execution/ExecutionCreationIT.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/execution/ExecutionClaimIT.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/execution/ExecutionTicketTest.java`

**Interfaces:**
- Produces: `POST /api/v1/incidents/{incidentId}/executions`.
- Produces: `POST /internal/v1/executions/{id}:claim`, `:heartbeat`, `:complete`, and `:fail`.
- Produces: `GET /internal/v1/execution-keys/jwks.json`.
- Produces: signed claims containing `jti`, execution/incident IDs, Runbook version checksum, canonical parameters, target, risk, fencing token, issuer/audience, `iat`, `nbf`, and `exp`.
- Consumes: approved request from Task 6 and Runbook/proposal from Task 5.

- [ ] **Step 1: Write failing transaction and ticket tests**

```java
@Test
void createsExecutionAndOutboxAtomically() {
    var created = service.create(approvedProposalId, idempotencyKey, operatorContext);
    assertThat(executionStore.find(created.id())).isPresent();
    assertThat(outboxStore.unpublishedForAggregate(created.id())).hasSize(1);
}

@Test
void duplicateCreateReturnsSameExecution() {
    var first = service.create(approvedProposalId, "execute-p-1", operatorContext);
    var second = service.create(approvedProposalId, "execute-p-1", operatorContext);
    assertThat(second.id()).isEqualTo(first.id());
}

@Test
void ticketCannotBeUsedForDifferentRunbookChecksum() {
    var token = signer.sign(validClaims());
    assertThatThrownBy(() -> verifier.verify(token, expectedWithChecksum("other")))
        .isInstanceOf(InvalidExecutionTicket.class);
}
```

- [ ] **Step 2: Run focused tests and observe failure**

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api -Dtest=ExecutionCreationIT,ExecutionClaimIT,ExecutionTicketTest test
```

Expected: FAIL because execution control and ticket types do not exist.

- [ ] **Step 3: Implement atomic execution creation**

Derive the stable business idempotency value as `sha256(proposal_id + ':' + proposal_hash)`. Within one transaction, lock the approval request, verify it is approved/unexpired and its proposal unchanged, insert execution with `pending` status, then insert this Outbox payload:

```json
{
  "eventId": "018f...",
  "eventType": "execution.requested.v1",
  "executionId": "018f...",
  "occurredAt": "2026-09-20T10:00:00Z"
}
```

`eventId` is the `outbox_event.id`, generated before both inserts. The Outbox payload contains no credentials, parameters, prompts, or target details. Move incident `AWAITING_APPROVAL -> EXECUTING` only when the creation transaction succeeds.

- [ ] **Step 4: Implement atomic claim with lease and fencing token**

Use one SQL statement and return no row when claim is illegal:

```sql
update execution
set status = 'running',
    claimed_by = :executor_id,
    lease_until = clock_timestamp() + interval '30 seconds',
    fencing_token = fencing_token + 1,
    started_at = coalesce(started_at, clock_timestamp()),
    updated_at = clock_timestamp()
where id = :execution_id
  and status in ('pending','running')
  and (status = 'pending' or lease_until < clock_timestamp())
returning id, incident_id, proposal_id, fencing_token, lease_until;
```

After this short transaction commits, build and sign a ticket with a 60-second TTL. Never call an external target while holding a database transaction.

- [ ] **Step 5: Implement asymmetric Demo signing and JWKS publication**

Under profile `demo`, generate one in-memory RSA-3072 key pair at startup, assign a random key ID, sign with `RS256`, and publish only the public JWK. In production profile, absence of configured signing key material must fail startup; do not generate a production key silently.

Heartbeat and completion endpoints require matching execution ID, executor identity, and current fencing token. Completion records an `execution_attempt` and transitions to verification; a stale token returns `409 STALE_FENCING_TOKEN`.

- [ ] **Step 6: Verify creation, duplicate request, lease expiry, and stale completion**

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api -Dtest=ExecutionCreationIT,ExecutionClaimIT,ExecutionTicketTest test
```

Expected: PASS; forced failure after execution insert rolls back its Outbox row, and two concurrent claims yield exactly one valid token.

- [ ] **Step 7: Commit execution control**

```powershell
git add apps/ops-api
git commit -m "feat: add signed execution control plane"
```

## Task 8: Publish Outbox events and build the separate Executor consumer

**Files:**
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/execution/adapter/out/stream/OutboxRelay.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/execution/adapter/out/stream/ExecutionStreamPublisher.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/execution/OutboxRelayIT.java`
- Modify: `apps/ops-executor/pom.xml`
- Create: `apps/ops-executor/src/main/resources/application.yml`
- Create: `apps/ops-executor/src/main/java/io/sentinelops/executor/stream/ExecutionMessage.java`
- Create: `apps/ops-executor/src/main/java/io/sentinelops/executor/stream/ExecutionMessageListener.java`
- Create: `apps/ops-executor/src/main/java/io/sentinelops/executor/controlplane/ControlPlaneClient.java`
- Create: `apps/ops-executor/src/main/java/io/sentinelops/executor/controlplane/ExecutorOAuth2TokenProvider.java`
- Create: `apps/ops-executor/src/main/java/io/sentinelops/executor/ticket/ExecutionTicketVerifier.java`
- Create: `apps/ops-executor/src/main/java/io/sentinelops/executor/runbook/RunbookAdapter.java`
- Create: `apps/ops-executor/src/main/java/io/sentinelops/executor/runbook/RunbookDispatcher.java`
- Create: `apps/ops-executor/src/test/java/io/sentinelops/executor/ExecutionMessageListenerTest.java`
- Create: `apps/ops-executor/src/test/java/io/sentinelops/executor/ExecutionTicketVerifierTest.java`

**Interfaces:**
- Produces: Stream `sentinelops.executions`, consumer group `ops-executors`, payload `{eventId, executionId}`.
- Produces: `RunbookAdapter.adapterId()` and `execute(AuthorizedRunbookStep, IdempotencyContext)`.
- Produces: executor workflow `message -> claim -> verify -> dispatch -> complete/fail -> acknowledge`.
- Produces: cached client-credentials tokens with a 30-second early-refresh margin; control-plane calls require audience `sentinelops-api`, while target adapters request only their configured target scope.
- Consumes: internal control-plane/JWKS APIs from Task 7.

- [ ] **Step 1: Write failing Outbox and listener tests**

```java
@Test
void relayClaimsDifferentRowsWithoutBlockingAndMarksOnlyPublishedRows() {}

@Test
void duplicateStreamMessageDoesNotDispatchTwice() {
    listener.onMessage(message);
    listener.onMessage(message);
    verify(adapter, times(1)).execute(any(), any());
    verify(controlPlane, times(2)).claim(message.executionId());
}

@Test
void invalidAudienceStopsBeforeAdapterDispatch() {
    when(controlPlane.claim(executionId)).thenReturn(ticketWithAudience("other-service"));
    assertThatThrownBy(() -> listener.onMessage(message)).isInstanceOf(InvalidExecutionTicket.class);
    verifyNoInteractions(adapter);
}
```

- [ ] **Step 2: Run tests and observe failure**

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api,apps/ops-executor -am -Dtest=OutboxRelayIT,ExecutionMessageListenerTest,ExecutionTicketVerifierTest test
```

Expected: FAIL because relay, listener, and verifier are absent.

- [ ] **Step 3: Implement short `SKIP LOCKED` Outbox batches**

Claim up to 50 unclaimed/expired rows and persist the lease in one short transaction:

```sql
with candidates as (
  select id
  from outbox_event
  where published_at is null
    and (claim_until is null or claim_until < clock_timestamp())
  order by created_at, id
  limit 50
  for update skip locked
)
update outbox_event o
set claimed_by = :relay_id,
    claim_until = clock_timestamp() + interval '30 seconds',
    publish_attempts = publish_attempts + 1
from candidates c
where o.id = c.id
returning o.id, o.aggregate_id, o.payload;
```

Commit before publishing. After `XADD`, mark the individual row published in a new transaction only when `claimed_by=:relay_id`; clear its claim fields and set `published_at`. On a publish error, store a truncated error code/message and release the claim for bounded retry. A crash after `XADD` may duplicate delivery and is intentionally handled by execution claim/idempotency. Emit `outbox_backlog` and publish-failure metrics.

- [ ] **Step 4: Implement executor workflow and strict ticket verification**

```java
public interface RunbookAdapter {
    String adapterId();
    ExecutionStepResult execute(AuthorizedRunbookStep step, IdempotencyContext context);
}

public record IdempotencyContext(UUID executionId, String stepId, long fencingToken) {
    public String key() { return executionId + ":" + stepId; }
}
```

Verify signature, `iss=sentinelops-api`, `aud=sentinelops-executor`, time claims with at most 5 seconds skew, execution ID, Runbook checksum, and positive fencing token. The dispatcher rejects an unknown adapter/operation before any network call. Acknowledge the Stream message only after control-plane completion/failure returns a terminal acceptance; transient network errors leave it pending for reclaim.

`ExecutorOAuth2TokenProvider` uses OAuth2 client credentials and keeps each token only in memory until `expiresAt - 30s`; it never logs token values. `ControlPlaneClient` attaches the API-audience token and maps `401/403`, `409 STALE_FENCING_TOKEN`, and transient `5xx` to distinct result types. Tests use a WireMock token endpoint and assert secrets/tokens never appear in captured application logs.

- [ ] **Step 5: Prove duplicate and restart semantics**

Use a Valkey Testcontainer and WireMock control plane in `OutboxRelayIT`/executor integration tests. Publish one event twice, simulate listener restart, and assert adapter side-effect count remains one because the second claim is rejected/returns terminal state.

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api,apps/ops-executor -am -Dtest=OutboxRelayIT,ExecutionMessageListenerTest,ExecutionTicketVerifierTest test
```

Expected: PASS and no test shares an in-memory queue with another process.

- [ ] **Step 6: Commit Outbox and Executor**

```powershell
git add apps/ops-api apps/ops-executor
git commit -m "feat: dispatch approved executions safely"
```

## Task 9: Build the controlled fault service, real Demo HTTP action, and verification probe

**Files:**
- Modify: `apps/demo-service/pom.xml`
- Create: `apps/demo-service/src/main/resources/application.yml`
- Create: `apps/demo-service/src/main/java/io/sentinelops/demo/fault/FaultMode.java`
- Create: `apps/demo-service/src/main/java/io/sentinelops/demo/fault/FaultState.java`
- Create: `apps/demo-service/src/main/java/io/sentinelops/demo/fault/DemoFaultController.java`
- Create: `apps/demo-service/src/main/java/io/sentinelops/demo/checkout/CheckoutController.java`
- Create: `apps/demo-service/src/main/java/io/sentinelops/demo/runbook/DemoRecoveryController.java`
- Create: `apps/demo-service/src/main/java/io/sentinelops/demo/security/DemoServiceSecurityConfig.java`
- Create: `apps/demo-service/src/main/java/io/sentinelops/demo/security/AudienceValidator.java`
- Create: `apps/demo-service/src/test/java/io/sentinelops/demo/DemoFaultFlowIT.java`
- Create: `apps/ops-executor/src/main/java/io/sentinelops/executor/adapter/DemoHttpRunbookAdapter.java`
- Create: `apps/ops-executor/src/test/java/io/sentinelops/executor/adapter/DemoHttpRunbookAdapterTest.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/execution/application/VerificationProbe.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/execution/adapter/out/verification/DemoHttpVerificationProbe.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/execution/application/ExecutionVerificationService.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/execution/adapter/in/web/ManualResolutionController.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/execution/ExecutionVerificationIT.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/execution/ManualResolutionIT.java`

**Interfaces:**
- Produces: Demo-only `POST /internal/demo/faults/connection-pool`, `DELETE /internal/demo/faults`, normal `POST /api/checkout`, executor `POST /internal/runbooks/recover-connection-pool`, and `GET /actuator/health/readiness`.
- Produces: OAuth2 Resource Server rules requiring audience `demo-service`, scope `demo:fault` for fault injection, and scope `runbook:execute:checkout` for recovery; checkout/readiness remain public on the private Demo network.
- Produces: Micrometer gauges/counters `demo_fault_active`, `demo_checkout_requests_total`, `demo_checkout_errors_total`, and checkout latency.
- Produces: `VerificationProbe.verify(VerificationSpec): VerificationResult`.
- Produces: `POST /api/v1/incidents/{id}/resolve` as an idempotent request to run the same objective verification; a reason, `If-Match`, and `Idempotency-Key` are mandatory, and the endpoint never writes `RESOLVED` directly.
- Consumes: signed `demo-http` Runbook step from Task 8.

- [ ] **Step 1: Write failing controlled-fault tests**

```java
@Test
void faultEndpointExistsOnlyWhenDemoModeIsTrue() {}

@Test
void checkoutFailsDuringFaultAndRecoversThroughRunbookEndpoint() {
    enableConnectionPoolFault();
    assertThat(checkout().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    recoverWithExecutorCredential();
    assertThat(checkout().getStatusCode()).isEqualTo(HttpStatus.OK);
}
```

Also start with `sentinelops.demo-mode=false` and assert `/internal/demo/faults/**` is `404`. With Demo mode enabled, assert missing/wrong-audience/wrong-scope tokens receive `401/403` before state changes.

- [ ] **Step 2: Run Demo and adapter tests and observe failure**

Run:

```powershell
.\mvnw.cmd -pl apps/demo-service,apps/ops-executor,apps/ops-api -am -Dtest=DemoFaultFlowIT,DemoHttpRunbookAdapterTest,ExecutionVerificationIT,ManualResolutionIT test
```

Expected: FAIL because endpoints, adapter, and verification service are absent.

- [ ] **Step 3: Implement a concurrency-safe fault state and metrics**

Use `AtomicReference<FaultMode>` with values `NONE` and `CONNECTION_POOL_EXHAUSTED`. Checkout returns deterministic `503` problem JSON and logs one structured event containing request/trace ID but no credentials. Demo fault endpoints are guarded by `@ConditionalOnProperty(name="sentinelops.demo-mode", havingValue="true")`.

Add OAuth2 Resource Server to `demo-service`. The recovery endpoint requires the executor service token with exact audience/scope and is idempotent: clearing an already clear fault returns `200` with `changed=false`. The fault controller requires the separate Demo controller scope; neither rule accepts a console user token merely because it is authenticated.

- [ ] **Step 4: Implement the allowlisted HTTP adapter**

`DemoHttpRunbookAdapter` accepts only operation `recover_connection_pool`, target alias `demo-checkout`, and parameters exactly `{replicas: 1}`. Resolve the target URL from configuration, never from ticket/model content. Send `Idempotency-Key: executionId:stepId` and fencing token headers; use 2-second connect and 5-second response timeouts.

- [ ] **Step 5: Implement objective verification**

For Stage 1, `DemoHttpVerificationProbe` performs the Runbook-declared `demo_checkout_health` probe up to 6 attempts at 5-second intervals outside a database transaction. Persist each sanitized result. On success, transition `VERIFYING -> RESOLVED`; after the final failure, transition to `TRIAGING` once, then `ESCALATED` if a second verification cycle fails. `ManualResolutionController` may start this same service only when a published Runbook verification policy is bound to the incident; otherwise return `409 NO_VERIFICATION_POLICY`. Audit the human reason but never treat it as proof.

- [ ] **Step 6: Verify fault, action, and non-recovery paths**

Run:

```powershell
.\mvnw.cmd -pl apps/demo-service,apps/ops-executor,apps/ops-api -am -Dtest=DemoFaultFlowIT,DemoHttpRunbookAdapterTest,ExecutionVerificationIT,ManualResolutionIT test
```

Expected: PASS; a model/diagnosis result cannot call the recovery endpoint, an unknown operation never sends HTTP, and failed probes never resolve the incident.

- [ ] **Step 7: Commit Demo service and verified action**

```powershell
git add apps/demo-service apps/ops-executor apps/ops-api
git commit -m "feat: add controlled recovery demonstration"
```

## Task 10: Implement the incident cockpit, diagnosis evidence, and approval workflow

**Files:**
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/incident/application/IncidentCockpitView.java`
- Create: `apps/ops-api/src/main/java/io/sentinelops/api/incident/application/IncidentCockpitQueryService.java`
- Modify: `apps/ops-api/src/main/java/io/sentinelops/api/incident/adapter/in/web/IncidentQueryController.java`
- Create: `apps/ops-api/src/test/java/io/sentinelops/api/incident/IncidentCockpitQueryIT.java`
- Create: `contracts/openapi/sentinelops-api.yaml`
- Modify: `web/ops-console/package.json`
- Create: `web/ops-console/src/api/generated.ts`
- Create: `web/ops-console/src/auth/AuthProvider.tsx`
- Create: `web/ops-console/src/auth/RequireRole.tsx`
- Create: `web/ops-console/src/features/incidents/incidentApi.ts`
- Create: `web/ops-console/src/features/incidents/IncidentListPage.tsx`
- Create: `web/ops-console/src/features/incidents/IncidentDetailPage.tsx`
- Create: `web/ops-console/src/features/incidents/IncidentTimeline.tsx`
- Create: `web/ops-console/src/features/incidents/DiagnosisPanel.tsx`
- Create: `web/ops-console/src/features/approvals/ApprovalCard.tsx`
- Create: `web/ops-console/src/features/executions/ExecutionCard.tsx`
- Create: `web/ops-console/src/features/incidents/IncidentDetailPage.test.tsx`
- Create: `web/ops-console/src/features/approvals/ApprovalCard.test.tsx`
- Create: `web/ops-console/src/features/executions/ExecutionCard.test.tsx`
- Create: `web/ops-console/src/mocks/handlers.ts`

**Interfaces:**
- Produces: `/incidents` and `/incidents/:incidentId` routes.
- Produces: one service-scoped incident cockpit read model containing core incident data plus latest diagnosis, evidence references, active approval, and latest execution; it excludes raw prompts, tickets, and unredacted results.
- Produces: typed query hooks for incident list/detail/timeline plus diagnosis, approval-request, decision, and execution mutations.
- Produces: `ApprovalCard` requiring a rejection reason and displaying proposal hash prefix, Runbook version, target, parameters, risk, expiry, verification, and requester.
- Consumes: API/OpenAPI endpoints created in Tasks 4–7 and OIDC issuer/client configuration.

- [ ] **Step 1: Write the failing cockpit read-model integration test**

`IncidentCockpitQueryIT` creates two service scopes and a full proposal/approval/execution fixture. Assert an authorized caller receives the latest entities with stable IDs and incident `ETag`, an Observer in another service receives `403`, and the serialized JSON contains none of `prompt`, `ticket`, raw provider response, or unredacted evidence. Run:

```powershell
.\mvnw.cmd -pl apps/ops-api -Dtest=IncidentCockpitQueryIT test
```

Expected: FAIL because the cockpit projection does not exist.

- [ ] **Step 2: Implement the bounded incident cockpit query**

Use a read-only transaction and bounded indexed queries: incident by ID/service authorization, latest diagnosis by `(incident_id, created_at desc, id desc)`, active approval by proposal, latest execution by `(incident_id, created_at desc, id desc)`, and at most 100 evidence metadata rows. Return `ETag: "<incident.version>"`; return evidence content only through the separately authorized evidence endpoint. Re-run `IncidentCockpitQueryIT` and expect PASS.

- [ ] **Step 3: Define the OpenAPI contract and generate types**

The contract must describe all Stage 1 public endpoints, Problem Details, cursor pagination, `ETag`, `If-Match`, `Idempotency-Key`, roles, risk enums, evidence references, proposal hash, and approval decision. Add an npm script:

```json
{
  "scripts": {
    "api:generate": "openapi-typescript ../../contracts/openapi/sentinelops-api.yaml -o src/api/generated.ts",
    "api:check": "npm run api:generate && git diff --exit-code -- src/api/generated.ts"
  }
}
```

- [ ] **Step 4: Write failing MSW-backed workflow tests**

```tsx
it('shows evidence beside the diagnosis and blocks self approval', async () => {
  renderIncidentDetail({ currentUser: operatorWhoRequestedAction });
  expect(await screen.findByText('数据库连接池耗尽')).toBeVisible();
  expect(screen.getByRole('link', { name: 'E-12' })).toBeVisible();
  expect(screen.getByRole('button', { name: '批准 8 分钟' })).toBeDisabled();
  expect(screen.getByText('请求者不能审批自己的 R1 变更')).toBeVisible();
});

it('sends decision with idempotency key and visible resource version', async () => {
  const user = userEvent.setup();
  renderApprovalAsIndependentApprover();
  await user.click(await screen.findByRole('button', { name: '批准 8 分钟' }));
  expect(capturedRequest.headers.get('Idempotency-Key')).toMatch(/^approval-/);
  expect(capturedRequest.headers.get('If-Match')).toBe('"7"');
});

it('requests approval and starts execution only from server-confirmed states', async () => {
  const user = userEvent.setup();
  renderIncidentDetail({ role: 'ON_CALL_OPERATOR', status: 'DIAGNOSED' });
  await user.click(await screen.findByRole('button', { name: '提交审批' }));
  expect(capturedRequest.url).toContain('/approval-requests');
  rerenderIncidentDetail({ role: 'ON_CALL_OPERATOR', approvalStatus: 'APPROVED' });
  await user.click(await screen.findByRole('button', { name: '执行已审批方案' }));
  expect(capturedRequest.url).toContain('/executions');
});
```

- [ ] **Step 5: Run frontend tests and observe failure**

Run:

```powershell
npm --prefix .\web\ops-console run api:generate
npm --prefix .\web\ops-console test -- --run src/features/incidents/IncidentDetailPage.test.tsx src/features/approvals/ApprovalCard.test.tsx src/features/executions/ExecutionCard.test.tsx
```

Expected: FAIL because incident/approval pages are absent.

- [ ] **Step 6: Implement role-aware incident pages**

Use query keys `['incidents', filters]`, `['incident', id]`, and `['incidentTimeline', id]`. Poll the incident list every 5 seconds, including empty and all-terminal queues, to discover newly arriving alerts. Poll active incident details every 5 seconds and stop polling terminal incident details. Never optimistically mark an approval/execution successful; render the server-returned state. On `412`, invalidate detail/timeline queries and display “事故已更新，请重新检查建议”。

Timeline items must show event type, actor, time, trace ID, sanitized payload summary, and evidence links. Diagnosis must show confidence as supporting metadata, not a certainty badge. Operators explicitly submit a diagnosed proposal for approval and, only after the server reports `approved`, explicitly start its execution. Use real buttons with keyboard/focus behavior and live-region status for mutations.

- [ ] **Step 7: Verify backend/frontend contract, accessibility smoke, and build**

Run:

```powershell
.\mvnw.cmd -pl apps/ops-api -Dtest=IncidentCockpitQueryIT test
npm --prefix .\web\ops-console run api:check
npm --prefix .\web\ops-console test -- --run
npm --prefix .\web\ops-console run lint
npm --prefix .\web\ops-console run build
```

Expected: PASS; no generated contract drift, no TypeScript errors, and tests cover Observer/Operator/Approver UI states.

- [ ] **Step 8: Commit the Stage 1 console workflow**

```powershell
git add apps/ops-api contracts/openapi web/ops-console
git commit -m "feat: add incident approval cockpit"
```

## Task 11: Package the Demo profile, run the browser E2E, and record `v0.1.0-demo`

**Files:**
- Create: `apps/ops-api/Dockerfile`
- Create: `apps/ops-executor/Dockerfile`
- Create: `apps/demo-service/Dockerfile`
- Create: `web/ops-console/Dockerfile`
- Create: `deploy/compose/compose.core.yml`
- Create: `deploy/compose/compose.demo.yml`
- Create: `deploy/keycloak/sentinelops-realm.json`
- Create: `deploy/observability/prometheus.yml`
- Create: `deploy/observability/alerts/demo-service.yml`
- Create: `deploy/observability/alertmanager.yml`
- Create: `web/ops-console/playwright.config.ts`
- Create: `web/ops-console/e2e/demo-incident.spec.ts`
- Create: `scripts/demo.ps1`
- Create: `scripts/verify.ps1`
- Create: `README.md`
- Create: `docs/runbooks/demo-flow.md`

**Interfaces:**
- Produces: `docker compose -p sentinelops -f deploy/compose/compose.core.yml -f deploy/compose/compose.demo.yml up -d --build`.
- Produces: Demo users `observer-demo`, `operator-demo`, and `approver-demo` with non-production credentials documented in the realm fixture.
- Produces: Prometheus rule `SentinelOpsDemoCheckoutFault` posting an Alertmanager webhook to `ops-api`.
- Produces: `scripts/demo.ps1` for start/wait/inject and `scripts/verify.ps1` for all Stage 1 gates.
- Consumes: every Stage 1 application and UI artifact.

- [ ] **Step 1: Write the failing browser E2E first**

```ts
test('detects, diagnoses, approves, executes, and verifies one incident', async ({ browser }) => {
  await injectDemoFault();

  const operator = await loginAs(browser, 'operator-demo');
  const incident = await waitForIncident(operator, 'checkout-api');
  await expect(incident.getByText('数据库连接池耗尽')).toBeVisible();
  await expect(incident.getByRole('link', { name: 'E-12' })).toBeVisible();
  await runDiagnosisAndSubmitApproval(incident);
  await expect(incident.getByRole('button', { name: '批准 8 分钟' })).toBeDisabled();

  const approver = await loginAs(browser, 'approver-demo');
  await approvePendingR1(approver);

  await incident.reload();
  await startApprovedExecution(incident);
  await expectResolvedWithVerification(operator, 'checkout-api');
  expect(await demoRecoverySideEffectCount()).toBe(1);
});
```

- [ ] **Step 2: Run Compose config/E2E and observe failure**

Run:

```powershell
docker compose -p sentinelops -f .\deploy\compose\compose.core.yml -f .\deploy\compose\compose.demo.yml config --quiet
npm --prefix .\web\ops-console run e2e
```

Expected: FAIL because Compose, realm, alerts, and browser helpers do not exist.

- [ ] **Step 3: Build pinned, health-checked containers**

Core contains PostgreSQL 17/pgvector, Valkey, ops-api, ops-executor, and web. Demo adds Keycloak 26.7.4, demo-service, Prometheus 3.13.3, and Alertmanager. Use explicit image tags, named volumes, non-root application containers, read-only root filesystems where supported, and health-based `depends_on`. Put every container on private networks; only web, Keycloak, and explicitly documented local observability ports bind to localhost.

The Prometheus rule fires when `demo_fault_active == 1` for 5 seconds and labels the alert with `service_key=checkout-api`, `severity=sev1`, and a stable fingerprint label. Alertmanager posts the standard v4 webhook payload to ops-api.

- [ ] **Step 4: Provision OIDC roles and users**

The realm fixture creates client `sentinelops-console` with Authorization Code + PKCE, API audience `sentinelops-api`, roles matching `PlatformRole`, service claim mapper, and three Demo-only users. It also creates confidential client `sentinelops-executor` with API audience/internal-executor role plus only `runbook:execute:checkout`, and confidential client `sentinelops-demo-controller` with only `demo:fault`. No wildcard redirect URI; allow exactly `http://localhost:4173/*` and the documented dev origin. Compose injects client secrets from Demo-only environment variables; they are not committed in production files.

- [ ] **Step 5: Implement deterministic Demo and verification scripts**

`scripts/demo.ps1` performs:

1. Compose build/up;
2. bounded health wait (maximum 180 seconds);
3. Demo fault injection;
4. print web URL and three user roles, never production credentials.

`scripts/verify.ps1` runs Maven verify, npm CI/lint/test/build, Compose config, Compose up, readiness checks, Playwright, duplicate Stream redelivery check, and teardown in `finally`. It exits non-zero on the first failed gate and preserves logs under `build/verification/`.

- [ ] **Step 6: Run the complete Stage 1 release gate**

Run:

```powershell
.\scripts\verify.ps1
```

Expected:

- Maven unit/integration/module tests PASS;
- frontend typecheck/lint/Vitest/build PASS;
- all containers become healthy within 180 seconds;
- Playwright completes the full workflow;
- only one active incident is created during the alert burst;
- self-approval is denied;
- exactly one recovery side effect occurs despite duplicate delivery;
- incident becomes `RESOLVED` only after a persisted successful probe;
- repository contains no committed real secret.

- [ ] **Step 7: Commit milestone artifacts and create the Demo tag**

```powershell
git add apps web contracts deploy scripts README.md docs/runbooks
git commit -m "feat: deliver SentinelOps demo vertical slice"
git tag -a v0.1.0-demo -m "SentinelOps AI verified demo milestone"
git status --short
```

Expected: tag created and `git status --short` prints nothing.

- [ ] **Step 8: Continue immediately to Stage 2A**

Open `docs/superpowers/plans/2026-09-20-sentinelops-ai-stage-2a-integrations.md` and start Task 1 in the same worktree. Do not pause for a new product confirmation; pause only if a real model credential, external monitoring endpoint, or new authority is required.
