package io.sentinelops.api.incident.application.evidence;

import java.time.Duration;
import java.util.Objects;

/** Caller budget with hard server-side limits. */
public record EvidenceBudget(int maxItems, int maxBytes, Duration maxWindow) {

    public static final int MAX_ITEMS = 10_000;
    public static final int MAX_BYTES = 1_048_576;
    public static final Duration MAX_WINDOW = Duration.ofHours(24);

    public EvidenceBudget {
        Objects.requireNonNull(maxWindow, "maxWindow");
        if (maxItems < 1 || maxItems > MAX_ITEMS
                || maxBytes < 256 || maxBytes > MAX_BYTES
                || maxWindow.isNegative() || maxWindow.isZero() || maxWindow.compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("invalid evidence budget");
        }
    }
}
