package io.sentinelops.demo.checkout;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.sentinelops.demo.fault.FaultMode;
import io.sentinelops.demo.fault.FaultState;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/checkout")
public class CheckoutController {

    private static final Logger LOG = LoggerFactory.getLogger(CheckoutController.class);

    private final FaultState faults;
    private final MeterRegistry meters;
    private final Counter requests;
    private final Counter errors;

    public CheckoutController(FaultState faults, MeterRegistry meters) {
        this.faults = faults;
        this.meters = meters;
        this.requests = Counter.builder("demo_checkout_requests")
                .description("Demo checkout requests")
                .register(meters);
        this.errors = Counter.builder("demo_checkout_errors")
                .description("Demo checkout failures")
                .register(meters);
    }

    @PostMapping
    ResponseEntity<?> checkout(
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        Timer.Sample sample = Timer.start(meters);
        requests.increment();
        try {
            if (faults.mode() == FaultMode.CONNECTION_POOL_EXHAUSTED) {
                errors.increment();
                String safeRequestId = requestId == null || requestId.isBlank()
                        ? UUID.randomUUID().toString()
                        : requestId.substring(0, Math.min(requestId.length(), 128));
                LOG.warn(
                        "Demo checkout unavailable requestId={} faultMode={}",
                        safeRequestId,
                        faults.mode());
                var problem = ProblemDetail.forStatusAndDetail(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "The controlled Demo connection pool fault is active.");
                problem.setTitle("Demo checkout unavailable");
                problem.setProperty("errorCode", "demo_connection_pool_exhausted");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
            }
            return ResponseEntity.ok(new CheckoutView(true));
        } finally {
            sample.stop(Timer.builder("demo_checkout_latency")
                    .description("Demo checkout request latency")
                    .register(meters));
        }
    }

    public record CheckoutView(boolean accepted) {}
}
