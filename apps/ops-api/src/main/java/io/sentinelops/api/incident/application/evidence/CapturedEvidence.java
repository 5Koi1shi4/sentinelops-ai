package io.sentinelops.api.incident.application.evidence;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Normalized evidence held in memory until Task 2 redacts and freezes it.
 * The hash is only a deterministic normalization hash and is not a persistence hash.
 */
public final class CapturedEvidence {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Comparator<EvidenceItem> ITEM_ORDER = Comparator
            .comparing(CapturedEvidence::numericTimestamp)
            .thenComparing(item -> OBJECT_MAPPER.writeValueAsString(item.labels()))
            .thenComparing(EvidenceItem::value);

    private final UUID incidentId;
    private final UUID serviceId;
    private final String sourceType;
    private final String queryId;
    private final Instant from;
    private final Instant to;
    private final Instant capturedAt;
    private final List<EvidenceItem> items;
    private final List<String> warnings;
    private final boolean truncated;
    private final int serializedBytes;
    private final String contentHash;

    private CapturedEvidence(
            UUID incidentId,
            UUID serviceId,
            String sourceType,
            String queryId,
            Instant from,
            Instant to,
            Instant capturedAt,
            List<EvidenceItem> items,
            List<String> warnings,
            boolean truncated,
            int serializedBytes,
            String contentHash) {
        this.incidentId = incidentId;
        this.serviceId = serviceId;
        this.sourceType = sourceType;
        this.queryId = queryId;
        this.from = from;
        this.to = to;
        this.capturedAt = capturedAt;
        this.items = List.copyOf(items);
        this.warnings = List.copyOf(warnings);
        this.truncated = truncated;
        this.serializedBytes = serializedBytes;
        this.contentHash = contentHash;
    }

    public static CapturedEvidence create(
            UUID incidentId,
            UUID serviceId,
            String sourceType,
            String queryId,
            Instant from,
            Instant to,
            List<EvidenceItem> sourceItems,
            List<String> sourceWarnings,
            boolean initiallyTruncated,
            int maxBytes) {
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(queryId, "queryId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(sourceItems, "sourceItems");
        Objects.requireNonNull(sourceWarnings, "sourceWarnings");
        if (maxBytes < 256 || maxBytes > EvidenceBudget.MAX_BYTES) {
            throw new IllegalArgumentException("invalid evidence byte budget");
        }

        var items = new ArrayList<>(sourceItems);
        items.sort(ITEM_ORDER);
        var warnings = new ArrayList<>(sourceWarnings);
        warnings.sort(String::compareTo);
        boolean truncated = initiallyTruncated;
        var canonical = canonicalBytes(sourceType, queryId, from, to, items, warnings, truncated, maxBytes);
        if (canonical.exceeded()) {
            truncated = true;
            if (canonicalBytes(sourceType, queryId, from, to, List.of(), List.of(), true, maxBytes).exceeded()) {
                throw new EvidenceBudgetExceeded("normalized evidence metadata exceeds byte budget");
            }
            // 二分前缀，避免逐条删除后重复序列化的平方开销。
            int low = 0;
            int high = warnings.size();
            while (low < high) {
                int mid = (low + high + 1) / 2;
                if (!canonicalBytes(sourceType, queryId, from, to, List.of(), warnings.subList(0, mid), true, maxBytes).exceeded()) low = mid;
                else high = mid - 1;
            }
            warnings = new ArrayList<>(warnings.subList(0, low));
            low = 0;
            high = items.size();
            while (low < high) {
                int mid = (low + high + 1) / 2;
                if (!canonicalBytes(sourceType, queryId, from, to, items.subList(0, mid), warnings, true, maxBytes).exceeded()) low = mid;
                else high = mid - 1;
            }
            items = new ArrayList<>(items.subList(0, low));
            canonical = canonicalBytes(sourceType, queryId, from, to, items, warnings, true, maxBytes);
        }
        if (canonical.exceeded()) {
            throw new IllegalStateException("normalized evidence remained over the byte budget");
        }
        return new CapturedEvidence(
                incidentId,
                serviceId,
                sourceType,
                queryId,
                from,
                to,
                Instant.now(),
                items,
                warnings,
                truncated,
                canonical.bytes().length,
                sha256Hex(canonical.bytes()));
    }

    public static CapturedEvidence bounded(EvidenceQuery query, String sourceType,
                                             List<EvidenceItem> sourceItems, List<String> warnings,
                                             EvidenceBudget budget) {
        var items = new ArrayList<>(sourceItems);
        items.sort(ITEM_ORDER);
        boolean truncated = items.size() > budget.maxItems();
        return create(query.incidentId(), query.serviceId(), sourceType, query.queryId(), query.from(), query.to(),
                items.subList(0, Math.min(items.size(), budget.maxItems())), warnings, truncated, budget.maxBytes());
    }

    public UUID incidentId() {
        return incidentId;
    }

    public UUID serviceId() {
        return serviceId;
    }

    public String sourceType() {
        return sourceType;
    }

    public String queryId() {
        return queryId;
    }

    public Instant from() {
        return from;
    }

    public Instant to() {
        return to;
    }

    public Instant capturedAt() {
        return capturedAt;
    }

    public List<EvidenceItem> items() {
        return items;
    }

    public List<String> warnings() {
        return warnings;
    }

    public boolean truncated() {
        return truncated;
    }

    public int serializedBytes() {
        return serializedBytes;
    }

    public String contentHash() {
        return contentHash;
    }

    private static SerializedJson canonicalBytes(
            String sourceType,
            String queryId,
            Instant from,
            Instant to,
            List<EvidenceItem> items,
            List<String> warnings,
            boolean truncated,
            int maxBytes) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("sourceType", sourceType);
        payload.put("queryId", queryId);
        payload.put("from", from.toString());
        payload.put("to", to.toString());
        var normalizedItems = new ArrayList<Map<String, Object>>();
        for (var item : items) {
            var normalized = new LinkedHashMap<String, Object>();
            normalized.put("timestamp", item.timestamp());
            normalized.put("value", item.value());
            normalized.put("labels", new LinkedHashMap<>(item.labels()));
            normalizedItems.add(normalized);
        }
        payload.put("items", normalizedItems);
        payload.put("warnings", warnings);
        payload.put("truncated", truncated);
        var output = new BoundedOutputStream(maxBytes);
        try {
            OBJECT_MAPPER.writeValue(output, payload);
            return new SerializedJson(output.bytes(), output.exceeded());
        } catch (JacksonException failure) {
            if (output.exceeded()) return new SerializedJson(new byte[0], true);
            throw new IllegalStateException("cannot serialize normalized evidence", failure);
        }
    }

