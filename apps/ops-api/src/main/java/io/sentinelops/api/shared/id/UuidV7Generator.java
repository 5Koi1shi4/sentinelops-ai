package io.sentinelops.api.shared.id;

import java.security.SecureRandom;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UuidV7Generator {

    private static final long TIMESTAMP_MASK = 0x0000_FFFF_FFFF_FFFFL;
    private static final long VERSION_7 = 0x7000L;
    private static final long RFC_4122_VARIANT = 0x8000_0000_0000_0000L;
    private static final long VARIANT_MASK = 0x3FFF_FFFF_FFFF_FFFFL;

    private final SecureRandom random = new SecureRandom();
    private long lastUnixMillis = -1;
    private int sequence;

    public synchronized UUID generate() {
        long unixMillis = Math.max(System.currentTimeMillis(), lastUnixMillis);
        if (unixMillis == lastUnixMillis) {
            sequence = (sequence + 1) & 0x0FFF;
            if (sequence == 0) {
                unixMillis++;
            }
        } else {
            sequence = random.nextInt(1 << 12);
        }
        lastUnixMillis = unixMillis;

        long mostSignificantBits =
                ((unixMillis & TIMESTAMP_MASK) << 16) | VERSION_7 | sequence;
        long leastSignificantBits =
                (random.nextLong() & VARIANT_MASK) | RFC_4122_VARIANT;
        return new UUID(mostSignificantBits, leastSignificantBits);
    }
}
