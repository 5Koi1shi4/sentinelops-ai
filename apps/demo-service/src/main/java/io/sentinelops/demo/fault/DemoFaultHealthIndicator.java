package io.sentinelops.demo.fault;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("demoFault")
public class DemoFaultHealthIndicator implements HealthIndicator {

    private final FaultState faults;

    public DemoFaultHealthIndicator(FaultState faults) {
        this.faults = faults;
    }

    @Override
    public Health health() {
        if (faults.active()) {
            return Health.down()
                    .withDetail("fault", faults.mode().name())
                    .build();
        }
        return Health.up().build();
    }
}
