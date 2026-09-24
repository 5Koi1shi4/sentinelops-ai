package io.sentinelops.api.shared.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.logs.Severity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 业务指标名称和维度在代码中固定，禁止用请求数据构造指标名。 */
@Component
public final class BusinessMetrics {
    private static final Logger LOG = LoggerFactory.getLogger(BusinessMetrics.class);

    private final ObservationRegistry observations;
    private final MeterRegistry meters;
    private final io.opentelemetry.api.logs.Logger otelLogs;
    private final String pricedProvider;
    private final String pricedModel;
    private final BigDecimal inputUsdPerMillion;
    private final BigDecimal outputUsdPerMillion;

    public BusinessMetrics(ObservationRegistry observations, MeterRegistry meters,
            OpenTelemetry openTelemetry) {
        this(observations, meters, openTelemetry, "", "", BigDecimal.valueOf(-1),
                BigDecimal.valueOf(-1));
    }

    @Autowired
    public BusinessMetrics(ObservationRegistry observations, MeterRegistry meters,
            OpenTelemetry openTelemetry,
            @Value("${sentinelops.ai.pricing.provider:}") String pricedProvider,
            @Value("${sentinelops.ai.pricing.model:}") String pricedModel,
            @Value("${sentinelops.ai.pricing.input-usd-per-million-tokens:-1}")
                    BigDecimal inputUsdPerMillion,
            @Value("${sentinelops.ai.pricing.output-usd-per-million-tokens:-1}")
                    BigDecimal outputUsdPerMillion) {
        this.observations = Objects.requireNonNull(observations, "observations");
        this.meters = Objects.requireNonNull(meters, "meters");
        this.otelLogs = Objects.requireNonNull(openTelemetry, "openTelemetry")
                .getLogsBridge().get("io.sentinelops.business");
        this.pricedProvider = pricedProvider == null ? "" : pricedProvider.trim();
        this.pricedModel = pricedModel == null ? "" : pricedModel.trim();
        this.inputUsdPerMillion = Objects.requireNonNull(inputUsdPerMillion);
        this.outputUsdPerMillion = Objects.requireNonNull(outputUsdPerMillion);
        boolean configured = !this.pricedProvider.isEmpty() || !this.pricedModel.isEmpty()
                || inputUsdPerMillion.signum() >= 0 || outputUsdPerMillion.signum() >= 0;
        if (configured && (this.pricedProvider.isEmpty() || this.pricedModel.isEmpty()
                || inputUsdPerMillion.signum() < 0 || outputUsdPerMillion.signum() < 0)) {
            throw new IllegalArgumentException(
                    "AI cost pricing requires provider, model, and both token rates");
        }
    }

    public Scope start(Operation operation) {
        return new Scope(operation);
    }

    public void incidentDetected(String severity) {
        afterCommit(() -> counter("sentinelops.incident.detected", "severity", severity)
                .increment());
    }

    public void diagnosisCompleted(String result, String risk, Duration elapsed, int citations) {
        counter("sentinelops.diagnosis.completed", "result", result, "risk", risk).increment();
        timer("sentinelops.diagnosis.duration", "result", result).record(elapsed);
        if (citations > 0) {
            counter("sentinelops.diagnosis.citations", "result", "success")
                    .increment(citations);
        }
    }

    public void approvalDecision(String status, Duration latency) {
        counter("sentinelops.approval.decisions", "status", status).increment();
        if (latency != null && !latency.isNegative()) {
            timer("sentinelops.approval.latency", "status", status).record(latency);
        }
    }

    public void citationValidation(String result) {
        afterCommit(() -> counter("sentinelops.diagnosis.citation.validation",
                "result", result).increment());
    }

    public void approvalRequested() {
        counter("sentinelops.approval.requests").increment();
    }

    public void executionOutcome(String status) {
        afterCommit(() -> counter("sentinelops.execution.outcomes", "status", status)
                .increment());
    }

    public void executionLeaseConflict() {
        counter("sentinelops.execution.lease.conflicts").increment();
    }

    public void executionFencingConflict() {
        counter("sentinelops.execution.fencing.conflicts").increment();
    }