    private record SerializedJson(byte[] bytes, boolean exceeded) {}

    /** Streams at most the configured cap while retaining whether more bytes were written. */
    private static final class BoundedOutputStream extends OutputStream {

        private final int limit;
        private final ByteArrayOutputStream buffer;
        private long totalBytes;

        private BoundedOutputStream(int limit) {
            this.limit = limit;
            this.buffer = new ByteArrayOutputStream(Math.min(limit, 4096));
        }

        @Override
        public void write(int value) throws IOException {
            if (totalBytes < limit) {
                buffer.write(value);
            }
            totalBytes = Math.min((long) limit + 1, totalBytes + 1);
            if (exceeded()) throw new IOException("normalized JSON size probe reached its limit");
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (bytes == null) {
                throw new NullPointerException("bytes");
            }
            if (offset < 0 || length < 0 || offset > bytes.length - length) {
                throw new IndexOutOfBoundsException();
            }
            long remaining = limit - totalBytes;
            if (remaining > 0 && length > 0) {
                int retained = (int) Math.min(remaining, length);
                buffer.write(bytes, offset, retained);
            }
            totalBytes = Math.min((long) limit + 1, totalBytes + length);
            if (exceeded()) throw new IOException("normalized JSON size probe reached its limit");
        }

        private byte[] bytes() {
            return buffer.toByteArray();
        }

        private boolean exceeded() {
            return totalBytes > limit;
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var result = digest.digest(bytes);
            var hex = new StringBuilder(result.length * 2);
            for (byte value : result) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static java.math.BigDecimal numericTimestamp(EvidenceItem item) {
        return new java.math.BigDecimal(item.timestamp());
    }

    public record EvidenceItem(String timestamp, String value, Map<String, String> labels) {
        public EvidenceItem {
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(labels, "labels");
            if (timestamp.length() > 32 || !timestamp.matches("[0-9]+(?:\\.[0-9]{1,9})?")
                    || value.length() > EvidenceBudget.MAX_BYTES) {
                throw new IllegalArgumentException("invalid evidence item");
            }
            var sorted = new java.util.TreeMap<String, String>();
            labels.forEach((key, labelValue) -> {
                if (key == null || key.isBlank() || labelValue == null) {
                    throw new IllegalArgumentException("invalid evidence label");
                }
                sorted.put(key, labelValue);
            });
            labels = Collections.unmodifiableMap(sorted);
        }
    }
}
