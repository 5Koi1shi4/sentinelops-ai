package io.sentinelops.demo.fault;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/demo/faults")
@ConditionalOnProperty(name = "sentinelops.demo-mode", havingValue = "true")
public class DemoFaultController {

    private final FaultState faults;

    public DemoFaultController(FaultState faults) {
        this.faults = faults;
    }

    @PostMapping("/connection-pool")
    FaultView enableConnectionPoolFault() {
        boolean changed = faults.enable(FaultMode.CONNECTION_POOL_EXHAUSTED);
        return new FaultView(faults.mode().name(), faults.active(), changed);
    }

    @DeleteMapping
    FaultView clearFaults() {
        boolean changed = faults.clear();
        return new FaultView(faults.mode().name(), faults.active(), changed);
    }

    public record FaultView(String mode, boolean active, boolean changed) {}
}