    public void verificationOutcome(String status) {
        counter("sentinelops.verification.outcomes", "status", status).increment();
    }

    public void incidentMttr(Duration elapsed) {
        if (elapsed != null && !elapsed.isNegative()) {
            timer("sentinelops.incident.mttr").record(elapsed);
        }
    }

    public void incidentMttd(Duration elapsed) {
        if (elapsed != null && !elapsed.isNegative()) {
            afterCommit(() -> timer("sentinelops.incident.mttd").record(elapsed));
        }
    }

    public void aiTokens(String provider, String model, long prompt, long completion) {
        if (prompt > 0) {
            counter("sentinelops.ai.tokens", "provider", provider, "model", model,
                    "type", "prompt").increment(prompt);
        }
        if (completion > 0) {
            counter("sentinelops.ai.tokens", "provider", provider, "model", model,
                    "type", "completion").increment(completion);
        }
        if (!pricedProvider.isEmpty() && pricedProvider.equals(provider)
                && pricedModel.equals(model)) {
            BigDecimal dollars = inputUsdPerMillion
                    .multiply(BigDecimal.valueOf(Math.max(0, prompt)))
                    .add(outputUsdPerMillion.multiply(
                            BigDecimal.valueOf(Math.max(0, completion))))
                    .divide(BigDecimal.valueOf(1_000_000), 12, RoundingMode.HALF_UP);
            counter("sentinelops.ai.cost.estimated", "provider", provider,
                    "model", model).increment(dollars.doubleValue());
        }
    }

    public void toolResult(String tool, String result) {
        counter("sentinelops.diagnosis.tool.results", "tool", tool,
                "result", result).increment();
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(meters);
    }

    private Timer timer(String name, String... tags) {
        return Timer.builder(name).tags(tags).publishPercentileHistogram().register(meters);
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    public enum Operation {
        INCIDENT_INGEST("sentinelops.incident.ingest"),
        DIAGNOSIS_RUN("sentinelops.diagnosis.run"),
        APPROVAL_REQUEST("sentinelops.approval.request"),
        APPROVAL_DECIDE("sentinelops.approval.decide"),
        EXECUTION_CREATE("sentinelops.execution.create"),
        EXECUTION_CLAIM("sentinelops.execution.claim"),
        EXECUTION_COMPLETE("sentinelops.execution.complete"),
        VERIFICATION_RUN("sentinelops.verification.run");

        private final String spanName;

        Operation(String spanName) {
            this.spanName = spanName;
        }
    }

    public final class Scope implements AutoCloseable {
        private final Operation operation;
        private final Observation observation;
        private final Observation.Scope opened;
        private final CorrelationContext correlation;
        private String result = "error";

        private Scope(Operation operation) {
            this.operation = Objects.requireNonNull(operation, "operation");
            this.observation = Observation.createNotStarted(operation.spanName, observations).start();
            this.opened = observation.openScope();
            this.correlation = new CorrelationContext(observation);
        }

        public Scope incident(UUID id) {
            correlation.id("incident.id", id);
            return this;
        }

        public Scope diagnosisRun(UUID id) {
            correlation.id("diagnosis.run.id", id);
            return this;
        }

        public Scope approval(UUID id) {
            correlation.id("approval.id", id);
            return this;
        }

        public Scope execution(UUID id) {
            correlation.id("execution.id", id);
            return this;
        }

        public Scope risk(String risk) {
            observation.lowCardinalityKeyValue("risk", risk);
            return this;
        }

        public Scope status(String status) {
            observation.lowCardinalityKeyValue("status", status);
            return this;
        }

        public Scope success() {
            result = "success";
            return this;
        }

        @Override
        public void close() {
            observation.lowCardinalityKeyValue("result", result);
            LOG.info("Business milestone operation={} result={}", operation.spanName, result);
            var record = otelLogs.logRecordBuilder()
                    .setSeverity(Severity.INFO)
                    .setBody("Business milestone")
                    .setAttribute("operation", operation.spanName)
                    .setAttribute("result", result);
            correlation.addTo(record);
            record.emit();
            correlation.close();
            opened.close();
            observation.stop();
        }
    }
}
