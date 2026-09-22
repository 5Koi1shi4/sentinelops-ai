package io.sentinelops.api.incident.adapter.out.http;

import io.sentinelops.api.incident.application.evidence.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

final class EvidenceResponseNormalizer {
    private EvidenceResponseNormalizer() {}

    static CapturedEvidence normalize(JsonNode root, String source, EvidenceQuery query,
                                       EvidenceQueryTemplate template, EvidenceBudget budget) {
        boolean metrics = source.equals("prometheus");
        if (root == null || !root.isObject() || !root.path("status").asString("").equals("success")) throw malformed();
        var data = root.path("data");
        if (!data.isObject() || !data.path("resultType").asString("").equals(metrics ? "matrix" : "streams")
                || !data.path("result").isArray()) throw malformed();
        int sampleCount = 0;
        for (var series : data.get("result")) {
            var values = series.path("values");
            if (!values.isArray()) throw malformed();
            if (values.size() > EvidenceBudget.MAX_ITEMS - sampleCount) {
                throw new EvidenceBudgetExceeded("provider response exceeds sample limit");
            }
            sampleCount += values.size();
        }
        var items = new ArrayList<CapturedEvidence.EvidenceItem>();
        var warnings = new ArrayList<String>();
        if (root.has("warnings")) {
            if (!root.get("warnings").isArray()) throw malformed();
            for (var warning : root.get("warnings")) {
                if (!warning.isString()) throw malformed();
                warnings.add(warning.asString());
            }
        }
        BigDecimal from = seconds(query.from());
        BigDecimal to = seconds(query.to());
        for (var series : data.get("result")) {
            var labelsNode = series.path(metrics ? "metric" : "stream");
            if (!series.isObject() || !labelsNode.isObject() || !series.path("values").isArray()
                    || series.has("histograms")) throw malformed();
            Map<String, String> labels = new TreeMap<>();
            int labelBytes = 0;
            for (var property : labelsNode.properties()) {
                if (!property.getValue().isString()) throw malformed();
                if (template.allowedLabels().contains(property.getKey())) {
                    var labelValue = property.getValue().asString();
                    if (labelValue.length() > 4096) throw oversizedLabels();
                    int valueBytes = labelValue.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    labelBytes += property.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + valueBytes;
                    if (valueBytes > 4096 || labelBytes > 8192) throw oversizedLabels();
                    labels.put(property.getKey(), labelValue);
                }
            }
            for (var sample : series.get("values")) {
                if (!sample.isArray() || sample.size() != 2 || !sample.get(1).isString()
                        || (metrics ? !sample.get(0).isNumber() : !sample.get(0).isString())) throw malformed();
                String timestamp = sample.get(0).asString();
                String value = sample.get(1).asString();
                if (timestamp.length() > 32 || (!metrics && !timestamp.matches("[0-9]{1,19}"))) throw malformed();
                BigDecimal numeric;
                try {
                    numeric = new BigDecimal(timestamp).stripTrailingZeros();
                } catch (NumberFormatException invalid) {
                    throw malformed();
                }
                if (numeric.signum() < 0 || numeric.scale() > 9 || Math.abs((long) numeric.scale()) > 32) throw malformed();
                var instant = metrics ? numeric : numeric.movePointLeft(9);
                if (instant.compareTo(from) < 0 || instant.compareTo(to) > 0) throw malformed();
                if (metrics && (value.length() > 128 || !value.matches("(?:[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?|NaN|[+-]Inf)"))) throw malformed();
                items.add(new CapturedEvidence.EvidenceItem(numeric.stripTrailingZeros().toPlainString(), value, labels));
            }
        }
        return CapturedEvidence.bounded(query, source, items, warnings, budget);
    }

    private static BigDecimal seconds(java.time.Instant time) {
        return BigDecimal.valueOf(time.getEpochSecond()).add(BigDecimal.valueOf(time.getNano(), 9));
    }

    private static EvidenceSourceException malformed() {
        return new EvidenceSourceException("invalid evidence provider response");
    }

    private static EvidenceBudgetExceeded oversizedLabels() {
        return new EvidenceBudgetExceeded("provider labels exceed normalization budget");
    }
}
