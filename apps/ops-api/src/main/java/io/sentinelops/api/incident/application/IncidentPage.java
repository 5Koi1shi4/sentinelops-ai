package io.sentinelops.api.incident.application;

import java.util.List;

public record IncidentPage(List<IncidentSummary> items, String nextCursor) {

    public IncidentPage {
        items = List.copyOf(items);
    }
}
