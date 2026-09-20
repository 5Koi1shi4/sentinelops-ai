package io.sentinelops.api.shared.time;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class TimeProvider {

    private final Clock clock;

    public TimeProvider() {
        this(Clock.systemUTC());
    }

    TimeProvider(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }
}
