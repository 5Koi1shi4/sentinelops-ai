package io.sentinelops.api.incident.application;

import java.util.List;

public record IncidentTimelinePage(List<IncidentTimelineItem> items, String nextCursor) {

    public IncidentTimelinePage {
        items = List.copyOf(items);
    }
}
